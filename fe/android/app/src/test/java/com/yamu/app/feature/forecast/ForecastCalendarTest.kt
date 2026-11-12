package com.yamu.app.feature.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 달력 면(Task 8.2.71) — 보일 달, 한 달 칸, 칸 색 단계, 읽어 주기 */
class ForecastCalendarTest {

    @Test fun `월급날까지 기간이 두 달에 걸치면 두 달, 월급날이 다음 달 첫날이어도 그 달까지`() {
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12)), calendarMonths(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 12, 7)))
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12)), calendarMonths(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 1)))
        assertEquals(listOf(YearMonth.of(2026, 11)), calendarMonths(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 25)))
        // 해를 넘겨도 이어진다
        assertEquals(listOf(YearMonth.of(2026, 12), YearMonth.of(2027, 1)), calendarMonths(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 10)))
    }

    @Test fun `한 달 칸 — 일요일부터 7칸씩, 앞뒤 빈칸`() {
        // 2026년 11월 1일은 일요일 → 앞 빈칸 없음, 30일 → 35칸(뒤 5칸 빈칸)
        val nov = monthGrid(YearMonth.of(2026, 11))
        assertEquals(35, nov.size)
        assertEquals(LocalDate.of(2026, 11, 1), nov[0])
        assertEquals(5, nov.takeLastWhile { it == null }.size)
        // 2026년 12월 1일은 화요일 → 앞 빈칸 2, 31일 → 35칸
        val dec = monthGrid(YearMonth.of(2026, 12))
        assertEquals(35, dec.size)
        assertNull(dec[0]); assertNull(dec[1])
        assertEquals(LocalDate.of(2026, 12, 1), dec[2])
        assertTrue(dec.size % 7 == 0)
        // 토요일에 시작하는 달은 6주(2026년 8월 1일 토요일, 31일 → 42칸)
        assertEquals(42, monthGrid(YearMonth.of(2026, 8)).size)
    }

    @Test fun `달 제목`() {
        assertEquals("2026년 11월", monthTitle(YearMonth.of(2026, 11)))
    }

    @Test fun `칸 색 — 안전 잔액 위는 여유에 따라 넷, 아래는 주황, 0원 아래는 빨강`() {
        val max = 680_000L
        assertEquals(CellTone.OK4, cellTone(680_000, 100_000, max))
        assertEquals(CellTone.OK3, cellTone(450_000, 100_000, max))   // (450-100)/(680-100)=0.60
        assertEquals(CellTone.OK2, cellTone(300_000, 100_000, max))   // 0.34
        assertEquals(CellTone.OK1, cellTone(120_000, 100_000, max))   // 0.03
        assertEquals(CellTone.BELOW_SAFE, cellTone(99_999, 100_000, max))
        assertEquals(CellTone.BELOW_ZERO, cellTone(-1, 100_000, max))
        // 안전 잔액을 모르면 0원부터 나눈다, 최고가 기준보다 낮으면 가장 옅게
        assertEquals(CellTone.OK4, cellTone(680_000, null, max))
        assertEquals(CellTone.OK1, cellTone(150_000, 100_000, 100_000))
        assertEquals(CellTone.OK1, cellTone(50_000, 0, 0))
    }

    @Test fun `칸 읽어 주기 — 날짜, 끝 잔액, 오간 돈, 구간`() {
        assertEquals(
            "11월 21일 (토), 끝 잔액 165,000원, −250,000원, 안전 잔액 아래",
            cellDescription(LocalDate.of(2026, 11, 21), 165_000, -250_000, BalanceZone.BELOW_SAFE),
        )
        assertEquals("11월 22일 (일), 끝 잔액 159,000원", cellDescription(LocalDate.of(2026, 11, 22), 159_000, 0, BalanceZone.OK))
        assertEquals("11월 23일 (월), −35,000원, 0원 아래", cellDescription(LocalDate.of(2026, 11, 23), null, -35_000, BalanceZone.BELOW_ZERO))
    }

    @Test fun `달력 칸을 누른 것도 사람이 바꾼 것 — 띠는 따라가고 칸은 밀리지 않는다`() {
        val s = DaySelection(15, 25)
        assertTrue(s.select(20, DaySource.CALENDAR))
        assertTrue(s.touched)
        assertTrue(stripFollows(DaySource.CALENDAR))
        assertEquals(false, panelSlides(DaySource.CALENDAR))
    }
}
