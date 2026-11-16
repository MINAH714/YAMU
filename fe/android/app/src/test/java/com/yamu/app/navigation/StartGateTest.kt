package com.yamu.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartGateTest {
    private var now = 1_000L
    private fun gate() = StartGate(now = { now }, maxMs = 1_500L)

    @Test
    fun `갈 곳을 정하기 전에는 스플래시를 둔다`() {
        val g = gate()
        now += 1_499
        assertTrue(g.keep())
    }

    @Test
    fun `갈 곳을 정하면 바로 걷는다`() {
        val g = gate()
        now += 10
        g.open()
        assertFalse(g.keep())
    }

    @Test
    fun `정하는 일이 멈춰도 시간이 지나면 걷는다`() {
        val g = gate()
        now += 1_500
        assertFalse(g.keep())
    }

    @Test
    fun `한 번 걷으면 다시 두지 않는다`() {
        val g = gate()
        g.open()
        now -= 100   // 시계가 거꾸로 가도
        assertFalse(g.keep())
    }
}
