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

/** 로그인한 사람 정보(토큰 말고) — 비밀이 아닌 값만 */
data class SessionInfo(
    val userId: Long?,
    val name: String?,
    val email: String?,
    val onboardingCompleted: Boolean,
    val deviceRegistered: Boolean,
)

/**
 * 세션 정보 저장(DataStore). 토큰은 TokenStore(암호화)에 따로.
 * deviceId는 이 앱 설치에 하나(UUID) — 재설치하면 바뀐다(API #15).
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
        val device = booleanPreferencesKey("device_registered")
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
            deviceRegistered = p[Keys.device] ?: false,
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

    suspend fun setDeviceRegistered(registered: Boolean) {
        context.sessionDataStore.edit { it[Keys.device] = registered }
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

    /** 로그아웃 · 세션 끝 — 기기 ID는 남긴다(같은 기기) */
    suspend fun clear() {
        context.sessionDataStore.edit { p ->
            p.remove(Keys.userId)
            p.remove(Keys.name)
            p.remove(Keys.email)
            p.remove(Keys.onboarding)
            p.remove(Keys.device)
        }
    }
}
