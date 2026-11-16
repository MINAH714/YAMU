package com.yamu.app.feature.forecast

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingAmountFitTest {

    @Test fun `칸 안에 들어가면 그대로`() {
        assertEquals(1f, rollingFitScale(naturalWidth = 200, maxWidth = 280))
        assertEquals(1f, rollingFitScale(naturalWidth = 280, maxWidth = 280))
        assertEquals(1f, rollingFitScale(naturalWidth = 500, maxWidth = Constraints.Infinity))
        assertEquals(1f, rollingFitScale(naturalWidth = 0, maxWidth = 280))
    }

    @Test fun `넘치면 칸에 맞게 줄이고 0_01 단위로 내린다`() {
        assertEquals(0.8f, rollingFitScale(naturalWidth = 350, maxWidth = 280), 0.0001f)
        val s = rollingFitScale(naturalWidth = 300, maxWidth = 280)   // 0.9333…
        assertEquals(0.93f, s, 0.0001f)
        assert(300 * s <= 280)
    }

    @Test fun `절반 아래로는 줄이지 않는다`() {
        assertEquals(0.5f, rollingFitScale(naturalWidth = 1000, maxWidth = 280))
    }
}
