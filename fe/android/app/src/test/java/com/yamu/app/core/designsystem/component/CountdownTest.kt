package com.yamu.app.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 남은 시간 링 계산 — 비율은 처음 남아 있던 시간 기준, 임박은 0초 초과 10초 이하, 마감 시각부터 만료 */
class CountdownTest {

    private val deadline = 60_000L
    private val total = 45_000L   // 화면이 처음 뜬 순간 45초 남아 있었음

    @Test
    fun `비율은 처음 남아 있던 시간에 대한 남은 시간이다`() {
        assertEquals(1f, Countdown.ratio(nowMs = 15_000, deadlineMs = deadline, totalMs = total), 1e-6f)
        assertEquals(0.5f, Countdown.ratio(nowMs = 37_500, deadlineMs = deadline, totalMs = total), 1e-6f)
        assertEquals(0f, Countdown.ratio(nowMs = 60_000, deadlineMs = deadline, totalMs = total), 1e-6f)
    }

    @Test
    fun `마감이 지났거나 처음보다 앞선 시각이어도 0과 1 사이로 자른다`() {
        assertEquals(0f, Countdown.ratio(nowMs = 70_000, deadlineMs = deadline, totalMs = total), 0f)
        assertEquals(1f, Countdown.ratio(nowMs = 0, deadlineMs = deadline, totalMs = total), 0f)
    }

    @Test
    fun `처음 그릴 때 이미 마감이 지나 전체 시간이 0이면 비율은 0이다`() {
        assertEquals(0f, Countdown.ratio(nowMs = 61_000, deadlineMs = deadline, totalMs = 0), 0f)
        assertEquals(0f, Countdown.ratio(nowMs = 61_000, deadlineMs = deadline, totalMs = -1_000), 0f)
    }

    @Test
    fun `임박은 남은 시간이 10초 이하이고 0초보다 클 때만이다`() {
        assertFalse(Countdown.urgent(nowMs = 49_999, deadlineMs = deadline, urgentMs = 10_000))
        assertTrue(Countdown.urgent(nowMs = 50_000, deadlineMs = deadline, urgentMs = 10_000))
        assertTrue(Countdown.urgent(nowMs = 59_999, deadlineMs = deadline, urgentMs = 10_000))
        assertFalse(Countdown.urgent(nowMs = 60_000, deadlineMs = deadline, urgentMs = 10_000))
    }

    @Test
    fun `보통 틱은 다음 틱 시각의 비율까지 250ms 동안 줄인다`() {
        val step = Countdown.step(nowMs = 37_500, deadlineMs = deadline, totalMs = total)
        assertEquals(250, step.durationMs)
        assertEquals(Countdown.ratio(37_750, deadline, total), step.target, 1e-6f)
    }

    @Test
    fun `마감까지 한 틱이 안 남으면 남은 시간 동안 0까지 줄여 속도가 같다`() {
        val step = Countdown.step(nowMs = 59_900, deadlineMs = deadline, totalMs = total)
        assertEquals(100, step.durationMs)
        assertEquals(0f, step.target, 0f)
        // 같은 속도: 100ms 동안 줄어드는 양 = 100 / 45000
        assertEquals(100f / total, Countdown.ratio(59_900, deadline, total) - step.target, 1e-6f)
    }

    @Test
    fun `마감이 지났으면 길이 0으로 바로 맞춘다`() {
        assertEquals(Countdown.Step(0f, 0), Countdown.step(nowMs = 60_000, deadlineMs = deadline, totalMs = total))
        assertEquals(Countdown.Step(0f, 0), Countdown.step(nowMs = 75_000, deadlineMs = deadline, totalMs = total))
    }

    @Test
    fun `틱마다 목표 비율은 늘지 않는다`() {
        var last = 1f
        var t = 15_000L
        while (t <= 61_000L) {
            val target = Countdown.step(t, deadline, total).target
            assertTrue("t=$t target=$target last=$last", target <= last)
            last = target
            t += 250
        }
    }

    @Test
    fun `만료는 마감 시각부터다`() {
        assertFalse(Countdown.expired(nowMs = 59_999, deadlineMs = deadline))
        assertTrue(Countdown.expired(nowMs = 60_000, deadlineMs = deadline))
    }
}
