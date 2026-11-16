package com.yamu.app.feature.report

import com.yamu.app.feature.report.data.CardBenefitLine
import com.yamu.app.feature.report.data.ShownMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/** S-04 혜택 리포트 문구 — 큰 숫자는 확정과 예정을 합친 총액이라 밑에 따로 적지 않고, 원화로 못 바꾼 혜택만 알린다(S15P31E103-493 · -578) */
class BenefitReportTextTest {

    @Test fun `원화로 못 바꾼 혜택은 건수만, 금액에 넣지 않았다고`() {
        assertEquals("원화로 바꾸지 못한 혜택 2건은 금액에 넣지 않았어요", unconvertedLine(2))
    }

    @Test fun `카드 줄 누르기 — 이번 달이고 지금 등록된 카드일 때만 R-02로`() {
        val nov = ShownMonth(YearMonth.of(2026, 11), YearMonth.of(2026, 11))
        val oct = ShownMonth(YearMonth.of(2026, 10), YearMonth.of(2026, 11))
        val card = CardBenefitLine(11, "생활카드", 16_000, 0, 0)
        assertEquals(11L, cardTarget(card, nov, setOf(11L, 16L)))
        assertNull("지난달 — R-02는 이번 달만 보여서", cardTarget(card, oct, setOf(11L)))
        assertNull("해지한 카드 — 404 막기", cardTarget(card, nov, setOf(16L)))
        assertNull("카드를 모르는 줄", cardTarget(card.copy(userCardId = null), nov, setOf(11L)))
        assertNull("등록된 카드를 아직 못 받음", cardTarget(card, nov, null))
    }

    @Test fun `종류별 · 카드별 보조 줄 — 있는 것만, 둘 다 없으면 줄 없음`() {
        // 예정 금액은 '(예정) ○원' — '확정 전'이라는 말은 쓰지 않는다(S15P31E103-576)
        assertEquals("(예정) 1,000원 · 원화로 못 바꾼 1건", amountSubLine(1_000, 1))
        assertEquals("(예정) 1,000원", amountSubLine(1_000, 0))
        assertEquals("원화로 못 바꾼 3건", amountSubLine(0, 3))
        assertNull(amountSubLine(0, 0))
    }
}
