package com.yamu.app.feature.billing.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

// 청구 · 환급 조회 — BE BillQueryController · RefundQueryController(노션 API 명세 V2 「청구서 목록」 · 「청구서 상세」 · 「환급 내역 조회」).
// 상태는 문자열로 받는다. CANCELED처럼 명세에 없던 값이 BE에 먼저 생겨도 응답 전체가 실패하지 않게.
// 금액은 서버 값을 그대로 쓰고 앱이 빼고 더하지 않는다(02 문서 3장)

// ───────────────────────── 서버 응답 ─────────────────────────

@Serializable data class BillListDto(val items: List<BillDto> = emptyList(), val summary: BillSummaryDto? = null)

@Serializable data class BillDto(
    val billId: Long? = null,
    val userCardId: Long? = null,
    val nickname: String? = null,
    val billingMonth: String? = null,           // YYYY-MM, 결제일이 속한 달
    val usagePeriod: UsagePeriodDto? = null,
    val closingDate: String? = null,
    val paymentDate: String? = null,            // 영업일 보정 전
    val withdrawalDate: String? = null,         // 결제일이 휴일이면 다음 영업일
    val status: String? = null,
    val itemCount: Int = 0,
    val itemAmount: Long = 0,
    val billingDiscount: Long = 0,
    val expectedAmount: Long? = null,           // 쌓이는 중일 때만
    val finalAmount: Long? = null,              // 확정 뒤에만
    val unpaidAmount: Long? = null,             // 미납 · CANCELED일 때만(CANCELED는 0)
    val paidAt: String? = null,
    val withdrawalAccount: AccountRefDto? = null,
)

@Serializable data class UsagePeriodDto(val from: String? = null, val to: String? = null)

@Serializable data class AccountRefDto(val accountId: Long? = null, val bankName: String? = null, val maskedAccountNo: String? = null)

/** 상태 · 월 필터와 상관없이 끝나지 않은 청구서로 계산한다(카드 필터는 따른다) */
@Serializable data class BillSummaryDto(val nextWithdrawal: BillNextWithdrawalDto? = null, val unpaidCount: Int = 0)

@Serializable data class BillNextWithdrawalDto(val billId: Long? = null, val date: String? = null, val expectedAmount: Long? = null)

@Serializable data class BillDetailDto(
    val billId: Long? = null,
    val userCardId: Long? = null,
    val nickname: String? = null,
    val maskedNumber: String? = null,
    val billingMonth: String? = null,
    val usagePeriod: UsagePeriodDto? = null,
    val closingDate: String? = null,
    val paymentDate: String? = null,
    val withdrawalDate: String? = null,
    val status: String? = null,
    val amounts: BillAmountsDto = BillAmountsDto(),
    val withdrawalAccount: AccountRefDto? = null,
    val items: List<BillUsageDto> = emptyList(),
    val discounts: List<BillDiscountDto> = emptyList(),
    val withdrawal: BillWithdrawalDto? = null,
    val refunds: List<BillRefundDto> = emptyList(),
)

@Serializable data class BillAmountsDto(
    val itemAmount: Long = 0,
    val billingDiscount: Long = 0,
    val expectedAmount: Long? = null,
    val finalAmount: Long? = null,
    val unpaidAmount: Long? = null,
)

/** 청구 회차 — 할부 결제 하나가 여러 청구서에 회차로 나뉘어 들어간다 */
@Serializable data class BillUsageDto(
    val billItemId: Long? = null,
    val paymentId: Long? = null,
    val approvedAt: String? = null,
    val merchantName: String? = null,
    val plan: String? = null,                   // LUMP_SUM · INSTALLMENT
    val seq: Int = 1,
    val totalSeq: Int = 1,
    val amount: Long = 0,
    val status: String? = null,                 // BillItemStatus
)

/** 진행 중 청구서의 예상 할인은 confirmedAt이 null이다(확정 때 금액이 바뀔 수 있음) */
@Serializable data class BillDiscountDto(val paymentBenefitId: Long? = null, val name: String? = null, val amount: Long = 0, val confirmedAt: String? = null)

/** 출금을 시도하기 전(진행 중 · 확정)에는 null. 실패 사유는 BE가 아직 남기지 않는다 */
@Serializable data class BillWithdrawalDto(val attemptedAt: String? = null, val result: String? = null)

@Serializable data class BillRefundDto(val refundId: Long? = null, val billItemId: Long? = null, val amount: Long = 0, val status: String? = null)

/** 합계 아래에 공통 페이지 칸(items · page · hasNext …)이 펼쳐져 온다 */
@Serializable data class RefundPageDto(
    val summary: RefundSummaryDto = RefundSummaryDto(),
    val items: List<RefundDto> = emptyList(),
    val page: Int = 1,
    val hasNext: Boolean = false,
)

/** 입금 전(PENDING) 환급만 — 기간 · 상태 필터와 상관없이 */
@Serializable data class RefundSummaryDto(val pendingAmount: Long = 0, val pendingCount: Long = 0, val estimatedAmount: Long = 0, val estimatedCount: Long = 0)

@Serializable data class RefundDto(
    val refundId: Long? = null,                 // 체크카드 취소 환불은 null(환급 행이 없음)
    val paymentId: Long? = null,
    val billId: Long? = null,                   // 체크카드는 null
    val nickname: String? = null,
    val merchantName: String? = null,
    val reason: String? = null,                 // CANCEL_AFTER_CONFIRM · CHECK_CANCEL
    val amount: Long = 0,                       // 무효(CANCELED)는 0
    val estimated: Boolean = false,             // 출금 전 청구서의 회차라 금액이 예상값
    val status: String? = null,
    val createdAt: String? = null,
    val scheduledAfter: String? = null,         // 출금 전이면 그 출금 예정일
    val dueDate: String? = null,                // 출금 완료일 + 3영업일. 체크카드는 취소일
    val completedAt: String? = null,
)

// ───────────────────────── 화면 모델 ─────────────────────────

/** 청구서 상태(BE BillStatus). 모르는 값은 UNKNOWN — 화면이 죽지 않고 '확인 중'으로 */
enum class BillState(val label: String) {
    ACCUMULATING("쌓이는 중"),
    CONFIRMED("출금 예정"),
    PAID("출금 완료"),
    UNPAID("미납"),
    CANCELED("정산 끝"),        // 미납이었다가 결제 취소로 남은 돈이 0원(BE 임시 값)
    UNKNOWN("확인 중"),
}

fun billStateOf(raw: String?): BillState =
    BillState.entries.firstOrNull { it != BillState.UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: BillState.UNKNOWN

/**
 * 화면에 보일 청구서 상태. 쌓이는 중 · 출금 예정인데 출금일이 [today]보다 앞이면 출금 완료로 본다(임시).
 * QA · 시연 서버는 SSAFY 계좌에서 돈이 빠지지 않게 확정 · 출금 배치를 꺼 둬서(BILLING_JOBS_ENABLED=false) 출금일이 지나도 BE 상태가 그대로다.
 * 배치가 도는 서버에서는 출금일 07:00에 PAID · UNPAID로 바뀌어 오므로, 결과를 몰라 BE가 다시 확인 중인 청구서만 하루 먼저 완료로 보인다.
 * 출금일 당일은 07:00 전후를 가를 수 없어 BE 값을 그대로 둔다
 */
internal fun billStateOf(raw: String?, withdrawalDate: LocalDate?, today: LocalDate): BillState {
    val state = billStateOf(raw)
    val open = state == BillState.ACCUMULATING || state == BillState.CONFIRMED
    return if (open && withdrawalDate != null && withdrawalDate.isBefore(today)) BillState.PAID else state
}

/** 청구 회차 상태(BE BillItemStatus) — 태그를 다는 것만 따로 */
enum class UsageState(val tag: String?) {
    NORMAL(null),

    /**
     * 결제를 취소해 더 낼 돈이 없는 회차. BE는 세 경우를 같은 값으로 준다 — 확정 전 취소(청구에서 빠짐), 미납 청구서의 취소
     * (미납액에서 뺌, BillItem.waiveUnpaid), 확정 뒤 몫이 0원인 회차 닫기(closeWithoutRefund). 뒤의 둘은 이용 금액에 남는데
     * 응답에 가를 칸이 아직 없어 줄마다 고르지 못하고 상세에 안내 한 줄만 단다([excludedNote])
     */
    EXCLUDED("청구 제외"),
    REFUND_PENDING("환급 대기"),
    REFUNDED("환급 완료"),
}

fun usageStateOf(raw: String?): UsageState = when (raw?.trim()?.uppercase()) {
    "EXCLUDED" -> UsageState.EXCLUDED
    "REFUND_PENDING" -> UsageState.REFUND_PENDING
    "REFUNDED" -> UsageState.REFUNDED
    else -> UsageState.NORMAL
}

/** 환급 상태(BE RefundStatus). CANCELED = 무효(원 청구서가 미납으로 끝나 돌려줄 돈이 없음, BE 임시 값) */
enum class RefundState { PENDING, COMPLETED, FAILED, CANCELED, UNKNOWN }

fun refundStateOf(raw: String?): RefundState =
    RefundState.entries.firstOrNull { it != RefundState.UNKNOWN && it.name.equals(raw?.trim(), ignoreCase = true) } ?: RefundState.UNKNOWN

data class Bill(
    val billId: Long,
    val userCardId: Long?,
    val cardName: String,
    val billingMonth: YearMonth?,
    val usageFrom: LocalDate?,
    val usageTo: LocalDate?,
    val closingDate: LocalDate?,
    val paymentDate: LocalDate?,
    val withdrawalDate: LocalDate?,
    val state: BillState,
    val itemCount: Int,
    val itemAmount: Long,
    val billingDiscount: Long,
    val expectedAmount: Long?,
    val finalAmount: Long?,
    val unpaidAmount: Long?,
    val paidAt: ZonedDateTime?,
    val account: String?,                       // '싸피은행 ·1234' — 카드에 계좌가 없으면 null
) {
    /** 크게 보여 줄 금액 — 쌓이는 중은 예상 청구액, 미납 · 정산 끝은 남은 미납액, 나머지는 확정 청구액 */
    val amount: Long
        get() = when (state) {
            BillState.ACCUMULATING -> expectedAmount ?: finalAmount ?: itemAmount
            BillState.UNPAID, BillState.CANCELED -> unpaidAmount ?: finalAmount ?: itemAmount
            BillState.CONFIRMED, BillState.PAID, BillState.UNKNOWN -> finalAmount ?: expectedAmount ?: itemAmount
        }

    val isEstimate: Boolean get() = state == BillState.ACCUMULATING

    /** 미납 · 정산 끝에서 남은 금액과 원래 청구액이 다르면 원래 청구액 */
    val originalAmount: Long?
        get() = finalAmount?.takeIf { (state == BillState.UNPAID || state == BillState.CANCELED) && it != amount }

    /** 결제일이 휴일이라 출금일이 밀렸다 */
    val withdrawalShifted: Boolean get() = paymentDate != null && withdrawalDate != null && paymentDate != withdrawalDate
}

data class NextBillWithdrawal(val billId: Long?, val date: LocalDate, val amount: Long)

data class BillList(val bills: List<Bill>, val next: NextBillWithdrawal?, val unpaidCount: Int) {
    /** 다음 출금이 아직 쌓이는 중인 청구서면 금액이 예상값 */
    val nextIsEstimate: Boolean get() = next?.billId?.let { id -> bills.firstOrNull { it.billId == id }?.isEstimate } == true
}

data class BillUsage(
    val billItemId: Long?,
    val paymentId: Long?,
    val merchant: String,
    val approvedOn: LocalDate?,
    val planLabel: String,                      // '일시불' · '할부 2/3회차'
    val amount: Long,
    val state: UsageState,
)

data class BillDiscount(val name: String, val amount: Long)

data class BillRefund(val refundId: Long?, val merchant: String?, val amount: Long, val state: RefundState)

data class BillDetail(
    val bill: Bill,
    val cardLast4: String?,
    val usages: List<BillUsage>,
    val discounts: List<BillDiscount>,
    val discountsEstimated: Boolean,
    val refunds: List<BillRefund>,
    val withdrawalAttemptedAt: ZonedDateTime?,
)

data class Refund(
    val key: String,                            // 같은 환급이 페이지가 밀려 두 번 와도 한 줄만
    val refundId: Long?,
    val paymentId: Long?,
    val billId: Long?,
    val cardName: String,
    val merchant: String,
    val isCheckCancel: Boolean,
    val amount: Long,
    val estimated: Boolean,
    val state: RefundState,
    val createdOn: LocalDate?,
    val scheduledAfter: LocalDate?,
    val dueDate: LocalDate?,
    val completedOn: LocalDate?,
)

data class RefundSummary(val pendingAmount: Long, val pendingCount: Int, val estimatedAmount: Long)

data class RefundPage(val items: List<Refund>, val page: Int, val hasNext: Boolean, val summary: RefundSummary)

// ───────────────────────── 바꾸기 ─────────────────────────

/** [today] = 한국 날짜. 출금일이 지난 청구서를 출금 완료로 볼 때 쓴다([billStateOf]) */
fun BillListDto.toBillList(today: LocalDate = LocalDate.now(KST)): BillList {
    val bills = items.mapNotNull { it.toBill(today) }
    val server = summary?.nextWithdrawal?.let { n ->
        val date = parseDate(n.date) ?: return@let null
        NextBillWithdrawal(n.billId, date, n.expectedAmount ?: return@let null)
    }
    // BE는 출금일이 지난 확정 청구서도 다음 출금으로 준다. 앱은 그 청구서를 출금 완료로 보이므로 목록에 남은 청구서 중 출금일이 가장 빠른 것으로 바꾼다.
    // 금액은 BE와 같은 규칙(쌓이는 중은 예상 청구액, 확정은 확정 청구액)으로 서버 값을 고르기만 한다
    val next = server?.takeUnless { it.date.isBefore(today) } ?: server?.let {
        bills.filter { b -> (b.state == BillState.ACCUMULATING || b.state == BillState.CONFIRMED) && b.withdrawalDate != null && b.amount > 0 }
            .minWithOrNull(compareBy<Bill>({ b -> b.withdrawalDate }, { b -> b.billId }))
            ?.let { b -> NextBillWithdrawal(b.billId, b.withdrawalDate!!, b.amount) }
    }
    return BillList(bills = bills, next = next, unpaidCount = summary?.unpaidCount ?: 0)
}

/** 번호가 없는 줄은 상세로 갈 수 없어 뺀다 */
fun BillDto.toBill(today: LocalDate = LocalDate.now(KST)): Bill? = Bill(
    billId = billId ?: return null,
    userCardId = userCardId,
    cardName = cardNameOf(nickname),
    billingMonth = parseYearMonth(billingMonth),
    usageFrom = parseDate(usagePeriod?.from),
    usageTo = parseDate(usagePeriod?.to),
    closingDate = parseDate(closingDate),
    paymentDate = parseDate(paymentDate),
    withdrawalDate = parseDate(withdrawalDate),
    state = billStateOf(status, parseDate(withdrawalDate), today),
    itemCount = itemCount,
    itemAmount = itemAmount,
    billingDiscount = billingDiscount,
    expectedAmount = expectedAmount,
    finalAmount = finalAmount,
    unpaidAmount = unpaidAmount,
    paidAt = parseInstant(paidAt),
    account = withdrawalAccount?.label(),
)

/** [requestedId] = 화면이 연 번호 — 응답에 번호가 빠져 와도 같은 청구서로 본다 */
fun BillDetailDto.toBillDetail(requestedId: Long, today: LocalDate = LocalDate.now(KST)): BillDetail {
    val usages = items.map { it.toUsage() }
    return BillDetail(
        bill = Bill(
            billId = billId ?: requestedId,
            userCardId = userCardId,
            cardName = cardNameOf(nickname),
            billingMonth = parseYearMonth(billingMonth),
            usageFrom = parseDate(usagePeriod?.from),
            usageTo = parseDate(usagePeriod?.to),
            closingDate = parseDate(closingDate),
            paymentDate = parseDate(paymentDate),
            withdrawalDate = parseDate(withdrawalDate),
            state = billStateOf(status, parseDate(withdrawalDate), today),
            itemCount = usages.size,
            itemAmount = amounts.itemAmount,
            billingDiscount = amounts.billingDiscount,
            expectedAmount = amounts.expectedAmount,
            finalAmount = amounts.finalAmount,
            unpaidAmount = amounts.unpaidAmount,
            paidAt = withdrawal?.takeIf { it.result.equals("SUCCESS", ignoreCase = true) }?.let { parseInstant(it.attemptedAt) },
            account = withdrawalAccount?.label(),
        ),
        cardLast4 = lastFour(maskedNumber),
        usages = usages,
        discounts = discounts.map { BillDiscount(it.name?.takeIf(String::isNotBlank) ?: "청구할인", it.amount) },
        discountsEstimated = discounts.any { it.confirmedAt == null },
        refunds = refunds.map { r ->
            val state = refundStateOf(r.status)
            BillRefund(
                refundId = r.refundId,
                merchant = usages.firstOrNull { it.billItemId != null && it.billItemId == r.billItemId }?.merchant,
                // 무효는 돌려줄 돈이 없다. 환급 내역(RefundQueryReader)은 0원으로 주는데 청구서 상세(BillQueryReader)는 원래 금액을
                // 그대로 줘서, 두 화면이 같게 보이도록 앱에서 0원으로 맞춘다(BE 불일치 — MR에 적음)
                amount = if (state == RefundState.CANCELED) 0 else r.amount,
                state = state,
            )
        },
        withdrawalAttemptedAt = parseInstant(withdrawal?.attemptedAt),
    )
}

private fun BillUsageDto.toUsage() = BillUsage(
    billItemId = billItemId,
    paymentId = paymentId,
    merchant = merchantName?.takeIf(String::isNotBlank) ?: "결제",
    approvedOn = parseInstant(approvedAt)?.toLocalDate(),
    planLabel = if (plan.equals("INSTALLMENT", ignoreCase = true) || totalSeq > 1) "할부 $seq/${totalSeq}회차" else "일시불",
    amount = amount,
    state = usageStateOf(status),
)

fun RefundPageDto.toRefundPage(requestedPage: Int): RefundPage = RefundPage(
    items = items.mapNotNull { it.toRefund() },
    page = requestedPage,
    hasNext = hasNext,
    summary = RefundSummary(summary.pendingAmount, summary.pendingCount.toInt(), summary.estimatedAmount),
)

/** 환급 번호도 결제 번호도 없으면 같은 줄인지 가릴 수 없고 눌러도 갈 곳이 없어 뺀다 */
fun RefundDto.toRefund(): Refund? {
    val key = refundId?.let { "r$it" } ?: paymentId?.let { "p$it" } ?: return null
    return Refund(
        key = key,
        refundId = refundId,
        paymentId = paymentId,
        billId = billId,
        cardName = nickname?.takeIf(String::isNotBlank) ?: "카드",
        merchant = merchantName?.takeIf(String::isNotBlank) ?: "결제",
        isCheckCancel = reason.equals("CHECK_CANCEL", ignoreCase = true),
        amount = amount,
        estimated = estimated,
        state = refundStateOf(status),
        createdOn = parseInstant(createdAt)?.toLocalDate(),
        scheduledAfter = parseDate(scheduledAfter),
        dueDate = parseDate(dueDate),
        completedOn = parseInstant(completedAt)?.toLocalDate(),
    )
}

private fun cardNameOf(nickname: String?) = nickname?.takeIf(String::isNotBlank) ?: "신용카드"

private fun AccountRefDto.label(): String? =
    listOfNotNull(bankName?.takeIf(String::isNotBlank), lastFour(maskedAccountNo)?.let { "·$it" }).joinToString(" ").ifEmpty { null }

/** '****-****-****-1021' · '0012-***-1234' → 끝 4자리. 숫자가 4개보다 적으면 null */
internal fun lastFour(masked: String?): String? = masked?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }

internal fun parseYearMonth(value: String?): YearMonth? = value?.let { runCatching { YearMonth.parse(it.trim()) }.getOrNull() }

/** 서버 시각(UTC에 Z) → 한국 시각. Z 없이 와도 BE 저장 규칙(UTC)대로 읽는다. 못 읽으면 null */
internal fun parseInstant(iso: String?): ZonedDateTime? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(KST) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC).atZoneSameInstant(KST) }.getOrNull()
}

// ───────────────────────── 화면 문장 ─────────────────────────

/** '12월 14일(월)' */
fun LocalDate.koreanDay(): String = "${monthValue}월 ${dayOfMonth}일(${dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

/** 목록 둘째 줄 */
fun Bill.rowLine(): String = when (state) {
    BillState.ACCUMULATING -> if (usageFrom != null && usageTo != null) "이용 ${usageFrom.toMonthDay()}~${usageTo.toMonthDay()}" else "이용기간 진행 중"
    BillState.CONFIRMED -> withdrawalDate?.let { "${it.toMonthDay()} 출금 예정" } ?: "출금 예정"
    BillState.PAID -> (paidAt?.toLocalDate() ?: withdrawalDate)?.let { "${it.toMonthDay()} 출금" } ?: "출금했어요"
    BillState.UNPAID -> withdrawalDate?.let { "${it.toMonthDay()} 출금 못 함" } ?: "출금 못 함"
    BillState.CANCELED -> "결제 취소로 낼 돈 없음"
    BillState.UNKNOWN -> withdrawalDate?.let { "${it.toMonthDay()} 출금일" } ?: "상태를 확인하고 있어요"
}

/** 금액 아래 작은 줄 — '예상' · '청구 42,000원' */
fun Bill.amountNote(): String? = if (isEstimate) "예상" else originalAmount?.let { "청구 ${it.toWon()}원" }

/** 상세 머리의 한 문장. 미납은 재출금이 없다(POL-007) */
fun Bill.statusLine(): String? = when (state) {
    BillState.ACCUMULATING -> usageTo?.let { "${it.koreanDay()}에 마감하면 금액이 정해져요" } ?: "이용기간이 끝나면 금액이 정해져요"
    BillState.CONFIRMED -> withdrawalDate?.let { "${it.koreanDay()}에 출금해요" } ?: "출금일을 확인하지 못했어요"
    BillState.PAID -> (paidAt?.toLocalDate() ?: withdrawalDate)?.let { "${it.koreanDay()}에 출금했어요" } ?: "출금했어요"
    BillState.UNPAID -> "출금하지 못했어요 · 다시 출금하지 않아요"
    BillState.CANCELED -> "결제를 취소해 낼 돈이 없어요"
    BillState.UNKNOWN -> null
}

/**
 * 상세 이용 내역 아래 한 줄. 확정된 청구서의 청구 제외는 이용 금액에 들어 있는지 응답만으로 가를 수 없어 알려 둔다.
 * 쌓이는 중인 청구서의 청구 제외는 확정 전 취소뿐이라 달지 않는다
 */
fun BillDetail.excludedNote(): String? =
    if (bill.state != BillState.ACCUMULATING && usages.any { it.state == UsageState.EXCLUDED }) {
        "청구 제외 가운데 청구가 확정된 뒤 취소한 건은 이용 금액에 남아 있어요"
    } else {
        null
    }

/** 월 머리 — 올해면 '12월 청구', 아니면 '2025년 12월 청구' */
fun billingMonthLabel(month: YearMonth?, today: LocalDate): String = when {
    month == null -> "청구 월 미확인"
    month.year == today.year -> "${month.monthValue}월 청구"
    else -> "${month.year}년 ${month.monthValue}월 청구"
}

fun Refund.tag(): String = when (state) {
    RefundState.PENDING -> if (estimated) "예상" else "입금 예정"
    RefundState.COMPLETED -> if (isCheckCancel) "환불 완료" else "입금 완료"
    RefundState.FAILED -> "입금 실패"
    RefundState.CANCELED -> "무효"
    RefundState.UNKNOWN -> "확인 중"
}

/** 카드 · 언제 — FAILED는 계좌가 없어 은행이 거절한 것뿐이라 BE가 다시 보내지 않는다(RefundDepositor) */
fun Refund.line(): String = "$cardName · " + when (state) {
    RefundState.PENDING ->
        if (estimated) scheduledAfter?.let { "${it.toMonthDay()} 출금 뒤 금액이 정해져요" } ?: "출금 뒤 금액이 정해져요"
        else dueDate?.let { "${it.toMonthDay()}까지 입금" } ?: "입금 준비 중"
    RefundState.COMPLETED -> (completedOn ?: dueDate)?.let { "${it.toMonthDay()} ${if (isCheckCancel) "환불" else "입금"}" } ?: "입금했어요"
    RefundState.FAILED -> "환급 계좌가 없어 입금하지 못했어요"
    RefundState.CANCELED -> "미납으로 끝나 돌려줄 돈이 없어요"
    RefundState.UNKNOWN -> createdOn?.let { "${it.toMonthDay()} 취소" } ?: "상태를 확인하고 있어요"
}

fun refundStateLabel(state: RefundState): String = when (state) {
    RefundState.PENDING -> "입금 예정"
    RefundState.COMPLETED -> "입금 완료"
    RefundState.FAILED -> "입금 실패"
    RefundState.CANCELED -> "무효"
    RefundState.UNKNOWN -> "확인 중"
}
