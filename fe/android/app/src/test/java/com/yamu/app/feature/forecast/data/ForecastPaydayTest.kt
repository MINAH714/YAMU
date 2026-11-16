package com.yamu.app.feature.forecast.data

import com.yamu.app.feature.forecast.chartForecast
import com.yamu.app.feature.forecast.chartRightPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 잔고예보 그래프 x축 끝을 다음 월급날로 — 7 · 14 · 30일이어도 월급날이 기간 안이면 월급 전날까지 선, 월급날은 축 끝 칸 */
class ForecastPaydayTest {

    // ── 다음 월급날 ──

    @Test fun `오늘 뒤 그달 월급날`() = assertEquals(LocalDate.of(2026, 11, 25), nextPayday(25, LocalDate.of(2026, 11, 16)))

    @Test fun `오늘이 월급날이면 다음 달 — 오늘 월급은 이미 오늘 잔액에 들어 있다`() =
        assertEquals(LocalDate.of(2026, 12, 25), nextPayday(25, LocalDate.of(2026, 11, 25)))

    @Test fun `월급날이 지났으면 다음 달`() = assertEquals(LocalDate.of(2026, 12, 10), nextPayday(10, LocalDate.of(2026, 11, 16)))

    @Test fun `31일 월급은 짧은 달엔 말일`() {
        assertEquals(LocalDate.of(2026, 11, 30), nextPayday(31, LocalDate.of(2026, 11, 16)))
        assertEquals(LocalDate.of(2026, 12, 31), nextPayday(31, LocalDate.of(2026, 11, 30)))
        assertEquals(LocalDate.of(2027, 2, 28), nextPayday(31, LocalDate.of(2027, 1, 31)))
        assertEquals(LocalDate.of(2028, 2, 29), nextPayday(30, LocalDate.of(2028, 2, 1)))
    }

    // ── 이 예보의 월급날 ──

    @Test fun `월급날까지 예보는 서버 기간 끝 다음 날 — 설정은 보지 않는다`() {
        val f = days30().copy(isNextIncome = true, to = LocalDate.of(2026, 11, 24))
        assertEquals(LocalDate.of(2026, 11, 25), f.payday(IncomeRule("월급", 3)))
    }

    @Test fun `N일 예보는 월급 설정이 없으면 모른다`() = assertNull(days30().payday(null))

    @Test fun `N일 예보는 설정의 매월 날짜로 센다`() = assertEquals(LocalDate.of(2026, 11, 25), days30().payday(IncomeRule("월급", 25)))

    @Test fun `기간 안에 같은 이름의 예정 수입이 있으면 그날 — 이번만 옮긴 회차`() {
        val f = days30(salaryOn = LocalDate.of(2026, 11, 27))
        assertEquals(LocalDate.of(2026, 11, 27), f.payday(IncomeRule("월급", 25)))
    }

    @Test fun `오늘 들어온 월급 줄은 다음 월급날이 아니다`() {
        val f = days30(salaryOn = TODAY)
        assertEquals(LocalDate.of(2026, 12, 16), f.payday(IncomeRule("월급", 16)))
    }

    // ── 월급 전날까지로 끊기 ──

    @Test fun `30일 예보를 25일 월급이면 24일까지 끊고 끝 칸은 25일`() {
        val f = days30().untilPayday(LocalDate.of(2026, 11, 25))
        assertEquals(LocalDate.of(2026, 11, 24), f.days.last().date)
        assertEquals(9, f.days.size)
        assertEquals(LocalDate.of(2026, 11, 24), f.to)
        assertEquals(LocalDate.of(2026, 11, 25), f.incomeDate)
        assertTrue(f.isNextIncome)
        assertEquals(8, f.horizonDays)
    }

    @Test fun `가장 낮을 때는 남은 날의 생활비 반영 선 최저`() {
        val f = days30().untilPayday(LocalDate.of(2026, 11, 25))
        assertEquals(balance(8) - 50_000, f.minBalance)
        assertEquals(LocalDate.of(2026, 11, 24), f.minBalanceDate)
        assertEquals(balance(8) - 50_000, f.endBalance)
    }

    @Test fun `월급날이 기간 밖이거나 오늘이면 그대로`() {
        val f = days30()
        assertSame(f, f.untilPayday(null))
        assertSame(f, f.untilPayday(LocalDate.of(2026, 12, 20)))
        assertSame(f, f.untilPayday(TODAY))
    }

    @Test fun `월급날까지 예보는 이미 월급 전날에서 끝나 다시 끊지 않는다`() {
        val f = days30().copy(isNextIncome = true)
        assertSame(f, f.untilPayday(LocalDate.of(2026, 11, 25)))
    }

    @Test fun `월급이 내일이면 오늘 하루만 남는다`() {
        val f = days30().untilPayday(TODAY.plusDays(1))
        assertEquals(listOf(TODAY), f.days.map { it.date })
        assertEquals(TODAY.plusDays(1), f.incomeDate)
    }

    @Test fun `월급날을 넘는 위험 구간은 월급 전날에서 끊고 최저 · 모자란 돈을 다시 고른다`() {
        val crossing = window(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 30), RiskLevel.DANGER, FlowConfidence.EXPECTED)
        val after = window(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), RiskLevel.DANGER, FlowConfidence.EXPECTED)
        val f = days30().copy(riskWindows = listOf(crossing, after), status = RiskLevel.DANGER, alertThreshold = 400_000)
            .untilPayday(LocalDate.of(2026, 11, 25))

        assertEquals(1, f.riskWindows.size)
        val w = f.riskWindows[0]
        assertEquals(LocalDate.of(2026, 11, 24), w.to)
        assertEquals(balance(8), w.lowestBalance)   // 'AI 생활비 제외' 선 24일
        assertEquals(400_000 - balance(8), w.shortfall)
        assertEquals(listOf("11월 카드값"), w.causes.map { it.title })   // 29일 원인은 빠진다
        assertEquals(RiskLevel.DANGER, f.status)
    }

    @Test fun `위험이 모두 월급 뒤면 안전`() {
        val after = window(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), RiskLevel.WARNING, FlowConfidence.CONSERVATIVE)
        val f = days30().copy(riskWindows = listOf(after), status = RiskLevel.WARNING).untilPayday(LocalDate.of(2026, 11, 25))
        assertTrue(f.riskWindows.isEmpty())
        assertEquals(RiskLevel.SAFE, f.status)
    }

    @Test fun `잘린 날의 카드값은 기간 뒤에 빠질 카드값으로 옮긴다`() {
        val bill = ForecastEvent(LocalDate.of(2026, 12, 10), FlowType.CARD_BILLING, "12월 카드값", -300_000, FlowConfidence.CONFIRMED)
        val later = ForecastEvent(LocalDate.of(2026, 12, 20), FlowType.CARD_BILLING, "다음 카드값", -100_000, FlowConfidence.CONFIRMED)
        val f = days30(extra = mapOf(bill.date to bill)).copy(beyondHorizon = listOf(later)).untilPayday(LocalDate.of(2026, 11, 25))
        assertEquals(listOf("12월 카드값", "다음 카드값"), f.beyondHorizon.map { it.title })
    }

    @Test fun `F-01 그림 예보 — 예보가 없으면 없음, 있으면 월급날에서 끊는다`() {
        assertNull(chartForecast(null, IncomeRule("월급", 25)))
        assertEquals(LocalDate.of(2026, 11, 24), chartForecast(days30(), IncomeRule("월급", 25))?.days?.last()?.date)
        assertEquals(LocalDate.of(2026, 12, 15), chartForecast(days30(), null)?.days?.last()?.date)
    }

    // ── 그래프 오른쪽 월급날 칸 ──

    @Test fun `월급 화살표가 있으면 하루 칸 하나를 비워 끝이 월급날`() {
        // 너비 340, 양쪽 8 — 첫 날 ~ 월급날 324를 9칸으로. 마지막 날(8번)은 끝에서 한 칸(36) 안쪽
        val pad = chartRightPad(width = 340f, edge = 8f, n = 9, paydaySlot = true, plain = 8f)
        assertEquals(44f, pad, 0.001f)
        val step = (340f - 8f - pad) / (9 - 1)
        assertEquals(36f, step, 0.001f)
        assertEquals(340f - 8f, 340f - pad + step, 0.001f)   // 마지막 날 + 한 칸 = 월급날 자리(화살표)
    }

    @Test fun `월급 화살표가 없으면 원래 여백`() {
        assertEquals(34f, chartRightPad(340f, 8f, 9, paydaySlot = false, plain = 34f), 0f)
        assertEquals(8f, chartRightPad(340f, 8f, 1, paydaySlot = true, plain = 8f), 0f)
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 16)

        fun balance(i: Int) = 500_000L - i * 20_000L

        /** 오늘(11/16)부터 30일(12/15까지) 7 · 14 · 30일 예보. 날마다 2만 원씩 줄고, 생활비 반영 선은 5만 원 아래 */
        fun days30(salaryOn: LocalDate? = null, extra: Map<LocalDate, ForecastEvent> = emptyMap()): Forecast {
            val days = (0 until 30).map { i ->
                val date = TODAY.plusDays(i.toLong())
                val events = listOfNotNull(
                    ForecastEvent(date, FlowType.SCHEDULED_INCOME, "월급", 3_000_000, FlowConfidence.CONFIRMED).takeIf { date == salaryOn },
                    extra[date],
                )
                ForecastDay(date, confirmed = balance(i), expected = balance(i), conservative = balance(i) - 50_000, events = events)
            }
            return testForecast(days = days, isNextIncome = false, from = TODAY, to = TODAY.plusDays(29), horizonDays = 30)
        }

        fun window(from: LocalDate, to: LocalDate, level: RiskLevel, line: FlowConfidence) = RiskWindow(
            from = from, to = to, level = level, line = line, lowestBalance = -999_999, shortfall = 999_999,
            causes = listOf(
                RiskCause(FlowType.CARD_BILLING, "11월 카드값", -300_000, LocalDate.of(2026, 11, 20)),
                RiskCause(FlowType.CARD_BILLING, "29일 카드값", -100_000, LocalDate.of(2026, 11, 29)),
            ),
        )
    }
}
