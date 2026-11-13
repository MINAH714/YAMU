package com.yamu.app.feature.link.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.feature.forecast.data.CashflowCreateRequest
import com.yamu.app.feature.forecast.data.CashflowCreatedDto
import com.yamu.app.feature.forecast.data.RecurrenceRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

// B-06 월급 · 고정 지출 — #19 후보 · #8 확정 · #17 잔고예보 설정 · 생활비 예측 상세(하루 생활비 제안). 09 문서 5-2

@Serializable data class CashflowItemsResponse(val items: List<CashflowItemDto> = emptyList(), val candidateCount: Int = 0)

@Serializable data class CashflowItemDto(
    val cashflowItemId: Long,
    val type: String,                         // INCOME · EXPENSE
    val title: String,
    val amount: Long = 0,                     // 금액 미정이면 서버가 null — 목록 전체가 실패하지 않게
    val occurrenceDate: String? = null,
    val recurrence: RecurrenceDto? = null,
    val kind: String? = null,                 // 보완안: CARD_BILL이면 읽기 전용 줄
    val source: String? = null,               // MANUAL · DETECTED
    val status: String? = null,               // CONFIRMED · CANDIDATE · IGNORED
    val detection: DetectionDto? = null,      // 왜 후보인지(BE -349 !200). 확인 전 후보의 첫 규칙에만 온다
)

@Serializable data class RecurrenceDto(val frequency: String? = null, val dayOfMonth: Int? = null)

/**
 * 반복 지출 후보의 근거 — 같은 카드 · 가맹점 · 금액 결제가 이번 달과 앞 5개 달 중 [sampleCount]개 달에 한 번씩 있었다.
 * [lastPaidAt]은 마지막 승인 UTC 시각(끝에 Z)
 */
@Serializable data class DetectionDto(val merchantName: String? = null, val sampleCount: Int = 0, val lastPaidAt: String? = null)

@Serializable data class CashflowPatchRequest(val status: String, val amount: Long? = null)

@Serializable data class ForecastSettingsPatch(
    val horizon: String,
    val incomeCashflowItemId: Long? = null,
    val dailyLivingCostTarget: Long? = null,
    @Transient val keepIncome: Boolean = false,   // 월급 후보를 넣지 않았으면 기간 기준 · 월급을 보내지 않아 서버 설정을 그대로 둔다
)

/**
 * `GET /forecasts/settings`에서 정해 둔 하루 목표와 월급. 월급은 정한 적이 없거나 더 받을 회차가 없으면 null이다.
 * 정해 둔 월급이 있으면 B-06은 보여 주기만 하고 다시 보내지 않는다
 */
@Serializable data class ForecastSettingsDto(val dailyLivingCostTarget: Long? = null, val income: SettingsIncomeDto? = null)

@Serializable data class SettingsIncomeDto(val cashflowItemId: Long, val title: String = "월급", val amount: Long? = null, val dayOfMonth: Int? = null)

/** `GET /forecasts/living-cost`에서 하루 평균만 — 최근 3개월 실제 소비의 하루 평균(예측 값이 아니다) */
@Serializable data class LivingCostDto(val dailyAverage: Long? = null)

interface CashflowApi {
    @GET("cashflow-items")
    suspend fun items(
        @Query("status") status: String,
        @Query("expand") expand: Boolean,
        @Query("from") from: String? = null,   // 안 보내면 서버가 오늘 ~ 29일 뒤로 본다. F-02 후보는 회차 목록과 같은 기간을 보낸다(!291 리뷰)
        @Query("to") to: String? = null,
    ): Response<ApiEnvelope<CashflowItemsResponse>>

    @PATCH("cashflow-items/{id}")
    suspend fun patch(@Path("id") id: Long, @Body body: CashflowPatchRequest): Response<ApiEnvelope<CashflowItemDto>>

    @GET("forecasts/settings")
    suspend fun settings(): Response<ApiEnvelope<ForecastSettingsDto>>

    @PATCH("forecasts/settings")
    suspend fun patchSettings(@Body body: JsonObject): Response<ApiEnvelope<kotlinx.serialization.json.JsonElement>>

    /** 생활비 예측 상세. accountIds 필수(1~20개, 비면 400) · 남의 계좌 404 · 고른 계좌 모두 기록이 없으면 422 DATA_INSUFFICIENT */
    @GET("forecasts/living-cost")
    suspend fun livingCost(@Query("accountIds") accountIds: List<Long>): Response<ApiEnvelope<LivingCostDto>>

    /**
     * 월급 등록(예정 수입 · 매월). 같은 [idempotencyKey]로 다시 보내면 처음 만든 항목을 201로 돌려준다(BE -358 !202).
     * 같은 키에 다른 내용이면 409 IDEMPOTENCY_KEY_REUSED, 그 항목을 지웠으면 404
     */
    @POST("cashflow-items")
    suspend fun create(
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: CashflowCreateRequest,
    ): Response<ApiEnvelope<CashflowCreatedDto>>
}

// ── 화면 값 ──

data class CashflowCandidate(
    val id: Long,
    val isIncome: Boolean,
    val title: String,
    val amount: Long,
    val dayOfMonth: Int?,
    val basis: String? = null,   // 왜 후보인지 — '최근 6개월 중 3달 · 마지막 10월 15일'
)

/** 이미 정해 둔 월급(잔고예보 설정) — 보여 주기만 한다 */
data class SavedIncome(val id: Long, val title: String, val amount: Long?, val dayOfMonth: Int?)

/** 월급이 들어올 계좌 고르기 한 줄 */
data class IncomeAccount(val accountId: Long, val label: String, val isPrimary: Boolean)

/** B-06에서 새로 넣는 월급 — 매월 [day]일(31은 말일, 짧은 달은 그달 말일) */
data class IncomeInput(val amount: Long, val day: Int, val accountId: Long) {
    /** 예정 수입(매월) 등록 본문. 첫 회차는 오늘 뒤 첫 [day]일 — 오늘 들어온 월급은 이미 잔액에 있을 수 있어 다음 달부터 센다 */
    fun toRequest(today: LocalDate): CashflowCreateRequest = CashflowCreateRequest(
        type = "INCOME",
        title = INCOME_TITLE,
        amount = amount,
        date = nextMonthlyDate(day, today).toString(),
        recurrence = RecurrenceRequest("MONTHLY", dayOfMonth = day),
        accountId = accountId,
    )

    companion object {
        const val INCOME_TITLE = "월급"
    }
}

/**
 * 매월 [day]일 회차 중 [today] 뒤의 첫 날. 그달에 그날이 없으면 말일이다 — 서버는 첫 발생일이 그달의 기준일(없으면 말일)과
 * 같아야 받는다(BE CashflowService 「매월 반복일은 첫 발생일의 날짜와 같아야」)
 */
fun nextMonthlyDate(day: Int, today: LocalDate): LocalDate {
    val month = YearMonth.from(today)
    val thisMonth = month.atDay(minOf(day, month.lengthOfMonth()))
    if (thisMonth.isAfter(today)) return thisMonth
    val next = month.plusMonths(1)
    return next.atDay(minOf(day, next.lengthOfMonth()))
}

/** 후보 근거 → '최근 6개월 중 3달 · 마지막 10월 15일'. 승인 시각은 UTC라 한국 날짜로 바꾼다 */
fun DetectionDto.basisLine(): String? {
    val months = sampleCount.takeIf { it > 0 }?.let { "최근 6개월 중 ${it}달" }
    val last = lastPaidAt?.let { iso -> runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(KST).toLocalDate() }.getOrNull() }
        ?.let { "마지막 ${it.toKoreanDate()}" }
    return listOfNotNull(months, last).joinToString(" · ").ifEmpty { null }
}

/** 카드대금 — 카드사 청구서에서 자동 반영이라 고치지 않는 줄 */
data class BillLine(val title: String, val amount: Long, val dayOfMonth: Int?)

/**
 * 매월 반복일 표시 — 'N일', 31은 '말일'. 서버 규칙(7.1.6)에서 31은 '31일이 없는 달은 말일'이라 매월 말일 반복이다.
 * 실제 회차 날짜는 서버가 계산한 값을 쓰고, 여기서는 반복 기준일만 글자로 바꾼다
 */
fun monthlyDayLabel(dayOfMonth: Int): String = if (dayOfMonth >= 31) "말일" else "${dayOfMonth}일"

/**
 * B-06에 미리 채울 값. 서버는 결제 기록의 반복 지출만 후보로 만들고 월급은 만들지 않는다(BE -349 !200, 월급은 잔고예보 설정 값).
 * 그래서 [income](수입 후보)은 보통 비어 있고 월급은 [savedIncome]이 없으면 사용자가 직접 넣는다
 */
data class CashflowDraft(
    val income: CashflowCandidate?,
    val expenses: List<CashflowCandidate>,
    val bills: List<BillLine>,
    val dailyLivingCost: Long?,
    val savedIncome: SavedIncome? = null,
    val incomeAccounts: List<IncomeAccount> = emptyList(),
    val incomeAccountsError: ApiError? = null,   // 받을 계좌를 못 읽음 — 계좌 0개(빈 목록)와 가른다(QA P1-04)
) {
    /** 찾은 고정 지출 · 카드대금이 없다(월급은 찾는 대상이 아니다) */
    val noFixed: Boolean get() = expenses.isEmpty() && bills.isEmpty()

    val isEmpty: Boolean get() = income == null && noFixed

    /** 월급을 여기서 새로 넣을 수 있는지 — 정해 둔 월급 · 수입 후보가 없고 들어올 계좌가 있을 때 */
    val canEnterIncome: Boolean get() = savedIncome == null && income == null && incomeAccounts.isNotEmpty()

    /**
     * 월급을 여기서 넣어야 하는데 받을 계좌를 못 읽었다 — 월급 줄 자리에 '계좌를 불러오지 못했어요 [다시 시도]'.
     * 전에는 실패를 빈 목록으로 삼켜 계좌가 없는 것처럼 월급 줄이 통째로 사라졌다(QA P1-04). 계좌가 정말 0개면 지금처럼 숨긴다
     */
    val incomeAccountsFailed: Boolean get() = savedIncome == null && income == null && incomeAccountsError != null

    val defaultIncomeAccount: IncomeAccount? get() = incomeAccounts.firstOrNull { it.isPrimary } ?: incomeAccounts.firstOrNull()
}

/** 사용자가 고른 결과 — 항목마다 넣을지 · 금액 */
data class CashflowDecision(val id: Long, val include: Boolean, val amount: Long?)

/** [createdIncomeId]는 이 화면에서 새로 등록한 월급 — 잔고예보 설정의 월급으로 지정한다 */
data class CashflowPlan(
    val decisions: List<CashflowDecision>,
    val incomeId: Long?,
    val dailyLivingCost: Long?,
    val createdIncomeId: Long? = null,
)

/** #19 후보 → B-06 줄. 월급 = 매달 오는 수입 중 가장 큰 것, 고정 지출은 날짜순 */
fun List<CashflowItemDto>.toDraft(dailyLivingCost: Long?): CashflowDraft {
    fun CashflowItemDto.day() = recurrence?.dayOfMonth ?: occurrenceDate?.takeLast(2)?.toIntOrNull()
    val bills = filter { it.kind.equals("CARD_BILL", ignoreCase = true) }
    val rest = this - bills.toSet()
    // 서버는 매월 반복 수입만 월급으로 받는다(아니면 400)
    val income = rest.filter { it.type.equals("INCOME", ignoreCase = true) && it.recurrence?.frequency.equals("MONTHLY", ignoreCase = true) }.maxByOrNull { it.amount }
    val expenses = rest.filter { it.type.equals("EXPENSE", ignoreCase = true) }.sortedBy { it.day() ?: 99 }
    return CashflowDraft(
        income = income?.let { CashflowCandidate(it.cashflowItemId, true, it.title, it.amount, it.day(), it.detection?.basisLine()) },
        expenses = expenses.map { CashflowCandidate(it.cashflowItemId, false, it.title, it.amount, it.day(), it.detection?.basisLine()) },
        bills = bills.map { BillLine(it.title, it.amount, it.day()) },
        dailyLivingCost = dailyLivingCost,
    )
}

/** B-06 [등록하고 첫 분석 보기] → #8 요청들 + #17 요청 */
fun CashflowPlan.toPatches(): Pair<List<Pair<Long, CashflowPatchRequest>>, ForecastSettingsPatch> {
    val patches = decisions.map { d ->
        d.id to if (d.include) CashflowPatchRequest("CONFIRMED", d.amount) else CashflowPatchRequest("IGNORED")
    }
    val income = incomeId?.takeIf { id -> decisions.any { it.id == id && it.include } } ?: createdIncomeId
    // 기간 기준 · 월급은 월급 후보를 넣었거나 월급을 새로 넣었을 때만 보낸다. 없거나 뺐는데 DAYS · null을 보내면 하루 생활비만 저장해도
    // 사용자가 정한 기간 기준이 바뀐다(!205 리뷰). 서버는 보낸 필드만 바꾸고, 월급이 없으면 NEXT_INCOME이어도 30일로 계산한다
    val settings = ForecastSettingsPatch(
        horizon = "NEXT_INCOME",
        incomeCashflowItemId = income,
        dailyLivingCostTarget = dailyLivingCost,
        keepIncome = income == null,
    )
    return patches to settings
}

/** #17 본문은 직접 만든다 — 서버는 보낸 필드만 바꾸므로 바꿀 필드만 담는다 */
fun ForecastSettingsPatch.toBody(): JsonObject = buildJsonObject {
    if (!keepIncome) {
        put("horizon", horizon)
        put("incomeCashflowItemId", incomeCashflowItemId)
    }
    dailyLivingCostTarget?.let { put("dailyLivingCostTarget", it) }
}

interface CashflowRepository {
    /**
     * B-06 미리 채울 값 — 후보(#19) · 정해 둔 월급 · 하루 생활비(정해 둔 목표, 없으면 생활비 상세의 하루 평균) · 월급 받을 계좌.
     * 평균과 계좌는 실패해도 화면은 연다. 계좌를 못 읽으면 [CashflowDraft.incomeAccountsError]에 담아 월급 줄에서 다시 시도하게 한다
     */
    suspend fun draft(): ApiResult<CashflowDraft>

    /** 월급 받을 계좌만 다시 — B-06 월급 줄의 [다시 시도]. 고친 금액 · 하루 생활비는 화면에 그대로 둔다 */
    suspend fun incomeAccounts(): ApiResult<List<IncomeAccount>>

    /** 월급 등록 → 새 항목 ID. 다시 보낼 때는 같은 키 · 같은 본문을 쓴다(서버가 한 번만 만든다) */
    suspend fun createIncome(body: CashflowCreateRequest, idempotencyKey: String): ApiResult<Long>

    /** 확정 — 항목마다 #8, 마지막에 #17. 다시 시도하면 처음부터(같은 값이라 결과가 같음) */
    suspend fun confirm(plan: CashflowPlan): ApiResult<Unit>
}

/**
 * 연결 계좌는 [LinkApi]로 직접 읽는다(잔고예보 저장소와 같은 방식). LinkRepository를 받으면 debug에서 계좌만 가짜일 때
 * 가짜 계좌 번호가 진짜 서버로 간다
 */
@Singleton
class CashflowRepositoryImpl @Inject constructor(private val api: CashflowApi, private val linkApi: LinkApi) : CashflowRepository {

    /**
     * 셋을 함께 부르되, 쓰지 않게 된 호출은 바로 끊는다. coroutineScope는 시작한 자식이 모두 끝나야 돌아오므로
     * 끊지 않으면 정해 둔 목표가 있거나 실패한 경우에도 생활비 상세 응답을 기다려 화면이 늦게 뜬다(!205 리뷰)
     */
    override suspend fun draft(): ApiResult<CashflowDraft> = coroutineScope {
        val items = async { apiCall { api.items(status = "CANDIDATE", expand = false) } }
        val settings = async { apiCall { api.settings() } }
        val accounts = async { apiCall { linkApi.accounts(null) } }
        val average = async { dailyAverage((accounts.await() as? ApiResult.Success)?.data?.accounts.orEmpty()) }
        // 정해 둔 하루 목표를 모른 채 평균을 채우면 저장할 때 목표가 바뀌어서 실패로 돌려 다시 읽게 한다
        val s = when (val r = settings.await()) {
            is ApiResult.Failure -> {
                coroutineContext.cancelChildren()
                return@coroutineScope r
            }
            is ApiResult.Success -> r.data
        }
        val candidates = when (val r = items.await()) {
            is ApiResult.Failure -> {
                coroutineContext.cancelChildren()
                return@coroutineScope r
            }
            is ApiResult.Success -> r.data
        }
        // 정해 둔 하루 목표가 먼저 — 평균으로 채우면 [등록하고 첫 분석 보기]만 눌러도 사용자가 정한 목표가 바뀐다
        val daily = s.dailyLivingCostTarget?.also { average.cancel() } ?: average.await()
        val saved = s.income?.let { SavedIncome(it.cashflowItemId, it.title, it.amount, it.dayOfMonth) }
        val linked = accounts.await()
        ApiResult.Success(
            candidates.items.toDraft(daily).copy(
                savedIncome = saved,
                incomeAccounts = (linked as? ApiResult.Success)?.data?.accounts.orEmpty().map { it.toIncomeAccount() },
                incomeAccountsError = (linked as? ApiResult.Failure)?.error,
            ),
        )
    }

    override suspend fun incomeAccounts(): ApiResult<List<IncomeAccount>> =
        apiCall { linkApi.accounts(null) }.map { r -> r.accounts.map { it.toIncomeAccount() } }

    private fun AccountDto.toIncomeAccount() =
        IncomeAccount(accountId, "${accountName?.takeIf { it.isNotBlank() } ?: "${bank.name} 입출금"} ··${maskedAccountNo.lastDigits()}", isPrimary)

    /**
     * 연결 계좌 전부의 최근 3개월 하루 평균(잔고예보도 고른 계좌가 없으면 전부를 쓴다). 기간 예측을 일수로 나누지 않는 것은
     * 계좌 하나라도 기록이 없으면 null이고, 월급일이 내일이면 기간이 0일이 되기 때문이다.
     * 계좌가 없으면 묻지 않고(빈 accountIds는 400), 기록이 없는 422를 포함해 어느 단계든 실패하면 제안 없음
     */
    private suspend fun dailyAverage(accounts: List<AccountDto>): Long? {
        val ids = accounts.map { it.accountId }.distinct().take(MAX_LIVING_COST_ACCOUNTS)
        if (ids.isEmpty()) return null
        val livingCost = apiCall { api.livingCost(ids) } as? ApiResult.Success ?: return null
        return livingCost.data.dailyAverage?.takeIf { it > 0 }
    }

    override suspend fun createIncome(body: CashflowCreateRequest, idempotencyKey: String): ApiResult<Long> =
        apiCall { api.create(idempotencyKey, body) }.map { it.cashflowItemId }

    override suspend fun confirm(plan: CashflowPlan): ApiResult<Unit> {
        val (patches, settings) = plan.toPatches()
        for ((id, body) in patches) {
            val r = apiCall { api.patch(id, body) }
            if (r is ApiResult.Failure) return r
        }
        val body = settings.toBody()
        return if (body.isEmpty()) ApiResult.Success(Unit) else apiCallNoData { api.patchSettings(body) }   // 빈 본문은 서버가 400으로 거절한다
    }

    private companion object {
        /** 생활비 상세가 받는 계좌 수 상한(BE MAX_ACCOUNTS) */
        const val MAX_LIVING_COST_ACCOUNTS = 20
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CashflowApiModule {
    @Provides
    @Singleton
    fun cashflowApi(retrofit: Retrofit): CashflowApi = retrofit.create(CashflowApi::class.java)
}
