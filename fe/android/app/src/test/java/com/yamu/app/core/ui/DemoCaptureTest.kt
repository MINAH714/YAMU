package com.yamu.app.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 모드 화면 캡처 허용(S15P31E103-434) — 켜면 24시간, 끄면 바로, 시각이 없으면 꺼짐 */
class DemoCaptureTest {
    private val now = 1_762_900_000_000L

    @Test fun `켜면 지금부터 24시간 뒤까지`() {
        assertEquals(now + 24 * 60 * 60 * 1000L, DemoCapture.untilFor(on = true, now = now))
        assertNull(DemoCapture.untilFor(on = false, now = now))
    }

    @Test fun `끝 시각 전에는 허용 · 그 순간부터 다시 막는다`() {
        val until = DemoCapture.untilFor(on = true, now = now)
        assertTrue(DemoCapture.isAllowed(until, now))
        assertTrue(DemoCapture.isAllowed(until, now + DemoCapture.ALLOW_MS - 1))
        assertFalse(DemoCapture.isAllowed(until, now + DemoCapture.ALLOW_MS))
    }

    @Test fun `끈 상태면 막는다`() {
        assertFalse(DemoCapture.isAllowed(null, now))
    }
}
