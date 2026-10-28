package com.yamu.app.core.security

import kotlinx.coroutines.flow.SharedFlow

data class AuthTokens(val accessToken: String, val refreshToken: String)

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

    /** 재발급도 실패해 다시 로그인해야 할 때 한 번씩 흘러나온다 → 앱이 로그인 화면으로 */
    val sessionExpired: SharedFlow<Unit>
    fun notifySessionExpired()
}
