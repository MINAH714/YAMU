package com.yamu.app.feature.performance.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실적 분석 카드 그림 아래 지난달 한 줄 — 채운 가장 높은 구간 기준(S15P31E103-602) */
class LastMonthPerfTest {
    private val tiers = listOf(500_000L, 300_000L)

    @Test fun `지난달은 서버가 준 이번 달에서 한 달 전이고 1월이면 전년 12월`() {
        assertEquals("2026-10", previousYearMonth("2026-11"))
        assertEquals("2025-12", previousYearMonth("2026-01"))
        assertNull(previousYearMonth(null))
        assertNull(previousYearMonth("2026-13"))
        assertNull(previousYearMonth("2026/11"))
    }

    @Test fun `채운 구간 중 가장 높은 구간 기준으로 달성률을 낸다`() {
        val p = lastMonthPerf("2026-10", 520_000, tiers)!!
        assertTrue(p.reached)
        assertEquals(500_000L, p.tier)
        assertEquals(104L, p.percent)
        assertEquals("10월 520,000원 · 50만 원 구간 달성 (104%)", p.text)

        val middle = lastMonthPerf("2026-10", 350_000, tiers)!!
        assertEquals(300_000L, middle.tier)
        assertEquals("10월 350,000원 · 30만 원 구간 달성 (116%)", middle.text)
    }

    @Test fun `기준 금액과 같으면 달성 100%`() {
        val p = lastMonthPerf("2026-10", 300_000, tiers)!!
        assertTrue(p.reached)
        assertEquals(100L, p.percent)
    }

    @Test fun `첫 구간에 못 미치면 첫 구간까지 얼마나 왔는지 내림으로`() {
        val p = lastMonthPerf("2026-10", 210_000, tiers)!!
        assertFalse(p.reached)
        assertEquals(300_000L, p.tier)
        assertEquals("10월 210,000원 · 30만 원 구간까지 70%", p.text)
        assertEquals("99.99%를 100%로 올리지 않는다", 99L, lastMonthPerf("2026-10", 299_999, tiers)!!.percent)
        assertEquals("10월 0원 · 30만 원 구간까지 0%", lastMonthPerf("2026-10", 0, tiers)!!.text)
    }

    @Test fun `그달 기록이 없거나 구간이 없으면 줄을 숨긴다`() {
        assertNull(lastMonthPerf("2026-10", null, tiers))
        assertNull(lastMonthPerf("2026-10", 300_000, emptyList()))
        assertNull(lastMonthPerf("2026-10", 300_000, listOf(0L)))
        assertNull(lastMonthPerf(null, 300_000, tiers))
    }

    @Test fun `지난달 목록에서 기록 없는 달과 실적 조건 없는 카드는 뺀다`() {
        fun item(id: Long, status: String, recognized: Long) = CardPerformanceItemDto(userCardId = id, recognizedAmount = recognized, status = status)
        val m = CardPerformancesDto(
            yearMonth = "2026-10",
            cards = listOf(item(1, "ACHIEVED", 320_000), item(2, "AT_RISK", 120_000), item(3, "INSUFFICIENT_DATA", 0), item(4, "NO_TARGET", 90_000)),
        ).toMonthRecognized("2026-10")
        assertEquals("2026-10", m.yearMonth)
        assertEquals(mapOf(1L to 320_000L, 2L to 120_000L), m.byCard)
    }

    @Test fun `실적 상세 구간은 기준 금액을 잃지 않는다`() {
        val d = PerformanceDetailDto(
            userCardId = 1,
            summary = PerfSummaryDto(targetAmount = 400_000, recognizedAmount = 0, remainingAmount = 400_000),
            tiers = listOf(TierDto(400_000, "40만 원 이상"), TierDto(300_000, "30만 원 이상")),
        ).toDetail()
        assertEquals(listOf(300_000L, 400_000L), d.tiers.map { it.threshold })
    }
}
