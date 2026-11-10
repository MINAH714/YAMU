package com.yamu.app.navigation

import com.yamu.app.core.security.SessionEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 세션이 끝났을 때 갈 곳(YamuApp) — 같은 화면으로 두 번 가지 않고, 탈퇴 화면은 스스로 끝낸다 */
class SessionEndTest {

    @Test
    fun `로그인이 끝나면 로그인 화면으로 보낸다`() {
        assertEquals(SessionEndMove(toWelcome = false, message = "다시 로그인해 주세요"), sessionEndMove(SessionEnd.EXPIRED, SessionPlace.OTHER))
    }

    @Test
    fun `이미 로그인 · 첫 화면이거나 탈퇴 화면이면 로그인 끝남으로 옮기지 않는다`() {
        listOf(SessionPlace.LOGIN, SessionPlace.WELCOME, SessionPlace.WITHDRAW).forEach { here ->
            assertNull(here.name, sessionEndMove(SessionEnd.EXPIRED, here))
        }
    }

    @Test
    fun `기기가 해제되면 이유를 알리며 첫 화면으로 보낸다 - 로그인 · 탈퇴 화면에서도`() {
        val expected = SessionEndMove(toWelcome = true, message = "다른 폰에서 등록해서 이 폰은 로그아웃됐어요")
        listOf(SessionPlace.OTHER, SessionPlace.LOGIN, SessionPlace.WITHDRAW).forEach { here ->
            assertEquals(here.name, expected, sessionEndMove(SessionEnd.DEVICE_REVOKED, here))
        }
    }

    @Test
    fun `이미 첫 화면이면 기기 해제로 다시 옮기지 않는다`() {
        assertNull(sessionEndMove(SessionEnd.DEVICE_REVOKED, SessionPlace.WELCOME))
    }
}
