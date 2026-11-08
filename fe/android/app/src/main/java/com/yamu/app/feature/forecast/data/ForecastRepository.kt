package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.feature.link.data.AccountDto
import com.yamu.app.feature.link.data.CardDto
import com.yamu.app.feature.link.data.LinkApi
import com.yamu.app.feature.link.data.lastDigits
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
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

    @POST("cashflow-items")
    suspend fun savePlan(@Body body: PurchasePlanRequest): Response<ApiEnvelope<CashflowCreatedDto>>

    @POST("cashflow-items")
    suspend fun createItem(@Body body: CashflowCreateRequest): Response<ApiEnvelope<CashflowCreatedDto>>

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

    /** I-02 [계획 저장] — #20 일반 예정 지출. 일시불 결과만(할부는 화면에서 막는다). 재시도 키가 없어 실패해도 다시 보내지 않는다 */
    suspend fun savePlan(simulation: Simulation): ApiResult<SavedPlan>

    /** 방금 저장한 계획(I-05) — 앱이 다시 켜졌으면 없음 */
    fun savedPlan(planId: Long): SavedPlan?

    suspend fun deletePlan(planId: Long): ApiResult<Unit>

    /** F-02a — #20 예정 수입·지출 등록(새 항목 ID). 서버에 재시도 키가 없어 실패해도 다시 보내지 않는다 */
    suspend fun createItem(form: CashflowForm): ApiResult<Long>

    /** F-02 — 기간 안 가계부 회차(확정만, 회차마다 한 줄). 마지막 값은 수정 화면이 [cachedEntry]로 쓴다 */
    suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>>

    fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry?

    /** F-02b 수정 — 이후 전부는 규칙이 나뉘어 ID가 바뀔 수 있어 목록을 새로 부른다 */
    suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit>

    suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit>

    companion object {
        /** 연결한 계좌가 없다 — BE가 accountIds 없이 부르면 내는 코드와 같게 둬 화면이 한 곳에서 빈 상태로 바꾼다 */
        const val NO_ACCOUNT = "ACCOUNT_SELECTION_REQUIRED"
    }
}

/** 고른 계좌 중 아직 연결된 것만. 고른 게 없거나 모두 해제됐으면 연결 계좌 전체 */
fun ForecastSelection.accountIdsFrom(accounts: List<ForecastAccountOption>): List<Long> {
    val all = accounts.map { it.accountId }
    return accountIds?.let { ids -> all.filter { it in ids } }.orEmpty().ifEmpty { all }
}

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
    return SimCardOption(userCardId, nickname?.takeIf { it.isNotBlank() } ?: productName, productName, cardType.equals("CREDIT", ignoreCase = true), reason)
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
class ForecastRepositoryImpl @Inject constructor(private val api: ForecastApi, private val linkApi: LinkApi) : ForecastRepository {

    @Volatile private var last: Forecast? = null
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
        return apiCall { api.forecast(selection.accountIdsFrom(accounts), period?.until, period?.days) }
            .map { it.toForecast().copy(scope = selection.scopeFrom(accounts)) }
            .also { r -> if (r is ApiResult.Success) last = r.data }
    }

    override fun cached(): Forecast? = last

    override fun selection(): ForecastSelection = current

    override fun select(selection: ForecastSelection) {
        current = selection
    }

    override suspend fun accounts(): ApiResult<List<ForecastAccountOption>> =
        apiCall { linkApi.accounts(null) }.map { r -> r.accounts.map { it.toOption() } }

    override suspend fun simulate(query: SimulationQuery): ApiResult<Simulation> {
        val accounts = when (val a = accounts()) {
            is ApiResult.Failure -> return a
            is ApiResult.Success -> a.data
        }
        if (accounts.isEmpty()) return ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT))
        val body = query.toRequest(current.accountIdsFrom(accounts), current.period)
        return apiCall { api.simulate(body) }.map { it.toSimulation(query) }
    }

    @Volatile private var categoryCache: List<MerchantCategory>? = null

    override suspend fun categories(): ApiResult<List<MerchantCategory>> {
        categoryCache?.let { return ApiResult.Success(it) }
        return apiCall { api.categories() }
            .map { list -> list.map { MerchantCategory(it.merchantCategoryCode, it.merchantCategoryName) } }
            .also { if (it is ApiResult.Success) categoryCache = it.data }
    }

    override suspend fun merchants(categoryCode: String): ApiResult<List<CatalogMerchant>> =
        apiCall { api.merchants(categoryCode) }.map { list -> list.map { CatalogMerchant(it.merchantId, it.merchantName) } }

    override suspend fun simulationCards(): ApiResult<List<SimCardOption>> =
        apiCall { linkApi.cards() }.map { r -> r.cards.mapNotNull { it.toSimCardOption() } }

    override suspend fun savePlan(simulation: Simulation): ApiResult<SavedPlan> {
        val body = simulation.toPlanRequest()
        return apiCall { api.savePlan(body) }.map { r ->
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

    override suspend fun createItem(form: CashflowForm): ApiResult<Long> =
        apiCall { api.createItem(form.toRequest()) }.map { it.cashflowItemId }

    @Volatile private var entries: List<CashflowEntry> = emptyList()

    override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> =
        apiCall { api.cashflowItems(from.toString(), to.toString()) }
            .map { r -> r.items.mapNotNull { it.toEntry() } }
            .also { if (it is ApiResult.Success) entries = it.data }

    override fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry? =
        entries.firstOrNull { it.itemId == itemId && it.occurrenceDate == occurrenceDate }

    override suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit> =
        apiCallNoData { api.updateItem(itemId, body) }

    override suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit> =
        apiCallNoData { api.deleteItem(itemId, scope.name, occurrenceDate?.toString()) }
}

@Module
@InstallIn(SingletonComponent::class)
object ForecastApiModule {
    @Provides
    @Singleton
    fun forecastApi(retrofit: Retrofit): ForecastApi = retrofit.create(ForecastApi::class.java)
}
