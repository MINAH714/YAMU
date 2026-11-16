package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.BenefitLine
import com.yamu.app.feature.forecast.data.SimBenefit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** I-02 혜택 카드 — 받을 혜택이 없으면 '혜택 1순위' 대신 이유 없는 문구(S15P31E103-591). 계산 못 함(null)과는 나눈다 */
class BuyCheckBenefitTest {

    @Test fun `순혜택 0원이고 줄도 모두 0원이거나 없으면 받을 혜택이 없다`() {
        assertTrue(hasNoBenefit(SimBenefit(BigDecimal.ZERO, emptyList(), charged = 12_000)))
        assertTrue(hasNoBenefit(SimBenefit(BigDecimal("0.00"), listOf(BenefitLine("포인트 적립", BigDecimal.ZERO)), charged = null)))
    }

    @Test fun `계산하지 못했으면(혜택 · 순혜택 null) 0원으로 보지 않는다`() {
        assertFalse(hasNoBenefit(null))
        assertFalse(hasNoBenefit(SimBenefit(null, emptyList(), charged = 12_000)))
    }

    @Test fun `혜택이 있거나 금액을 모르는 줄이 있으면 '없다'고 하지 않는다`() {
        assertFalse(hasNoBenefit(SimBenefit(BigDecimal("0.5"), listOf(BenefitLine("포인트 적립", BigDecimal("0.5"))), charged = 12_000)))
        // 합계는 0원인데 아직 원화로 바꾸지 못한 줄('금액 미정')이 있다 — 혜택이 없다고 단정하지 않는다
        assertFalse(hasNoBenefit(SimBenefit(BigDecimal.ZERO, listOf(BenefitLine("포인트 적립", null)), charged = 12_000)))
    }
}
