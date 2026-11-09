package com.yamu.app.core.network

import com.yamu.app.core.security.AuthTokens
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
 * 401 TOKEN_EXPIRED → #21로 재발급 → 원래 요청 다시(07 문서 3-3).
 * 한 번에 한 요청만 재발급하고, 다른 요청이 이미 새 토큰을 받았으면 그 토큰을 쓴다.
 * 재발급이 서버에서 거절되면 토큰을 지우고 '세션 끝남'을 알린다.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorCount() >= 1) return null                     // 같은 요청이 또 401 → 그만
        if (response.errorCode() != "TOKEN_EXPIRED") return null

        val failedToken = response.request.header(YamuHeaders.AUTHORIZATION)?.removePrefix("Bearer ")
        synchronized(lock) {
            val current = tokenStore.accessToken
            if (current != null && current != failedToken) return response.request.withBearer(current)

            val refresh = tokenStore.refreshTokenBlocking() ?: return expire()
            val result = runCatching { authApi.refresh(RefreshRequest(refresh)).execute() }.getOrNull()
                ?: return null                                          // 네트워크 문제 — 로그인은 유지, 이번 요청만 실패
            val tokens = result.body()?.takeIf { result.isSuccessful && it.success }?.data
                ?: return expire()                                      // 재발급 거절(INVALID_REFRESH_TOKEN 등)
            tokenStore.saveBlocking(AuthTokens(tokens.accessToken, tokens.refreshToken))
            return response.request.withBearer(tokens.accessToken)
        }
    }

    private fun expire(): Request? {
        tokenStore.clearBlocking()
        tokenStore.notifySessionExpired()
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
