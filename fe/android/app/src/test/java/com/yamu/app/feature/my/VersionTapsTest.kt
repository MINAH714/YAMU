package com.yamu.app.feature.my

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마이 버전 글자 숨은 입구(S15P31E103-434) — 1.5초 안에 이어서 7번 */
class VersionTapsTest {

    private fun VersionTaps.tapAll(times: Int, start: Long = 0L, gap: Long = 300L): List<Boolean> =
        (0 until times).map { tap(start + it * gap) }

    @Test fun `이어서 7번 누르면 일곱 번째에만 열린다`() {
        val r = VersionTaps().tapAll(7)
        assertEquals(List(6) { false } + true, r)
    }

    @Test fun `사이가 1_5초를 넘으면 처음부터 센다`() {
        val taps = VersionTaps()
        taps.tapAll(6)                              // 0 ~ 1500ms
        assertFalse("1.6초 쉬고 누르면 1번째", taps.tap(1_500 + 1_600))
        assertFalse(taps.tapAll(5, start = 3_400).last())
        assertTrue("다시 7번째", taps.tap(3_400 + 5 * 300))
    }

    @Test fun `열린 뒤에는 다시 7번을 세야 한다`() {
        val taps = VersionTaps()
        assertTrue(taps.tapAll(7).last())
        assertFalse("열린 바로 뒤 한 번은 새로 센 첫 번째", taps.tap(2_100))
        assertTrue(taps.tapAll(6, start = 2_400).last())
    }
}
