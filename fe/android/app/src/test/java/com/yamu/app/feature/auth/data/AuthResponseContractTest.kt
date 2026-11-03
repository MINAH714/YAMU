package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BE 응답 예시 JSON을 앱 DTO로 읽는다 — 값은 BE auth/dto · account/dto record와 같은 이름 · 모양 */
class AuthResponseContractTest {

    @Test fun `본인인증 요청 응답`() {
        val r = YamuJson.decodeFromString<PhoneVerificationResponse>(
            """{"verificationId":"pv_1","expiresAt":"2026-11-03T01:03:00Z","demoCode":"123456"}""",
        )
        assertEquals("pv_1", r.verificationId)
        assertEquals("123456", r.demoCode)
    }

    @Test fun `본인인증 확인 응답 — 가입 진행 토큰과 모드`() {
        val r = YamuJson.decodeFromString<PhoneConfirmResponse>(
            """{"signupToken":"sgt_1","expiresIn":1800,"mode":"DEVICE_RECOVERY","nextStep":"ACCOUNT_LINK"}""",
        )
        assertEquals("sgt_1", r.signupToken)
        assertEquals("DEVICE_RECOVERY", r.mode)
    }

    @Test fun `계좌 후보 응답 — 이미 연결된 계좌 표시`() {
        val r = YamuJson.decodeFromString<AccountCandidatesResponse>(
            """{"accounts":[{"candidateId":"cand_1","bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-****-1234","accountName":"주거래","alreadyLinked":true}],"expiresAt":"2026-11-03T01:30:00Z"}""",
        )
        val c = r.accounts.single().toCandidate()
        assertEquals("cand_1", c.id)
        assertEquals("1234", c.last4)
        assertTrue(c.alreadyLinked)
    }

    @Test fun `1원 요청 응답`() {
        val r = YamuJson.decodeFromString<OneWonAuthResponse>(
            """{"verificationToken":"avt_1","bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-****-1234","authRequestedAt":"2026-11-03T01:00:00Z","authExpiresAt":"2026-11-03T01:05:00Z","depositorNamePrefix":"YAMU","codeLength":4,"hint":null,"deliveryUncertain":true}""",
        )
        assertEquals("avt_1", r.verificationToken)
        assertEquals(4, r.codeLength)
        assertTrue(r.deliveryUncertain)
    }

    @Test fun `로그인 challenge · 로그인 응답`() {
        val c = YamuJson.decodeFromString<LoginChallengeResponse>(
            """{"challengeId":"ch_1","challenge":"abc","expiresIn":60,"biometricEnabled":false}""",
        )
        assertEquals("ch_1", c.challengeId)
        assertFalse(c.biometricEnabled)
        val l = YamuJson.decodeFromString<LoginResponse>(
            """{"accessToken":"a","refreshToken":"r","tokenType":"Bearer","expiresIn":900,"user":{"userId":7,"name":"야무테스터","onboardingCompleted":true}}""",
        )
        assertEquals("a", l.accessToken)
        assertEquals(7L, l.user?.userId)
        assertTrue(l.user?.onboardingCompleted == true)
    }

    @Test fun `가입 · 재등록 기기 등록 응답 — 토큰이 온다`() {
        val r = YamuJson.decodeFromString<DeviceResponse>(
            """{"userDeviceId":7,"deviceName":"Pixel","biometricEnabled":true,"status":"ACTIVE","registeredAt":"2026-11-03T01:00:00Z","accessToken":"a","refreshToken":"r","expiresIn":900}""",
        )
        assertEquals(7L, r.userDeviceId)
        assertTrue(r.biometricEnabled)
        assertEquals("a", r.accessToken)
    }
}
