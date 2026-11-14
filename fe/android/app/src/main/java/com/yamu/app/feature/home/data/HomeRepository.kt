package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import dagger.Module
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
}

data class SyncResult(val syncedAtIso: String?, val failedCount: Int)

interface HomeRepository {
    suspend fun loadHome(): ApiResult<HomeSummary>

    /** 당겨서 새로고침 — 30초 안에 다시 부르면 서버를 부르지 않고 마지막 결과를 준다(서버도 같은 규칙) */
    suspend fun sync(): ApiResult<SyncResult>

    /** 혜택 히어로 — [month](한국 기준)의 확정 · 확정 전 혜택. 서버 기본값에 맡기지 않고 달을 넘긴다 */
    suspend fun loadBenefit(month: YearMonth): ApiResult<HomeBenefit>

    /** 결제 칸 — 자동 선택이 켜졌는지 · 기본 카드 */
    suspend fun loadPaySettings(): ApiResult<PaySettings>
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

    private companion object {
        const val SYNC_COOLDOWN_MS = 30_000L
    }
}

/** 응답은 왔지만 홈이 쓸 칸이 없을 때의 코드 — 0원 · 자동 선택으로 지어내지 않고 그 칸만 실패로 그린다 */
const val MISSING_BENEFIT = "HOME_BENEFIT_MISSING"
const val MISSING_PAY_SETTINGS = "HOME_PAY_SETTINGS_MISSING"

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
