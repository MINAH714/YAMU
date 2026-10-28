package com.yamu.app.feature.performance.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.core.security.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

/** 실적 · 유불리 — #65 · #66 · #18 · #35 · #32 · #8. 11 문서 7-2 */
interface PerformanceApi {
    @GET("users/{userId}/card-performances")
    suspend fun overview(@Path("userId") userId: String, @Query("yearMonth") yearMonth: String? = null): Response<ApiEnvelope<CardPerformancesDto>>

    @GET("user-cards/{id}/performance")
    suspend fun detail(@Path("id") userCardId: Long): Response<ApiEnvelope<PerformanceDetailDto>>

    @GET("user-cards/{id}/performance/analysis")
    suspend fun analysis(@Path("id") userCardId: Long, @Query("scenarioMoveItemIds") scenarioMoveItemIds: String? = null): Response<ApiEnvelope<PerformanceAnalysisDto>>

    /** 본문은 직접 만든다 — 되돌리기는 "decision": null이 있어야 해서(앱 JSON 설정은 null을 뺀다) */
    @POST("user-cards/{id}/performance/decisions")
    suspend fun decide(@Path("id") userCardId: Long, @Body body: JsonObject): Response<ApiEnvelope<JsonElement>>

    @PATCH("cashflow-items/{id}")
    suspend fun moveCashflow(@Path("id") cashflowItemId: Long, @Body body: MoveCashflowRequest): Response<ApiEnvelope<JsonElement>>

    @GET("benefit-limits")
    suspend fun limits(): Response<ApiEnvelope<BenefitLimitsDto>>
}

interface PerformanceRepository {
    /** R-01 — 매번 새로(결제 뒤 값이 바뀌어야 하므로) */
    suspend fun overview(): ApiResult<PerfOverview>

    /** 마지막으로 받은 목록 — R-02 · R-03의 카드 이름 · 탭 */
    fun cachedOverview(): PerfOverview?

    suspend fun detail(userCardId: Long): ApiResult<PerfDetail>

    /** R-03 — 달성 추천이고 옮길 수 있는 예정 지출이 있으면 what-if까지 */
    suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis>

    /** #35 — [decision]이 null이면 되돌리기 */
    suspend fun decide(userCardId: Long, decision: PerfDecision?, analysisVersion: String?): ApiResult<Unit>

    /** #8 — 예정 지출(통신비)의 결제 카드를 이 카드로 */
    suspend fun moveCashflow(cashflowItemId: Long, userCardId: Long): ApiResult<Unit>

    suspend fun limits(): ApiResult<BenefitLimits>
}

/** #35 본문 — null을 빼지 않도록 직접 */
fun decisionBody(decision: PerfDecision?, analysisVersion: String?): JsonObject = buildJsonObject {
    put("decision", decision?.let { JsonPrimitive(it.name) } ?: JsonNull)
    if (analysisVersion != null) put("analysisVersion", analysisVersion)
    put("muteNotifications", true)
}

@Singleton
class PerformanceRepositoryImpl @Inject constructor(
    private val api: PerformanceApi,
    private val sessionStore: SessionStore,
) : PerformanceRepository {

    @Volatile private var last: PerfOverview? = null

    override suspend fun overview(): ApiResult<PerfOverview> = coroutineScope {
        val userId = sessionStore.read().userId?.toString() ?: "me"
        when (val r = apiCall { api.overview(userId) }) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> {
                val cards = r.data.cards.map { it.toCardPerf() }
                // #65에 판정이 아직 없으면(보완 전) 기준 전 카드만 #18로 채운다
                val filled = cards.map { card ->
                    async {
                        if (!card.pending || card.guide != null) return@async card
                        when (val a = apiCall { api.analysis(card.userCardId) }) {
                            is ApiResult.Success -> card.copy(guide = PerfGuide.from(a.data.guide) ?: PerfGuide.INSUFFICIENT_DATA, decision = card.decision ?: readDecision(a.data.decision))
                            is ApiResult.Failure -> card.copy(guide = if (a.error is ApiError.DataInsufficient) PerfGuide.INSUFFICIENT_DATA else null)
                        }
                    }
                }.awaitAll()
                val overview = filled.toOverview(r.data.yearMonth, r.data.daysLeft, r.data.dataSyncedAt)
                last = overview
                ApiResult.Success(overview)
            }
        }
    }

    override fun cachedOverview(): PerfOverview? = last

    override suspend fun detail(userCardId: Long): ApiResult<PerfDetail> = apiCall { api.detail(userCardId) }.map { it.toDetail() }

    override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> {
        val base = apiCall { api.analysis(userCardId) }.map { it.toAnalysis() }
        val analysis = (base as? ApiResult.Success)?.data ?: return base
        val move = analysis.movable?.takeIf { analysis.guide == PerfGuide.ACHIEVE_RECOMMENDED } ?: return base
        // what-if: 옮겼다고 가정한 월말 예상(저장 안 함). 실패해도 화면은 그린다
        val scenario = apiCall { api.analysis(userCardId, move.cashflowItemId.toString()) }
        val projected = (scenario as? ApiResult.Success)?.data?.projection?.projectedRecognizedAmount
        return ApiResult.Success(analysis.copy(projectedIfMoved = projected))
    }

    override suspend fun decide(userCardId: Long, decision: PerfDecision?, analysisVersion: String?): ApiResult<Unit> =
        apiCallNoData { api.decide(userCardId, decisionBody(decision, analysisVersion)) }

    override suspend fun moveCashflow(cashflowItemId: Long, userCardId: Long): ApiResult<Unit> =
        apiCallNoData { api.moveCashflow(cashflowItemId, MoveCashflowRequest(userCardId)) }

    override suspend fun limits(): ApiResult<BenefitLimits> = apiCall { api.limits() }.map { it.toLimits() }
}

@Module
@InstallIn(SingletonComponent::class)
object PerformanceApiModule {
    @Provides
    @Singleton
    fun performanceApi(retrofit: Retrofit): PerformanceApi = retrofit.create(PerformanceApi::class.java)
}
