package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.RiskWindow
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 잔고예보 그래프 계산(Task 8.2.65) — 세로 범위 · 구간 · 급감 · 손가락 위치, 처음 고른 날과 말풍선 · 읽어 주기 문장 */
class ForecastChartMathTest {

    // ── 세로 범위 ──

    @Test fun `잔액이 크면 0원과 먼 안전 잔액을 넣지 않아 선이 납작해지지 않는다(P2-21)`() {
        val r = chartRange(listOf(3_480_000, 3_005_000, 2_825_000), threshold = 100_000)
        assertTrue("바닥이 선 바로 밑", r.bottom > 2_500_000)
        assertFalse(r.shows(0))
        assertFalse(r.shows(100_000))
    }

    @Test fun `안전 잔액이 선 바로 밑이면 범위에 넣어 점선이 보인다`() {
        val r = chartRange(listOf(680_000, 205_000, 120_000), threshold = 100_000)
        assertTrue(r.shows(100_000))
    }

    @Test fun `선이 모두 0원 이상이면 여백 때문에 0원 아래가 생기지 않는다`() {
        val r = chartRange(listOf(680_000, 25_000), threshold = 100_000)
        assertEquals(0.0, r.bottom, 0.0)
        assertFalse("0원 선은 바닥에 붙으면 그리지 않는다", r.shows(0))
    }

    @Test fun `0원 아래로 내려가면 0원 선과 그 아래가 범위에 들어온다`() {
        val r = chartRange(listOf(480_000, 55_000, -175_000), threshold = 100_000)
        assertTrue(r.shows(0))
        assertTrue(r.bottom < -175_000)
    }

    @Test fun `거의 평평한 선은 최소 폭(5만 원)을 둬 1,000원 차이가 절벽처럼 보이지 않는다`() {
        val r = chartRange(listOf(300_000, 299_000), threshold = null)
        assertTrue(r.top - r.bottom >= CHART_MIN_SPAN_WON)
        val gap = r.fraction(300_000.0) - r.fraction(299_000.0)
        assertTrue("두 날 차이가 높이의 5%도 안 된다", gap < 0.05f)
    }

    @Test fun `큰 잔액의 최소 폭은 최고 잔액의 10%`() {
        val r = chartRange(listOf(5_000_000, 4_990_000), threshold = null)
        assertTrue(r.top - r.bottom >= 5_000_000 * CHART_MIN_SPAN_RATIO)
    }

    @Test fun `값이 없으면 기본 범위`() {
        assertEquals(ChartRange(0.0, 1.0), chartRange(emptyList(), 100_000))
    }

    // ── 구간 ──

    @Test fun `구간 — 0원 아래는 빨강, 안전 잔액 아래는 주황, 안전 잔액을 모르면 0원만 본다`() {
        assertEquals(BalanceZone.BELOW_ZERO, zoneOf(-1, 100_000))
        assertEquals(BalanceZone.BELOW_SAFE, zoneOf(0, 100_000))
        assertEquals(BalanceZone.BELOW_SAFE, zoneOf(99_999, 100_000))
        assertEquals(BalanceZone.OK, zoneOf(100_000, 100_000))
        assertEquals(BalanceZone.OK, zoneOf(5, null))
        assertEquals(BalanceZone.BELOW_ZERO, zoneOf(-5, null))
    }

    // ── 급감(임시 기준) ──

    @Test fun `급감 — 하루에 20만 원 이상이거나 전날 잔액의 30% 이상 빠지는 날`() {
        // 655,000 → 445,000(21만 · 32%), 445,000 → 400,000(10%), 400,000 → 150,000(25만), 150,000 → 100,000(33%)
        val days = sharpDropDays(listOf(655_000, 445_000, 400_000, 150_000, 100_000, 120_000))
        assertEquals(setOf(1, 3, 4), days)
    }

    @Test fun `급감 — 19만 원이고 30% 미만이면 아니다, 오르는 날도 아니다`() {
        assertEquals(emptySet<Int>(), sharpDropDays(listOf(1_000_000, 810_000, 900_000)))
    }

    @Test fun `급감 — 전날이 0원 이하면 금액 기준만, 빈 날은 건너뛴다`() {
        assertEquals(setOf(2), sharpDropDays(listOf(-10_000, -60_000, -300_000)))
        assertEquals(emptySet<Int>(), sharpDropDays(listOf(500_000, null, 100_000)))
    }

    // ── 손가락 위치 ↔ 날 ──

    @Test fun `가로 위치 → 가장 가까운 날, 끝을 넘으면 양 끝 날`() {
        assertEquals(0, dayAt(0f, 25))
        assertEquals(24, dayAt(1f, 25))
        assertEquals(12, dayAt(0.5f, 25))
        assertEquals(0, dayAt(-0.3f, 25))
        assertEquals(24, dayAt(1.7f, 25))
        assertEquals(0, dayAt(0.7f, 1))
        assertEquals(15, dayAt(fractionOf(15, 25), 25))
    }

    @Test fun `손잡이 높이 — 두 날 사이는 곧게 잇고, 빈 날이면 있는 쪽 값`() {
        val values = listOf(100L, 200L, null, 400L)
        assertEquals(100.0, valueAt(values, 0f)!!, 0.001)
        assertEquals(150.0, valueAt(values, 1f / 6f)!!, 0.001)   // 0번과 1번 사이 가운데
        assertEquals(200.0, valueAt(values, 0.5f)!!, 0.001)       // 1번과 빈 2번 사이
        assertEquals(400.0, valueAt(values, 1f)!!, 0.001)
        assertNull(valueAt(listOf<Long?>(null, null), 0.5f))
        assertNull(valueAt(emptyList(), 0.5f))
    }

    // ── 화면 쪽 도움 함수 ──

    private val d0 = LocalDate.of(2026, 11, 12)
    private fun ev(title: String, amount: Long, date: LocalDate = d0) = ForecastEvent(date, FlowType.SCHEDULED_OUTFLOW, title, amount, FlowConfidence.CONFIRMED)
    private fun window(from: LocalDate) = RiskWindow(from, from.plusDays(3), RiskLevel.WARNING, FlowConfidence.CONSERVATIVE, 70_000, 30_000, emptyList())

    @Test fun `처음 고른 날 — 안전 잔액 아래로 처음 내려가는 날, 없으면 가장 낮은 날, 그것도 없으면 마지막 날`() {
        val days = (0L until 10L).map { day(d0.plusDays(it)) }
        assertEquals(6, initialChartDay(testForecast(days = days, riskWindows = listOf(window(d0.plusDays(8)), window(d0.plusDays(6))).sortedBy { it.from })))
        // 위험 구간이 없으면 가장 낮은 날(testForecast는 minBalanceDate = to)
        assertEquals(9, initialChartDay(testForecast(days = days, to = d0.plusDays(9))))
        assertEquals(9, initialChartDay(testForecast(days = days, to = d0.plusDays(30))))
    }

    @Test fun `돈이 오가는 날 — 0원이 아닌 입출금이 있는 날만 동전`() {
        val days = listOf(day(d0), day(d0.plusDays(1), ev("월세", -250_000)), day(d0.plusDays(2), ev("확인 전", 0)))
        assertEquals(setOf(1), eventDayIndices(days))
    }

    @Test fun `말풍선 윗줄 — 돈이 오가면 날짜와 첫 이름(여러 건이면 외 N건), 아니면 날짜만(오늘 표시)`() {
        val rent = day(d0.plusDays(9), ev("월세", -250_000))
        assertEquals("11/21 (토) · 월세", chartDayLabel(rent, today = d0))
        val two = day(d0.plusDays(4), ev("신한카드 청구", -150_000), ev("카드 사용 예상", -36_000))
        assertEquals("11/16 (월) · 신한카드 청구 외 1건", chartDayLabel(two, today = d0))
        assertEquals("11월 12일 (목, 오늘)", chartDayLabel(day(d0), today = d0))
    }

    @Test fun `읽어 주기 — 날짜, 선마다 끝 잔액, 그날 입출금(P2-28)`() {
        val rent = day(d0.plusDays(9), ev("월세", -250_000), expected = 255_000, conservative = 201_000)
        assertEquals(
            "11월 21일 (토), AI 생활비 반영 201,000원, AI 생활비 제외 255,000원, 월세 −250,000원",
            chartDayState(rent, hasConservative = true),
        )
        assertEquals("11월 21일 (토), AI 생활비 제외 255,000원, 월세 −250,000원", chartDayState(rent, hasConservative = false))
    }

    // ── '월급날까지' x축 끝 월급날 칸(S15P31E103-567) ──

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
}
