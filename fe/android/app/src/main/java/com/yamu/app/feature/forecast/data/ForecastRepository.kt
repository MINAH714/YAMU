package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
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
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 잔고예보 · 이거 사도 될까? — #10 · #33 · #20 · #9. 12 문서 8-2 */
interface ForecastApi {
    @GET("forecasts/balance")
    suspend fun forecast(@Query("until") until: String = "NEXT_INCOME"): Response<ApiEnvelope<ForecastDto>>

    @POST("spending-decisions")
    suspend fun check(@Body body: SpendingDecisionRequest): Response<ApiEnvelope<SpendingDecisionDto>>

    @POST("cashflow-items")
    suspend fun savePlan(@Body body: PurchasePlanRequest): Response<ApiEnvelope<CashflowCreatedDto>>

    @POST("cashflow-items")
    suspend fun createItem(@Body body: CashflowCreateRequest): Response<ApiEnvelope<CashflowCreatedDto>>

    @DELETE("cashflow-items/{id}")
    suspend fun deletePlan(@Path("id") cashflowItemId: Long): Response<ApiEnvelope<JsonElement>>
}

interface ForecastRepository {
    /** F-01 · F-02 — 돌아올 때마다 새로(결제 · 계획 저장 뒤 바뀌므로) */
    suspend fun forecast(): ApiResult<Forecast>

    fun cached(): Forecast?

    /** I-02 · I-03 — #33 판정 그대로 */
    suspend fun check(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck>

    /** I-04 · I-02 [계획 저장] — #20 PURCHASE_PLAN */
    suspend fun savePlan(option: BuyOption, title: String?, amount: Long): ApiResult<SavedPlan>

    /** 방금 저장한 계획(I-05) — 앱이 다시 켜졌으면 없음(잔고예보의 계획 줄에서 다시) */
    fun savedPlan(planId: Long): SavedPlan?

    suspend fun deletePlan(planId: Long): ApiResult<Unit>

    /** F-02a — #20 예정 수입·지출 등록(새 항목 ID). 서버에 재시도 키가 없어 실패해도 다시 보내지 않는다 */
    suspend fun createItem(form: CashflowForm): ApiResult<Long>
}

@Singleton
class ForecastRepositoryImpl @Inject constructor(private val api: ForecastApi) : ForecastRepository {

    @Volatile private var last: Forecast? = null
    private val plans = ConcurrentHashMap<Long, SavedPlan>()

    override suspend fun forecast(): ApiResult<Forecast> =
        apiCall { api.forecast() }.map { it.toForecast() }.also { r -> if (r is ApiResult.Success) last = r.data }

    override fun cached(): Forecast? = last

    override suspend fun check(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck> =
        apiCall { api.check(SpendingDecisionRequest(amount, title?.takeIf { it.isNotBlank() }, purchaseDate.toString())) }
            .map { it.toBuyCheck(title, amount, purchaseDate) }

    override suspend fun savePlan(option: BuyOption, title: String?, amount: Long): ApiResult<SavedPlan> {
        val body = option.toPlanRequest(title, amount)
        return apiCall { api.savePlan(body) }.map { r ->
            SavedPlan(
                id = r.cashflowItemId,
                title = body.title,
                amount = amount,
                date = parseDate(r.occurrenceDate) ?: option.date,
                minBefore = r.forecastImpact?.minBalanceBefore,
                minAfter = r.forecastImpact?.minBalanceAfter,
                shortfallFrom = parseDate(r.forecastImpact?.shortfallFrom),
            ).also { plans[it.id] = it }
        }
    }

    override fun savedPlan(planId: Long): SavedPlan? = plans[planId]

    override suspend fun deletePlan(planId: Long): ApiResult<Unit> =
        apiCallNoData { api.deletePlan(planId) }.also { if (it is ApiResult.Success) plans.remove(planId) }

    override suspend fun createItem(form: CashflowForm): ApiResult<Long> =
        apiCall { api.createItem(form.toRequest()) }.map { it.cashflowItemId }
}

@Module
@InstallIn(SingletonComponent::class)
object ForecastApiModule {
    @Provides
    @Singleton
    fun forecastApi(retrofit: Retrofit): ForecastApi = retrofit.create(ForecastApi::class.java)
}
