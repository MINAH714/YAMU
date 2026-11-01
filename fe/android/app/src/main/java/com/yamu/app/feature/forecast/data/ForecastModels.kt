package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.link.data.LivingCostDto
import com.yamu.app.feature.link.data.lastDigits
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

// ── 서버 요청 · 응답 — #10 잔고예보(v0.3.1) · #33 소비 판단 · #20 구매 계획. 12 문서 8-2 ──

@Serializable data class ForecastDto(
    val account: ForecastAccountDto? = null,
    val accounts: List<ForecastAccountDto> = emptyList(),       // 10/26 회의 — 계좌별(보완 필요)
    val horizon: HorizonDto? = null,
    val alertThreshold: Long? = null,
    val summary: ForecastSummaryDto = ForecastSummaryDto(),
    val series: List<SeriesPointDto> = emptyList(),
    val beyondHorizon: List<ForecastEventDto> = emptyList(),
    val livingCost: LivingCostDto? = null,
    val isEstimate: Boolean = true,
)

@Serializable data class ForecastAccountDto(
    val accountId: Long? = null,
    val bankName: String? = null,
    val nickname: String? = null,          // 10/26 회의 — 계좌 별칭
    val category: String? = null,          // 10/26 회의 — 월급 · 생활비 · 저축 …
    val maskedAccountNo: String? = null,
    val balance: Long? = null,
    val syncedAt: String? = null,
    val minBalance: Long? = null,
    val shortfallFrom: String? = null,
)

@Serializable data class HorizonDto(
    val type: String? = null,
    val from: String? = null,
    val to: String? = null,
    val nextIncome: NextIncomeEventDto? = null,
    val fallback: Boolean = false,
)

@Serializable data class NextIncomeEventDto(val date: String, val amount: Long? = null, val title: String? = null)

@Serializable data class ForecastSummaryDto(
    val status: String? = null,
    val minBalance: Long? = null,
    val minBalanceDate: String? = null,
    val shortfallFrom: String? = null,          // 보완안 ⑥
    val scheduledOutflow: Long? = null,         // 보완안 ⑥
)

@Serializable data class SeriesPointDto(
    val date: String,
    val confirmed: Long? = null,
    val expected: Long? = null,
    val events: List<ForecastEventDto> = emptyList(),
)

@Serializable data class ForecastEventDto(
    val type: String,                           // INCOME · EXPENSE · RECURRING_EXPENSE · CARD_BILLING · PURCHASE_PLAN · REFUND
    val title: String,
    val amount: Long,
    val date: String? = null,
    val refId: Long? = null,
)

@Serializable data class SpendingDecisionRequest(
    val amount: Long,
    val title: String? = null,
    val purchaseDate: String? = null,
    val categoryCode: String? = null,           // 지금 명세는 필수 — 보완안은 선택(12 문서 8-6 ③)
)

@Serializable data class SpendingDecisionDto(
    val verdict: String = "UNKNOWN",            // OK · CAUTION · HOLD · UNKNOWN
    val headline: String? = null,
    val affordable: AffordableDto? = null,
    val methods: List<MethodDto> = emptyList(),
    val reasons: List<ReasonDto> = emptyList(),
    val alternatives: List<AlternativeDto> = emptyList(),
    val monthlyBaseline: BaselineDto? = null,   // 보완안 ④
    val isEstimate: Boolean = true,
)

@Serializable data class AffordableDto(val beforeNextIncome: Long? = null, val nextIncomeDate: String? = null, val alertThreshold: Long? = null)

@Serializable data class MethodDto(
    val method: String,                         // CHECK_LUMP_SUM · CREDIT_LUMP_SUM · CREDIT_INSTALLMENT
    val userCardId: Long? = null,
    val nickname: String? = null,
    val months: Int? = null,
    val withdrawals: List<WithdrawalDto> = emptyList(),
    val minBalance: Long? = null,
    val shortfallFrom: String? = null,
    val status: String? = null,                 // SAFE · WARNING · DANGER
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

/** #20 구매 계획 — kind PURCHASE_PLAN */
@Serializable data class PurchasePlanRequest(
    val kind: String = "PURCHASE_PLAN",
    val type: String = "EXPENSE",
    val title: String,
    val amount: Long,
    val date: String,
    val userCardId: Long? = null,
    val plan: String = "LUMP_SUM",
    val installmentMonths: Int? = null,
)

@Serializable data class CashflowCreatedDto(
    val cashflowItemId: Long,
    val occurrenceDate: String? = null,
    val forecastImpact: ForecastImpactDto? = null,
)

@Serializable data class ForecastImpactDto(
    val minBalanceBefore: Long? = null,
    val minBalanceAfter: Long? = null,
    val shortfallFrom: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

data class ForecastEvent(
    val date: LocalDate?,
    val title: String,
    val amount: Long,
    val isPlan: Boolean,
    val planId: Long?,
    val afterIncome: Boolean,
    val isCardBill: Boolean = false,            // 카드대금 — 가계부 항목이 아니라 F-02에서 고칠 수 없다
)

data class ForecastAccount(val name: String, val category: String?, val last4: String, val balance: Long?, val minBalance: Long?, val shortfallFrom: LocalDate?)

data class Forecast(
    val currentBalance: Long?,
    val balanceAsOfIso: String?,
    val minBalance: Long?,
    val shortfallFrom: LocalDate?,
    val incomeDate: LocalDate?,
    val incomeAmount: Long?,
    val incomeTitle: String,
    val from: LocalDate?,
    val to: LocalDate?,
    val fallback: Boolean,
    val upcoming: List<ForecastEvent>,          // 월급 전까지 예정된 돈(계획 제외)
    val plans: List<ForecastEvent>,             // 구매 계획(월급 뒤 포함)
    val scheduledOutflow: Long,                 // 예정 지출 합(양수)
    val livingCost: Long?,
    val points: List<Pair<LocalDate, Long>>,    // 그래프 — 예상 선
    val isEstimate: Boolean,
    val accounts: List<ForecastAccount>,
)

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
    /** '10월 26일에 사기' · '3개월 할부' */
    val title: String get() = if (method == BuyMethod.CREDIT_INSTALLMENT) "${months ?: 0}개월 할부" else "${date.monthValue}월 ${date.dayOfMonth}일에 사기"

    /** '체크카드' · '신용카드 일시불' · '신용카드' · '월급 뒤' */
    val methodLabel: String get() = when (method) {
        BuyMethod.CHECK_LUMP_SUM -> "체크카드"
        BuyMethod.CREDIT_LUMP_SUM -> "신용카드 일시불"
        BuyMethod.CREDIT_INSTALLMENT -> "신용카드"
        BuyMethod.DELAY -> "월급 뒤"
    }

    /** '−50,000원 월급 전' · '133,400원 매달' */
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
    /** 미루기 대안(있으면 주 버튼 '○월 ○일로 계획 저장') */
    val delay: BuyOption? get() = options.firstOrNull { it.method == BuyMethod.DELAY }
}

data class SavedPlan(
    val id: Long,
    val title: String,
    val amount: Long,
    val date: LocalDate,
    val minBefore: Long?,
    val minAfter: Long?,
    val shortfallFrom: LocalDate?,
)

// ── 서버 값 → 화면 값 ──

private val PLAN_TYPES = setOf("PURCHASE_PLAN")

fun ForecastDto.toForecast(): Forecast {
    val incomeDate = parseDate(horizon?.nextIncome?.date)
    val to = parseDate(horizon?.to)
    fun ForecastEventDto.toEvent(pointDate: LocalDate?, after: Boolean) = ForecastEvent(
        date = parseDate(date) ?: pointDate,
        title = title,
        amount = amount,
        isPlan = type.uppercase() in PLAN_TYPES,
        planId = refId.takeIf { type.uppercase() in PLAN_TYPES },
        afterIncome = after,
        isCardBill = type.uppercase() == "CARD_BILLING",
    )
    val inHorizon = series.flatMap { p -> p.events.map { it.toEvent(parseDate(p.date), after = false) } }
    val beyond = beyondHorizon.map { it.toEvent(null, after = true) }
    val upcoming = inHorizon.filter { !it.isPlan && it.amount != 0L }.sortedBy { it.date }
    val points = series.mapNotNull { p -> parseDate(p.date)?.let { d -> (p.expected ?: p.confirmed)?.let { d to it } } }.sortedBy { it.first }
    val primary = account ?: accounts.firstOrNull()
    return Forecast(
        // 지금 필드(account)는 합친 결과 — 없으면 계좌별 잔액 합
        currentBalance = account?.balance ?: accounts.mapNotNull { it.balance }.takeIf { it.isNotEmpty() }?.sum(),
        balanceAsOfIso = primary?.syncedAt,
        minBalance = summary.minBalance,
        // 부족 시작일: 서버 값 → 없으면 예상 선이 처음 0 아래인 날(표시용)
        shortfallFrom = parseDate(summary.shortfallFrom) ?: points.firstOrNull { it.second < 0 }?.first,
        incomeDate = incomeDate,
        incomeAmount = horizon?.nextIncome?.amount,
        incomeTitle = horizon?.nextIncome?.title ?: "월급",
        from = parseDate(horizon?.from),
        to = to,
        fallback = horizon?.fallback ?: false,
        upcoming = upcoming,
        plans = (inHorizon + beyond).filter { it.isPlan }.sortedBy { it.date },
        scheduledOutflow = summary.scheduledOutflow ?: -upcoming.filter { it.amount < 0 }.sumOf { it.amount },
        livingCost = livingCost?.projectedAmount,
        points = points,
        isEstimate = isEstimate,
        accounts = accounts.map {
            ForecastAccount(
                name = it.nickname ?: listOfNotNull(it.bankName, "입출금").joinToString(" "),
                category = it.category,
                last4 = it.maskedAccountNo?.lastDigits().orEmpty(),
                balance = it.balance,
                minBalance = it.minBalance,
                shortfallFrom = parseDate(it.shortfallFrom),
            )
        },
    )
}

/** #33 → I-02 · I-03 · I-04 */
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
        // 미룬 날에는 처음 방식(카드)으로 산다고 본다
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

/** I-04 · I-02 [계획 저장] → #20 본문 */
fun BuyOption.toPlanRequest(title: String?, amount: Long) = PurchasePlanRequest(
    title = title?.takeIf { it.isNotBlank() }?.take(30) ?: "구매 계획",
    amount = amount,
    date = date.toString(),
    userCardId = userCardId,
    plan = if (method == BuyMethod.CREDIT_INSTALLMENT) "INSTALLMENT" else "LUMP_SUM",
    installmentMonths = months.takeIf { method == BuyMethod.CREDIT_INSTALLMENT },
)
