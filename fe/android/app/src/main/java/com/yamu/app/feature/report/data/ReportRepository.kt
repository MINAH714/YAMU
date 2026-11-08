package com.yamu.app.feature.report.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallFile
import com.yamu.app.core.network.map
import com.yamu.app.feature.payment.data.PaymentEdits
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** 소비분석 — #54 · #55 · #4 · #63. 13 문서 9·10-3 */
interface ReportApi {
    @GET("reports/monthly")
    suspend fun monthly(@Query("yearMonth") yearMonth: String? = null): Response<ApiEnvelope<MonthlyReportDto>>

    /** 기본값이 지난달이라 이번 달을 넘긴다 */
    @GET("reports/monthly/benefits")
    suspend fun benefits(@Query("yearMonth") yearMonth: String): Response<ApiEnvelope<BenefitReportDto>>

    @GET("payments")
    suspend fun payments(@Query("page") page: Int, @Query("size") size: Int, @Query("yearMonth") yearMonth: String? = null): Response<ApiEnvelope<PaymentsPageDto>>

    /** 결제 내역과 같은 조건(page · size 빼고). 성공은 text/csv 파일, 실패는 공통 JSON */
    @GET("payments/export")
    suspend fun export(@Query("yearMonth") yearMonth: String? = null): Response<ResponseBody>
}

interface ReportRepository {
    /** S-01 — 이번 달 소비 + 결제 건수 */
    suspend fun monthly(): ApiResult<MonthlySpend>

    /** S-04 — 이번 달 받은 · 놓친 혜택 */
    suspend fun benefits(): ApiResult<BenefitReport>

    /** S-05 — 이번 달 결제 내역(쪽마다 20건) */
    suspend fun payments(page: Int): ApiResult<PaymentsPage>

    /** S-05 '이 조건으로 CSV 저장' — 화면과 같은 이번 달. 분당 3번까지(429 RATE_LIMITED) */
    suspend fun exportCsv(): ApiResult<CsvFile>
}

@Singleton
class ReportRepositoryImpl @Inject constructor(private val api: ReportApi, private val edits: PaymentEdits) : ReportRepository {

    override suspend fun monthly(): ApiResult<MonthlySpend> = coroutineScope {
        val report = async { apiCall { api.monthly() } }
        val count = async { apiCall { api.payments(page = 1, size = 1) } }   // 건수만(#54에 없음)
        val c = (count.await() as? ApiResult.Success)?.data?.summary?.count
        report.await().map { it.toSpend(c) }
    }

    override suspend fun benefits(): ApiResult<BenefitReport> =
        apiCall { api.benefits(YearMonth.now().toString()) }.map { it.toReport() }

    /** 목록의 메모를 받아 둔다 — 결제 상세 응답엔 memo가 없다 */
    override suspend fun payments(page: Int): ApiResult<PaymentsPage> =
        apiCall { api.payments(page = page, size = PAGE_SIZE) }.map { dto ->
            dto.items.forEach { edits.rememberMemo(it.paymentId, it.memo) }
            dto.toPage()
        }

    override suspend fun exportCsv(): ApiResult<CsvFile> =
        apiCallFile { api.export() }.map { CsvFile(csvFileName(it.contentDisposition, fallback = defaultCsvName()), it.bytes) }

    companion object { const val PAGE_SIZE = 20 }
}

@Module
@InstallIn(SingletonComponent::class)
object ReportApiModule {
    @Provides
    @Singleton
    fun reportApi(retrofit: Retrofit): ReportApi = retrofit.create(ReportApi::class.java)
}
