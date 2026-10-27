package com.yamu.app.feature.report.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.IssuerDto
import com.yamu.app.feature.link.data.brandOf
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// ── 서버 요청 · 응답 — #54 월간 소비 · #55 혜택 리포트 · #4 결제 내역. 13 문서 9·10-3 ──

@Serializable data class MonthlyReportDto(
    val yearMonth: String? = null,
    val isPartialMonth: Boolean = true,
    val totalSpent: Long = 0,
    val livingSpent: Long? = null,              // 보완안 ①
    val prevSamePeriodSpent: Long? = null,      // 보완안 ①
    val periodTo: String? = null,               // 보완안 — 집계 끝 날
    val byCategory: List<CategoryAmountDto> = emptyList(),
    val benefitSummary: MonthBenefitDto? = null,
)

@Serializable data class CategoryAmountDto(val categoryCode: String? = null, val categoryName: String, val amount: Long = 0, val ratio: Double? = null)

@Serializable data class MonthBenefitDto(val expected: Long? = null, val actual: Long? = null)

@Serializable data class BenefitReportDto(
    val yearMonth: String? = null,
    val status: String? = null,                 // CONFIRMED · PARTIAL
    val summary: BenefitReportSummaryDto = BenefitReportSummaryDto(),
    val differences: List<DifferenceDto> = emptyList(),
    val byCategory: List<CategoryAmountDto> = emptyList(),   // 보완안 ②
)

@Serializable data class BenefitReportSummaryDto(
    val expectedBenefit: Long = 0,
    val confirmedBenefit: Long = 0,
    val yamuPickExtra: Long? = null,            // 보완안 ②
    val missedBenefit: Long? = null,            // 보완안 ②
)

@Serializable data class DifferenceDto(
    val paymentId: Long? = null,
    val approvedAt: String? = null,
    val merchantName: String,
    val expectedAmount: Long = 0,
    val actualAmount: Long = 0,
    val reason: String? = null,
)

@Serializable data class PaymentsPageDto(
    val summary: PaymentsSummaryDto? = null,
    val items: List<PaymentItemDto> = emptyList(),
    val page: Int = 1,
    val hasNext: Boolean = false,
)

@Serializable data class PaymentsSummaryDto(val count: Int = 0, val amount: Long = 0)

@Serializable data class PaymentItemDto(
    val paymentId: Long,
    val status: String = "APPROVED",            // APPROVED · CANCELED · DECLINED
    val approvedAt: String? = null,
    val merchant: PaymentMerchantDto? = null,
    val amount: Long = 0,
    val card: PaymentCardDto? = null,
    val benefitAmount: Long? = null,
    val benefitType: String? = null,            // 보완안 ③ — INSTANT_DISCOUNT · BILLING_DISCOUNT · POINT · CASHBACK
    val performanceRecognized: Boolean = true,
)

@Serializable data class PaymentMerchantDto(val name: String, val categoryName: String? = null)

@Serializable data class PaymentCardDto(val userCardId: Long? = null, val nickname: String? = null, val issuer: IssuerDto? = null)

// ── 앱 안에서 쓰는 값 ──

data class CategoryShare(val name: String, val ratio: Int?, val amount: Long)

data class MonthlySpend(
    val periodLabel: String,
    val month: Int?,
    val isLiving: Boolean,                      // 생활 소비(서버 값)인지, 카드 결제 합계인지
    val spent: Long,
    val diffFromPrev: Long?,                    // 지난달 같은 기간 대비(서버 두 값의 차 — 표시용)
    val categories: List<CategoryShare>,
    val benefit: Long?,
    val paymentCount: Int?,
)

data class MissedBenefit(val merchant: String, val date: LocalDate?, val reason: String, val amount: Long)

data class BenefitReport(
    val periodLabel: String,
    val received: Long,
    val yamuExtra: Long?,
    val missedTotal: Long,
    val missed: List<MissedBenefit>,
    val byPlace: List<Pair<String, Long>>,
)

data class PaymentItem(
    val paymentId: Long,
    val date: LocalDate?,
    val time: String?,
    val merchant: String,
    val brand: Brand?,
    val amount: Long,
    val benefit: Long?,
    val benefitLabel: String,                   // 할인 · 적립 · 혜택
    val excluded: Boolean,                      // 실적 제외
    val canceled: Boolean,
)

data class PaymentsPage(val count: Int, val amount: Long, val items: List<PaymentItem>, val page: Int, val hasNext: Boolean)

// ── 서버 값 → 화면 값 ──

/** '10월 1~27일'(이번 달 중간) · '10월'(끝난 달) */
fun periodLabel(yearMonth: String?, partial: Boolean, periodTo: String? = null, today: LocalDate = LocalDate.now(KST)): String {
    val ym = yearMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(today)
    if (!partial) return "${ym.monthValue}월"
    val end = periodTo?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.dayOfMonth
        ?: if (YearMonth.from(today) == ym) today.dayOfMonth else ym.atEndOfMonth().dayOfMonth
    return "${ym.monthValue}월 1~${end}일"
}

/** 놓친 혜택 이유(#55 differences.reason) → 짧은 말 */
fun missedReason(code: String?): String = when (code?.uppercase()) {
    "LIMIT_EXHAUSTED" -> "한도 소진"
    "LIMIT_PARTIAL" -> "한도 일부만"
    "CANCELED" -> "결제 취소"
    "TIER_NOT_MET" -> "실적 미달"
    "RULE_CHANGED" -> "혜택 바뀜"
    "AUTO_SELECT_OFF" -> "자동 선택 꺼짐"
    "USER_SELECTED" -> "직접 고른 카드"
    else -> "혜택 안 됨"
}

fun MonthlyReportDto.toSpend(paymentCount: Int?): MonthlySpend {
    val living = livingSpent
    return MonthlySpend(
        periodLabel = periodLabel(yearMonth, isPartialMonth, periodTo),
        month = yearMonth?.takeLast(2)?.toIntOrNull(),
        isLiving = living != null,
        spent = living ?: totalSpent,
        diffFromPrev = if (living != null && prevSamePeriodSpent != null) living - prevSamePeriodSpent else null,
        categories = byCategory.sortedByDescending { it.amount }.take(3).map { CategoryShare(it.categoryName, it.ratio?.roundToInt(), it.amount) },
        benefit = benefitSummary?.let { it.expected ?: it.actual },
        paymentCount = paymentCount,
    )
}

fun BenefitReportDto.toReport(): BenefitReport {
    val missed = differences.filter { it.expectedAmount > it.actualAmount }.map {
        MissedBenefit(
            merchant = it.merchantName,
            date = it.approvedAt?.let { a -> runCatching { OffsetDateTime.parse(a).atZoneSameInstant(KST).toLocalDate() }.getOrNull() },
            reason = missedReason(it.reason),
            amount = it.expectedAmount - it.actualAmount,
        )
    }
    return BenefitReport(
        periodLabel = periodLabel(yearMonth, status.equals("PARTIAL", ignoreCase = true)),
        received = summary.expectedBenefit.takeIf { it > 0 } ?: summary.confirmedBenefit,
        yamuExtra = summary.yamuPickExtra?.takeIf { it > 0 },
        missedTotal = summary.missedBenefit ?: missed.sumOf { it.amount },
        missed = missed,
        byPlace = byCategory.sortedByDescending { it.amount }.map { it.categoryName to it.amount },
    )
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

fun PaymentItemDto.toItem(): PaymentItem {
    val at = approvedAt?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST) }.getOrNull() }
    val brand = card?.let { c ->
        c.issuer?.let { brandOf(it.name, color = it.color) } ?: c.nickname?.let { brandOf(it, shortName = it.substringBefore(' ')) }
    }
    return PaymentItem(
        paymentId = paymentId,
        date = at?.toLocalDate(),
        time = at?.format(HM),
        merchant = merchant?.name ?: "결제",
        brand = brand,
        amount = amount,
        benefit = benefitAmount?.takeIf { it > 0 },
        benefitLabel = when (benefitType?.uppercase()) {
            "INSTANT_DISCOUNT", "BILLING_DISCOUNT" -> "할인"
            "POINT", "CASHBACK" -> "적립"
            else -> "혜택"
        },
        excluded = !performanceRecognized,
        canceled = status.equals("CANCELED", ignoreCase = true),
    )
}

fun PaymentsPageDto.toPage() = PaymentsPage(
    count = summary?.count ?: items.size,
    amount = summary?.amount ?: 0,
    items = items.map { it.toItem() },
    page = page,
    hasNext = hasNext,
)

/** 지난달 같은 기간과 비교 — 서버 두 값의 차를 문장으로 */
fun spendDiffSentence(diff: Long): String = when {
    diff > 0 -> "지난달 같은 기간보다 ${diff.toWon()}원 더 썼어요"
    diff < 0 -> "지난달 같은 기간보다 ${(-diff).toWon()}원 덜 썼어요"
    else -> "지난달 같은 기간과 같아요"
}

/** 결제 내역 날짜 머리 — '10월 16일 (금) · 오늘' */
fun paymentDayLabel(date: LocalDate?, today: LocalDate = LocalDate.now(KST)): String {
    if (date == null) return "날짜 없음"
    val week = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    val base = "${date.monthValue}월 ${date.dayOfMonth}일 ($week)"
    return if (date == today) "$base · 오늘" else base
}

/** 다음 쪽을 붙일 때 · 돌아와 첫 쪽을 다시 받을 때 — 같은 결제는 한 번만(새 결제가 맨 위로) */
fun mergePayments(first: List<PaymentItem>, rest: List<PaymentItem>): List<PaymentItem> {
    val ids = first.mapTo(HashSet()) { it.paymentId }
    return first + rest.filter { it.paymentId !in ids }
}
