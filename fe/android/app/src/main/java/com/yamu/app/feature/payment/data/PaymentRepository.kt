package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
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
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 결제(고객 앱) — #5 · #64 · #77 · #12 · #1 · #3 · 추가 7. 가맹점 단말 API(#27 · #63)는 모의 POS 앱에만 */
interface PaymentApi {
    @GET("merchants")
    suspend fun merchants(@Query("channel") channel: String?, @Query("size") size: Int = 50): Response<ApiEnvelope<MerchantsResponse>>

    @POST("recommendations")
    suspend fun recommend(@Body body: RecommendRequest): Response<ApiEnvelope<RecommendResponse>>

    @POST("payments/barcodes")
    suspend fun issueCode(@Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String): Response<ApiEnvelope<BarcodeResponse>>

    @GET("payments/barcodes/{token}")
    suspend fun codeStatus(@Path("token") token: String): Response<ApiEnvelope<BarcodeStatusResponse>>

    @POST("payments/{id}/approve")
    suspend fun approve(
        @Path("id") paymentId: Long,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: ApproveRequest,
    ): Response<ApiEnvelope<PaymentResultDto>>

    @POST("payments/{id}/reject")
    suspend fun reject(
        @Path("id") paymentId: Long,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: RejectRequest,
    ): Response<ApiEnvelope<JsonElement>>

    @GET("payments/{id}")
    suspend fun detail(@Path("id") paymentId: Long): Response<ApiEnvelope<PaymentResultDto>>
}

interface PaymentRepository {
    /** P-01 가맹점 고르기 — 오프라인 · 온라인 모두 */
    suspend fun merchants(): ApiResult<List<Merchant>>

    /** P-01 — 서버 추천(#64) 그대로 */
    suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation>

    /** P-06 결제 코드 — 본인 인증 토큰(BARCODE) 필요 */
    suspend fun issueCode(stepUpToken: String): ApiResult<PayCode>

    /** 1초마다 — 가맹점이 찍으면 결제 요청 · 추천 2장이 같이 온다 */
    suspend fun codeStatus(token: String): ApiResult<PayCodeStatus>

    /** P-06c 확정. 같은 결제 · 카드 · 할부를 다시 시도할 땐 같은 [idempotencyKey] */
    suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome>

    /** P-06c [거절] — 코드도 바로 닫힌다 */
    suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit>

    /** 방금 승인한 결과(P-07) — 앱이 다시 켜졌으면 없음 → [detail] */
    fun recentReceipt(paymentId: Long): PaymentReceipt?

    /** #3 결제 상세(P-08 · 앱이 다시 켜진 P-07) */
    suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt>
}

@Singleton
class PaymentRepositoryImpl @Inject constructor(private val api: PaymentApi) : PaymentRepository {

    private val receipts = ConcurrentHashMap<Long, PaymentReceipt>()

    override suspend fun merchants(): ApiResult<List<Merchant>> =
        apiCall { api.merchants(channel = null) }.map { r -> r.items.map { it.toMerchant() } }

    override suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation> =
        apiCall { api.recommend(RecommendRequest(merchantId, amount)) }.map { r ->
            Recommendation(
                merchantName = r.merchant?.name.orEmpty(),
                amount = r.amount.takeIf { it > 0 } ?: amount,
                cards = r.candidates.sortedBy { it.rank }.map { it.toPayCard() },
                isEstimate = r.isEstimate,
            )
        }

    override suspend fun issueCode(stepUpToken: String): ApiResult<PayCode> =
        apiCall { api.issueCode(stepUpToken) }.map {
            PayCode(it.barcodeToken, it.barcodeValue ?: it.barcodeToken, it.qrValue ?: "yamu://pay/${it.barcodeToken}", it.expiresAt)
        }

    override suspend fun codeStatus(token: String): ApiResult<PayCodeStatus> = apiCall { api.codeStatus(token) }.map { it.toStatus() }

    override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> {
        val body = ApproveRequest(card.userCardId)
        return apiCall { api.approve(request.paymentId, idempotencyKey, body) }.map { r ->
            if (r.status.equals("APPROVED", ignoreCase = true)) {
                val receipt = r.toReceipt(request, card)
                receipts[r.paymentId] = receipt
                ApproveOutcome.Approved(receipt)
            } else {
                ApproveOutcome.Declined(
                    message = declineMessage(r.failure?.code),
                    retryable = r.retryable,
                    remainingAttempts = r.remainingAttempts,
                    suggestedCardId = r.suggestedCards.firstOrNull()?.userCardId,
                )
            }
        }
    }

    override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> =
        apiCallNoData { api.reject(paymentId, idempotencyKey, RejectRequest()) }

    override fun recentReceipt(paymentId: Long): PaymentReceipt? = receipts[paymentId]

    override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> = apiCall { api.detail(paymentId) }.map { it.toReceipt() }
}

@Module
@InstallIn(SingletonComponent::class)
object PaymentApiModule {
    @Provides
    @Singleton
    fun paymentApi(retrofit: Retrofit): PaymentApi = retrofit.create(PaymentApi::class.java)
}
