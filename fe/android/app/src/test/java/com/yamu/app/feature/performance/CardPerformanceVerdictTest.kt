package com.yamu.app.feature.performance

import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfDetail
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerfTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 카드 실적 화면 '더 써야 할까요?'가 넘을 것 같을 때 — 답 · 이유 문장과 예상이 닿는 단계(S15P31E103-615) */
class CardPerformanceVerdictTest {

    private fun analysis(target: Long, projected: Long, scheduled: Long? = null) = PerfAnalysis(
        userCardId = 16, month = 11, guide = PerfGuide.KEEP_NATURAL, headline = null, target = target, projected = projected,
        shortfall = 0, extraBenefit = null, benefitNames = emptyList(), steps = emptyList(), movable = null,
        decision = null, isEstimate = true, version = null, scheduled = scheduled,
    )

    /** 노리 — 20만 넘김, 다음 단계 30만, 단계 20/30/50/100만 */
    private val nori = PerfDetail(
        16, 11, 300_000, "30만원 이상", 260_000, 40_000, false, false,
        listOf(
            PerfTier("20만원 이상", "GS25 환급할인", 10_000, true, 200_000),
            PerfTier("30만원 이상", "통신 자동이체 2,500원", 20_000, false, 300_000),
            PerfTier("50만원 이상", null, 30_000, false, 500_000),
            PerfTier("100만원 이상", null, 50_000, false, 1_000_000),
        ),
        null,
    )

    @Test fun `예정 지출이 들어간 예상이면 그것까지 말하고 만원 아래는 버린다`() {
        val (title, body) = analysis(300_000, 763_954, scheduled = 300_000).verdict("KB국민카드", secured = 200_000)
        assertEquals("평소처럼만 쓰면 돼요", title)
        assertEquals("평소 소비와 예정된 지출을 합하면, 이번 달 실적은 약 76만원으로 예상돼요.", body)
    }

    @Test fun `예정 지출이 없으면 평소처럼 쓰면`() {
        val (title, body) = analysis(300_000, 360_000, scheduled = 0).verdict("KB국민카드", secured = null)
        assertEquals("평소처럼만 쓰면 돼요", title)
        assertEquals("평소처럼 쓰면 이번 달 실적은 약 36만원으로 예상돼요.", body)
    }

    @Test fun `기준에 딱 닿는 예상도 버림 때문에 기준보다 낮게 말하지 않는다`() {
        assertEquals("30만원", aboutManWon(300_999))
        assertEquals("9,500원", aboutManWon(9_500))
        assertEquals("123만원", aboutManWon(1_234_567))
    }

    @Test fun `예상이 닿는 가장 높은 못 넘긴 단계`() {
        assertEquals(500_000L, nori.expectedThreshold(763_954))
        assertEquals(300_000L, nori.expectedThreshold(300_000))
        assertNull("다음 단계에 못 닿으면 없음", nori.expectedThreshold(280_000))
        assertNull(nori.expectedThreshold(null))
    }

    @Test fun `단계 줄에 기준 금액이 실린다`() {
        assertEquals(listOf(200_000L, 300_000L, 500_000L, 1_000_000L), nori.benefitLadder().map { it.threshold })
    }
}
