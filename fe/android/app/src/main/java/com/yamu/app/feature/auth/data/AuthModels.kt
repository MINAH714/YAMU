package com.yamu.app.feature.auth.data

import kotlinx.serialization.Serializable

// ── 서버 요청 · 응답(API #67 · #68 · #22 · #14 · #15 · #46 · #47) ──

@Serializable data class SignupRequest(val email: String, val password: String, val name: String)

@Serializable data class SignupResponse(
    val userId: Long? = null,
    val email: String? = null,
    val name: String? = null,
    val nextSteps: List<String> = emptyList(),
)

@Serializable data class LoginRequest(val email: String, val password: String)

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

@Serializable data class DeviceRequest(
    val deviceId: String,
    val deviceName: String? = null,
    val publicKey: String,
    val biometricTypes: List<String>,
)

@Serializable data class DeviceResponse(val userDeviceId: Long? = null, val status: String? = null)

/** purpose: APP_UNLOCK · BARCODE · CARD_REGISTER · CARD_DELETE · ACCOUNT_LINK · WITHDRAW_MEMBER · DATA_EXPORT */
@Serializable data class ChallengeRequest(val purpose: String, val deviceId: String? = null)

@Serializable data class ChallengeResponse(
    val challengeId: String,
    val challenge: String,
    val purpose: String? = null,
    val allowedMethods: List<String> = listOf("PIN"),
    val expiresAt: String? = null,
)

@Serializable data class VerifyRequest(
    val challengeId: String,
    val method: String,
    val deviceId: String? = null,
    val signature: String? = null,
    val pin: String? = null,
)

@Serializable data class VerifyResponse(
    val stepUpToken: String,
    val purpose: String? = null,
    val method: String? = null,
    val expiresAt: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

/** 앱을 켰을 때 갈 곳 */
sealed interface SessionStart {
    data object Welcome : SessionStart
    data object Login : SessionStart
    data object Unlock : SessionStart
}

data class LoginResult(val onboardingCompleted: Boolean)

data class StepUpChallenge(val challengeId: String, val challenge: ByteArray, val allowsBiometric: Boolean) {
    override fun equals(other: Any?) = other is StepUpChallenge && other.challengeId == challengeId
    override fun hashCode() = challengeId.hashCode()
}

data class StepUpToken(val token: String, val purpose: String?)

object StepUpPurpose {
    const val APP_UNLOCK = "APP_UNLOCK"
    const val BARCODE = "BARCODE"
    const val ACCOUNT_LINK = "ACCOUNT_LINK"
    const val CARD_REGISTER = "CARD_REGISTER"
    const val CARD_DELETE = "CARD_DELETE"
}
