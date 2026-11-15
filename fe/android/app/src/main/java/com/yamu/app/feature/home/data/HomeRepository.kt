package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import com.yamu.app.feature.link.data.AccountsResponse
import com.yamu.app.feature.performance.data.BenefitLimitsDto
import com.yamu.app.feature.performance.data.CardPerformancesDto
import com.yamu.app.feature.performance.data.toCardPerf
import dagger.Module
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

interface HomeApi {
    /** #58 홈 요약 */
    @GET("home")
    suspend fun home(): Response<ApiEnvelope<HomeDto>>

    /** #76 금융 데이터 동기화 — SSAFY 금융망 계좌 잔액 · 거래내역 다시 읽기 */
    @POST("sync")
    suspend fun sync(): Response<ApiEnvelope<SyncDto>>

    /** #54 월간 소비 — 홈은 혜택 확정 · 예상 합계만 쓴다(S15P31E103-480) */
    @GET("reports/monthly")
    suspend fun monthly(@Query("yearMonth") yearMonth: String): Response<ApiEnvelope<HomeMonthlyDto>>

    /** 내 정보 — 홈은 결제 설정(자동 선택 · 기본 카드)만 쓴다 */
    @GET("users/me")
    suspend fun me(): Response<ApiEnvelope<HomeMeDto>>

    /** #65 카드별 실적(카드 목록 순서) — 내 카드 혜택 현황(S15P31E103-481) */
    @GET("users/me/card-performances")
    suspend fun cardPerformances(): Response<ApiEnvelope<CardPerformancesDto>>

    /** #32 혜택 한도 — 한도 그룹마다 이름 · 남은 금액(모르면 null) · 상태 */
    @GET("benefit-limits")
    suspend fun benefitLimits(): Response<ApiEnvelope<BenefitLimitsDto>>

    /** 잔고예보 — 홈 요약은 기준 계좌 하나 · N일(until=DAYS와 days를 같이, BE -318) */
    @GET("forecasts/balance")
    suspend fun forecast(
        @Query("accountIds") accountIds: List<Long>,
        @Query("until") until: String,
        @Query("days") days: Int,
    ): Response<ApiEnvelope<HomeForecastDto>>

    /** 연결 계좌(금융망을 부르지 않는다) — 요약 칸의 계좌 칩 */
    @GET("accounts")
    suspend fun accounts(): Response<ApiEnvelope<AccountsResponse>>

    /** #28 월간 혜택 리포트 — 홈은 YAMU 결제의 카드별 혜택(cards)만 쓴다(BE -489). 달 · 출처는 서버 기본값(지난달 · ALL)에 맡기지 않는다 */
    @GET("reports/monthly/benefits")
    suspend fun benefitReport(
        @Query("yearMonth") yearMonth: String,
        @Query("source") source: String,
    ): Response<ApiEnvelope<HomeBenefitReportDto>>
}

data class SyncResult(val syncedAtIso: String?, val failedCount: Int)

interface HomeRepository {
    suspend fun loadHome(): ApiResult<HomeSummary>

    /** 당겨서 새로고침 — 30초 안에 다시 부르면 서버를 부르지 않고 마지막 결과를 준다(서버도 같은 규칙) */
    suspend fun sync(): ApiResult<SyncResult>

    /**
     * 혜택 히어로(옛 서버, 홈 응답에 benefitSummary가 없을 때) — [month](한국 기준)의 확정 · 확정 전 혜택.
     * reports/monthly는 출처를 가르지 않는다(시드 거래엔 혜택 줄이 없어 사실상 YAMU 결제뿐). 서버 기본값에 맡기지 않고 달을 넘긴다
     */
    suspend fun loadBenefit(month: YearMonth): ApiResult<HomeBenefit>

    /** 결제 칸 — 자동 선택이 켜졌는지 · 기본 카드 */
    suspend fun loadPaySettings(): ApiResult<PaySettings>

    /** 내 카드 혜택 현황 · 놓치기 아까운 혜택 — 실적이 실패하면 실패, 한도만 실패하면 limits = null */
    suspend fun loadCards(): ApiResult<HomeCardsData>

    /** 잔고예보 요약(옛 서버) — [accountId] 하나 · [HOME_FORECAST_DAYS]일. 계좌 목록을 못 받으면 계좌 칩만 비운다 */
    suspend fun loadForecastBrief(accountId: Long): ApiResult<HomeForecastBrief>

    /** 요약 칸 계좌 칩 '신한은행 ·0275'(새 홈 칸은 계좌 번호만 준다). 못 받으면 null */
    suspend fun loadAccountLabel(accountId: Long): String?

    /** 카드별 [month] YAMU 결제 혜택(userCardId → 혜택). 응답에 cards가 없으면(옛 서버) 실패 */
    suspend fun loadCardBenefits(month: YearMonth): ApiResult<Map<Long, CardBenefit>>
}

@Singleton
class HomeRepositoryImpl @Inject constructor(
    private val api: HomeApi,
) : HomeRepository {

    private var lastSync: Pair<Long, SyncResult>? = null

    override suspend fun loadHome(): ApiResult<HomeSummary> =
        apiCall { api.home() }.map { it.toSummary(LocalDate.now(KST)) }

    override suspend fun sync(): ApiResult<SyncResult> {
        val now = System.currentTimeMillis()
        lastSync?.let { (at, result) -> if (now - at < SYNC_COOLDOWN_MS) return ApiResult.Success(result) }
        return apiCall { api.sync() }
            .map { SyncResult(it.syncedAt, it.failed.size) }
            .also { if (it is ApiResult.Success) lastSync = now to it.data }
    }

    override suspend fun loadBenefit(month: YearMonth): ApiResult<HomeBenefit> =
        apiCall { api.monthly(month.toString()) }.andThen { dto ->
            dto.toBenefit(LocalDate.now(KST))?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.Unknown(MISSING_BENEFIT))
        }

    override suspend fun loadPaySettings(): ApiResult<PaySettings> =
        apiCall { api.me() }.andThen { dto ->
            dto.toPaySettings()?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.Unknown(MISSING_PAY_SETTINGS))
        }

    override suspend fun loadCards(): ApiResult<HomeCardsData> = coroutineScope {
        val perf = async { apiCall { api.cardPerformances() } }
        val limits = async { apiCall { api.benefitLimits() } }
        val l = (limits.await() as? ApiResult.Success)?.data?.cards
        perf.await().map { dto -> HomeCardsData(dto.cards.map { it.toCardPerf() }, l) }
    }

    override suspend fun loadForecastBrief(accountId: Long): ApiResult<HomeForecastBrief> = coroutineScope {
        val forecast = async { apiCall { api.forecast(listOf(accountId), until = "DAYS", days = HOME_FORECAST_DAYS) } }
        val accounts = async { apiCall { api.accounts() } }
        val label = (accounts.await() as? ApiResult.Success)?.data?.accounts?.labelOf(accountId)
        forecast.await().map { it.toBrief(accountId, label) }
    }

    override suspend fun loadAccountLabel(accountId: Long): String? =
        (apiCall { api.accounts() } as? ApiResult.Success)?.data?.accounts?.labelOf(accountId)

    override suspend fun loadCardBenefits(month: YearMonth): ApiResult<Map<Long, CardBenefit>> =
        apiCall { api.benefitReport(month.toString(), source = YAMU_SOURCE) }.andThen { dto ->
            dto.toCardBenefits()?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.Unknown(MISSING_CARD_BENEFITS))
        }

    private companion object {
        /** 혜택 리포트 출처 — 홈 benefitSummary와 같은 YAMU 결제만 */
        const val YAMU_SOURCE = "YAMU"
        const val SYNC_COOLDOWN_MS = 30_000L
    }
}

/** 응답은 왔지만 홈이 쓸 칸이 없을 때의 코드 — 0원 · 자동 선택으로 지어내지 않고 그 칸만 실패로 그린다 */
const val MISSING_BENEFIT = "HOME_BENEFIT_MISSING"
const val MISSING_PAY_SETTINGS = "HOME_PAY_SETTINGS_MISSING"
const val MISSING_CARD_BENEFITS = "HOME_CARD_BENEFITS_MISSING"

/** 홈 응답은 왔지만 새 칸(benefitSummary · forecastPreview)만 실패했을 때(partialErrors)의 코드 */
const val HOME_AREA_FAILED = "HOME_AREA_FAILED"

private inline fun <T, R> ApiResult<T>.andThen(next: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Success -> next(data)
    is ApiResult.Failure -> this
}

@Module
@InstallIn(SingletonComponent::class)
object HomeApiModule {
    @Provides
    @Singleton
    fun homeApi(retrofit: Retrofit): HomeApi = retrofit.create(HomeApi::class.java)
}
