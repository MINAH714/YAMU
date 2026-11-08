package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

// ── #33 소비 판단(I-02 · I-03 · I-04) — BE에 없는 API. S15P31E103-326에서 가상 구매 시뮬레이션으로 바꾸며 지운다 ──

@Serializable data class SpendingDecisionRequest(
    val amount: Long,
    val title: String? = null,
    val purchaseDate: String? = null,
    val categoryCode: String? = null,
)

@Serializable data class SpendingDecisionDto(
    val verdict: String = "UNKNOWN",            // OK · CAUTION · HOLD · UNKNOWN
    val headline: String? = null,
    val methods: List<MethodDto> = emptyList(),
    val reasons: List<ReasonDto> = emptyList(),
    val alternatives: List<AlternativeDto> = emptyList(),
    val monthlyBaseline: BaselineDto? = null,
    val isEstimate: Boolean = true,
)

@Serializable data class MethodDto(
    val method: String,                         // CHECK_LUMP_SUM · CREDIT_LUMP_SUM · CREDIT_INSTALLMENT
    val userCardId: Long? = null,
    val nickname: String? = null,
    val months: Int? = null,
    val withdrawals: List<WithdrawalDto> = emptyList(),
    val minBalance: Long? = null,
    val shortfallFrom: String? = null,
    val status: String? = null,
)

@Serializable data class WithdrawalDto(val date: String, val amount: Long)

@Serializable data class ReasonDto(val axis: String? = null, val level: String? = null, val message: String)

@Serializable data class AlternativeDto(
    val type: String,                           // DELAY · INSTALLMENT
    val until: String? = null,
    val minBalanceAfter: Long? = null,
    val months: Int? = null,
    val monthlyAmount: Long? = null,
    val message: String? = null,
)

@Serializable data class BaselineDto(val averageMonthly: Long? = null, val overRatio: Double? = null, val periodLabel: String? = null)

enum class BuyVerdict(val label: String) {
    OK("괜찮아요"),
    CAUTION("미루는 게 좋아요"),
    HOLD("사지 않는 게 좋아요"),
    UNKNOWN("아직 판단하기 어려워요");

    companion object {
        fun from(value: String?): BuyVerdict = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

enum class BuyMethod { CHECK_LUMP_SUM, CREDIT_LUMP_SUM, CREDIT_INSTALLMENT, DELAY }

/** 사는 방법 한 줄(I-04) — 서버가 준 방식 · 대안 그대로 */
data class BuyOption(
    val method: BuyMethod,
    val date: LocalDate,
    val userCardId: Long?,
    val cardName: String?,
    val months: Int?,
    val minBalance: Long?,
    val monthlyAmount: Long?,
    val shortfallFrom: LocalDate?,
    val recommended: Boolean,
) {
    val title: String get() = if (method == BuyMethod.CREDIT_INSTALLMENT) "${months ?: 0}개월 할부" else "${date.monthValue}월 ${date.dayOfMonth}일에 사기"

    val methodLabel: String get() = when (method) {
        BuyMethod.CHECK_LUMP_SUM -> "체크카드"
        BuyMethod.CREDIT_LUMP_SUM -> "신용카드 일시불"
        BuyMethod.CREDIT_INSTALLMENT -> "신용카드"
        BuyMethod.DELAY -> "월급 뒤"
    }

    val valueLabel: String? get() = when {
        method == BuyMethod.CREDIT_INSTALLMENT && monthlyAmount != null -> "${monthlyAmount.toWon()}원 매달"
        minBalance != null -> "${minBalance.toWon()}원 월급 전"
        else -> null
    }
}

data class BuyCheck(
    val title: String?,
    val amount: Long,
    val purchaseDate: LocalDate,
    val verdict: BuyVerdict,
    val headline: String?,
    val firstMethodLabel: String?,
    val baselineRatio: Int?,
    val baselineAverage: Long?,
    val baselinePeriod: String?,
    val reasons: List<String>,
    val options: List<BuyOption>,
    val isEstimate: Boolean,
) {
    val delay: BuyOption? get() = options.firstOrNull { it.method == BuyMethod.DELAY }
}

fun SpendingDecisionDto.toBuyCheck(title: String?, amount: Long, purchaseDate: LocalDate): BuyCheck {
    val options = methods.mapNotNull { m ->
        val method = runCatching { BuyMethod.valueOf(m.method.uppercase()) }.getOrNull() ?: return@mapNotNull null
        BuyOption(
            method = method,
            date = parseDate(m.withdrawals.firstOrNull()?.date)?.takeIf { method == BuyMethod.CHECK_LUMP_SUM } ?: purchaseDate,
            userCardId = m.userCardId,
            cardName = m.nickname,
            months = m.months,
            minBalance = m.minBalance,
            monthlyAmount = m.withdrawals.firstOrNull()?.amount.takeIf { method == BuyMethod.CREDIT_INSTALLMENT },
            shortfallFrom = parseDate(m.shortfallFrom),
            recommended = false,
        )
    }
    val first = options.firstOrNull()
    val delays = alternatives.filter { it.type.equals("DELAY", ignoreCase = true) }.mapNotNull { alt ->
        val until = parseDate(alt.until) ?: return@mapNotNull null
        BuyOption(BuyMethod.DELAY, until, first?.userCardId, first?.cardName, null, alt.minBalanceAfter, null, null, recommended = true)
    }
    return BuyCheck(
        title = title?.takeIf { it.isNotBlank() },
        amount = amount,
        purchaseDate = purchaseDate,
        verdict = BuyVerdict.from(verdict),
        headline = headline,
        firstMethodLabel = first?.methodLabel,
        baselineRatio = monthlyBaseline?.overRatio?.roundToInt(),
        baselineAverage = monthlyBaseline?.averageMonthly,
        baselinePeriod = monthlyBaseline?.periodLabel,
        reasons = reasons.map { it.message } + alternatives.mapNotNull { it.message },
        options = options + delays,
        isEstimate = isEstimate,
    )
}

/** I-04 · I-02 [계획 저장] → #20 본문. 카드가 없는 선택지는 [accountId]로 나간다 */
fun BuyOption.toPlanRequest(title: String?, amount: Long, accountId: Long? = null) = PurchasePlanRequest(
    type = "EXPENSE",
    title = title?.takeIf { it.isNotBlank() }?.take(30) ?: "구매 계획",
    amount = amount,
    date = date.toString(),
    accountId = accountId.takeIf { userCardId == null },
    userCardId = userCardId,
)
