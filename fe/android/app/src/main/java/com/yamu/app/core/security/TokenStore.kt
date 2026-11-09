package com.yamu.app.core.security

import kotlinx.coroutines.flow.SharedFlow

data class AuthTokens(val accessToken: String, val refreshToken: String)

/** 로그인이 끝난 이유 — 앱이 어디로 보낼지가 다르다 */
enum class SessionEnd {
    /** 재발급 거절 · 401 UNAUTHORIZED → 로그인 화면(기기는 남아 PIN으로 다시) */
    EXPIRED,

    /** 서버에서 이 기기가 해제됨(401 DEVICE_NOT_REGISTERED, 다른 폰에서 재등록 등) → 이 폰을 비우고 첫 화면 */
    DEVICE_REVOKED,
}

/**
 * 토큰 보관 — Access는 메모리, Refresh는 암호화해서 기기에(KeystoreTokenStore).
 * 토큰 재발급(TokenAuthenticator)은 OkHttp 스레드에서 동기로 부르므로 *Blocking 함수가 있다.
 */
interface TokenStore {
    val accessToken: String?

    /** 저장된 Refresh 토큰 — 메모리에 없으면 기기에서 읽는다(백그라운드 스레드에서만) */
    fun refreshTokenBlocking(): String?

    suspend fun save(tokens: AuthTokens)
    fun saveBlocking(tokens: AuthTokens)

    suspend fun clear()
    fun clearBlocking()

    /** 다시 로그인해야 할 때 이유와 함께 한 번씩 흘러나온다 → 앱이 로그인 화면이나 첫 화면으로 */
    val sessionExpired: SharedFlow<SessionEnd>
    fun notifySessionExpired(reason: SessionEnd = SessionEnd.EXPIRED)
}
