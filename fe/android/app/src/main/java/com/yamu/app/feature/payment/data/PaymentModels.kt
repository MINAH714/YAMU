package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.BigDecimalSerializer
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.IssuerDto
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.link.data.lastDigits
import kotlinx.serialization.Serializable
import java.math.BigDecimal

// ── 서버 요청 · 응답 — #5 가맹점(모의 POS) · #77 코드 발급 · #12 코드 상태 · #1 승인 · #3 상세 · 추가 7 거절. 10 문서 6-2 ──
// #64 결제 전 단일 추천은 명세 V2에서 빠졌다. 후보 모양(CandidateDto)은 #12 recommendation이 같이 쓴다

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

@Serializable data class CandidateDto(
    val rank: Int = 0,
    val userCardId: Long,
    val nickname: String? = null,
    val issuerName: String? = null,          // #64
    val issuer: IssuerDto? = null,           // 보완안(#12 후보에도 — 10 문서 6-8 ④)
    val productName: String? = null,         // 임시(Q-19, BE 칸 확정 전) — nickname은 별명이 있으면 별명이라 그림은 이 이름으로 찾는다
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
    val type: String? = null,                // #12 후보: DISCOUNT · CASHBACK · POINT(BE 엔진 BenefitType)
    val amount: Long = 0,
    val expectedAmount: Long? = null,        // #3
    val actualAmount: Long? = null,
    val realization: String? = null,         // #12 후보: AT_APPROVAL · AT_BILLING · AFTER_BILLING · POINT_ACCRUAL
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
    val uncomparedCards: List<UncomparedCardDto> = emptyList(),   // 임시(명세 반영 전) 혜택을 비교하지 못한 카드 — 순위 없음
    val noBenefitNotice: String? = null,     // 임시 — 바로 결제할 수 있는 비교 못 한 카드가 있을 때만. 그 카드로 승인하기 전에 확인받는다
)

/** BE PaymentRequestView.UncomparedCard. reason: BENEFIT_DATA_INSUFFICIENT(혜택만 모름) · PAYMENT_AVAILABILITY_UNKNOWN(결제 가능 여부도 모름) */
@Serializable data class UncomparedCardDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuer: IssuerDto? = null,           // 임시(Q-19, BE 칸 확정 전) — 후보(CandidateDto)와 같은 모양
    val productName: String? = null,         // 임시(Q-19, BE 칸 확정 전)
    val cardType: String = "CREDIT",
    val maskedNumber: String? = null,
    val reason: String? = null,
    val message: String? = null,
    val directPaymentAllowed: Boolean = false,   // BENEFIT_DATA_INSUFFICIENT만 true — 직접 고르면 혜택 없이 원금으로 결제
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

/** 메모 찾기용 결제 내역(#4) — 결제 번호 · 메모만 읽는다 */
@Serializable data class MemoPageDto(val items: List<MemoItemDto> = emptyList(), val hasNext: Boolean = false)

@Serializable data class MemoItemDto(val paymentId: Long, val memo: String? = null)

// ── 앱 안에서 쓰는 값 ──

data class Merchant(val id: Long, val name: String, val categoryName: String?, val isOnline: Boolean, val posCode: String?)

/** 결제할 수 있는 카드 한 장 — 서버 추천 순서 그대로, 혜택을 비교하지 못한 카드는 그 뒤에 */
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
    /** 추천의 uncomparedCards에서 온 카드 — 순위 · 혜택이 없고 [reason]에 서버 문구. 미리 고르지 않고, 고르면 승인 전에 혜택 없음을 확인받는다 */
    val benefitUnknown: Boolean = false,
    /** false면 줄만 보이고 고를 수 없다(비교 못 한 카드 중 결제 가능 여부까지 모르는 카드 — directPaymentAllowed false) */
    val selectable: Boolean = true,
    /** 카드 상품명 — [name]은 별명일 수 있어 그림은 이 이름으로 찾는다. 응답 · 카드 목록 어디에도 없으면 null */
    val productName: String? = null,
    /** 응답에 카드사 칸이 없어 [brand]를 별명 글자로 만들었다 — 카드 목록에서 찾으면 그 카드사로 바꾼다([withListedCards]) */
    val issuerUnknown: Boolean = false,
) {
    /** 앱이 알아서 골라도 되는 카드 — 처음 1순위 · 거절 뒤 다음 카드. 비교 못 한 카드는 사용자가 직접 골라야 한다 */
    val autoSelectable: Boolean get() = selectable && !benefitUnknown

    /** 카드 그림을 찾는 이름(CardImages) — 상품명을 모르면 화면 이름(별명이 없으면 서버가 상품명을 준다) */
    val imageName: String get() = productName ?: name
}

/** 비교 못 한 카드를 골라 승인할 때 확인받는 문구 — 서버가 noBenefitNotice를 안 보냈을 때(BE RecommendationResult.NO_BENEFIT_NOTICE와 같은 문장) */
const val NO_BENEFIT_NOTICE = "혜택 정보를 확인할 수 없어 혜택 없이 결제됩니다."

data class PayCode(val token: String, val barcodeValue: String, val qrValue: String, val expiresAtIso: String)

enum class CodeState { ISSUED, SCANNED, USED, EXPIRED }

data class PaymentRequest(
    val paymentId: Long,
    val merchantName: String,
    val categoryName: String?,
    val amount: Long,
    val confirmDeadlineIso: String?,
    val cards: List<PayCard>,
    val cardsUnknown: Boolean = false,       // 카드가 없는 게 아니라 확인하지 못했다(추천 스냅샷 없음 · 데이터 부족인데 비교 못 한 카드도 안 옴)
    val noBenefitNotice: String = NO_BENEFIT_NOTICE,
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
    /** false면 [memo]가 null이어도 '없음'이 아니라 '모름'(상세 응답에 memo가 없다) — 저장하면 서버 메모를 덮어쓸 수 있다 */
    val memoKnown: Boolean = true,
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

/**
 * 결제 완료 · 상세의 카드사 — 응답의 카드사 칸, 없으면 결제 요청 때 고른 같은 카드의 카드사(카드 목록으로 붙인 값), 그것도 없으면 별명 글자.
 * 임시: 승인 · 상세 응답의 selectedCard에 아직 카드사 칸이 없다(S15P31E103-389에서 더함)
 */
private fun receiptBrand(selected: SelectedCardDto?, card: PayCard?): Brand? {
    val sameCard = card?.takeIf { selected == null || it.userCardId == selected.userCardId }
    return when {
        selected != null && (selected.issuer != null || selected.issuerName != null) -> cardBrand(selected.issuer, selected.issuerName, selected.nickname)
        sameCard != null -> sameCard.brand
        selected != null -> cardBrand(null, null, selected.nickname)
        else -> null
    }
}

fun CandidateDto.toPayCard() = PayCard(
    userCardId = userCardId,
    brand = cardBrand(issuer, issuerName, nickname),
    name = nickname ?: productName ?: issuer?.name ?: issuerName ?: "카드",
    last4 = maskedNumber?.lastDigits().orEmpty(),
    isCheck = cardType.equals("CHECK", ignoreCase = true),
    netBenefit = netBenefit,
    benefitName = benefitNameOf(benefits),
    chargedAmount = chargedAmount,
    installmentOptions = installmentOptions,
    reason = reason,
    performanceNote = performanceImpact?.note,
    productName = productName,
    issuerUnknown = issuer == null && issuerName == null,
)

/**
 * 카드 줄에 보일 혜택 이름 — 이번 결제에 금액이 붙은 혜택 중 원 단위(할인 · 캐시백)에서 가장 큰 것, 원 단위가 없을 때만 포인트에서 가장 큰 것.
 * 없으면 null(줄에는 서버 reason).
 * 서버는 이번 결제에 맞지 않아 0원인 혜택(스타벅스 결제의 '교보문고 …')도 같이 보낸다. 공유 한도 배분이 안 정해진 혜택은 amount가 null로 와서
 * 0으로 읽힌다(YamuJson coerceInputValues).
 * 임시: amount는 혜택마다 자기 단위(원 · 포인트)이고 응답에 원화 환산값이 없어 원과 포인트를 견줄 수 없다(포인트 가치가 더 커도 원 단위가 보인다).
 * BE 엔진은 혜택별 krwValue를 계산하므로 결제 응답에 실리면 그 값으로 고른다
 */
internal fun benefitNameOf(benefits: List<BenefitDto>): String? {
    val (points, won) = benefits.filter { it.amount > 0 }.partition { it.isPoint }
    return (won.maxByOrNull { it.amount } ?: points.maxByOrNull { it.amount })?.name
}

private val BenefitDto.isPoint: Boolean
    get() = type.equals("POINT", ignoreCase = true) || realization.equals("POINT_ACCRUAL", ignoreCase = true)

fun UncomparedCardDto.toPayCard() = PayCard(
    userCardId = userCardId,
    brand = cardBrand(issuer, null, nickname),
    name = nickname ?: productName ?: issuer?.name ?: "카드",
    last4 = maskedNumber?.lastDigits().orEmpty(),
    isCheck = cardType.equals("CHECK", ignoreCase = true),
    netBenefit = BigDecimal.ZERO,
    benefitName = null,
    chargedAmount = null,
    installmentOptions = emptyList(),
    reason = message ?: if (directPaymentAllowed) "혜택 정보를 확인하지 못해 비교하지 못했어요" else "결제할 수 있는지 확인하지 못했어요",
    performanceNote = null,
    benefitUnknown = true,
    selectable = directPaymentAllowed,
    productName = productName,
    issuerUnknown = issuer == null,
)

fun BarcodeStatusResponse.toStatus(): PayCodeStatus {
    val state = runCatching { CodeState.valueOf(status.uppercase()) }.getOrDefault(CodeState.ISSUED)
    val request = payment?.takeIf { state == CodeState.SCANNED }?.let { p ->
        val rec = p.recommendation
        val ranked = rec?.candidates.orEmpty().sortedBy { it.rank }.map { it.toPayCard() }
        // 비교 못 한 카드는 순위 없이 뒤에 — 앞 후보와 같은 카드가 오면(서버 규칙상 없음) 후보 쪽만 남긴다
        val uncompared = rec?.uncomparedCards.orEmpty().filter { u -> ranked.none { it.userCardId == u.userCardId } }.map { it.toPayCard() }
        PaymentRequest(
            paymentId = p.paymentId,
            merchantName = p.merchant.name,
            categoryName = p.merchant.categoryName,
            amount = p.amount,
            confirmDeadlineIso = p.confirmDeadline,
            cards = ranked + uncompared,
            // 데이터 부족이어도 비교 못 한 카드가 오면 그 카드들을 보인다. 둘 다 없으면 '확인하지 못함'(옛 응답 · 스냅샷 없음)
            cardsUnknown = rec == null || (rec.status == "DATA_INSUFFICIENT" && ranked.isEmpty() && uncompared.isEmpty()),
            noBenefitNotice = rec?.noBenefitNotice ?: NO_BENEFIT_NOTICE,
        )
    }
    // 찍혔는데 요청 내용이 없으면 아직 찍히기 전으로 본다(다음 확인에서 다시)
    return PayCodeStatus(if (state == CodeState.SCANNED && request == null) CodeState.ISSUED else state, request)
}

/**
 * 카드 목록(GET /api/cards)에서 userCardId가 같은 카드의 상품명 · 카드사를 결제 요청 카드에 붙인다(Q-19).
 * 임시: 바코드 응답의 후보 · 비교 못 한 카드에는 카드사 · 상품명 칸이 없고 nickname은 별명이 있으면 별명이다. 그대로 두면 별명을 정한 카드는
 * 그림을 못 찾고 카드사 칩에 별명 글자가 나온다. 응답에 칸이 있으면 응답 값을 먼저 쓰고, 목록에 없는 카드는 그대로 둔다
 */
fun PaymentRequest.withListedCards(listed: Map<Long, LinkedCard>): PaymentRequest =
    if (listed.isEmpty()) this else copy(cards = cards.map { it.withListed(listed[it.userCardId]) })

internal fun PayCard.withListed(card: LinkedCard?): PayCard = if (card == null) this else copy(
    brand = if (issuerUnknown) card.brand else brand,
    productName = productName ?: card.productName,
    issuerUnknown = false,
)

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
        cardBrand = receiptBrand(selectedCard, card),
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
        memoKnown = memo != null || request != null,   // 방금 승인한 결제는 메모가 없다. 상세(#3)는 memo 칸이 없어 모른다
    )
}

/**
 * #1 승인 거절 응답 → 화면 값.
 * 출금 미확인(WITHDRAWAL_NOT_CONFIRMED)은 서버가 retryable을 줘도 다시 결제하지 않게 한다 — 은행이 그 출금을 늦게 처리하면 BE 복구가
 * 이 결제를 승인으로 바로잡는데(WithdrawalRecovery), 다시 승인을 시작하면 그 요청 번호가 지워져 바로잡지 못하고 돈이 두 번 나갈 수 있다.
 * 새 코드로 다시 결제해도 같다. 그래서 다른 카드를 권하지 않고 결제 내역에서 결과를 보게 한다(BE POS 문구와 같은 안내)
 */
fun PaymentResultDto.toDeclined(): ApproveOutcome.Declined {
    val resultUnknown = failure?.code == "WITHDRAWAL_NOT_CONFIRMED"
    return ApproveOutcome.Declined(
        message = declineMessage(failure?.code),
        retryable = retryable && !resultUnknown,
        remainingAttempts = remainingAttempts,
        suggestedCardId = if (resultUnknown) null else suggestedCards.firstOrNull()?.userCardId,
    )
}
