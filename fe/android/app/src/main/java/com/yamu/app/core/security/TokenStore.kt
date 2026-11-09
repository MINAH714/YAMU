package com.yamu.app.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AuthTokens(val accessToken: String, val refreshToken: String)

/** 로그인이 끝난 이유 — 앱이 어디로 보낼지가 다르다 */
enum class SessionEnd {
    /** 재발급 거절 · 401 UNAUTHORIZED → 로그인 화면(기기는 남아 PIN으로 다시) */
    EXPIRED,

    /** 서버에서 이 기기가 해제됨(401 DEVICE_NOT_REGISTERED, 다른 폰에서 재등록 등) → 이 폰을 비우고 첫 화면 */
    DEVICE_REVOKED,
}

/** 세션이 끝났다는 알림. [tokenVersion]은 끝난 세션의 토큰 번호 — 그 뒤 새로 저장(로그인)했으면 지난 알림이다 */
data class SessionEndEvent(val reason: SessionEnd, val tokenVersion: Long)

/**
 * 토큰 보관 — Access는 메모리, Refresh는 암호화해서 기기에(KeystoreTokenStore).
 * 토큰 재발급(TokenAuthenticator)은 OkHttp 스레드에서 동기로 부르므로 *Blocking 함수가 있다.
 */
interface TokenStore {
    val accessToken: String?

    /** 토큰을 저장할 때마다 1씩 는다 — 늦게 처리된 '세션 끝남'이 그사이 새로 로그인한 세션을 건드리지 않게 */
    val tokenVersion: Long

    /** 저장된 Refresh 토큰 — 메모리에 없으면 기기에서 읽는다(백그라운드 스레드에서만) */
    fun refreshTokenBlocking(): String?

    suspend fun save(tokens: AuthTokens)
    fun saveBlocking(tokens: AuthTokens)

    suspend fun clear()
    fun clearBlocking()

    /**
     * [accessToken]이 아직 지금 Access 토큰이면 지우고 그 토큰 번호를 돌려준다. 그사이 다시 로그인 · 로그아웃했으면 null.
     * 저장과 같은 잠금 안에서 비교하고 지운다(비교한 뒤 지우기 전에 새 로그인이 저장되는 틈이 없게)
     */
    fun clearIfCurrentBlocking(accessToken: String?): Long?

    /** [tokenVersion] 뒤로 새로 저장한 적이 없으면 지우고 true(이미 비어 있어도 true). 저장과 같은 잠금 안에서 */
    suspend fun clearIfVersion(tokenVersion: Long): Boolean

    /** 아직 처리하지 않은 '세션 끝남' — 화면이 처리하고 [consumeSessionEnd]할 때까지 남는다(화면을 다시 만드는 중에 생겨도 놓치지 않게) */
    val sessionEnd: StateFlow<SessionEndEvent?>
    fun notifySessionEnd(event: SessionEndEvent)
    fun consumeSessionEnd(event: SessionEndEvent)
}

/**
 * '세션 끝남'을 처리할 때까지 들고 있는다. 받을 화면이 없을 때(화면 회전 · 다시 만들기) 생겨도 다음 수집기가 받는다.
 * 같은 세션에서 기기 해제와 세션 끝남이 겹치면 기기 해제를 남긴다(첫 화면으로 가야 하므로)
 */
class SessionEndSignal {
    private val state = MutableStateFlow<SessionEndEvent?>(null)
    val pending: StateFlow<SessionEndEvent?> = state.asStateFlow()

    fun publish(event: SessionEndEvent) = state.update { current ->
        val keepRevoked = current?.reason == SessionEnd.DEVICE_REVOKED && current.tokenVersion == event.tokenVersion
        if (keepRevoked) current else event
    }

    /** 처리한 알림만 지운다 — 처리하는 사이 새 알림이 왔으면 그건 남긴다 */
    fun consume(event: SessionEndEvent) {
        state.compareAndSet(event, null)
    }
}

/**
 * 화면이 '세션 끝남'을 처리하기 전에 부른다. 지난 알림(그 뒤 새로 로그인)이면 false라 아무것도 하지 않는다.
 * 기기 해제면 저장과 같은 잠금 안에서 토큰 번호를 다시 확인하고 지운 뒤 [forgetDevice]로 세션 · 기기 키까지 비운다
 */
suspend fun TokenStore.settleSessionEnd(event: SessionEndEvent, forgetDevice: suspend () -> Unit): Boolean = when (event.reason) {
    SessionEnd.EXPIRED -> event.tokenVersion == tokenVersion
    SessionEnd.DEVICE_REVOKED -> clearIfVersion(event.tokenVersion).also { same -> if (same) forgetDevice() }
}
