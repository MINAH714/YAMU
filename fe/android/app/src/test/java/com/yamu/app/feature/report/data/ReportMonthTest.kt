package com.yamu.app.feature.report.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** 고를 수 있는 달 — 이번 달 포함 최근 12개월, 다음 달 없음 */
class ReportMonthTest {
    private var now = YearMonth.of(2026, 11)
    private val months = ReportMonth { now }

    @Test fun `처음엔 이번 달, 다음 달로는 못 간다`() {
        val shown = months.current()
        assertEquals(now, shown.month)
        assertTrue(shown.isThisMonth)
        assertFalse(shown.canNext)
        assertTrue(shown.canPrev)
        months.select(now.plusMonths(1))
        assertEquals(now, months.current().month)
    }

    @Test fun `최근 12개월까지 — 그보다 앞은 끝 달로 당긴다`() {
        val first = YearMonth.of(2025, 12)
        months.select(first)
        assertEquals(first, months.current().month)
        assertFalse(months.current().canPrev)
        assertTrue(months.current().canNext)
        months.select(first.minusMonths(1))
        assertEquals(first, months.current().month)
        assertEquals(REPORT_MONTHS.toLong(), java.time.temporal.ChronoUnit.MONTHS.between(first, now) + 1)
    }

    @Test fun `이번 달을 고르면 이번 달을 따라간다 — 달이 바뀌면 새 달`() {
        months.select(YearMonth.of(2026, 10))
        months.select(now)
        now = YearMonth.of(2026, 12)
        assertEquals(YearMonth.of(2026, 12), months.current().month)
    }

    @Test fun `지난 달을 골라 두면 달이 바뀌어도 그 달 — 범위를 벗어나면 끝 달로`() {
        months.select(YearMonth.of(2026, 10))
        now = YearMonth.of(2026, 12)
        assertEquals(YearMonth.of(2026, 10), months.current().month)
        assertTrue(months.current().canNext)
        now = YearMonth.of(2027, 11)   // 10월이 12개월 밖으로
        assertEquals(YearMonth.of(2026, 12), months.current().month)
    }

    @Test fun `소비분석만 다음 달 하나까지 — 혜택 리포트 · 결제 내역은 이번 달로 당긴다(S15P31E103-483)`() {
        assertTrue(months.currentWithNext().canNext)
        months.select(now.plusMonths(1))
        val report = months.currentWithNext()
        assertEquals(now.plusMonths(1), report.month)
        assertTrue(report.isNextMonth)
        assertFalse("다음 달 하나까지", report.canNext)
        assertEquals(now, months.current().month)
        assertFalse(months.current().isNextMonth)
        months.select(now.plusMonths(3))
        assertEquals("두 달 뒤는 다음 달로 당긴다", now.plusMonths(1), months.currentWithNext().month)
    }

    @Test fun `예보가 다음 달에 닿지 않으면 이번 달까지 — 골라 둔 다음 달도 이번 달로 읽고, 닿으면 다시 그 달(!312 리뷰)`() {
        months.select(now.plusMonths(1))
        val closed = months.currentWithNext(nextOpen = false)
        assertEquals(now, closed.month)
        assertFalse(closed.canNext)
        assertEquals(REPORT_MONTHS, closed.choices.size)
        assertEquals(now.plusMonths(1), months.currentWithNext(nextOpen = true).month)
    }

    @Test fun `연 · 월 시트의 달 — 최근 12개월 + 다음 달`() {
        val choices = months.currentWithNext().choices
        assertEquals(REPORT_MONTHS + 1, choices.size)
        assertEquals(YearMonth.of(2025, 12), choices.first())
        assertEquals(YearMonth.of(2026, 12), choices.last())
        assertEquals(REPORT_MONTHS, months.current().choices.size)
    }

    @Test fun `reset이면 이번 달로`() {
        months.select(YearMonth.of(2026, 8))
        months.reset()
        assertEquals(now, months.current().month)
    }

    @Test fun `달 이름 — 올해는 '11월', 다른 해는 '2025년 12월'`() {
        assertEquals("11월", monthTitle(YearMonth.of(2026, 11), now))
        assertEquals("1월", monthTitle(YearMonth.of(2026, 1), now))
        assertEquals("2025년 12월", monthTitle(YearMonth.of(2025, 12), now))
    }
}
