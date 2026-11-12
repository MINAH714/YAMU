package com.yamu.app.feature.performance

import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.MovableItem
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfDecision
import com.yamu.app.feature.performance.data.PerfGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실적 화면에서 카드 실적 상세(R-02)로 가는 길(S15P31E103-451) — R-03 판정마다 버튼이나 링크 하나 · R-01 그 밖의 카드 줄 */
class PerfCardDetailPathTest {

    @Test fun `R-03은 판정 · 기록 · 옮길 지출과 상관없이 R-02로 가는 길이 하나 있다`() {
        for (guide in PerfGuide.entries) for (decision in listOf(null) + PerfDecision.entries) for (movable in listOf(null, MOVABLE)) {
            val a = analysis(guide, decision, movable)
            val case = "$guide · 기록 $decision · 옮길 지출 ${movable != null}"
            val viaButton = a.primaryAction() == PerfAction.DETAIL
            val viaLink = a.detailLinkPlace() != DetailLinkPlace.NONE
            assertTrue("$case — 버튼이나 링크 중 하나", viaButton || viaLink)
            assertTrue("$case — 둘 다 두지 않는다", !(viaButton && viaLink))
            // 링크가 ② 안에 있으면 ②가 그려져야 보인다
            if (a.detailLinkPlace() == DetailLinkPlace.REASONS) assertTrue(case, a.drawsReasons)
        }
    }

    @Test fun `행동 버튼이 있는 판정은 본문에 자세히 보기 링크를 둔다`() {
        assertEquals(PerfAction.KEEP, analysis(PerfGuide.KEEP_NATURAL).primaryAction())
        assertEquals(DetailLinkPlace.REASONS, analysis(PerfGuide.KEEP_NATURAL).detailLinkPlace())
        assertEquals(PerfAction.SKIP, analysis(PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED).primaryAction())
        assertEquals(DetailLinkPlace.REASONS, analysis(PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED).detailLinkPlace())
        assertEquals(PerfAction.MOVE, analysis(PerfGuide.ACHIEVE_RECOMMENDED, movable = MOVABLE).primaryAction())
        assertEquals(DetailLinkPlace.REASONS, analysis(PerfGuide.ACHIEVE_RECOMMENDED, movable = MOVABLE).detailLinkPlace())
    }

    @Test fun `기록한 뒤 달성이면 근거 칸이 없어 판정 아래에 링크를 둔다`() {
        val a = analysis(PerfGuide.ALREADY_ACHIEVED, decision = PerfDecision.KEEP)
        assertEquals(PerfAction.REJUDGE, a.primaryAction())
        assertEquals(DetailLinkPlace.HEAD, a.detailLinkPlace())
    }

    @Test fun `아래 버튼이 이미 자세히 보기면 링크를 겹쳐 두지 않는다`() {
        for (a in listOf(analysis(PerfGuide.ACHIEVE_RECOMMENDED), analysis(PerfGuide.ALREADY_ACHIEVED), analysis(PerfGuide.INSUFFICIENT_DATA))) {
            assertEquals(a.guide.name, PerfAction.DETAIL, a.primaryAction())
            assertEquals(a.guide.name, DetailLinkPlace.NONE, a.detailLinkPlace())
        }
    }

    @Test fun `그 밖의 카드 줄 읽기 이름은 카드 이름과 달성 · 실적 조건 없음`() {
        assertEquals("우리카드 NU 달성", card(noTarget = false).otherRowLabel())
        assertEquals("우리카드 NU 실적 조건 없음", card(noTarget = true).otherRowLabel())
        assertNotEquals(card(noTarget = false).otherRowLabel(), card(noTarget = true).otherRowLabel())
    }

    private companion object {
        val MOVABLE = MovableItem(cashflowItemId = 3, title = "통신비", amount = 55_000, deadlineIso = "2026-11-25", note = null)

        fun analysis(guide: PerfGuide, decision: PerfDecision? = null, movable: MovableItem? = null) = PerfAnalysis(
            userCardId = 1, month = 11, guide = guide, headline = null, target = 300_000, projected = 250_000, shortfall = 50_000,
            extraBenefit = 5_000, benefitNames = emptyList(), steps = emptyList(), movable = movable, decision = decision,
            isEstimate = true, version = "v1",
        )

        fun card(noTarget: Boolean) = CardPerf(
            userCardId = 2, brand = brandOf("우리카드"), name = "우리카드 NU", target = if (noTarget) null else 300_000, targetLabel = null,
            recognized = 320_000, remaining = 0, achieved = !noTarget, noTarget = noTarget, guide = null, decision = null,
            provisional = false, recentChange = null, recentAtIso = null,
        )
    }
}
