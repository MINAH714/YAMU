package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.BigDecimalSerializer
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.IssuerDto
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.link.data.lastDigits
import kotlinx.serialization.Serializable
import java.math.BigDecimal

// ── 서버 요청 · 응답 — #5 가맹점 · #64 추천 · #77 코드 발급 · #12 코드 상태 · #1 승인 · #3 상세 · 추가 7 거절. 10 문서 6-2 ──

@Serializable data class MerchantsResponse(val items: List<MerchantDto> = emptyList())

@Serializable data class MerchantDto(
    val merchantId: Long,
    val name: String,
    val categoryCode: String? = null,
    val categoryName: String? = null,
    val channel: String = "OFFLINE",
    val posCode: String? = null,
)

@Serializable data class MerchantRefDto(
    val merchantId: Long? = null,
    val name: String = "가맹점",                // BE에서 null일 수 있다
    val categoryCode: String? = null,
    val categoryName: String? = null,
)

@Serializable data class RecommendRequest(val merchantId: Long, val amount: Long, val channel: String = "OFFLINE")

@Serializable data class RecommendResponse(
    val merchant: MerchantRefDto? = null,
    val amount: Long = 0,
    val candidates: List<CandidateDto> = emptyList(),
    val isEstimate: Boolean = false,
)

@Serializable data class CandidateDto(
    val rank: Int = 0,
    val userCardId: Long,
    val nickname: String? = null,
    val issuerName: String? = null,          // #64
    val issuer: IssuerDto? = null,           // 보완안(#12 후보에도 — 10 문서 6-8 ④)
    val cardType: String = "CREDIT",
    val maskedNumber: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val netBenefit: BigDecimal = BigDecimal.ZERO,   // 포인트 환산율에 소수가 있으면 1.5처럼 온다(S15P31E103-283)
    val benefits: List<BenefitDto> = emptyList(),
    val chargedAmount: Long? = null,
    val installmentOptions: List<Int> = emptyList(),
    val reason: String? = null,
    val performanceImpact: PerformanceImpactDto? = null,
)

@Serializable data class BenefitDto(
    val name: String = "혜택",                 // #3 상세에서 null일 수 있다
    val type: String? = null,                // BILLING_DISCOUNT · INSTANT_DISCOUNT · POINT …
    val amount: Long = 0,
    val expectedAmount: Long? = null,        // #3
    val actualAmount: Long? = null,
)

@Serializable data class PerformanceImpactDto(val recognized: Boolean = true, val note: String? = null)

@Serializable data class BarcodeResponse(
    val barcodeToken: String,
    val barcodeValue: String? = null,
    val qrValue: String? = null,
    val status: String = "ISSUED",
    val expiresAt: String,
)

@Serializable data class BarcodeStatusResponse(
    val status: String,                      // ISSUED · SCANNED · USED · EXPIRED
    val expiresAt: String? = null,
    val payment: PaymentRequestDto? = null,
)

@Serializable data class PaymentRequestDto(
    val paymentId: Long,
    val status: String = "REQUESTED",
    val merchant: MerchantRefDto,
    val amount: Long,
    val plan: String = "LUMP_SUM",           // POS가 정한다
    val installmentMonths: Int? = null,
    val confirmDeadline: String? = null,
    val recommendation: RecommendationDto? = null,
)

@Serializable data class RecommendationDto(
    val status: String? = null,              // 임시(명세 반영 전) READY · PARTIAL_READY · NO_ELIGIBLE_CARD · DATA_INSUFFICIENT
    val candidates: List<CandidateDto> = emptyList(),
    val excludedCount: Int = 0,
    val isEstimate: Boolean = false,
)

/** 결제 방식 · 할부 개월은 POS가 정한다 — BE는 이 칸들이 오면 400 INVALID_INSTALLMENT */
@Serializable data class ApproveRequest(val userCardId: Long)

@Serializable data class RejectRequest(val reason: String = "USER_REJECTED")

/** #1 승인 응답과 #3 상세 응답 — 같은 모양으로 받는다(없는 필드는 null) */
@Serializable data class PaymentResultDto(
    val paymentId: Long,
    val status: String,                      // APPROVED · DECLINED · CANCELED …
    val merchant: MerchantRefDto? = null,    // #3에만
    val amount: Long = 0,
    val plan: String? = null,
    val installmentMonths: Int? = null,
    val chargedAmount: Long? = null,
    val accountBalanceAfter: Long? = null,
    val selectedCard: SelectedCardDto? = null,
    val approvalNo: String? = null,
    val approvedAt: String? = null,
    val benefits: List<BenefitDto> = emptyList(),
    val billItems: List<BillItemDto> = emptyList(),
    val performance: PaidPerformanceDto? = null,
    val failure: FailureDto? = null,
    val retryable: Boolean = false,
    val remainingAttempts: Int? = null,
    val suggestedCards: List<SuggestedCardDto> = emptyList(),
    val source: String? = null,              // #3 — YAMU · SEED(가입 전 거래, 취소 불가)
    val canceledAt: String? = null,          // #3
    val refunds: List<RefundDto> = emptyList(),   // #3 — 출금 뒤 취소로 생긴 환급
    val memo: String? = null,                // #3에는 아직 없다(BE에 요청). 오면 이 값을 먼저 쓴다
)

@Serializable data class SelectedCardDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuerName: String? = null,
    val issuer: IssuerDto? = null,
    val maskedNumber: String? = null,
    val cardType: String = "CREDIT",
)

@Serializable data class BillItemDto(
    val seq: Int = 1,
    val amount: Long? = null,                // #3 — 수수료를 아직 셈하지 않았으면 null
    val principalAmount: Long? = null,       // #3
    val billedAmount: Long? = null,          // 보완안: 청구할인 반영 금액
    val billingMonth: String? = null,
    val expectedWithdrawalDate: String? = null,   // #1
    val withdrawalDate: String? = null,           // #3
)

@Serializable data class PaidPerformanceDto(
    val yearMonth: String? = null,
    val recognizedAmount: Long = 0,
    val recognized: Boolean = true,
    val recognizedTotalAfter: Long? = null,  // 보완안(10 문서 6-8 ⑥)
    val targetAmount: Long? = null,
)

@Serializable data class FailureDto(val code: String, val message: String? = null)

@Serializable data class SuggestedCardDto(val userCardId: Long)

/** 취소 사유는 BE가 저장하지 않아 보내지 않는다 → 본문 {}. 취소 금액 칸을 넣으면 400 PARTIAL_CANCEL_NOT_SUPPORTED */
@Serializable data class CancelRequest(val reason: String? = null)

/** 결제 취소 응답 — 신용카드는 회차별 결과 · 환급, 체크카드는 입금 뒤 잔액 */
@Serializable data class CancelResponseDto(
    val paymentId: Long,
    val status: String = "CANCELED",
    val canceledAt: String? = null,
    val billItems: List<CancelBillItemDto> = emptyList(),
    val refunds: List<RefundDto> = emptyList(),
    val accountBalanceAfter: Long? = null,
)

@Serializable data class CancelBillItemDto(val seq: Int = 1, val amount: Long = 0, val before: String? = null, val after: String? = null)

/** 환급 한 줄 — 취소 응답은 amount, 상세는 refundAmount로 온다 */
@Serializable data class RefundDto(
    val amount: Long? = null,
    val refundAmount: Long? = null,
    val dueDate: String? = null,              // 출금 전 회차는 null(출금 뒤 3영업일로 정해진다)
    val status: String? = null,               // PENDING · COMPLETED …
)

/**
 * 메모 본문. memo 칸이 빠지면 BE가 400이라 지울 때도 null 대신 ""를 보낸다
 * (YamuJson은 null 칸을 빼고 보낸다 — explicitNulls = false)
 */
@Serializable data class MemoRequest(val memo: String)

@Serializable data class MemoResponseDto(val paymentId: Long, val memo: String? = null, val memoUpdatedAt: String? = null)

// ── 앱 안에서 쓰는 값 ──

data class Merchant(val id: Long, val name: String, val categoryName: String?, val isOnline: Boolean, val posCode: String?)

/** 결제할 수 있는 카드 한 장 — 서버 추천 순서 그대로 */
data class PayCard(
    val userCardId: Long,
    val brand: Brand,
    val name: String,
    val last4: String,
    val isCheck: Boolean,
    val netBenefit: BigDecimal,
    val benefitName: String?,
    val chargedAmount: Long?,
    val installmentOptions: List<Int>,
    val reason: String?,
    val performanceNote: String?,
)

data class Recommendation(val merchantName: String, val amount: Long, val cards: List<PayCard>, val isEstimate: Boolean)

data class PayCode(val token: String, val barcodeValue: String, val qrValue: String, val expiresAtIso: String)

enum class CodeState { ISSUED, SCANNED, USED, EXPIRED }

data class PaymentRequest(
    val paymentId: Long,
    val merchantName: String,
    val categoryName: String?,
    val amount: Long,
    val confirmDeadlineIso: String?,
    val cards: List<PayCard>,
    val cardsUnknown: Boolean = false,       // 카드가 없는 게 아니라 확인하지 못했다(추천 스냅샷 없음 · 데이터 부족)
)

data class PayCodeStatus(val state: CodeState, val request: PaymentRequest?)

data class ReceiptBenefit(val name: String, val amount: Long)

enum class PayStatus {
    APPROVED, CANCELED, DECLINED, OTHER;

    companion object {
        fun of(value: String?): PayStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** 돌려받을 금액 — [dueDateIso]가 없으면 아직 출금 전이라 출금 뒤 3영업일 안으로 정해진다 */
data class RefundLine(val amount: Long, val dueDateIso: String?, val completed: Boolean)

fun RefundDto.toLine() = RefundLine(refundAmount ?: amount ?: 0, dueDate, status.equals("COMPLETED", ignoreCase = true))

data class PaymentReceipt(
    val paymentId: Long,
    val merchantName: String?,
    val categoryName: String?,
    val amount: Long,
    val paidAtIso: String?,
    val cardId: Long?,
    val cardBrand: Brand?,
    val cardName: String?,
    val cardLast4: String?,
    val isCheck: Boolean,
    val approvalNo: String?,
    val installmentMonths: Int?,
    val benefits: List<ReceiptBenefit>,
    val chargedAmount: Long?,
    val balanceAfter: Long?,
    val billDateIso: String?,
    val billAmount: Long?,
    val perfMonth: Int?,
    val perfRecognized: Long?,
    val perfCounted: Boolean,
    val perfTotalAfter: Long?,
    val perfTarget: Long?,
    val status: PayStatus = PayStatus.APPROVED,
    val isSeed: Boolean = false,              // 가입 전 거래(SEED) — 취소할 수 없다
    val canceledAtIso: String? = null,
    val refunds: List<RefundLine> = emptyList(),
    val memo: String? = null,
) {
    /** 승인된 야무 결제만 취소할 수 있다(BE PAYMENT_NOT_CANCELABLE의 NOT_APPROVED · SEED_TRANSACTION과 같은 기준) */
    val cancelable: Boolean get() = status == PayStatus.APPROVED && !isSeed
    val canceled: Boolean get() = status == PayStatus.CANCELED
}

/** 취소 결과 — [balanceAfter]는 체크카드 환불 입금 뒤 잔액(못 읽었거나 신용카드면 null) */
data class CancelResult(
    val paymentId: Long,
    val canceledAtIso: String?,
    val excludedBills: Int,
    val refunds: List<RefundLine>,
    val balanceAfter: Long?,
)

fun CancelResponseDto.toResult() = CancelResult(
    paymentId = paymentId,
    canceledAtIso = canceledAt,
    excludedBills = billItems.count { it.after.equals("EXCLUDED", ignoreCase = true) },
    refunds = refunds.map { it.toLine() },
    balanceAfter = accountBalanceAfter,
)

sealed interface ApproveOutcome {
    data class Approved(val receipt: PaymentReceipt) : ApproveOutcome
    data class Declined(val message: String, val retryable: Boolean, val remainingAttempts: Int?, val suggestedCardId: Long?) : ApproveOutcome
}

// ── 서버 값 → 화면 값 ──

fun MerchantDto.toMerchant() = Merchant(merchantId, name, categoryName, channel.equals("ONLINE", ignoreCase = true), posCode)

private fun cardBrand(issuer: IssuerDto?, issuerName: String?, nickname: String?): Brand {
    val name = issuer?.name ?: issuerName ?: nickname ?: "카드"
    return brandOf(name, color = issuer?.color)
}

fun CandidateDto.toPayCard() = PayCard(
    userCardId = userCardId,
    brand = cardBrand(issuer, issuerName, nickname),
    name = nickname ?: issuer?.name ?: issuerName ?: "카드",
    last4 = maskedNumber?.lastDigits().orEmpty(),
    isCheck = cardType.equals("CHECK", ignoreCase = true),
    netBenefit = netBenefit,
    benefitName = benefits.firstOrNull()?.name,
    chargedAmount = chargedAmount,
    installmentOptions = installmentOptions,
    reason = reason,
    performanceNote = performanceImpact?.note,
)

fun BarcodeStatusResponse.toStatus(): PayCodeStatus {
    val state = runCatching { CodeState.valueOf(status.uppercase()) }.getOrDefault(CodeState.ISSUED)
    val request = payment?.takeIf { state == CodeState.SCANNED }?.let { p ->
        PaymentRequest(
            paymentId = p.paymentId,
            merchantName = p.merchant.name,
            categoryName = p.merchant.categoryName,
            amount = p.amount,
            confirmDeadlineIso = p.confirmDeadline,
            cards = p.recommendation?.candidates.orEmpty().sortedBy { it.rank }.map { it.toPayCard() },
            cardsUnknown = p.recommendation == null || p.recommendation.status == "DATA_INSUFFICIENT",
        )
    }
    // 찍혔는데 요청 내용이 없으면 아직 찍히기 전으로 본다(다음 확인에서 다시)
    return PayCodeStatus(if (state == CodeState.SCANNED && request == null) CodeState.ISSUED else state, request)
}

/** #1 · #3 → P-07 · P-08 줄. [request]는 결제 요청 때 받은 가맹점 · 카드(승인 응답엔 가맹점이 없다) */
fun PaymentResultDto.toReceipt(request: PaymentRequest? = null, card: PayCard? = null): PaymentReceipt {
    val bill = billItems.minByOrNull { it.seq }
    val isCheck = selectedCard?.cardType?.equals("CHECK", ignoreCase = true) ?: card?.isCheck ?: false
    return PaymentReceipt(
        paymentId = paymentId,
        merchantName = merchant?.name ?: request?.merchantName,
        categoryName = merchant?.categoryName ?: request?.categoryName,
        amount = amount.takeIf { it > 0 } ?: request?.amount ?: 0,
        paidAtIso = approvedAt,
        cardId = selectedCard?.userCardId ?: card?.userCardId,
        cardBrand = selectedCard?.let { cardBrand(it.issuer, it.issuerName, it.nickname) } ?: card?.brand,
        cardName = selectedCard?.nickname ?: card?.name,
        cardLast4 = selectedCard?.maskedNumber?.lastDigits() ?: card?.last4,
        isCheck = isCheck,
        approvalNo = approvalNo,
        installmentMonths = installmentMonths?.takeIf { plan.equals("INSTALLMENT", ignoreCase = true) },
        benefits = benefits.map { ReceiptBenefit(it.name, it.actualAmount ?: it.expectedAmount ?: it.amount) }.filter { it.amount > 0 },
        chargedAmount = chargedAmount,
        balanceAfter = accountBalanceAfter,
        billDateIso = if (isCheck) null else bill?.let { it.expectedWithdrawalDate ?: it.withdrawalDate },
        billAmount = if (isCheck) null else bill?.let { it.billedAmount ?: it.amount ?: it.principalAmount },
        perfMonth = performance?.yearMonth?.takeLast(2)?.toIntOrNull(),
        perfRecognized = performance?.recognizedAmount,
        perfCounted = performance?.recognized ?: true,
        perfTotalAfter = performance?.recognizedTotalAfter,
        perfTarget = performance?.targetAmount,
        status = PayStatus.of(status),
        isSeed = source.equals("SEED", ignoreCase = true),
        canceledAtIso = canceledAt,
        refunds = refunds.map { it.toLine() },
        memo = memo,
    )
}
