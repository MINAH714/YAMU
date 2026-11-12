package com.yamu.app.feature.auth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 08 문서 4-2 입력 규칙 · 4-3 오류 문장 */
class AuthValidationTest {

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
        assertEquals("간편 비밀번호가 맞지 않아요 · 3번 더 틀리면 30분 동안 잠겨요", authErrorMessage("STEP_UP_FAILED", remainingAttempts = 3))
        assertEquals("5번 틀려서 잠겼어요 · 14:30에 풀려요", authErrorMessage("DEVICE_LOCKED", unlockAt = "14:30"))
        assertEquals("쉬운 번호는 쓸 수 없어요", authErrorMessage("INVALID_PIN_FORMAT"))
        assertNull(authErrorMessage("SOMETHING_ELSE"))
    }

    @Test
    fun `로그인 PIN 틀림은 몇 번 더 틀리면 잠기는지 · 남은 횟수가 없으면 서명 실패 문장`() {
        assertEquals("간편 비밀번호가 맞지 않아요 · 2번 더 틀리면 30분 동안 잠겨요", authErrorMessage("PIN_MISMATCH", remainingAttempts = 2))
        assertEquals("간편 비밀번호가 맞지 않아요 · 1번 더 틀리면 30분 동안 잠겨요", authErrorMessage("PIN_MISMATCH", remainingAttempts = 1))
        assertEquals("본인 확인에 실패했어요. 다시 시도해 주세요", authErrorMessage("STEP_UP_FAILED"))
        assertEquals("5번 틀려서 잠겼어요 · 30분 뒤에 다시 시도해 주세요", authErrorMessage("DEVICE_LOCKED"))
    }

    @Test
    fun `잠금 풀리는 시각은 서버 UTC를 이 폰 시각으로`() {
        assertEquals("21:30", lockedUntilClock("2026-11-01T12:30:00Z", java.time.ZoneId.of("Asia/Seoul")))
        assertNull(lockedUntilClock("2026-11-01 12:30", java.time.ZoneId.of("Asia/Seoul")))
        assertNull(lockedUntilClock(null))
    }

    @Test
    fun `잠금 남은 시간은 풀리는 시각까지 · 지났거나 못 읽으면 null`() {
        val now = java.time.Instant.parse("2026-11-01T12:00:00Z")
        assertEquals(90_000L, lockRemainingMillis("2026-11-01T12:01:30Z", now))
        assertNull(lockRemainingMillis("2026-11-01T11:59:59Z", now))
        assertNull(lockRemainingMillis("2026-11-01T12:00:00Z", now))
        assertNull(lockRemainingMillis("2026-11-01 12:30", now))
        assertNull(lockRemainingMillis(null, now))
    }
}
