package com.yamu.app.core.network

import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.TokenStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

@Serializable
data class Sample(val name: String)

interface TestApi {
    @GET("sample")
    suspend fun sample(): Response<ApiEnvelope<Sample>>
}

fun MockWebServer.retrofit(client: OkHttpClient = OkHttpClient()): Retrofit = Retrofit.Builder()
    .baseUrl(url("/"))
    .client(client)
    .addConverterFactory(YamuJson.asConverterFactory("application/json".toMediaType()))
    .build()

fun json(code: Int, body: String): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json")
    .body(body)
    .build()

fun errorJson(code: Int, errorCode: String): MockResponse =
    json(code, """{"success":false,"data":null,"error":{"code":"$errorCode","message":"m"}}""")

/** 테스트용 토큰 저장소 — 메모리만 */
class FakeTokenStore(access: String?, private var refresh: String?) : TokenStore {
    override var accessToken: String? = access
        private set
    var expiredCount = 0
        private set
    private val expired = MutableSharedFlow<Unit>(replay = 1)
    override val sessionExpired: SharedFlow<Unit> = expired

    override fun refreshTokenBlocking(): String? = refresh
    override suspend fun save(tokens: AuthTokens) = saveBlocking(tokens)
    override fun saveBlocking(tokens: AuthTokens) {
        accessToken = tokens.accessToken
        refresh = tokens.refreshToken
    }
    override suspend fun clear() = clearBlocking()
    override fun clearBlocking() {
        accessToken = null
        refresh = null
    }
    override fun notifySessionExpired() {
        expiredCount++
        expired.tryEmit(Unit)
    }
}
