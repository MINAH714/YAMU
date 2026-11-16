package com.yamu.app.feature.forecast.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 잔고예보 그래프를 월급날에서 끊는 계산(S15P31E103-567) — 월급이 25일이면 7 · 14 · 30일을 골라도 x축 끝이 25일이다.
 * 서버는 '월급날까지'면 월급 전날까지만 잔액을 주므로(명세 · 2026-11-04 결정) 앱도 선은 월급 전날까지만 두고 월급날은 축 끝 칸으로만 그린다
 */

/** 잔고예보 설정의 월급 — 이름과 매월 들어오는 날(1~31, 짧은 달은 말일) */
data class IncomeRule(val title: String, val dayOfMonth: Int)

/**
 * [after] 다음 첫 월급날 — 그달 [dayOfMonth]일(짧은 달은 말일)이 [after]보다 뒤면 그날, 아니면 다음 달 같은 날.
 * BE nextIncomeDate와 같은 규칙이라 오늘이 월급날이면 다음 달 월급날이다
 */
internal fun nextPayday(dayOfMonth: Int, after: LocalDate): LocalDate {
    fun on(month: YearMonth) = month.atDay(dayOfMonth.coerceIn(1, month.lengthOfMonth()))
    val month = YearMonth.from(after)
    return on(month).takeIf { it.isAfter(after) } ?: on(month.plusMonths(1))
}

/**
 * 이 예보의 다음 월급날. '월급날까지' 예보면 서버 기간 끝 다음 날([Forecast.incomeDate])이고, 7 · 14 · 30일이면 정해 둔 월급([rule])으로 찾는다.
 * 기간 안에 월급과 같은 이름의 예정 수입이 있으면 그날(이번만 옮긴 회차도 맞게), 없으면 매월 날짜로 센다. 기준 날은 예보 첫 날(서버 오늘)이다
 */
internal fun Forecast.payday(rule: IncomeRule?): LocalDate? {
    incomeDate?.let { return it }
    if (rule == null) return null
    val first = days.firstOrNull()?.date ?: return null
    days.firstOrNull { d -> d.date.isAfter(first) && d.events.any { it.type == FlowType.SCHEDULED_INCOME && it.amount > 0 && it.title == rule.title } }
        ?.let { return it.date }
    return nextPayday(rule.dayOfMonth, first)
}

/**
 * 월급날([payday])이 7 · 14 · 30일 기간 안이면 월급 전날까지로 자른 예보. 끝 날이 월급 전날이고 [Forecast.incomeDate]가 월급날이라
 * '월급날까지' 예보와 같은 모양이 되어 그래프 끝 월급날 칸 · '월급 전 가장 낮을 때' · 상태 태그가 그대로 따라온다.
 * 금액은 서버 값에서 고르기만 한다. 가장 낮을 때는 남은 날 중 가장 낮은 선(생활비 반영, 없으면 제외)의 최저이고,
 * 위험 구간은 월급 전날에서 끊어 그 안의 최저를 다시 고르며, 잘린 날의 카드값은 '기간 뒤에 빠질 카드값'으로 옮긴다.
 * 계좌별 위험([Forecast.hasAccountRisk])은 날짜가 없어 서버 값 그대로다. 월급날을 모르거나 기간 밖(첫 날 이하 · 끝 날 뒤)이면 그대로 돌려준다
 */
internal fun Forecast.untilPayday(payday: LocalDate?): Forecast {
    if (payday == null || isNextIncome || days.isEmpty()) return this
    val first = days.first().date
    if (!payday.isAfter(first) || payday.isAfter(days.last().date)) return this
    val end = payday.minusDays(1)
    val kept = days.filter { it.date.isBefore(payday) }
    val cut = days.filterNot { it.date.isBefore(payday) }
    val ride = kept.mapNotNull { d -> ((if (hasConservative) d.conservative else null) ?: d.expected)?.let { d.date to it } }
    val low = ride.minByOrNull { it.second }
    val windows = riskWindows.filter { it.from.isBefore(payday) }.map { w ->
        if (!w.to.isAfter(end)) return@map w
        val lowest = kept.filter { !it.date.isBefore(w.from) }
            .mapNotNull { d -> if (w.line == FlowConfidence.EXPECTED) d.expected else d.conservative ?: d.expected }
            .minOrNull()
        w.copy(
            to = end,
            lowestBalance = lowest ?: w.lowestBalance,
            shortfall = if (lowest != null && alertThreshold != null) (alertThreshold - lowest).coerceAtLeast(0) else w.shortfall,
            causes = w.causes.filter { it.date == null || it.date.isBefore(payday) },
        )
    }
    val level = when {
        windows.any { it.level == RiskLevel.DANGER } -> RiskLevel.DANGER
        windows.isNotEmpty() -> RiskLevel.WARNING
        status == RiskLevel.WARNING || status == RiskLevel.DANGER -> RiskLevel.SAFE   // 위험이 모두 월급 뒤였다
        else -> status
    }
    val cutBills = cut.flatMap { it.events }.filter { it.isCardBill && it.amount != 0L }
    return copy(
        to = end,
        isNextIncome = true,
        horizonDays = ChronoUnit.DAYS.between(first, end).toInt(),
        status = level,
        minBalance = low?.second ?: minBalance,
        minBalanceDate = low?.first ?: minBalanceDate,
        endBalance = ride.lastOrNull()?.second ?: endBalance,
        days = kept,
        riskWindows = windows,
        beyondHorizon = cutBills + beyondHorizon,
    )
}
