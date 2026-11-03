package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 요청 JSON 필드 이름이 BE auth/dto와 같은지 — YamuJson은 기본값 · null을 보내지 않는다 */
class AuthRequestJsonTest {
    private inline fun <reified T> fields(value: T) = YamuJson.parseToJsonElement(YamuJson.encodeToString(value)).jsonObject

    @Test fun `기기 등록 — platform은 늘, 지문 키를 빼면 지문 칸이 없다`() {
        val off = fields(DeviceRequest("0b8d…", "Pixel", platform = "ANDROID", devicePublicKey = "MFkw"))
        assertEquals(setOf("deviceId", "deviceName", "platform", "devicePublicKey"), off.keys)
        assertEquals("ANDROID", off["platform"]!!.jsonPrimitive.content)

        val on = fields(DeviceRequest("0b8d…", null, "ANDROID", "MFkw", biometricPublicKey = "MFkx", biometricTypes = listOf("FINGERPRINT")))
        assertEquals(setOf("deviceId", "platform", "devicePublicKey", "biometricPublicKey", "biometricTypes"), on.keys)
    }

    @Test fun `로그인 — PIN은 pin을 같이, 지문은 pin 없이`() {
        val pin = fields(LoginRequest(7, "ch_1", AuthMethod.PIN, "MEUC", pin = "482913"))
        assertEquals(setOf("userDeviceId", "challengeId", "method", "signature", "pin"), pin.keys)
        val bio = fields(LoginRequest(7, "ch_1", AuthMethod.BIOMETRIC, "MEUC"))
        assertFalse("pin" in bio.keys)
        assertEquals(setOf("userDeviceId"), fields(LoginChallengeRequest(7)).keys)
    }

    @Test fun `본인 확인 — PIN에도 서명이 가고 기기 번호는 보내지 않는다`() {
        assertEquals(setOf("challengeId", "method", "signature", "pin"), fields(VerifyRequest("ch_1", AuthMethod.PIN, "MEUC", "482913")).keys)
        assertEquals(setOf("purpose"), fields(ChallengeRequest(StepUpPurpose.SECURITY_SETTINGS)).keys)
    }

    @Test fun `기기 등록 응답 — 회원으로 부르면 토큰이 없다`() {
        val member = YamuJson.decodeFromString<DeviceResponse>("""{"userDeviceId":7,"biometricEnabled":false,"status":"ACTIVE","registeredAt":"2026-11-02T01:00:00Z"}""")
        assertEquals(7L, member.userDeviceId)
        assertEquals(null, member.accessToken)
    }
}
