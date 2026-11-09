package com.yamu.app.feature.billing.data

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
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

/** 청구서 목록 · 상세 · 환급 내역 — BE BillQueryController · RefundQueryController. 조회만 한다 */
interface BillingApi {
    /** null인 쿼리는 Retrofit이 보내지 않는다 — 카드를 고르지 않으면 전체 신용카드 */
    @GET("billings")
    suspend fun bills(@Query("userCardId") userCardId: Long?): Response<ApiEnvelope<BillListDto>>

    @GET("billings/{billId}")
    suspend fun bill(@Path("billId") billId: Long): Response<ApiEnvelope<BillDetailDto>>

    /** 기간을 보내지 않으면 오늘을 포함한 최근 3개월(생성일 기준) */
    @GET("refunds")
    suspend fun refunds(@Query("page") page: Int, @Query("size") size: Int = REFUND_PAGE_SIZE): Response<ApiEnvelope<RefundPageDto>>

    companion object {
        const val REFUND_PAGE_SIZE = 20
    }
}

interface BillingRepository {
    /** 끝나지 않은 청구서 전부와 최근 3개월. [userCardId]를 주면 그 카드만 — 없는 카드 · 남의 카드는 404, 체크카드는 빈 목록 */
    suspend fun bills(userCardId: Long? = null): ApiResult<BillList>

    /** 없는 청구서와 남의 청구서는 BE가 똑같이 404로 답한다 */
    suspend fun bill(billId: Long): ApiResult<BillDetail>

    /** 최근 3개월 환급(생성 최신순). [page]는 1부터 */
    suspend fun refunds(page: Int): ApiResult<RefundPage>
}

@Singleton
class BillingRepositoryImpl @Inject constructor(private val api: BillingApi) : BillingRepository {
    override suspend fun bills(userCardId: Long?): ApiResult<BillList> = apiCall { api.bills(userCardId) }.map { it.toBillList() }

    override suspend fun bill(billId: Long): ApiResult<BillDetail> = apiCall { api.bill(billId) }.map { it.toBillDetail(billId) }

    override suspend fun refunds(page: Int): ApiResult<RefundPage> = apiCall { api.refunds(page) }.map { it.toRefundPage(page) }
}

@Module
@InstallIn(SingletonComponent::class)
object BillingApiModule {
    @Provides
    @Singleton
    fun billingApi(retrofit: Retrofit): BillingApi = retrofit.create(BillingApi::class.java)
}
