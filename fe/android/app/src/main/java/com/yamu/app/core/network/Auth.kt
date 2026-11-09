package com.yamu.app.core.network

import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.SessionEnd
import com.yamu.app.core.security.TokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException

/** #21 토큰 재발급 — 인증 헤더 없는 별도 클라이언트로 부른다(재발급이 재발급을 부르지 않게) */
interface AuthApi {
    @POST("auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<ApiEnvelope<TokenResponse>>
}

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0,
)

/** 저장된 Access 토큰을 Authorization 헤더로 붙인다 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.accessToken ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().withBearer(token))
    }
}

/**
 * 401을 코드별로 처리한다(07 문서 3-3).
 * - TOKEN_EXPIRED: #21로 재발급 → 원래 요청 다시. 한 번에 한 요청만 재발급하고, 다른 요청이 이미 새 토큰을 받았으면 그 토큰을 쓴다.
 *   재발급이 서버에서 거절되면 토큰을 지우고 '세션 끝남'을 알린다. 재발급 응답을 못 받으면 로그인은 두고 이번 요청만 네트워크 오류로 끝낸다
 *   (BE는 직전 Refresh 토큰이 유예 시간 안에 다시 오면 재사용으로 보지 않는다 — -352에서 60초).
 * - DEVICE_NOT_REGISTERED: 다른 폰에서 재등록해 이 기기가 서버에서 해제됐다. 토큰을 지우고 '기기 해제'를 알린다(세션 · 기기 키는 앱이 받아서 지운다).
 * - UNAUTHORIZED: 토큰을 실었는데 거절됐다(탈퇴, -351 전 BE의 해제된 기기). 토큰을 지우고 '세션 끝남'을 알린다.
 * 끝내는 일은 실패한 요청의 토큰이 아직 지금 토큰일 때만 한다. 여러 요청이 함께 401을 받아도 한 번만 알리고,
 * 로그아웃이나 다시 로그인한 뒤 늦게 도착한 401은 이번 요청만 실패로 끝난다.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorCount() >= 1) return null                     // 같은 요청이 또 401 → 그만
        val failedToken = response.request.header(YamuHeaders.AUTHORIZATION)?.removePrefix("Bearer ")
        return when (response.errorCode()) {
            "TOKEN_EXPIRED" -> refresh(response, failedToken)
            "DEVICE_NOT_REGISTERED" -> endSession(failedToken, SessionEnd.DEVICE_REVOKED)
            "UNAUTHORIZED" -> endSession(failedToken, SessionEnd.EXPIRED)
            else -> null
        }
    }

    private fun refresh(response: Response, failedToken: String?): Request? {
        synchronized(lock) { return refreshLocked(response, failedToken) }
    }

    private fun refreshLocked(response: Response, failedToken: String?): Request? {
        val current = tokenStore.accessToken
        if (current != null && current != failedToken) return response.request.withBearer(current)
        if (current == null && failedToken != null) return null         // 다른 요청이 이미 세션을 끝냈거나 로그아웃했다

        val refresh = tokenStore.refreshTokenBlocking() ?: return expire(SessionEnd.EXPIRED)
        val result = try {
            authApi.refresh(RefreshRequest(refresh)).execute()
        } catch (e: Exception) {
            // 응답이 끊겼거나 읽지 못했다 — 서버가 이미 바꿨을 수 있어 로그인은 둔다. 원래 401을 넘기면 화면이 '다시 로그인'으로 보여서
            // 네트워크 오류로 끝낸다(IOException만 던진다. 다른 예외는 OkHttp 비동기 호출에서 앱을 멈춘다)
            response.close()
            throw IOException("토큰 재발급 응답을 받지 못함", e)
        }
        val tokens = result.body()?.takeIf { result.isSuccessful && it.success }?.data
            ?: return expire(SessionEnd.EXPIRED)                        // 재발급 거절(INVALID_REFRESH_TOKEN 등)
        tokenStore.saveBlocking(AuthTokens(tokens.accessToken, tokens.refreshToken))
        return response.request.withBearer(tokens.accessToken)
    }

    private fun endSession(failedToken: String?, reason: SessionEnd): Request? {
        if (failedToken == null) return null                            // 토큰 없이 보낸 요청 — 끝낼 로그인이 없다
        synchronized(lock) {
            if (tokenStore.accessToken != failedToken) return null
            return expire(reason)
        }
    }

    private fun expire(reason: SessionEnd): Request? {
        tokenStore.clearBlocking()
        tokenStore.notifySessionExpired(reason)
        return null
    }
}

private fun Request.withBearer(token: String): Request =
    newBuilder().header(YamuHeaders.AUTHORIZATION, "Bearer $token").build()

private fun Response.priorCount(): Int {
    var count = 0
    var prior = priorResponse
    while (prior != null) {
        count++
        prior = prior.priorResponse
    }
    return count
}

private fun Response.errorCode(): String? = runCatching {
    YamuJson.decodeFromString<ApiEnvelope<JsonElement>>(peekBody(16_384).string()).error?.code
}.getOrNull()
