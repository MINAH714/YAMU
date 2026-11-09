package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
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
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
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
)

@Serializable data class RecurrenceDto(val frequency: String? = null, val dayOfMonth: Int? = null)

@Serializable data class CashflowPatchRequest(val status: String, val amount: Long? = null)

@Serializable data class ForecastSettingsPatch(
    val horizon: String,
    val incomeCashflowItemId: Long? = null,
    val dailyLivingCostTarget: Long? = null,
    @Transient val keepIncome: Boolean = false,   // 월급 후보를 넣지 않았으면 기간 기준 · 월급을 보내지 않아 서버 설정을 그대로 둔다
)

/**
 * `GET /forecasts/settings`에서 정해 둔 하루 목표만(정한 적이 없으면 null). 정해 둔 월급은 읽지 않는다 —
 * 저장할 때 월급 후보를 넣은 경우에만 월급을 보내므로 지킬 값이 없다
 */
@Serializable data class ForecastSettingsDto(val dailyLivingCostTarget: Long? = null)

/** `GET /forecasts/living-cost`에서 하루 평균만 — 최근 3개월 실제 소비의 하루 평균(예측 값이 아니다) */
@Serializable data class LivingCostDto(val dailyAverage: Long? = null)

interface CashflowApi {
    @GET("cashflow-items")
    suspend fun items(@Query("status") status: String, @Query("expand") expand: Boolean): Response<ApiEnvelope<CashflowItemsResponse>>

    @PATCH("cashflow-items/{id}")
    suspend fun patch(@Path("id") id: Long, @Body body: CashflowPatchRequest): Response<ApiEnvelope<CashflowItemDto>>

    @GET("forecasts/settings")
    suspend fun settings(): Response<ApiEnvelope<ForecastSettingsDto>>

    @PATCH("forecasts/settings")
    suspend fun patchSettings(@Body body: JsonObject): Response<ApiEnvelope<kotlinx.serialization.json.JsonElement>>

    /** 생활비 예측 상세. accountIds 필수(1~20개, 비면 400) · 남의 계좌 404 · 고른 계좌 모두 기록이 없으면 422 DATA_INSUFFICIENT */
    @GET("forecasts/living-cost")
    suspend fun livingCost(@Query("accountIds") accountIds: List<Long>): Response<ApiEnvelope<LivingCostDto>>
}

// ── 화면 값 ──

data class CashflowCandidate(
    val id: Long,
    val isIncome: Boolean,
    val title: String,
    val amount: Long,
    val dayOfMonth: Int?,
)

/** 카드대금 — 카드사 청구서에서 자동 반영이라 고치지 않는 줄 */
data class BillLine(val title: String, val amount: Long, val dayOfMonth: Int?)

/**
 * 매월 반복일 표시 — 'N일', 31은 '말일'. 서버 규칙(7.1.6)에서 31은 '31일이 없는 달은 말일'이라 매월 말일 반복이다.
 * 실제 회차 날짜는 서버가 계산한 값을 쓰고, 여기서는 반복 기준일만 글자로 바꾼다
 */
fun monthlyDayLabel(dayOfMonth: Int): String = if (dayOfMonth >= 31) "말일" else "${dayOfMonth}일"

data class CashflowDraft(
    val income: CashflowCandidate?,
    val expenses: List<CashflowCandidate>,
    val bills: List<BillLine>,
    val dailyLivingCost: Long?,
) {
    val isEmpty: Boolean get() = income == null && expenses.isEmpty() && bills.isEmpty()
}

/** 사용자가 고른 결과 — 항목마다 넣을지 · 금액 */
data class CashflowDecision(val id: Long, val include: Boolean, val amount: Long?)

data class CashflowPlan(
    val decisions: List<CashflowDecision>,
    val incomeId: Long?,
    val dailyLivingCost: Long?,
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
        income = income?.let { CashflowCandidate(it.cashflowItemId, true, it.title, it.amount, it.day()) },
        expenses = expenses.map { CashflowCandidate(it.cashflowItemId, false, it.title, it.amount, it.day()) },
        bills = bills.map { BillLine(it.title, it.amount, it.day()) },
        dailyLivingCost = dailyLivingCost,
    )
}

/** B-06 [등록하고 첫 분석 보기] → #8 요청들 + #17 요청 */
fun CashflowPlan.toPatches(): Pair<List<Pair<Long, CashflowPatchRequest>>, ForecastSettingsPatch> {
    val patches = decisions.map { d ->
        d.id to if (d.include) CashflowPatchRequest("CONFIRMED", d.amount) else CashflowPatchRequest("IGNORED")
    }
    val income = incomeId?.takeIf { id -> decisions.any { it.id == id && it.include } }
    // 기간 기준 · 월급은 월급 후보를 넣었을 때만 보낸다. 후보가 없거나 뺐는데 DAYS · null을 보내면 하루 생활비만 저장해도
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
    /** B-06 미리 채울 값 — 후보(#19) · 하루 생활비(정해 둔 목표, 없으면 생활비 상세의 하루 평균. 평균은 실패해도 됨) */
    suspend fun draft(): ApiResult<CashflowDraft>

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
        val average = async { dailyAverage() }
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
                average.cancel()
                return@coroutineScope r
            }
            is ApiResult.Success -> r.data
        }
        // 정해 둔 하루 목표가 먼저 — 평균으로 채우면 [등록하고 첫 분석 보기]만 눌러도 사용자가 정한 목표가 바뀐다
        val daily = s.dailyLivingCostTarget?.also { average.cancel() } ?: average.await()
        ApiResult.Success(candidates.items.toDraft(daily))
    }

    /**
     * 연결 계좌 전부의 최근 3개월 하루 평균(잔고예보도 고른 계좌가 없으면 전부를 쓴다). 기간 예측을 일수로 나누지 않는 것은
     * 계좌 하나라도 기록이 없으면 null이고, 월급일이 내일이면 기간이 0일이 되기 때문이다.
     * 계좌가 없으면 묻지 않고(빈 accountIds는 400), 기록이 없는 422를 포함해 어느 단계든 실패하면 제안 없음
     */
    private suspend fun dailyAverage(): Long? {
        val accounts = apiCall { linkApi.accounts(null) } as? ApiResult.Success ?: return null
        val ids = accounts.data.accounts.map { it.accountId }.distinct().take(MAX_LIVING_COST_ACCOUNTS)
        if (ids.isEmpty()) return null
        val livingCost = apiCall { api.livingCost(ids) } as? ApiResult.Success ?: return null
        return livingCost.data.dailyAverage?.takeIf { it > 0 }
    }

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
