package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 날짜 띠 · 고른 날 칸 · 목록 연결(Task 8.2.70) — 고른 날 하나를 같이 보고 서로 되먹이지 않는지, 띠 글자, 위 문장 */
class ForecastDaySyncTest {

    // ── 고른 날 하나 ──

    @Test fun `처음 날은 범위 안으로, 사람이 바꾸기 전에는 첫 안내가 남는다`() {
        val s = DaySelection(initial = 40, count = 25)
        assertEquals(24, s.day)
        assertEquals(DaySource.INIT, s.source)
        assertFalse(s.touched)
        assertEquals(0, s.version)
    }

    @Test fun `다른 날로 바꾸면 바꾼 곳이 남고 첫 안내가 끝난다`() {
        val s = DaySelection(15, 25)
        assertTrue(s.select(9, DaySource.LIST))
        assertEquals(9, s.day)
        assertEquals(DaySource.LIST, s.source)
        assertTrue(s.touched)
        assertEquals(1, s.version)
    }

    @Test fun `같은 날을 다시 고르면 바뀐 것이 없어 바꾼 곳도 그대로, 위 금액은 다시 머문다(version)`() {
        val s = DaySelection(15, 25)
        s.select(9, DaySource.CHART)
        assertFalse(s.select(9, DaySource.STRIP))
        assertEquals(DaySource.CHART, s.source)
        assertEquals(2, s.version)
    }

    @Test fun `범위 밖은 양 끝 날로, 다시 고르는 것(INIT)은 첫 안내를 끝내지 않는다`() {
        val s = DaySelection(5, 25)
        s.select(-3, DaySource.INIT)
        assertEquals(0, s.day)
        assertFalse(s.touched)
        assertEquals(0, s.version)
        s.select(99, DaySource.PANEL)
        assertEquals(24, s.day)
    }

    @Test fun `띠를 미는 동안 띠가 다시 고른 날을 따라 움직이지 않는다 — 되먹임 없음`() {
        // 띠(STRIP)가 바꾼 날은 띠가 다시 굴러가지 않고, 다른 곳에서 바꾸면 띠가 따라간다
        assertFalse(stripFollows(DaySource.STRIP))
        DaySource.entries.filter { it != DaySource.STRIP }.forEach { assertTrue(it.name, stripFollows(it)) }
        // 그래프를 끄는 동안엔 바로 옮기고(늦지 않게), 누르기 · 칸 · 목록은 부드럽게 굴러간다
        assertFalse(stripGlides(DaySource.CHART))
        assertTrue(stripGlides(DaySource.PILL))
        assertTrue(stripGlides(DaySource.PANEL))
        assertTrue(stripGlides(DaySource.LIST))
    }

    @Test fun `고른 날 칸은 칸을 밀었을 때만 밀려 바뀐다 — 그래프를 빠르게 끌 때 칸이 쌓이지 않게`() {
        assertTrue(panelSlides(DaySource.PANEL))
        DaySource.entries.filter { it != DaySource.PANEL }.forEach { assertFalse(it.name, panelSlides(it)) }
    }

    @Test fun `그래프를 끄는 동안 아직 굴러가는 띠에서 온 날은 받지 않는다`() {
        assertFalse(accepts(DaySource.STRIP, chartDragging = true))
        assertTrue(accepts(DaySource.CHART, chartDragging = true))
        assertTrue(accepts(DaySource.STRIP, chartDragging = false))
        // 시나리오: 그래프로 10일을 고르는 중 띠가 3일을 보내도 10일이 남는다
        val s = DaySelection(0, 25)
        listOf(10 to DaySource.CHART, 3 to DaySource.STRIP).forEach { (i, from) -> if (accepts(from, chartDragging = true)) s.select(i, from) }
        assertEquals(10, s.day)
    }

    // ── 날짜 띠 ──

    private val d0 = LocalDate.of(2026, 11, 12)
    private fun ev(amount: Long, title: String = "월세") = ForecastEvent(d0, FlowType.SCHEDULED_OUTFLOW, title, amount, FlowConfidence.CONFIRMED)

    @Test fun `알약 — 오가는 돈 합과 구간은 손잡이가 타는 선(반영, 없으면 제외) 기준`() {
        val days = listOf(
            day(d0, expected = 680_000, conservative = 680_000),
            day(d0.plusDays(1), ev(-150_000, "신한카드 청구"), ev(-36_000, "카드 사용 예상"), expected = 505_000, conservative = 90_000),
            day(d0.plusDays(2), ev(0, "확인 전"), expected = -5_000, conservative = -40_000),
        )
        val s = stripDays(days, hasConservative = true, threshold = 100_000)
        assertEquals(listOf(0L, -186_000L, 0L), s.map { it.net })
        assertEquals(listOf(BalanceZone.OK, BalanceZone.BELOW_SAFE, BalanceZone.BELOW_ZERO), s.map { it.zone })
        // 반영 선이 없으면 제외 선으로
        assertEquals(BalanceZone.OK, stripDays(days, hasConservative = false, threshold = 100_000)[1].zone)
    }

    @Test fun `알약 윗줄 — 첫 칸은 오늘, 1일은 달, 나머지는 요일`() {
        assertEquals("오늘", pillTopLabel(d0, 0))
        assertEquals("12월", pillTopLabel(LocalDate.of(2026, 12, 1), 19))
        assertEquals("토", pillTopLabel(LocalDate.of(2026, 11, 21), 9))
    }

    @Test fun `알약 금액 — 만 원 단위로 줄인다`() {
        assertNull(pillAmount(0))
        assertEquals("−2.5만", pillAmount(-25_000))
        assertEquals("−15만", pillAmount(-150_000))
        assertEquals("−18.6만", pillAmount(-186_000))
        assertEquals("+280만", pillAmount(2_800_000))
        assertEquals("−125.4만", pillAmount(-1_254_000))
        assertEquals("−6천", pillAmount(-6_000))
        assertEquals("+0.3천", pillAmount(300))
        assertEquals("+0.1천", pillAmount(50))                  // 100원이 안 돼도 빈칸으로 두지 않는다
        // 1억부터 억, 1조부터 조 — 50dp 칸에 들어가게(큰 금액 QA)
        assertEquals("−9999만", pillAmount(-99_990_000))
        assertEquals("−9999.99만", pillAmount(-99_999_999))     // 버리므로 1억으로 올라가지 않는다
        assertEquals("−1억", pillAmount(-100_000_000))
        assertEquals("−2.5억", pillAmount(-250_000_000))
        assertEquals("−1000억", pillAmount(-100_000_000_000))
        assertEquals("−5000억", pillAmount(-500_000_000_000))
        assertEquals("+1.2조", pillAmount(1_200_000_000_000))
    }

    @Test fun `알약 금액 — 반올림하지 않고 100원 자리까지 버린다`() {
        assertEquals("−1.23만", pillAmount(-12_399))           // 반올림이면 1.24만
        assertEquals("−18.63만", pillAmount(-186_350))
        assertEquals("+1.05만", pillAmount(10_500))            // 소수 둘째 자리 앞의 0을 지키고
        assertEquals("−6.3천", pillAmount(-6_399))
        assertEquals("−9.9천", pillAmount(-9_999))             // 10천으로 올라가지 않는다
        assertEquals("−2.53억", pillAmount(-253_999_999))
    }

    @Test fun `알약 읽어 주기 — 날짜, 금액, 구간`() {
        assertEquals("11월 21일 (토), −250,000원, 안전 잔액 아래", pillDescription(StripDay(LocalDate.of(2026, 11, 21), -250_000, BalanceZone.BELOW_SAFE)))
        assertEquals("11월 22일 (일)", pillDescription(StripDay(LocalDate.of(2026, 11, 22), 0, BalanceZone.OK)))
    }

    // ── 위 금액 · 문장 ──

    @Test fun `미는 동안 위 제목과 금액 — 그날 날짜, 손잡이가 타는 선 값`() {
        assertEquals("11월 27일 (금) 잔액", heroLiveTitle(LocalDate.of(2026, 11, 27)))
        val d = day(d0, expected = 220_000, conservative = 94_000)
        assertEquals(94_000L, rideValue(d, hasConservative = true))
        assertEquals(220_000L, rideValue(d, hasConservative = false))
        assertEquals(220_000L, rideValue(day(d0, expected = 220_000, conservative = null), hasConservative = true))
    }

    @Test fun `월급을 못 찾은 이유 — 기간을 고르지 않았거나 월급날까지를 골랐는데 N일이 왔을 때만(P1-08)`() {
        val days30 = testForecast(isNextIncome = false, horizonDays = 30)
        val line = "월급을 찾지 못해 오늘부터 30일로 계산했어요. 월급을 등록하면 월급날까지로 바꿔 보여 드려요."
        assertEquals(line, incomeMissingNote(days30, ForecastSelection()))
        assertEquals(line, incomeMissingNote(days30, ForecastSelection(ForecastPeriod.NEXT_INCOME)))
        assertNull("14일을 직접 골랐으면 이유가 아니다", incomeMissingNote(days30, ForecastSelection(ForecastPeriod.DAYS_14)))
        assertNull(incomeMissingNote(testForecast(isNextIncome = true), ForecastSelection()))
    }

    @Test fun `예보할 날이 하루뿐일 때 — 월급날이 내일이면 그렇게, 모르면 오늘만(P2-24)`() {
        val eve = testForecast(isNextIncome = true, to = LocalDate.of(2026, 12, 6))
        assertEquals("내일(12월 7일)이 월급날이에요. 월급이 들어오면 다음 월급날까지 다시 예보해요.", singleDayNote(eve))
        assertEquals("예보할 날이 오늘 하루뿐이라 그래프 없이 오늘 끝 잔액만 보여 드려요.", singleDayNote(testForecast(isNextIncome = false)))
    }
}
