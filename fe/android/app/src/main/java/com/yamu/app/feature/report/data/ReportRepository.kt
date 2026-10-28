package com.yamu.app.feature.report.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** 소비분석 — #54 · #55 · #4. 13 문서 9·10-3 */
interface ReportApi {
    @GET("reports/monthly")
    suspend fun monthly(@Query("yearMonth") yearMonth: String? = null): Response<ApiEnvelope<MonthlyReportDto>>

    /** 기본값이 지난달이라 이번 달을 넘긴다 */
    @GET("reports/monthly/benefits")
    suspend fun benefits(@Query("yearMonth") yearMonth: String): Response<ApiEnvelope<BenefitReportDto>>

    @GET("payments")
    suspend fun payments(@Query("page") page: Int, @Query("size") size: Int, @Query("yearMonth") yearMonth: String? = null): Response<ApiEnvelope<PaymentsPageDto>>
}

interface ReportRepository {
    /** S-01 — 이번 달 소비 + 결제 건수 */
    suspend fun monthly(): ApiResult<MonthlySpend>

    /** S-04 — 이번 달 받은 · 놓친 혜택 */
    suspend fun benefits(): ApiResult<BenefitReport>

    /** S-05 — 이번 달 결제 내역(쪽마다 20건) */
    suspend fun payments(page: Int): ApiResult<PaymentsPage>
}

@Singleton
class ReportRepositoryImpl @Inject constructor(private val api: ReportApi) : ReportRepository {

    override suspend fun monthly(): ApiResult<MonthlySpend> = coroutineScope {
        val report = async { apiCall { api.monthly() } }
        val count = async { apiCall { api.payments(page = 1, size = 1) } }   // 건수만(#54에 없음)
        val c = (count.await() as? ApiResult.Success)?.data?.summary?.count
        report.await().map { it.toSpend(c) }
    }

    override suspend fun benefits(): ApiResult<BenefitReport> =
        apiCall { api.benefits(YearMonth.now().toString()) }.map { it.toReport() }

    override suspend fun payments(page: Int): ApiResult<PaymentsPage> =
        apiCall { api.payments(page = page, size = PAGE_SIZE) }.map { it.toPage() }

    companion object { const val PAGE_SIZE = 20 }
}

@Module
@InstallIn(SingletonComponent::class)
object ReportApiModule {
    @Provides
    @Singleton
    fun reportApi(retrofit: Retrofit): ReportApi = retrofit.create(ReportApi::class.java)
}
