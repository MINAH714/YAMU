package com.yamu.app.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "yamu_session")

/** 로그인한 사람 · 이 기기 정보(토큰 말고) — 비밀이 아닌 값만 */
data class SessionInfo(
    val userId: Long?,
    val name: String?,
    val email: String?,
    val onboardingCompleted: Boolean,
    /** 서버가 정한 기기 번호(기기 등록 응답) — 로그인할 때 쓴다. 없으면 이 폰은 아직 등록 전 */
    val userDeviceId: Long?,
    /** 이 기기에 지문 키를 등록했나(지문 로그인 · 지문 본인 확인) */
    val biometricEnabled: Boolean,
) {
    val deviceRegistered: Boolean get() = userDeviceId != null
}

/**
 * 세션 정보 저장(DataStore). 토큰은 TokenStore(암호화)에 따로.
 * deviceId는 이 앱 설치에 하나(UUID) — 재설치하면 바뀐다(기기 등록 deviceId).
 * 로그아웃은 토큰만 지우고 여기 값은 남긴다 — 같은 폰이면 PIN으로 바로 다시 로그인.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val userId = longPreferencesKey("user_id")
        val name = stringPreferencesKey("name")
        val email = stringPreferencesKey("email")
        val onboarding = booleanPreferencesKey("onboarding_completed")
        val userDeviceId = longPreferencesKey("user_device_id")
        val biometric = booleanPreferencesKey("biometric_enabled")
        val legacyDevice = booleanPreferencesKey("device_registered")   // 예전 값(참/거짓) — 기기 번호로 바뀌어 지운다
        val deviceId = stringPreferencesKey("device_id")
        val autoLock = intPreferencesKey("auto_lock_seconds")
    }

    suspend fun read(): SessionInfo {
        val p = context.sessionDataStore.data.first()
        return SessionInfo(
            userId = p[Keys.userId],
            name = p[Keys.name],
            email = p[Keys.email],
            onboardingCompleted = p[Keys.onboarding] ?: false,
            userDeviceId = p[Keys.userDeviceId],
            biometricEnabled = p[Keys.biometric] ?: false,
        )
    }

    suspend fun saveUser(userId: Long?, name: String?, email: String?, onboardingCompleted: Boolean) {
        context.sessionDataStore.edit { p ->
            if (userId != null) p[Keys.userId] = userId
            if (name != null) p[Keys.name] = name
            if (email != null) p[Keys.email] = email
            p[Keys.onboarding] = onboardingCompleted
        }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.sessionDataStore.edit { it[Keys.onboarding] = done }
    }

    /** 기기 등록이 끝났을 때 */
    suspend fun saveDevice(userDeviceId: Long, biometricEnabled: Boolean) {
        context.sessionDataStore.edit { p ->
            p[Keys.userDeviceId] = userDeviceId
            p[Keys.biometric] = biometricEnabled
            p.remove(Keys.legacyDevice)
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.sessionDataStore.edit { it[Keys.biometric] = enabled }
    }

    suspend fun deviceId(): String {
        context.sessionDataStore.data.first()[Keys.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.sessionDataStore.edit { it[Keys.deviceId] = id }
        return id
    }

    /** 앱 자동 잠금(M-08) — 앱을 떠난 뒤 이 시간이 지나 돌아오면 잠금. 기본 60초(#41) */
    val autoLockSeconds: Flow<Int> = context.sessionDataStore.data.map { it[Keys.autoLock] ?: 60 }

    suspend fun setAutoLockSeconds(seconds: Int) {
        context.sessionDataStore.edit { it[Keys.autoLock] = seconds }
    }

    /** 이 폰을 처음 상태로(서버에서 기기가 없어짐 · 디버그 '처음 사용') — 설치 UUID와 자동 잠금 설정은 남긴다 */
    suspend fun clear() {
        context.sessionDataStore.edit { p ->
            p.remove(Keys.userId)
            p.remove(Keys.name)
            p.remove(Keys.email)
            p.remove(Keys.onboarding)
            p.remove(Keys.userDeviceId)
            p.remove(Keys.biometric)
            p.remove(Keys.legacyDevice)
        }
    }
}
