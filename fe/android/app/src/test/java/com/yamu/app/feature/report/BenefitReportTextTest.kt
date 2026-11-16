package com.yamu.app.feature.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S-04 혜택 리포트 문구 — 큰 숫자는 확정과 예정을 합친 총액이라 밑에 따로 적지 않고, 원화로 못 바꾼 혜택만 알린다(S15P31E103-493 · -578) */
class BenefitReportTextTest {

    @Test fun `원화로 못 바꾼 혜택은 건수만, 금액에 넣지 않았다고`() {
        assertEquals("원화로 바꾸지 못한 혜택 2건은 금액에 넣지 않았어요", unconvertedLine(2))
    }

    @Test fun `종류별 · 카드별 보조 줄 — 있는 것만, 둘 다 없으면 줄 없음`() {
        // 예정 금액은 '(예정) ○원' — '확정 전'이라는 말은 쓰지 않는다(S15P31E103-576)
        assertEquals("(예정) 1,000원 · 원화로 못 바꾼 1건", amountSubLine(1_000, 1))
        assertEquals("(예정) 1,000원", amountSubLine(1_000, 0))
        assertEquals("원화로 못 바꾼 3건", amountSubLine(0, 3))
        assertNull(amountSubLine(0, 0))
    }
}
