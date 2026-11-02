package com.yamu.app.feature.auth.data

import kotlinx.serialization.Serializable

// ── 서버 요청 · 응답(API 명세 V2 「인증」 — BE auth/dto와 같은 이름) ──

/** 이메일 가입 — 가짜 가입 화면에서만. Task 8.2.9에서 휴대폰 본인인증으로 바뀐다 */
@Serializable data class SignupRequest(val email: String, val password: String, val name: String)

@Serializable data class SignupResponse(
    val userId: Long? = null,
    val email: String? = null,
    val name: String? = null,
    val nextSteps: List<String> = emptyList(),
)

/** 로그인 준비 — 기기 번호로 60초짜리 일회용 challenge를 받는다 */
@Serializable data class LoginChallengeRequest(val userDeviceId: Long)

@Serializable data class LoginChallengeResponse(
    val challengeId: String,
    val challenge: String,
    val expiresIn: Long = 60,
    val biometricEnabled: Boolean = false,
)

/** method PIN: 기기 키로 서명 + pin(숫자 6자리) · BIOMETRIC: 지문 키로 서명(pin 없음) */
@Serializable data class LoginRequest(
    val userDeviceId: Long,
    val challengeId: String,
    val method: String,
    val signature: String,
    val pin: String? = null,
)

@Serializable data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0,
    val user: LoginUser? = null,
)

@Serializable data class LoginUser(val userId: Long? = null, val name: String? = null, val onboardingCompleted: Boolean = false)

@Serializable data class LogoutRequest(val refreshToken: String)

@Serializable data class PinRequest(val pin: String)

/**
 * 기기 등록. 공개키는 EC P-256 X.509 DER의 표준 Base64. 지문 키를 빼고 보내면 지문은 꺼진다.
 * platform은 기본값을 두지 않는다 — YamuJson은 기본값을 보내지 않아서(encodeDefaults=false) 빠지면 400
 */
@Serializable data class DeviceRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val platform: String,
    val devicePublicKey: String,
    val biometricPublicKey: String? = null,
    val biometricTypes: List<String>? = null,
)

/** 토큰은 가입 · 기기 재등록 중(가입 진행 토큰)에 불렀을 때만 온다 */
@Serializable data class DeviceResponse(
    val userDeviceId: Long,
    val deviceName: String? = null,
    val biometricEnabled: Boolean = false,
    val status: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
)

/** purpose: StepUpPurpose. 기기는 Access 토큰에서 서버가 읽는다 */
@Serializable data class ChallengeRequest(val purpose: String)

@Serializable data class ChallengeResponse(
    val challengeId: String,
    val challenge: String,
    val purpose: String? = null,
    val allowedMethods: List<String> = listOf("PIN"),
    val expiresAt: String? = null,
)

/** PIN도 기기 키 서명이 함께 간다 */
@Serializable data class VerifyRequest(
    val challengeId: String,
    val method: String,
    val signature: String,
    val pin: String? = null,
)

@Serializable data class VerifyResponse(
    val stepUpToken: String,
    val purpose: String? = null,
    val method: String? = null,
    val expiresAt: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

/** 앱을 켰을 때 갈 곳 — 이 폰에 기기 번호가 있으면 PIN · 지문 로그인, 없으면 첫 화면 */
sealed interface SessionStart {
    data object Welcome : SessionStart
    data object Login : SessionStart
}

data class LoginResult(val onboardingCompleted: Boolean)

/** 로그인 · 본인 확인 challenge — 서명은 [challenge] 문자열 그대로(ChallengeSigner) */
data class StepUpChallenge(val challengeId: String, val challenge: String, val allowsBiometric: Boolean)

data class StepUpToken(val token: String, val purpose: String?)

object AuthMethod {
    const val PIN = "PIN"
    const val BIOMETRIC = "BIOMETRIC"
}

object StepUpPurpose {
    const val APP_UNLOCK = "APP_UNLOCK"
    const val BARCODE = "BARCODE"
    const val ACCOUNT_LINK = "ACCOUNT_LINK"
    const val CARD_REGISTER = "CARD_REGISTER"
    const val CARD_DELETE = "CARD_DELETE"
    /** 간편 비밀번호 바꾸기 · 지문 켜기/끄기 */
    const val SECURITY_SETTINGS = "SECURITY_SETTINGS"
}
