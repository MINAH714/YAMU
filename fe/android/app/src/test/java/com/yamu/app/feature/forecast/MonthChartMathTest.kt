package com.yamu.app.feature.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 한 달 그래프(Task 8.2.99) — 날 종류 · 빗금 묶음 · 실선/점선 나누기 · 바닥 날짜 · 손잡이 값 · 띠 알약 글자 */
class MonthChartMathTest {

    private val nov = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 13)
    private val horizonEnd = today.plusDays(30)   // BE days=30은 오늘부터 30일 뒤까지(docs/be/forecast.md 예시 11/06 → 12/06)

    // ── 날 종류 ──

    @Test fun `이번 달 — 지난 잔고가 없으면 1일~어제는 데이터 없음, 오늘, 그 뒤는 예상`() {
        val k = monthDayKinds(nov, today, actualDates = emptySet(), expectedUntil = horizonEnd)
        assertEquals(30, k.size)
        assertTrue((0 until 12).all { k[it] == DayKind.NO_DATA })
        assertEquals(DayKind.TODAY, k[12])
        assertTrue((13 until 30).all { k[it] == DayKind.EXPECTED })
    }

    @Test fun `지난 잔고를 받은 날만 실제다(지출액으로 만들지 않는다, COMMON-03)`() {
        val k = monthDayKinds(nov, today, actualDates = setOf(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12)), expectedUntil = horizonEnd)
        assertEquals(DayKind.NO_DATA, k[8])
        assertEquals(DayKind.ACTUAL, k[9])
        assertEquals(DayKind.NO_DATA, k[10])
        assertEquals(DayKind.ACTUAL, k[11])
    }

    @Test fun `다음 달 — 예보가 닿는 날까지 예상, 그 뒤는 범위 밖(FORECAST-06)`() {
        val k = monthDayKinds(YearMonth.of(2026, 12), today, emptySet(), horizonEnd)
        assertEquals(31, k.size)
        assertEquals(DayKind.EXPECTED, k[0])
        assertEquals("12/13이 예보 마지막 날", DayKind.EXPECTED, k[12])
        assertEquals(DayKind.OUT_OF_RANGE, k[13])
        assertEquals(DayKind.OUT_OF_RANGE, k[30])
    }

    @Test fun `지난달은 통째로 데이터 없음이고 오늘이 없다`() {
        val k = monthDayKinds(YearMonth.of(2026, 10), today, emptySet(), horizonEnd)
        assertEquals(31, k.size)
        assertTrue(k.all { it == DayKind.NO_DATA })
    }

    @Test fun `예보를 못 받았으면 오늘 뒤도 데이터 없음(지어 그리지 않는다)`() {
        val k = monthDayKinds(nov, today, emptySet(), expectedUntil = null)
        assertEquals(DayKind.TODAY, k[12])
        assertEquals(DayKind.NO_DATA, k[13])
    }

    @Test fun `2월은 28일, 윤년 2월은 29일`() {
        assertEquals(28, monthDayKinds(YearMonth.of(2027, 2), today, emptySet(), null).size)
        assertEquals(29, monthDayKinds(YearMonth.of(2028, 2), today, emptySet(), null).size)
    }

    // ── 빗금 묶음 ──

    @Test fun `빗금은 같은 종류가 이어지는 곳마다 하나, 종류가 바뀌면 나눈다`() {
        val k = listOf(DayKind.NO_DATA, DayKind.NO_DATA, DayKind.TODAY, DayKind.EXPECTED, DayKind.OUT_OF_RANGE, DayKind.OUT_OF_RANGE)
        assertEquals(listOf(KindRun(DayKind.NO_DATA, 0, 1), KindRun(DayKind.OUT_OF_RANGE, 4, 5)), hatchRuns(k))
    }

    @Test fun `데이터 없음 바로 뒤 범위 밖이면 묶음 둘`() {
        val k = listOf(DayKind.NO_DATA, DayKind.OUT_OF_RANGE)
        assertEquals(listOf(KindRun(DayKind.NO_DATA, 0, 0), KindRun(DayKind.OUT_OF_RANGE, 1, 1)), hatchRuns(k))
    }

    @Test fun `실제 날 사이 빈 날 하나도 따로 묶는다`() {
        val k = listOf(DayKind.ACTUAL, DayKind.NO_DATA, DayKind.ACTUAL)
        assertEquals(listOf(KindRun(DayKind.NO_DATA, 1, 1)), hatchRuns(k))
        assertTrue(hatchRuns(listOf(DayKind.ACTUAL, DayKind.TODAY, DayKind.EXPECTED)).isEmpty())
    }

    // ── 실선 · 점선 ──

    @Test fun `오늘 값은 실선과 점선 모두에 넣어 두 선이 이어진다`() {
        val kinds = listOf(DayKind.ACTUAL, DayKind.TODAY, DayKind.EXPECTED, DayKind.OUT_OF_RANGE)
        val (actual, expected) = splitByKind(listOf(10L, 20L, 30L, null), kinds)
        assertEquals(listOf(10L, 20L, null, null), actual)
        assertEquals(listOf(null, 20L, 30L, null), expected)
    }

    @Test fun `빗금 날은 값이 와도 두 선 모두 비운다`() {
        val (actual, expected) = splitByKind(listOf(5L, 6L), listOf(DayKind.NO_DATA, DayKind.OUT_OF_RANGE))
        assertEquals(listOf(null, null), actual)
        assertEquals(listOf(null, null), expected)
    }

    @Test fun `손잡이가 타는 값은 날마다 처음으로 값이 있는 선`() {
        assertEquals(listOf(10L, 20L, 30L, null), firstValues(listOf(listOf(10L, 20L, null, null), listOf(null, 20L, 30L, null))))
    }

    // ── 바닥 날짜 ──

    @Test fun `바닥 날짜는 1 8 15 22 29일, 2월 28일 달은 29일이 없다`() {
        assertEquals(listOf(0, 7, 14, 21, 28), monthAxisIndices(30))
        assertEquals(listOf(0, 7, 14, 21), monthAxisIndices(28))
    }

    // ── 손잡이 값 ──

    @Test fun `두 날 모두 값이 있으면 잇는다`() {
        assertEquals(15.0, valueAtStrict(listOf(10L, 20L), 0.5f)!!, 0.001)
    }

    @Test fun `빈 날 위에서는 옆 날 값을 끌어오지 않고 가까운 날 값을 쓴다`() {
        val v = listOf(null, null, 100L, 200L)
        assertNull("빗금 한가운데", valueAtStrict(v, 0.2f))
        assertNull("빈 날 쪽에 가까움", valueAtStrict(v, 0.4f))
        assertEquals("값 있는 날 쪽에 가까움", 100.0, valueAtStrict(v, 0.62f)!!, 0.001)
        assertEquals("잔고예보 쪽(valueAt)은 옆 날 값을 끌어온다", 100.0, valueAt(v, 0.4f)!!, 0.001)
    }

    // ── 띠 알약 ──

    @Test fun `한 달 띠 알약 윗줄은 오늘만 '오늘', 1일도 요일`() {
        assertEquals("오늘", monthPillTopLabel(StripDay(today, 0, BalanceZone.OK, DayKind.TODAY)))
        assertEquals("일", monthPillTopLabel(StripDay(LocalDate.of(2026, 11, 1), 0, BalanceZone.OK, DayKind.NO_DATA)))
        assertEquals("잔고예보 띠는 그대로 1일에 달 이름", "12월", pillTopLabel(LocalDate.of(2026, 12, 1), 5))
    }

    @Test fun `알약 읽어 주기에 날 종류를 넣는다(색만으로 전하지 않게)`() {
        assertEquals("11월 20일 (금), 예상 잔고, −250,000원", pillDescription(StripDay(LocalDate.of(2026, 11, 20), -250_000, BalanceZone.OK, DayKind.EXPECTED)))
        assertEquals("11월 2일 (월), 잔고 데이터 없음", pillDescription(StripDay(LocalDate.of(2026, 11, 2), 0, BalanceZone.OK, DayKind.NO_DATA)))
        assertEquals("잔고예보 띠는 그대로", "11월 20일 (금), −250,000원", pillDescription(StripDay(LocalDate.of(2026, 11, 20), -250_000, BalanceZone.OK)))
    }

    @Test fun `말풍선 짧은 이름`() {
        assertEquals("데이터 없음", kindShortLabel(DayKind.NO_DATA))
        assertEquals("범위 밖", kindShortLabel(DayKind.OUT_OF_RANGE))
        assertEquals("지금", kindShortLabel(DayKind.TODAY))
    }

    @Test fun `말풍선 빈 값 — 오늘은 '지금' 대신 '확인할 수 없음', 다른 날은 날 종류 이름(!311 리뷰)`() {
        assertEquals("확인할 수 없음", monthEmptyValue(DayKind.TODAY))
        assertEquals("데이터 없음", monthEmptyValue(DayKind.NO_DATA))
        assertEquals("범위 밖", monthEmptyValue(DayKind.OUT_OF_RANGE))
        assertEquals("기본 빈 값도 같은 규칙", "확인할 수 없음", MonthChart(listOf(DayKind.TODAY)).emptyValue(DayKind.TODAY))
    }

    @Test fun `바닥 '오늘' 글자 — 덮이는 날짜 글자만 빼고, 여백만큼 떨어진 글자는 남긴다(!311 리뷰)`() {
        val today = 100f..130f
        val labels = listOf(0f..20f, 90f..110f, 128f..150f, 134f..156f, 133f..150f)
        assertEquals(listOf(true, false, false, true, false), axisLabelsClearOf(labels, today, gap = 4f))
    }
}
