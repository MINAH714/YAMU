package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.feature.link.data.AccountDto
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

    @POST("spending-decisions")
    suspend fun check(@Body body: SpendingDecisionRequest): Response<ApiEnvelope<SpendingDecisionDto>>

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

    /** I-02 · I-03 — #33 판정 그대로 */
    suspend fun check(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck>

    /** I-04 · I-02 [계획 저장] — #20 */
    suspend fun savePlan(option: BuyOption, title: String?, amount: Long): ApiResult<SavedPlan>

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

fun AccountDto.toOption() = ForecastAccountOption(
    accountId = accountId,
    name = accountName?.takeIf { it.isNotBlank() } ?: "${bank.name} 입출금",
    bankName = bank.name,
    last4 = maskedAccountNo.lastDigits(),
    balance = balance,
    isPrimary = isPrimary,
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
        val period = current.period
        return apiCall { api.forecast(current.accountIdsFrom(accounts), period?.until, period?.days) }
            .map { it.toForecast() }
            .also { r -> if (r is ApiResult.Success) last = r.data }
    }

    override fun cached(): Forecast? = last

    override fun selection(): ForecastSelection = current

    override fun select(selection: ForecastSelection) {
        current = selection
    }

    override suspend fun accounts(): ApiResult<List<ForecastAccountOption>> =
        apiCall { linkApi.accounts(null) }.map { r -> r.accounts.map { it.toOption() } }

    override suspend fun check(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck> =
        apiCall { api.check(SpendingDecisionRequest(amount, title?.takeIf { it.isNotBlank() }, purchaseDate.toString())) }
            .map { it.toBuyCheck(title, amount, purchaseDate) }

    override suspend fun savePlan(option: BuyOption, title: String?, amount: Long): ApiResult<SavedPlan> {
        val accountId = if (option.userCardId != null) null else when (val a = apiCall { linkApi.accounts(null) }) {
            is ApiResult.Failure -> return a
            is ApiResult.Success -> a.data.accounts.firstOrNull { it.isPrimary }?.accountId
                ?: return ApiResult.Failure(ApiError.InvalidRequest("ACCOUNT_NOT_LINKED"))
        }
        val body = option.toPlanRequest(title, amount, accountId)
        return apiCall { api.savePlan(body) }.map { r ->
            SavedPlan(
                id = r.cashflowItemId,
                title = body.title,
                amount = amount,
                date = parseDate(r.occurrenceDate) ?: option.date,
                minBefore = null,
                minAfter = null,
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
