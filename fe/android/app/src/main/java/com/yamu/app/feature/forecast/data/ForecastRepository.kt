package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.feature.link.data.AccountDto
import com.yamu.app.feature.link.data.CardDto
import com.yamu.app.feature.link.data.CashflowApi
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowPatchRequest
import com.yamu.app.feature.link.data.LinkApi
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.link.data.lastDigits
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 잔고예보 · 이거 사도 될까? · 예정 수입·지출 — docs/be/forecast.md · cashflow.md */
interface ForecastApi {
    /** accountIds는 필수(없으면 400 ACCOUNT_SELECTION_REQUIRED). until · days를 빼면 잔고예보 설정, N일은 until=DAYS와 days를 같이 */
    @GET("forecasts/balance")
    suspend fun forecast(
        @Query("accountIds") accountIds: List<Long>,
        @Query("until") until: String?,
        @Query("days") days: Int?,
    ): Response<ApiEnvelope<BalanceForecastDto>>

    /** 가상 구매 전후 비교. 계산만 하고 결제 · 청구 · 실적 · 한도는 바꾸지 않아 다시 보내도 된다 */
    @POST("forecasts/simulations")
    suspend fun simulate(@Body body: SimulationRequest): Response<ApiEnvelope<SimulationResponseDto>>

    /** 업종 49개(인증 없이 공개) — docs/be/merchant-catalog-api.md */
    @GET("merchant-categories")
    suspend fun categories(): Response<ApiEnvelope<List<MerchantCategoryDto>>>

    /** 업종의 브랜드. 결제 쪽 GET merchants(channel · size)와 같은 주소지만 merchantCategoryCode로 거른다 */
    @GET("merchants")
    suspend fun merchants(@Query("merchantCategoryCode") merchantCategoryCode: String): Response<ApiEnvelope<List<CatalogMerchantDto>>>

    /** 생활비 예측 상세 — 잔고예보와 같은 accountIds · until · days. 계좌별 값까지(includeAccountDetails) */
    @GET("forecasts/living-cost")
    suspend fun livingCost(
        @Query("accountIds") accountIds: List<Long>,
        @Query("until") until: String?,
        @Query("days") days: Int?,
        @Query("includeAccountDetails") includeAccountDetails: Boolean = true,
    ): Response<ApiEnvelope<LivingCostDetailDto>>

    /** 예정 지출 회차와 결제 연결 — 새로 연결하면 201, 같은 쌍을 다시 보내면 기존 연결을 200(다시 보내도 안전) */
    @POST("cashflow-payment-matches")
    suspend fun confirmMatch(@Body body: MatchConfirmRequest): Response<ApiEnvelope<MatchConfirmedDto>>

    /** 연결 해제 — 204 */
    @DELETE("cashflow-payment-matches/{matchId}")
    suspend fun deleteMatch(@Path("matchId") matchId: Long): Response<ApiEnvelope<JsonElement>>

    @GET("cashflow-payment-matches")
    suspend fun matchStatus(@Query("cashflowItemId") cashflowItemId: Long, @Query("occurrenceDate") occurrenceDate: String): Response<ApiEnvelope<MatchStatusDto>>

    @GET("cashflow-items/{id}/payment-match-candidates")
    suspend fun matchCandidates(@Path("id") cashflowItemId: Long, @Query("occurrenceDate") occurrenceDate: String): Response<ApiEnvelope<MatchCandidatesDto>>

    /**
     * 등록 둘 다 Idempotency-Key(UUID)를 붙인다(BE -358 !202). 같은 키 · 같은 내용을 다시 보내면 처음 만든 항목을 201로,
     * 같은 키에 다른 내용이면 409 IDEMPOTENCY_KEY_REUSED, 그 항목을 지웠으면 404
     */
    @POST("cashflow-items")
    suspend fun savePlan(@Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String, @Body body: PurchasePlanRequest): Response<ApiEnvelope<CashflowCreatedDto>>

    @POST("cashflow-items")
    suspend fun createItem(@Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String, @Body body: CashflowCreateRequest): Response<ApiEnvelope<CashflowCreatedDto>>

    @GET("cashflow-items")
    suspend fun cashflowItems(@Query("from") from: String, @Query("to") to: String, @Query("expand") expand: Boolean = true): Response<ApiEnvelope<CashflowListResponse>>

    @PATCH("cashflow-items/{id}")
    suspend fun updateItem(@Path("id") cashflowItemId: Long, @Body body: CashflowUpdateRequest): Response<ApiEnvelope<JsonElement>>

    @DELETE("cashflow-items/{id}")
    suspend fun deleteItem(@Path("id") cashflowItemId: Long, @Query("scope") scope: String, @Query("occurrenceDate") occurrenceDate: String?): Response<ApiEnvelope<JsonElement>>

    @DELETE("cashflow-items/{id}")
    suspend fun deletePlan(@Path("id") cashflowItemId: Long): Response<ApiEnvelope<JsonElement>>
}

interface ForecastRepository {
    /** F-01 · F-02 — 지금 고른 기간 · 계좌([selection])로. 돌아올 때마다 새로(결제 · 계획 저장 뒤 바뀌므로) */
    suspend fun forecast(): ApiResult<Forecast>

    fun cached(): Forecast?

    /** 고른 기간 · 계좌 — 앱을 켜 둔 동안만(서버 설정에는 저장하지 않는다, 2026-11-08 결정) */
    fun selection(): ForecastSelection

    fun select(selection: ForecastSelection)

    /** 계좌 고르기 시트 — 연결 계좌 전체 */
    suspend fun accounts(): ApiResult<List<ForecastAccountOption>>

    /** I-02 — 가상 구매. 잔고예보와 같은 기간 · 계좌로 불러 '사기 전'이 잔고예보 화면과 같다 */
    suspend fun simulate(query: SimulationQuery): ApiResult<Simulation>

    /** I-01b 업종(한 번 받으면 기억) · 업종의 브랜드 · 내 카드 */
    suspend fun categories(): ApiResult<List<MerchantCategory>>

    suspend fun merchants(categoryCode: String): ApiResult<List<CatalogMerchant>>

    suspend fun simulationCards(): ApiResult<List<SimCardOption>>

    /** I-02 [계획 저장] — #20 일반 예정 지출. 일시불 결과만(할부는 화면에서 막는다). 다시 보낼 때는 같은 [idempotencyKey] */
    suspend fun savePlan(simulation: Simulation, idempotencyKey: String): ApiResult<SavedPlan>

    /** 방금 저장한 계획(I-05) — 앱이 다시 켜졌으면 없음 */
    fun savedPlan(planId: Long): SavedPlan?

    suspend fun deletePlan(planId: Long): ApiResult<Unit>

    /** F-02a — #20 예정 수입·지출 등록(새 항목 ID). 다시 보낼 때는 같은 [idempotencyKey] — 서버가 한 번만 만든다 */
    suspend fun createItem(form: CashflowForm, idempotencyKey: String): ApiResult<Long>

    /** F-02 — 기간 안 가계부 회차(확정만, 회차마다 한 줄). 마지막 값은 수정 화면이 [cachedEntry]로 쓴다 */
    suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>>

    fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry?

    /** F-02b 수정 — 이후 전부는 규칙이 나뉘어 ID가 바뀔 수 있어 목록을 새로 부른다 */
    suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit>

    suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit>

    /**
     * F-02 확인 전 고정 지출 후보 — 규칙마다 하나(B-06과 같은 GET cashflow-items?status=CANDIDATE&expand=false). 카드대금 줄은 뺀다.
     * 기간은 회차 목록([cashflowEntries])과 같게 보낸다. 안 보내면 서버 기본(오늘 ~ 29일 뒤)이라 그 뒤 월급날까지 첫 회차가 오는 후보가 빠진다.
     * 예보 줄에는 항목 ID가 없어 화면이 이름 · 금액으로 맞춘다([matchCandidate])
     */
    suspend fun cashflowCandidates(from: LocalDate, to: LocalDate): ApiResult<List<CashflowCandidate>>

    /** 후보 넣기(CONFIRMED) · 빼기(IGNORED) — #8. 이미 바뀐 후보면 서버가 404 · 409로 거절한다 */
    suspend fun decideCandidate(itemId: Long, confirm: Boolean): ApiResult<Unit>

    /**
     * F-02 '지난 7일' — [from] ~ [to] 사이 지난 카드 예정 지출(결제와 연결만 할 수 있는 회차).
     * 수정 화면이 [cachedEntry]로 찾을 수 있게 오늘부터의 목록과 따로 기억한다
     */
    suspend fun pastCardEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>>

    /** 회차의 결제 연결 — 없으면 null */
    suspend fun matchStatus(itemId: Long, occurrenceDate: LocalDate): ApiResult<PaymentMatch?>

    /** 연결할 만한 결제 후보와 지금 연결(있으면) */
    suspend fun matchCandidates(itemId: Long, occurrenceDate: LocalDate): ApiResult<MatchCandidates>

    suspend fun confirmMatch(itemId: Long, occurrenceDate: LocalDate, paymentId: Long): ApiResult<MatchConfirmed>

    suspend fun deleteMatch(matchId: Long): ApiResult<Unit>

    /** F-01b 생활비 예측 — 지금 고른 기간 · 계좌로(잔고예보와 같은 기간을 설명한다) */
    suspend fun livingCost(): ApiResult<LivingCostDetail>

    /**
     * 잔고예보 설정에 정해 둔 월급(GET forecasts/settings) — 7 · 14 · 30일 예보의 그래프를 월급날에서 자를 때 쓴다([untilPayday]).
     * 정한 월급이 없으면 null. 가짜 저장소는 월급을 모르는 것으로 둔다
     */
    suspend fun incomeRule(): ApiResult<IncomeRule?> = ApiResult.Success(null)

    /**
     * 안전 잔액 저장(PATCH forecasts/settings의 alertThreshold, 0 ~ 2,147,483,647원, S15P31E103-593). 보낸 값으로 덮어쓰는 요청이라
     * 같은 값을 다시 보내도 결과가 같다. 저장하면 받아 둔 예보는 옛 안전 잔액으로 계산한 것이라 버린다
     */
    suspend fun saveAlertThreshold(amount: Long): ApiResult<Unit>

    companion object {
        /** 연결한 계좌가 없다 — BE가 accountIds 없이 부르면 내는 코드와 같게 둬 화면이 한 곳에서 빈 상태로 바꾼다 */
        const val NO_ACCOUNT = "ACCOUNT_SELECTION_REQUIRED"
    }
}

/**
 * 예보에 넣을 계좌 — 고른 계좌가 있으면 그중 아직 연결된 것만, 모두 골랐거나([ForecastSelection.all]) 고르지 않았거나
 * 고른 계좌가 모두 해제됐으면 연결 계좌 전체(S15P31E103-548)
 */
fun ForecastSelection.accountIdsFrom(accounts: List<ForecastAccountOption>): List<Long> {
    val ids = accounts.map { it.accountId }
    if (all) return ids
    return accountIds?.let { chosen -> ids.filter { it in chosen } }.orEmpty().ifEmpty { ids }
}

/**
 * 가상 구매가 볼 계좌 — 카드의 출금 계좌 하나(S15P31E103-548). 잔고예보에서 고른 계좌 밖에서 빠지는 카드면 BE가 전후를 같게 두고
 * 안내만 줘서, 산 돈이 어디서도 빠지지 않는 것처럼 보였다. 카드를 모르거나(추천 카드는 응답 전) 그 계좌가 목록에 없으면 null
 */
fun cardAccountIds(userCardId: Long?, accounts: List<ForecastAccountOption>): List<Long>? =
    userCardId?.let { id -> accounts.firstOrNull { id in it.cardIds } }?.let { listOf(it.accountId) }

/** 등록 카드 → 카드 고르기 줄. 등록 중 · 해제 카드는 빼고, 결제할 수 없는 카드는 이유를 달아 둔다(BE CARD_NOT_ELIGIBLE과 같은 기준) */
fun CardDto.toSimCardOption(): SimCardOption? {
    val state = status?.uppercase()
    if (state == "PENDING" || state == "UNREGISTERED") return null
    val reason = when {
        state == "INACTIVE" -> "꺼 둔 카드"
        issuerStatus.equals("SUSPENDED", ignoreCase = true) -> "카드사에서 정지한 카드"
        issuerStatus.equals("EXPIRED", ignoreCase = true) -> "유효기간이 지난 카드"
        else -> null
    }
    return SimCardOption(
        userCardId, nickname?.takeIf { it.isNotBlank() } ?: productName, productName, cardType.equals("CREDIT", ignoreCase = true), reason,
        brand = brandOf(issuer.name, color = issuer.color),
    )
}

fun AccountDto.toOption() = ForecastAccountOption(
    accountId = accountId,
    name = accountName?.takeIf { it.isNotBlank() } ?: "${bank.name} 입출금",
    bankName = bank.name,
    last4 = maskedAccountNo.lastDigits(),
    balance = balance,
    isPrimary = isPrimary,
    cardIds = linkedCards.map { it.userCardId }.toSet(),
)

@Singleton
class ForecastRepositoryImpl @Inject constructor(
    private val api: ForecastApi,
    private val linkApi: LinkApi,
    private val cashflowApi: CashflowApi,
) : ForecastRepository {

    /**
     * 마지막 예보와 그 예보를 받은 선택. 선택이 바뀌면 [cached]는 null이다 — 홈에서 '30일 · 기준 계좌'로 바꿔 들어왔을 때
     * 새 응답 전(실패하면 계속) 칩 밑에 앞 선택의 숫자가 보이지 않게(!319 리뷰)
     */
    @Volatile private var last: Pair<ForecastSelection, Forecast>? = null
    @Volatile private var current = ForecastSelection()
    private val plans = ConcurrentHashMap<Long, SavedPlan>()

    override suspend fun forecast(): ApiResult<Forecast> {
        val accounts = when (val a = accounts()) {
            is ApiResult.Failure -> return a
            is ApiResult.Success -> a.data
        }
        if (accounts.isEmpty()) return ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT))
        val selection = current   // 한 번만 읽어 기간 · 계좌 · 목록 범위가 같은 선택을 쓰게 한다
        val period = selection.period
        val ids = selection.accountIdsFrom(accounts)
        return apiCall { api.forecast(ids, period?.until, period?.days) }
            .map { it.toForecast().copy(scope = selection.scopeFrom(accounts), accounts = accounts.filter { a -> a.accountId in ids }) }
            .also { r -> if (r is ApiResult.Success) last = selection to r.data }
    }

    /** 지금 선택으로 받은 마지막 예보만 — 늦게 온 앞 선택의 응답도 지금 선택과 다르면 쓰지 않는다 */
    override fun cached(): Forecast? = last?.takeIf { it.first == current }?.second

    override fun selection(): ForecastSelection = current

    override fun select(selection: ForecastSelection) {
        current = selection
    }

    override suspend fun accounts(): ApiResult<List<ForecastAccountOption>> =
        apiCall { linkApi.accounts(null) }.map { r -> r.accounts.map { it.toOption() } }

    /**
     * 카드를 골랐으면 그 카드의 출금 계좌로만 계산한다([cardAccountIds]). 추천 카드는 응답이 와야 카드를 알아 잔고예보 선택으로 먼저 부르고,
     * 그 카드의 출금 계좌가 보낸 계좌와 다르면 그 계좌로 한 번 더 부른다(시뮬레이션은 계산만 해서 다시 불러도 남는 것이 없다).
     * 두 번째도 userCardId 없이 부르므로 '혜택이 가장 큰 카드' 표시는 그대로다
     */
    override suspend fun simulate(query: SimulationQuery): ApiResult<Simulation> {
        val accounts = when (val a = accounts()) {
            is ApiResult.Failure -> return a
            is ApiResult.Success -> a.data
        }
        if (accounts.isEmpty()) return ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT))
        val selection = current
        suspend fun call(ids: List<Long>) = apiCall { api.simulate(query.toRequest(ids, selection.period)) }
            .map { it.toSimulation(query).copy(accounts = accounts.filter { a -> a.accountId in ids }) }
        val ids = cardAccountIds(query.userCardId, accounts) ?: selection.accountIdsFrom(accounts)
        val first = call(ids)
        if (query.userCardId != null || first !is ApiResult.Success) return first
        val cardIds = cardAccountIds(first.data.card.userCardId, accounts)
        return if (cardIds == null || cardIds == ids) first else call(cardIds)
    }

    @Volatile private var categoryCache: List<MerchantCategory>? = null

    override suspend fun categories(): ApiResult<List<MerchantCategory>> {
        categoryCache?.let { return ApiResult.Success(it) }
        return apiCall { api.categories() }
            .map { list -> list.map { it.toCategory() } }
            .also { if (it is ApiResult.Success) categoryCache = it.data }
    }

    override suspend fun merchants(categoryCode: String): ApiResult<List<CatalogMerchant>> =
        apiCall { api.merchants(categoryCode) }.map { list -> list.map { CatalogMerchant(it.merchantId, it.merchantName) } }

    override suspend fun simulationCards(): ApiResult<List<SimCardOption>> =
        apiCall { linkApi.cards() }.map { r -> r.cards.mapNotNull { it.toSimCardOption() } }

    override suspend fun savePlan(simulation: Simulation, idempotencyKey: String): ApiResult<SavedPlan> {
        val body = simulation.toPlanRequest()
        return apiCall { api.savePlan(idempotencyKey, body) }.map { r ->
            SavedPlan(
                id = r.cashflowItemId,
                title = body.title,
                amount = body.amount,
                date = parseDate(r.occurrenceDate) ?: simulation.query.purchaseDate,
                minBefore = simulation.before?.minBalance,
                minAfter = simulation.after?.minBalance,
                shortfallFrom = null,
            ).also { plans[it.id] = it }
        }
    }

    override fun savedPlan(planId: Long): SavedPlan? = plans[planId]

    override suspend fun deletePlan(planId: Long): ApiResult<Unit> =
        apiCallNoData { api.deletePlan(planId) }.also { if (it is ApiResult.Success) plans.remove(planId) }

    override suspend fun createItem(form: CashflowForm, idempotencyKey: String): ApiResult<Long> =
        apiCall { api.createItem(idempotencyKey, form.toRequest()) }.map { it.cashflowItemId }

    @Volatile private var entries: List<CashflowEntry> = emptyList()

    override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> =
        apiCall { api.cashflowItems(from.toString(), to.toString()) }
            .map { r -> r.items.mapNotNull { it.toEntry() } }
            .also { if (it is ApiResult.Success) entries = it.data }

    override fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry? =
        (entries + pastEntries).firstOrNull { it.itemId == itemId && it.occurrenceDate == occurrenceDate }

    @Volatile private var pastEntries: List<CashflowEntry> = emptyList()

    override suspend fun pastCardEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> =
        apiCall { api.cashflowItems(from.toString(), to.toString()) }
            .map { r -> r.items.mapNotNull { it.toEntry() }.filter { it.isMatchable } }
            .also { if (it is ApiResult.Success) pastEntries = it.data }

    override suspend fun matchStatus(itemId: Long, occurrenceDate: LocalDate): ApiResult<PaymentMatch?> =
        apiCall { api.matchStatus(itemId, occurrenceDate.toString()) }.map { it.match?.toMatch() }

    override suspend fun matchCandidates(itemId: Long, occurrenceDate: LocalDate): ApiResult<MatchCandidates> =
        apiCall { api.matchCandidates(itemId, occurrenceDate.toString()) }.map { it.toCandidates() }

    override suspend fun confirmMatch(itemId: Long, occurrenceDate: LocalDate, paymentId: Long): ApiResult<MatchConfirmed> =
        apiCall { api.confirmMatch(MatchConfirmRequest(itemId, occurrenceDate.toString(), paymentId)) }.map { it.toConfirmed() }

    override suspend fun deleteMatch(matchId: Long): ApiResult<Unit> = apiCallNoData { api.deleteMatch(matchId) }

    override suspend fun livingCost(): ApiResult<LivingCostDetail> {
        val accounts = when (val a = accounts()) {
            is ApiResult.Failure -> return a
            is ApiResult.Success -> a.data
        }
        if (accounts.isEmpty()) return ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT))
        val selection = current
        val names = accounts.associate { it.accountId to it.name }
        return apiCall { api.livingCost(selection.accountIdsFrom(accounts), selection.period?.until, selection.period?.days) }
            .map { it.toDetail(names) }
    }

    override suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit> =
        apiCallNoData { api.updateItem(itemId, body) }

    override suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit> =
        apiCallNoData { api.deleteItem(itemId, scope.name, occurrenceDate?.toString()) }

    override suspend fun cashflowCandidates(from: LocalDate, to: LocalDate): ApiResult<List<CashflowCandidate>> =
        apiCall { cashflowApi.items(status = "CANDIDATE", expand = false, from = from.toString(), to = to.toString()) }
            .map { it.items.toFixedCandidates() }

    override suspend fun decideCandidate(itemId: Long, confirm: Boolean): ApiResult<Unit> =
        apiCall { cashflowApi.patch(itemId, CashflowPatchRequest(if (confirm) "CONFIRMED" else "IGNORED")) }.map { }

    override suspend fun incomeRule(): ApiResult<IncomeRule?> =
        apiCall { cashflowApi.settings() }.map { s -> s.income?.let { i -> i.dayOfMonth?.let { IncomeRule(i.title, it) } } }

    override suspend fun saveAlertThreshold(amount: Long): ApiResult<Unit> =
        apiCallNoData { cashflowApi.patchSettings(buildJsonObject { put("alertThreshold", amount) }) }
            .also { if (it is ApiResult.Success) last = null }
}

@Module
@InstallIn(SingletonComponent::class)
object ForecastApiModule {
    @Provides
    @Singleton
    fun forecastApi(retrofit: Retrofit): ForecastApi = retrofit.create(ForecastApi::class.java)
}
