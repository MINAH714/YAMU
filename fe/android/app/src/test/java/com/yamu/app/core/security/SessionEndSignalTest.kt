package com.yamu.app.core.security

import com.yamu.app.core.network.FakeTokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** '세션 끝남'을 화면이 처리할 때까지 남기고, 지난 알림은 새 로그인을 건드리지 않는다 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEndSignalTest {

    private val revoked = SessionEndEvent(SessionEnd.DEVICE_REVOKED, tokenVersion = 0)
    private val expired = SessionEndEvent(SessionEnd.EXPIRED, tokenVersion = 0)

    @Test
    fun `받는 화면이 없을 때 생긴 알림도 다음 수집기가 받는다`() = runTest(UnconfinedTestDispatcher()) {
        val signal = SessionEndSignal()
        val seen = mutableListOf<SessionEndEvent>()
        val before = launch { signal.pending.filterNotNull().collect { seen += it } }
        before.cancel()                                              // 화면 회전 — 수집기가 잠깐 없다

        signal.publish(revoked)

        assertEquals(revoked, signal.pending.filterNotNull().first())   // 다시 만든 화면이 받는다
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `처리한 알림만 지우고 그사이 온 새 알림은 남긴다`() {
        val signal = SessionEndSignal()
        val later = SessionEndEvent(SessionEnd.EXPIRED, tokenVersion = 3)
        signal.publish(revoked)
        signal.publish(later)

        signal.consume(revoked)
        assertEquals(later, signal.pending.value)

        signal.consume(later)
        assertNull(signal.pending.value)
    }

    @Test
    fun `같은 세션에서 겹치면 기기 해제를 남긴다`() {
        val signal = SessionEndSignal()
        signal.publish(revoked)
        signal.publish(expired)
        assertEquals(revoked, signal.pending.value)

        val nextSession = SessionEndEvent(SessionEnd.EXPIRED, tokenVersion = 1)
        signal.publish(nextSession)
        assertEquals(nextSession, signal.pending.value)
    }

    @Test
    fun `기기 해제는 그 뒤 새로 로그인하지 않았을 때만 이 폰을 비운다`() = runTest {
        val store = FakeTokenStore(access = null, refresh = null)    // 인증기가 이미 토큰을 지운 상태(토큰 번호 0)
        var forgotten = 0

        assertTrue(store.settleSessionEnd(revoked) { forgotten++ })
        assertEquals(1, forgotten)
    }

    @Test
    fun `알림을 처리하기 전에 새로 로그인했으면 기기도 토큰도 지우지 않는다`() = runTest {
        val store = FakeTokenStore(access = null, refresh = null)
        store.save(AuthTokens("new", "r2"))                          // 다시 등록 · 로그인 — 토큰 번호 1
        var forgotten = 0

        assertFalse(store.settleSessionEnd(revoked) { forgotten++ })
        assertFalse(store.settleSessionEnd(expired) { forgotten++ })

        assertEquals(0, forgotten)
        assertEquals("new", store.accessToken)
    }

    @Test
    fun `세션 끝남은 기기를 비우지 않고 지난 알림인지만 본다`() = runTest {
        val store = FakeTokenStore(access = null, refresh = null)
        var forgotten = 0

        assertTrue(store.settleSessionEnd(expired) { forgotten++ })
        assertEquals(0, forgotten)
    }
}
