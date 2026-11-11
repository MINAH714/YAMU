package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.BigDecimalSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

// ── 생활비 예측 상세 — GET /api/forecasts/living-cost(BE LivingCostForecast, docs/be/forecast.md 「생활비 상세」) ──
// 잔고예보와 같은 accountIds · until · days로 불러야 같은 기간을 설명한다. 어느 계좌에도 기록이 없으면 422 DATA_INSUFFICIENT

@Serializable data class LivingCostDetailDto(
    val accountIds: List<Long> = emptyList(),
    val projectedAmount: Long? = null,
    val dailyAverage: Long? = null,
    val monthlyAverage: Long? = null,
    val method: String? = null,
    val dataOrigin: String? = null,
    val confidence: String? = null,
    val period: LivingPeriodDto? = null,
    val sources: List<LivingSourceDto> = emptyList(),
    val excluded: List<LivingExclusionDto> = emptyList(),
    val dailyTarget: Long? = null,
    val paceCheck: PaceCheckDto? = null,
    val dataSyncedAt: String? = null,
    val accountLivingCosts: List<AccountLivingCostDto>? = null,
)

/** basis는 실제로 쓴 기간 기준(다음 월급일이 없으면 NEXT_INCOME이어도 DAYS) */
@Serializable data class LivingPeriodDto(val from: String? = null, val to: String? = null, val days: Int? = null, val basis: String? = null)

@Serializable data class LivingSourceDto(val yearMonth: String? = null, val amount: Long = 0)

@Serializable data class LivingExclusionDto(val type: String? = null, val note: String? = null)

/** overRatio는 이번 달 하루 평균이 평소보다 몇 배 많은지에서 1을 뺀 값(0.3이면 30% 초과), flagged는 1.3배를 넘었는지 */
@Serializable data class PaceCheckDto(
    val thisMonthDaily: Long? = null,
    val averageDaily: Long? = null,
    @Serializable(with = BigDecimalSerializer::class) val overRatio: BigDecimal? = null,
    val flagged: Boolean = false,
)

@Serializable data class AccountLivingCostDto(
    val accountId: Long,
    val dailyAverage: Long? = null,
    val monthlyAverage: Long? = null,
    val projectedAmount: Long? = null,
    val method: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

enum class LivingConfidence(val label: String) {
    HIGH("기록 60일 이상"),
    MEDIUM("기록 14~59일"),
    LOW("기록 13일 이하"),
    INSUFFICIENT("기록 없음"),
    UNKNOWN(""),
}

data class LivingMonth(val month: YearMonth, val amount: Long)

data class PaceCheck(val thisMonthDaily: Long?, val averageDaily: Long?, val overPercent: Int?, val flagged: Boolean)

/** 계좌 하나 — [name]은 연결 계좌 목록에서 찾은 이름(못 찾으면 null), 기록이 없으면 [projectedAmount] · [dailyAverage]가 null */
data class AccountLivingCost(val accountId: Long, val name: String?, val projectedAmount: Long?, val dailyAverage: Long?, val method: LivingMethod) {
    val hasRecord: Boolean get() = projectedAmount != null || dailyAverage != null
}

data class LivingCostDetail(
    val projectedAmount: Long?,
    val method: LivingMethod,
    val confidence: LivingConfidence,
    val seeded: Boolean,
    val dailyAverage: Long?,
    val monthlyAverage: Long?,
    val from: LocalDate?,
    val to: LocalDate?,
    val days: Int?,
    val isNextIncome: Boolean,
    val months: List<LivingMonth>,
    val excludedNotes: List<String>,
    val dailyTarget: Long?,
    val pace: PaceCheck?,
    val accounts: List<AccountLivingCost>,
) {
    /** '월급 전까지' · '30일 동안' — 같은 기간의 잔고예보 문구와 맞춘다 */
    val periodLabel: String get() = if (isNextIncome) "월급 전까지" else "${days ?: 30}일 동안"

    /** 기록이 없는 계좌 — 계좌가 여럿인데 일부만 기록이 있으면 잔고예보의 'AI 생활비 반영' 선이 빠진 이유 */
    val accountsWithoutRecord: List<AccountLivingCost> get() = accounts.filter { !it.hasRecord }
}

fun LivingCostDetailDto.toDetail(accountNames: Map<Long, String> = emptyMap()) = LivingCostDetail(
    projectedAmount = projectedAmount,
    method = enumOf(method, LivingMethod.UNKNOWN),
    confidence = enumOf(confidence, LivingConfidence.UNKNOWN),
    seeded = dataOrigin.equals("SEED", ignoreCase = true),
    dailyAverage = dailyAverage,
    monthlyAverage = monthlyAverage,
    from = parseDate(period?.from),
    to = parseDate(period?.to),
    days = period?.days,
    isNextIncome = period?.basis.equals("NEXT_INCOME", ignoreCase = true),
    months = sources.mapNotNull { s -> s.yearMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }?.let { LivingMonth(it, s.amount) } }.sortedBy { it.month },
    excludedNotes = excluded.mapNotNull { it.note?.takeIf { n -> n.isNotBlank() } },
    dailyTarget = dailyTarget,
    pace = paceCheck?.let { p ->
        PaceCheck(p.thisMonthDaily, p.averageDaily, p.overRatio?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toInt(), p.flagged)
    },
    accounts = accountLivingCosts.orEmpty().map { a ->
        AccountLivingCost(a.accountId, accountNames[a.accountId], a.projectedAmount, a.dailyAverage, enumOf(a.method, LivingMethod.UNKNOWN))
    },
)
