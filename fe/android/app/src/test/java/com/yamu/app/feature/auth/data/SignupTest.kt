package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.link.data.BankDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 가입 · 기기 재등록 — 본인인증 칸 규칙 · 오류 문장 · 요청 JSON 필드(BE dto와 같은 이름) */
class SignupTest {
    private val today = LocalDate.of(2026, 11, 2)
    private inline fun <reified T> fields(value: T) = YamuJson.parseToJsonElement(YamuJson.encodeToString(value)).jsonObject.keys

    @Test fun `생년월일은 8자리 · 있는 날짜 · 오늘 전`() {
        assertEquals(LocalDate.of(1998, 1, 1), SignupValidation.birthDate("19980101", today))
        assertNull(SignupValidation.birthDate("19980230", today))   // 없는 날짜
        assertNull(SignupValidation.birthDate("1998011", today))    // 7자리
        assertNull(SignupValidation.birthDate("20261102", today))   // 오늘
        assertNotNull(SignupValidation.birthDateError("abc", today))
    }

    @Test fun `휴대폰 번호는 서버와 같게 010은 11자리 · 011 등은 10~11자리`() {
        assertNull(SignupValidation.phone("01012345678"))
        assertNull(SignupValidation.phone("0111234567"))
        assertNotNull(SignupValidation.phone("0101234567"))         // 010은 11자리
        assertNotNull(SignupValidation.phone("010-1234-5678"))
        assertNotNull(SignupValidation.phone("02012345678"))
    }

    @Test fun `이름은 2~20자`() {
        assertNull(SignupValidation.name("김야무"))
        assertNotNull(SignupValidation.name("김"))
        assertNotNull(SignupValidation.name("가".repeat(21)))
    }

    @Test fun `가입 오류 문장 — 남은 횟수 · 기다릴 시간`() {
        assertEquals("인증번호가 맞지 않아요 · 4번 남았어요", signupErrorMessage("VERIFICATION_CODE_MISMATCH", remainingAttempts = 4))
        assertEquals("숫자가 맞지 않아요 · 2번 남았어요", signupErrorMessage("AUTH_CODE_MISMATCH", remainingAttempts = 2))
        assertEquals("방금 보냈어요 · 40초 뒤 다시 받아 주세요", signupErrorMessage("AUTH_REQUEST_TOO_FREQUENT", retryAfterSeconds = 40))
        assertEquals("시간이 지났어요 · 1원을 다시 받아 주세요", signupErrorMessage("VERIFICATION_TOKEN_EXPIRED"))
        assertEquals("시간이 지났어요 · 본인인증부터 다시 해 주세요", signupErrorMessage(SIGNUP_TOKEN_INVALID))
        assertNull(signupErrorMessage("PIN_MISMATCH"))   // 인증 문장으로 넘긴다
    }

    @Test fun `본인인증 요청 · 1원 요청 · 계좌 연결 · 재등록 기기 등록 필드`() {
        assertEquals(setOf("name", "birthDate", "carrier", "phoneNumber"), fields(PhoneVerificationRequest("김야무", "1998-01-01", "SKT", "01012345678")))
        assertEquals(setOf("candidateIds", "primaryCandidateId"), fields(OneWonAuthRequest(listOf("cand_1"), "cand_1")))
        assertEquals(setOf("verificationToken", "authCode"), fields(SignupAccountLinkRequest("avt_1", "1004")))
        val recovery = DeviceRequest("uuid", null, "ANDROID", "MFkw", verificationToken = "avt_1", authCode = "1004")
        assertEquals(setOf("deviceId", "platform", "devicePublicKey", "verificationToken", "authCode"), fields(recovery))
    }

    @Test fun `계좌 후보 — 가린 번호에서 끝 4자리 · 이름이 없으면 은행 이름`() {
        val c = AccountCandidateDto("cand_1", BankDto("004", "KB국민은행"), "****-****-****-1234").toCandidate()
        assertEquals("1234", c.last4)
        assertEquals("KB국민은행", c.name)
    }

    @Test fun `본인인증 확인 응답 — 이미 가입이면 재등록`() {
        val r = YamuJson.decodeFromString(PhoneConfirmResponse.serializer(), """{"signupToken":"sgt_x","expiresIn":1800,"mode":"DEVICE_RECOVERY","nextStep":"ACCOUNT_LINK"}""")
        assertEquals("DEVICE_RECOVERY", r.mode)
    }
}
