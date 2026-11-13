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

    /** 서버 기본값이 지난달이라 늘 달을 넘긴다 */
    @GET("reports/monthly/benefits")
    suspend fun benefits(@Query("yearMonth") yearMonth: String): Response<ApiEnvelope<BenefitReportDto>>

    @GET("payments")
    suspend fun payments(@Query("page") page: Int, @Query("size") size: Int, @Query("yearMonth") yearMonth: String? = null): Response<ApiEnvelope<PaymentsPageDto>>

    /** 결제 내역과 같은 조건(page · size 빼고). 성공은 text/csv 파일, 실패는 공통 JSON */
    @GET("payments/export")
    suspend fun export(@Query("yearMonth") yearMonth: String? = null): Response<ResponseBody>
}

/** 달([month])은 모두 한국 기준이고, 서버 기본값(이번 달 · 지난달)에 맡기지 않고 늘 넘긴다 — 세 화면과 CSV가 같은 달을 보게([ReportMonth]) */
interface ReportRepository {
    /** S-01 — 그달 소비 + 결제 건수 */
    suspend fun monthly(month: YearMonth): ApiResult<MonthlySpend>

    /** S-04 — 그달 받은 · 놓친 혜택 */
    suspend fun benefits(month: YearMonth): ApiResult<BenefitReport>

    /** S-05 — 그달 결제 내역(쪽마다 20건) */
    suspend fun payments(page: Int, month: YearMonth): ApiResult<PaymentsPage>

    /**
     * S-05 '이 조건으로 CSV 저장' — [month](한국 기준) 한 달. 분당 3번까지(429 RATE_LIMITED).
     * 달을 넘기는 이유: 저장 창에 보인 이름의 달과 내용의 달이 말일 자정 무렵에도 같게
     */
    suspend fun exportCsv(month: YearMonth): ApiResult<CsvFile>
}

@Singleton
class ReportRepositoryImpl @Inject constructor(private val api: ReportApi, private val edits: PaymentEdits) : ReportRepository {

    override suspend fun monthly(month: YearMonth): ApiResult<MonthlySpend> = coroutineScope {
        val ym = month.toString()
        val report = async { apiCall { api.monthly(ym) } }
        val count = async { apiCall { api.payments(page = 1, size = 1, yearMonth = ym) } }   // 건수만(#54에 없음) — 같은 달로
        val c = (count.await() as? ApiResult.Success)?.data?.summary?.count
        report.await().map { it.toSpend(c) }
    }

    override suspend fun benefits(month: YearMonth): ApiResult<BenefitReport> =
        apiCall { api.benefits(month.toString()) }.map { it.toReport() }

    /** 목록의 메모를 받아 둔다 — 결제 상세 응답엔 memo가 없다. 메모는 결제 번호로 묶여 있어 달이 바뀌어도 그대로 맞다 */
    override suspend fun payments(page: Int, month: YearMonth): ApiResult<PaymentsPage> =
        apiCall { api.payments(page = page, size = PAGE_SIZE, yearMonth = month.toString()) }.map { dto ->
            dto.items.forEach { edits.rememberMemo(it.paymentId, it.memo) }
            dto.toPage()
        }

    override suspend fun exportCsv(month: YearMonth): ApiResult<CsvFile> =
        apiCallFile { api.export(yearMonth = month.toString()) }.map { CsvFile(csvFileName(it.contentDisposition, fallback = defaultCsvName(month)), it.bytes) }

    companion object { const val PAGE_SIZE = 20 }
}

@Module
@InstallIn(SingletonComponent::class)
object ReportApiModule {
    @Provides
    @Singleton
    fun reportApi(retrofit: Retrofit): ReportApi = retrofit.create(ReportApi::class.java)
}
