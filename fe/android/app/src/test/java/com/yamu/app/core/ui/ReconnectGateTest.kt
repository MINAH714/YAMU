package com.yamu.app.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 연결이 돌아오면 다시 불러오기(S15P31E103-464) — 끊김을 본 뒤에만, 한 번만 */
class ReconnectGateTest {
    @Test fun `끊긴 채 등록하면 인터넷이 확인될 때 한 번 알린다`() {
        val gate = ReconnectGate(startedOffline = true)
        assertFalse(gate.backOnline(validated = false))   // 망은 잡혔지만 인터넷 확인 전
        assertTrue(gate.backOnline(validated = true))
        assertFalse(gate.backOnline(validated = true))    // 같은 연결의 다음 변경은 다시 부르지 않는다
    }

    @Test fun `이미 인터넷이 될 때 등록하면 알리지 않는다 — 서버 오류에서 되풀이하지 않게`() {
        val gate = ReconnectGate(startedOffline = false)
        assertFalse(gate.backOnline(validated = true))
        assertFalse(gate.backOnline(validated = true))
    }

    @Test fun `다시 끊겼다 돌아오면 또 한 번 알린다`() {
        val gate = ReconnectGate(startedOffline = false)
        gate.lost()
        assertTrue(gate.backOnline(validated = true))
        gate.lost()
        assertFalse(gate.backOnline(validated = false))
        assertTrue(gate.backOnline(validated = true))
    }
}
