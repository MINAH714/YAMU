package com.yamu.app.feature.auth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 08 문서 4-2 입력 규칙 · 4-3 오류 문장 */
class AuthValidationTest {

    @Test
    fun `이름은 2~20자`() {
        assertNull(AuthValidation.name("김야무"))
        assertNotNull(AuthValidation.name("김"))
        assertNotNull(AuthValidation.name("가".repeat(21)))
    }

    @Test
    fun `이메일 형식`() {
        assertNull(AuthValidation.email("yamu@ssafy.com"))
        assertNull(AuthValidation.email(" yamu@ssafy.com "))
        assertNotNull(AuthValidation.email("yamu@ssafy"))
        assertNotNull(AuthValidation.email("yamu.ssafy.com"))
    }

    @Test
    fun `비밀번호는 8자 이상 영문 숫자 특수문자`() {
        assertNull(AuthValidation.password("Yamu1234!"))
        assertNotNull(AuthValidation.password("Yamu1234"))     // 특수문자 없음
        assertNotNull(AuthValidation.password("yamu!!!!"))     // 숫자 없음
        assertNotNull(AuthValidation.password("1234!!!!"))     // 영문 없음
        assertNotNull(AuthValidation.password("Ya1!"))         // 짧음
    }

    @Test
    fun `간편 비밀번호는 6자리 숫자 쉬운 번호 금지`() {
        assertNull(AuthValidation.pin("482913"))
        assertNull(AuthValidation.pin("135790"))
        assertEquals("숫자 6자리를 넣어 주세요", AuthValidation.pin("12345"))
        assertEquals("숫자 6자리를 넣어 주세요", AuthValidation.pin("12a456"))
        assertEquals("쉬운 번호는 쓸 수 없어요", AuthValidation.pin("111111"))
        assertEquals("쉬운 번호는 쓸 수 없어요", AuthValidation.pin("123456"))
        assertEquals("쉬운 번호는 쓸 수 없어요", AuthValidation.pin("987654"))
    }

    @Test
    fun `인증 오류 문장`() {
        assertEquals("간편 비밀번호가 맞지 않아요 · 3번 남았어요", authErrorMessage("STEP_UP_FAILED", remainingAttempts = 3))
        assertEquals("5번 틀려서 잠겼어요 · 14:30에 풀려요", authErrorMessage("DEVICE_LOCKED", unlockAt = "14:30"))
        assertEquals("이미 가입된 이메일이에요", authErrorMessage("DUPLICATE_EMAIL"))
        assertNull(authErrorMessage("SOMETHING_ELSE"))
    }
}
