package com.yamu.app.feature.auth.data

import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 기기 등록(POST /auth/devices) 요청 만들기 · 결과 저장 — 가입 · 기기 재등록(가입 진행 토큰)과 지문 켜기 · 끄기(본인 확인)가 같이 쓴다.
 * 기기 키는 있으면 그대로(없으면 만든다), 지문 키는 켤 때마다 새로 만든다.
 */
@Singleton
class DeviceRegistration @Inject constructor(
    private val deviceKeys: DeviceKeyManager,
    private val sessionStore: SessionStore,
    private val tokenStore: TokenStore,
) {
    /** 키를 못 만들면 null. 재등록이면 1원 결과([verificationToken] · [authCode])를 같이 보낸다 */
    suspend fun request(biometric: Boolean, verificationToken: String? = null, authCode: String? = null): DeviceRequest? = runCatching {
        DeviceRequest(
            deviceId = sessionStore.deviceId(),
            deviceName = deviceKeys.deviceName(),
            platform = "ANDROID",
            devicePublicKey = deviceKeys.devicePublicKey(),
            biometricPublicKey = if (biometric) deviceKeys.createBiometricKey() else null,
            biometricTypes = if (biometric) deviceKeys.biometricTypes() else null,
            verificationToken = verificationToken,
            authCode = authCode,
        )
    }.getOrNull()

    /** 기기 번호 · 지문 여부를 저장하고, 토큰이 왔으면(가입 · 재등록) 토큰도 */
    suspend fun saved(response: DeviceResponse) {
        sessionStore.saveDevice(response.userDeviceId, response.biometricEnabled)
        if (!response.biometricEnabled) deviceKeys.deleteBiometricKey()
        val access = response.accessToken
        val refresh = response.refreshToken
        if (access != null && refresh != null) tokenStore.save(AuthTokens(access, refresh))
    }

    /** 서버에 안 올라간 지문 키는 버린다 */
    fun failed(biometric: Boolean) {
        if (biometric) deviceKeys.deleteBiometricKey()
    }
}
