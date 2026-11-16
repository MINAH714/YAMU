package com.yamu.app.feature.payment.data

import com.yamu.app.core.format.KST
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
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 결제(고객 앱) — #5 · #64 · #77 · #12 · #1 · #3 · 추가 7 · #31 취소 · #42 메모. 가맹점 단말 API(#27 · #63)는 모의 POS 앱에만 */
interface PaymentApi {
    @POST("payments/barcodes")
    suspend fun issueCode(@Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String): Response<ApiEnvelope<BarcodeResponse>>

    /** [새 코드] — 인증 토큰 없이 이전 코드를 닫고 새 코드(S15P31E103-530). 응답은 발급과 같다 */
    @POST("payments/barcodes/{token}/reissue")
    suspend fun reissueCode(@Path("token") previousToken: String): Response<ApiEnvelope<BarcodeResponse>>

    /** 추천은 여기 recommendation으로만 받는다 — 결제 전 단일 추천(#64)은 명세 V2에서 빠졌고 가맹점 목록(#5)은 모의 POS만 쓴다 */
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

    @POST("payments/{id}/cancel")
    suspend fun cancel(
        @Path("id") paymentId: Long,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: CancelRequest,
    ): Response<ApiEnvelope<CancelResponseDto>>

    @PATCH("payments/{id}/memo")
    suspend fun memo(@Path("id") paymentId: Long, @Body body: MemoRequest): Response<ApiEnvelope<MemoResponseDto>>

    /** 메모 찾기 — 결제 내역(#4)을 한국 날짜 기간으로 */
    @GET("payments")
    suspend fun memoPage(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("page") page: Int,
        @Query("size") size: Int = 100,
    ): Response<ApiEnvelope<MemoPageDto>>
}

interface PaymentRepository {
    /** P-06 결제 코드 — 본인 인증 토큰(BARCODE) 필요 */
    suspend fun issueCode(stepUpToken: String): ApiResult<PayCode>

    /**
     * P-06 [새 코드] — 다시 인증하지 않고 [previousToken]을 닫고 새 코드를 받는다. 같은 기기에서 처음 인증한 지 10분 안이고
     * 이전 코드가 스캔 · 결제에 쓰이지 않았을 때만(임시 정책). 아니면 STEP_UP_REQUIRED(403)라 본인 인증 뒤 [issueCode]
     */
    suspend fun reissueCode(previousToken: String): ApiResult<PayCode>

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

    /** P-08 결제 전체 취소. 같은 결제를 다시 시도할 땐 같은 [idempotencyKey] — BE가 처음 결과를 그대로 준다 */
    suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult>

    /** P-08 메모. [memo]가 null · 빈 문자열이면 지운다. 저장된 메모(지웠으면 null)를 돌려준다 */
    suspend fun saveMemo(paymentId: Long, memo: String?): ApiResult<String?>

    /**
     * 상세에 memo가 없어 모를 때([PaymentReceipt.memoKnown] false) 결제 내역에서 찾는다. 못 찾거나 실패하면 null(여전히 모름).
     * BE 상세 응답에 memo가 생기면 필요 없다
     */
    suspend fun findMemo(paymentId: Long, paidAtIso: String?): KnownMemo? = null
}

@Singleton
class PaymentRepositoryImpl @Inject constructor(private val api: PaymentApi, private val edits: PaymentEdits) : PaymentRepository {

    private val receipts = ConcurrentHashMap<Long, PaymentReceipt>()

    override suspend fun issueCode(stepUpToken: String): ApiResult<PayCode> =
        apiCall { api.issueCode(stepUpToken) }.map { it.toPayCode() }

    override suspend fun reissueCode(previousToken: String): ApiResult<PayCode> =
        apiCall { api.reissueCode(previousToken) }.map { it.toPayCode() }

    private fun BarcodeResponse.toPayCode() =
        PayCode(barcodeToken, barcodeValue ?: barcodeToken, qrValue ?: "yamu://pay/$barcodeToken", expiresAt)

    override suspend fun codeStatus(token: String): ApiResult<PayCodeStatus> = apiCall { api.codeStatus(token) }.map { it.toStatus() }

    override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> {
        val body = ApproveRequest(card.userCardId)
        return apiCall { api.approve(request.paymentId, idempotencyKey, body) }.map { r ->
            if (r.status.equals("APPROVED", ignoreCase = true)) {
                val receipt = r.toReceipt(request, card)
                receipts[r.paymentId] = receipt
                ApproveOutcome.Approved(receipt)
            } else {
                r.toDeclined()
            }
        }
    }

    override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> =
        apiCallNoData { api.reject(paymentId, idempotencyKey, RejectRequest()) }

    override fun recentReceipt(paymentId: Long): PaymentReceipt? = receipts[paymentId]

    /**
     * 상세 응답엔 memo가 없어 목록에서 받아 둔 메모로 채운다([PaymentEdits]). 받아 둔 게 없으면 '모름'.
     * 취소된 결제면 취소 응답 때처럼 알린다 — 응답이 끊겨 다시 읽어 보니 취소됐거나 다른 기기에서 취소한 경우에도
     * 결제 완료(P-07) · 결제 내역이 승인으로 남지 않게
     */
    override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> =
        apiCall { api.detail(paymentId) }.map { dto ->
            val receipt = dto.toReceipt()
            if (receipt.canceled) noteCanceled(paymentId, receipt.canceledAtIso)
            when {
                receipt.memoKnown -> receipt.also { edits.rememberMemo(paymentId, it.memo) }
                else -> edits.memo(paymentId)?.let { receipt.copy(memo = it.text, memoKnown = true) } ?: receipt
            }
        }

    /**
     * 결제일(한국 날짜)과 그 전날의 결제 내역에서 찾는다 — 목록은 요청 시각으로 거르므로 자정 직전 요청 · 직후 승인이면 전날에 있다.
     * 받은 줄의 메모는 모두 기억한다. 기본 목록은 승인 · 취소만이라 거절된 결제는 못 찾는다(모름으로 남는다)
     */
    override suspend fun findMemo(paymentId: Long, paidAtIso: String?): KnownMemo? {
        edits.memo(paymentId)?.let { return it }
        val day = paidAtIso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST).toLocalDate() }.getOrNull() } ?: return null
        for (page in 1..MEMO_LOOKUP_PAGES) {
            val result = apiCall { api.memoPage(from = day.minusDays(1).toString(), to = day.toString(), page = page) }
            val data = (result as? ApiResult.Success)?.data ?: return null
            data.items.forEach { edits.rememberMemo(it.paymentId, it.memo) }
            data.items.firstOrNull { it.paymentId == paymentId }?.let { return KnownMemo(it.memo) }
            if (!data.hasNext) return null
        }
        return null
    }

    override suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult> =
        apiCall { api.cancel(paymentId, idempotencyKey, CancelRequest()) }.map { it.toResult() }.also {
            if (it is ApiResult.Success) noteCanceled(paymentId, it.data.canceledAtIso)
        }

    /** 방금 승인한 영수증(P-07)이 승인 상태로 남지 않게 지우고, 처음 알게 된 취소일 때만 다른 화면에 알린다 */
    private fun noteCanceled(paymentId: Long, canceledAtIso: String?) {
        receipts.remove(paymentId)
        if (edits.markCanceled(paymentId, canceledAtIso)) edits.changed()
    }

    override suspend fun saveMemo(paymentId: Long, memo: String?): ApiResult<String?> =
        apiCall { api.memo(paymentId, MemoRequest(memo.orEmpty())) }.map { it.memo }.also {
            if (it is ApiResult.Success) {
                edits.rememberMemo(paymentId, it.data)
                edits.changed()
            }
        }

    private companion object {
        /** 하루 이틀 결제가 100건을 넘는 일은 드물다 — 3쪽(300건)까지만 보고 모름으로 둔다 */
        const val MEMO_LOOKUP_PAGES = 3
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PaymentApiModule {
    @Provides
    @Singleton
    fun paymentApi(retrofit: Retrofit): PaymentApi = retrofit.create(PaymentApi::class.java)
}
