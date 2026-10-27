package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiEnvelope
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
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

interface HomeApi {
    /** #58 홈 요약 */
    @GET("home")
    suspend fun home(): Response<ApiEnvelope<HomeDto>>

    /** #76 금융 데이터 동기화 — SSAFY 금융망 계좌 잔액 · 거래내역 다시 읽기 */
    @POST("sync")
    suspend fun sync(): Response<ApiEnvelope<SyncDto>>
}

data class SyncResult(val syncedAtIso: String?, val failedCount: Int)

interface HomeRepository {
    suspend fun loadHome(): ApiResult<HomeSummary>

    /** 당겨서 새로고침 — 30초 안에 다시 부르면 서버를 부르지 않고 마지막 결과를 준다(서버도 같은 규칙) */
    suspend fun sync(): ApiResult<SyncResult>
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

    private companion object {
        const val SYNC_COOLDOWN_MS = 30_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
object HomeApiModule {
    @Provides
    @Singleton
    fun homeApi(retrofit: Retrofit): HomeApi = retrofit.create(HomeApi::class.java)
}
