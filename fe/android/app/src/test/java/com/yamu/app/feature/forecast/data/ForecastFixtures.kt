package com.yamu.app.feature.forecast.data

import java.time.LocalDate

/** 테스트용 예보 — 필요한 칸만 바꿔 쓴다 */
internal fun testForecast(
    days: List<ForecastDay> = emptyList(),
    isNextIncome: Boolean = true,
    from: LocalDate? = LocalDate.of(2026, 11, 1),
    to: LocalDate? = LocalDate.of(2026, 11, 25),
    horizonDays: Int? = 24,
    accountIds: List<Long> = listOf(1),
    status: RiskLevel = RiskLevel.SAFE,
    riskWindows: List<RiskWindow> = emptyList(),
    livingCost: ForecastLivingCost? = null,
    excluded: List<ExcludedNote> = emptyList(),
) = Forecast(
    accountIds = accountIds,
    currentBalance = 950_000,
    from = from,
    to = to,
    isNextIncome = isNextIncome,
    horizonDays = horizonDays,
    alertThreshold = 100_000,
    status = status,
    hasAccountRisk = false,
    atRiskAccountCount = 0,
    minBalance = 350_000,
    minBalanceDate = to,
    endBalance = 350_000,
    days = days,
    riskWindows = riskWindows,
    livingCost = livingCost,
    excluded = excluded,
    isEstimate = true,
    calculatedAtIso = null,
)

internal fun day(date: LocalDate, vararg events: ForecastEvent, expected: Long = 500_000, conservative: Long? = 400_000) =
    ForecastDay(date, confirmed = expected, expected = expected, conservative = conservative, events = events.toList())
