package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastDay
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.RiskWindow
import com.yamu.app.feature.forecast.data.testForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 7 · 14 · 30일을 고르면 고른 기간 끝까지 그린다(S15P31E103-595). -567은 기간 안에 월급날이 있으면 월급 전날까지 끊어
 * 30일을 골라도 그래프 · 달력이 25일(월급날)에서 멈췄다. 이제 기간 안의 월급은 그날 입금으로만 보이고, 축 끝 월급 칸은 '월급날까지'에만 있다
 */
class ForecastPeriodFullTest {

    @Test fun `30일 예보는 기간 안에 월급날이 있어도 끝 날까지 남는다`() {
        val f = days30()
        assertEquals(30, f.days.size)
        assertEquals(LAST, f.days.last().date)
        assertNull("축 끝 월급 칸 · 띠 끝 월급 알약 · 달력 월급 칸은 그리지 않는다", f.incomeDate)
    }

    @Test fun `달력은 첫 날 달부터 30일째 달까지 넘긴다`() {
        val f = days30()
        assertEquals(
            listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12)),
            calendarMonths(f.days.first().date, f.incomeDate ?: f.days.last().date),
        )
    }

    @Test fun `그래프 오른쪽은 월급 칸 없이 30일째가 끝 — 월급은 그날 동전으로`() {
        val f = days30()
        assertEquals(8f, chartRightPad(width = 340f, edge = 8f, n = f.days.size, paydaySlot = f.incomeDate != null, plain = 8f), 0f)
        assertTrue(f.days.indexOfFirst { it.date == PAYDAY } in eventDayIndices(f.days))
    }

    @Test fun `위 문구는 고른 기간 기준`() {
        assertEquals("30일 동안 안전 잔액 위", statusTag(days30())?.first)
    }

    @Test fun `월급 뒤에 잔액이 안전 잔액 아래로 내려가면 그날을 처음 고른다`() {
        val from = LocalDate.of(2026, 12, 1)
        val risk = RiskWindow(from, LAST, RiskLevel.WARNING, FlowConfidence.CONSERVATIVE, lowestBalance = 80_000, shortfall = 20_000, causes = emptyList())
        val f = days30().copy(status = RiskLevel.WARNING, riskWindows = listOf(risk))
        assertEquals("12월 1일부터 안전 잔액 아래", statusTag(f)?.first)
        assertEquals(f.days.indexOfFirst { it.date == from }, initialChartDay(f))
    }

    @Test fun `월급날까지 예보는 지금처럼 축 끝이 월급날`() {
        val f = testForecast(days = days30().days.take(8), isNextIncome = true, from = TODAY, to = PAYDAY.minusDays(1), horizonDays = 7)
        assertEquals(PAYDAY, f.incomeDate)
        assertEquals("월급날까지 안전 잔액 위", statusTag(f)?.first)
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 17)
        val PAYDAY: LocalDate = LocalDate.of(2026, 11, 25)
        val LAST: LocalDate = TODAY.plusDays(29)   // 12월 16일

        /** 오늘(11/17)부터 30일. 25일에 월급이 들어온다 */
        fun days30(): Forecast {
            val days = (0 until 30).map { i ->
                val date = TODAY.plusDays(i.toLong())
                val events = listOfNotNull(ForecastEvent(date, FlowType.SCHEDULED_INCOME, "월급", 3_000_000, FlowConfidence.CONFIRMED).takeIf { date == PAYDAY })
                val balance = if (date.isBefore(PAYDAY)) 500_000L - i * 20_000L else 3_300_000L - i * 20_000L
                ForecastDay(date, confirmed = balance, expected = balance, conservative = balance - 50_000, events = events)
            }
            return testForecast(days = days, isNextIncome = false, from = TODAY, to = LAST, horizonDays = 30)
        }
    }
}
