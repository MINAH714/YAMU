package com.yamu.app.core.network

import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.SessionEnd
import com.yamu.app.core.security.SessionEndEvent
import com.yamu.app.core.security.SessionEndSignal
import com.yamu.app.core.security.TokenStore
import kotlinx.coroutines.flow.StateFlow
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

/**
 * 테스트용 토큰 저장소 — 메모리만. 저장 · 지우기 · 비교해서 지우기는 진짜처럼 한 잠금 안에서.
 * [afterAccessTokenRead]는 누가 [accessToken]을 읽은 바로 뒤에 한 번 불린다 — '확인한 직후 새 로그인이 저장됨'을 흉내 낸다
 */
class FakeTokenStore(access: String?, private var refresh: String?) : TokenStore {
    private val lock = Any()
    private var access: String? = access
    var afterAccessTokenRead: (() -> Unit)? = null

    override val accessToken: String?
        get() {
            val value = synchronized(lock) { access }
            afterAccessTokenRead?.also { afterAccessTokenRead = null }?.invoke()
            return value
        }
    override var tokenVersion: Long = 0
        private set

    /** 알린 '세션 끝남' 차례대로 */
    val events = mutableListOf<SessionEndEvent>()
    val ended: List<SessionEnd> get() = events.map { it.reason }
    val expiredCount: Int get() = events.size
    private val signal = SessionEndSignal()
    override val sessionEnd: StateFlow<SessionEndEvent?> = signal.pending

    override fun refreshTokenBlocking(): String? = synchronized(lock) { refresh }
    override suspend fun save(tokens: AuthTokens) = saveBlocking(tokens)
    override fun saveBlocking(tokens: AuthTokens) {
        synchronized(lock) {
            access = tokens.accessToken
            refresh = tokens.refreshToken
            tokenVersion++
        }
    }
    override suspend fun clear() = clearBlocking()
    override fun clearBlocking() = synchronized(lock) { clearLocked() }
    override fun clearIfCurrentBlocking(accessToken: String?): Long? = synchronized(lock) {
        if (access != accessToken) null else tokenVersion.also { clearLocked() }
    }
    override suspend fun clearIfVersion(tokenVersion: Long): Boolean = synchronized(lock) {
        (this.tokenVersion == tokenVersion).also { same -> if (same) clearLocked() }
    }
    private fun clearLocked() {
        access = null
        refresh = null
    }
    override fun notifySessionEnd(event: SessionEndEvent) {
        events += event
        signal.publish(event)
    }
    override fun consumeSessionEnd(event: SessionEndEvent) = signal.consume(event)
}
