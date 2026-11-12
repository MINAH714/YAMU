package com.yamu.app.navigation

import com.yamu.app.feature.auth.WelcomeLogin
import org.junit.Assert.assertEquals
import org.junit.Test

/** 첫 화면 '로그인'이 여는 화면 — 기기가 있으면 간편 비밀번호, 없으면 본인인증(2차 QA 14) */
class WelcomeLoginRouteTest {

    @Test
    fun `기기가 있으면 간편 비밀번호 로그인 화면을 연다`() {
        assertEquals(Login, welcomeLoginRoute(WelcomeLogin.PIN))
    }

    @Test
    fun `기기가 없으면 본인인증 화면을 연다`() {
        assertEquals(IdentityVerify, welcomeLoginRoute(WelcomeLogin.VERIFY))
    }
}
