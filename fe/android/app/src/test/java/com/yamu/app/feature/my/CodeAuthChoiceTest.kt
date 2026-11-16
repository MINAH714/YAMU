package com.yamu.app.feature.my

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보안 · 인증의 결제 코드 열 때 인증 — 목록 글자와 선택 창이 같은 값을 보는지(S15P31E103-585) */
class CodeAuthChoiceTest {

    @Test fun `서버는 지문 또는 간편 비밀번호인데 이 기기 지문이 꺼져 있으면 목록도 창도 간편 비밀번호만`() {
        val pinOnly = codeAuthPinOnly(pinOnlyForCode = false, biometricOn = false)
        assertTrue(pinOnly)
        assertEquals("간편 비밀번호만", codeAuthLabel(pinOnly))
        val choices = codeAuthChoices(biometricOn = false)
        assertEquals(listOf(true to "간편 비밀번호만"), choices)
        assertEquals("창에서 체크되는 줄이 목록 글자와 같다", codeAuthLabel(pinOnly), choices.single { it.first == pinOnly }.second)
    }

    @Test fun `지문이 켜져 있고 서버가 지문 또는 간편 비밀번호면 그 줄에 체크`() {
        val pinOnly = codeAuthPinOnly(pinOnlyForCode = false, biometricOn = true)
        assertFalse(pinOnly)
        assertEquals("지문 또는 간편 비밀번호", codeAuthLabel(pinOnly))
        val choices = codeAuthChoices(biometricOn = true)
        assertEquals(listOf(false to "지문 또는 간편 비밀번호", true to "간편 비밀번호만"), choices)
        assertEquals(codeAuthLabel(pinOnly), choices.single { it.first == pinOnly }.second)
    }

    @Test fun `서버가 간편 비밀번호만이면 지문이 켜져 있어도 간편 비밀번호만`() {
        val pinOnly = codeAuthPinOnly(pinOnlyForCode = true, biometricOn = true)
        assertTrue(pinOnly)
        assertEquals(codeAuthLabel(pinOnly), codeAuthChoices(biometricOn = true).single { it.first == pinOnly }.second)
    }

    @Test fun `서버가 간편 비밀번호만이고 지문도 꺼져 있으면 한 줄뿐`() {
        assertTrue(codeAuthPinOnly(pinOnlyForCode = true, biometricOn = false))
        assertEquals(1, codeAuthChoices(biometricOn = false).size)
    }
}
