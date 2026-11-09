package com.yamu.app.core.network

import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.SessionEnd
import com.yamu.app.core.security.SessionEndEvent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 401 TOKEN_EXPIRED → 재발급(#21) → 원래 요청 다시, 해제된 기기 · 거절된 토큰 → 세션 끝(07 문서 3-3) */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun authApi(): AuthApi = server.retrofit().create(AuthApi::class.java)

    private fun api(store: FakeTokenStore, authApi: AuthApi = authApi()): TestApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenAuthenticator(store, authApi))
            .build()
        return server.retrofit(client).create(TestApi::class.java)
    }

    /** 이미 날아간 요청이 늦게 401을 받은 상황 — 어떤 토큰을 실었는지 직접 정한다 */
    private fun unauthorized(code: String, bearer: String?): Response {
        val request = Request.Builder().url(server.url("/sample"))
            .apply { bearer?.let { header(YamuHeaders.AUTHORIZATION, "Bearer $it") } }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("""{"success":false,"data":null,"error":{"code":"$code","message":"m"}}""".toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Test
    fun `만료되면 재발급 뒤 새 토큰으로 다시 보낸다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "TOKEN_EXPIRED"))
        server.enqueue(json(200, """{"success":true,"data":{"accessToken":"new","refreshToken":"r2","tokenType":"Bearer","expiresIn":1800}}"""))
        server.enqueue(json(200, """{"success":true,"data":{"name":"야무"}}"""))

        val result = apiCall { api(store).sample() }

        assertEquals(ApiResult.Success(Sample("야무")), result)
        assertEquals("Bearer old", server.takeRequest().headers["Authorization"])
        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.url.encodedPath)
        assertNull(refresh.headers["Authorization"])                 // 재발급엔 인증 헤더 없음
        assertEquals("Bearer new", server.takeRequest().headers["Authorization"])
        assertEquals("new", store.accessToken)
        assertEquals("r2", store.refreshTokenBlocking())             // Refresh도 바뀜(rotation)
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `재발급이 거절되면 토큰을 지우고 세션 끝남을 알린다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "bad")
        server.enqueue(errorJson(401, "TOKEN_EXPIRED"))
        server.enqueue(errorJson(401, "INVALID_REFRESH_TOKEN"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error is ApiError.Unauthorized)
        assertNull(store.accessToken)
        assertNull(store.refreshTokenBlocking())
        assertEquals(listOf(SessionEnd.EXPIRED), store.ended)
    }

    @Test
    fun `재발급 응답을 못 받으면 로그인은 두고 이번 요청만 네트워크 오류로 끝낸다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        val closed = MockWebServer().apply { start() }
        val unreachable = closed.retrofit().create(AuthApi::class.java)
        closed.close()                                                // 재발급 서버에 닿지 않는다
        server.enqueue(errorJson(401, "TOKEN_EXPIRED"))

        val result = apiCall { api(store, unreachable).sample() }

        assertEquals(ApiResult.Failure(ApiError.Network), result)     // '다시 로그인'이 아니라 '인터넷 연결'
        assertEquals("old", store.accessToken)
        assertEquals("r1", store.refreshTokenBlocking())             // 다음 요청이 같은 Refresh로 다시 재발급(BE 유예 시간)
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `토큰을 실은 요청이 UNAUTHORIZED를 받으면 토큰을 지우고 로그인으로 보낸다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "UNAUTHORIZED"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error is ApiError.Unauthorized)
        assertEquals(1, server.requestCount)                         // 재발급하지 않는다
        assertNull(store.accessToken)
        assertNull(store.refreshTokenBlocking())
        assertEquals(listOf(SessionEnd.EXPIRED), store.ended)
    }

    @Test
    fun `해제된 기기면 토큰을 지우고 기기 해제를 알린다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "DEVICE_NOT_REGISTERED"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error.code == "DEVICE_NOT_REGISTERED")
        assertEquals(1, server.requestCount)
        assertNull(store.accessToken)
        assertNull(store.refreshTokenBlocking())
        assertEquals(listOf(SessionEnd.DEVICE_REVOKED), store.ended)
        assertEquals(SessionEndEvent(SessionEnd.DEVICE_REVOKED, tokenVersion = 0), store.sessionEnd.value)   // 받는 화면이 없어도 남는다
    }

    @Test
    fun `재발급한 토큰으로 다시 보낸 요청이 기기 해제를 받으면 세션을 끝낸다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "TOKEN_EXPIRED"))
        server.enqueue(json(200, """{"success":true,"data":{"accessToken":"new","refreshToken":"r2","tokenType":"Bearer","expiresIn":1800}}"""))
        server.enqueue(errorJson(401, "DEVICE_NOT_REGISTERED"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error.code == "DEVICE_NOT_REGISTERED")
        assertEquals(3, server.requestCount)
        assertNull(store.accessToken)                                // 재발급한 새 토큰도 지운다
        assertNull(store.refreshTokenBlocking())
        assertEquals(listOf(SessionEndEvent(SessionEnd.DEVICE_REVOKED, tokenVersion = 1)), store.events)
    }

    @Test
    fun `재발급한 토큰도 만료면 다시 재발급하지 않는다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "TOKEN_EXPIRED"))
        server.enqueue(json(200, """{"success":true,"data":{"accessToken":"new","refreshToken":"r2","tokenType":"Bearer","expiresIn":1800}}"""))
        server.enqueue(errorJson(401, "TOKEN_EXPIRED"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error.code == "TOKEN_EXPIRED")
        assertEquals(3, server.requestCount)
        assertEquals("new", store.accessToken)
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `늦은 401을 확인한 직후 새 로그인이 저장돼도 새 토큰을 지우지 않는다`() {
        listOf("UNAUTHORIZED", "DEVICE_NOT_REGISTERED").forEach { code ->
            val store = FakeTokenStore(access = "old", refresh = "r1")
            store.afterAccessTokenRead = { store.saveBlocking(AuthTokens("new", "r2")) }   // 누가 지금 토큰을 읽자마자 PIN 로그인이 끝남
            val authenticator = TokenAuthenticator(store, authApi())

            assertNull(authenticator.authenticate(null, unauthorized(code, bearer = "old")))
            store.accessToken                                        // 아직 안 불렸으면 여기서 로그인이 끝난다

            assertEquals(code, "new", store.accessToken)
            assertEquals(code, "r2", store.refreshTokenBlocking())
            assertTrue(code, store.events.all { it.tokenVersion < store.tokenVersion })   // 알림이 남았어도 지난 세션 것이라 화면이 무시한다
        }
    }

    @Test
    fun `재발급이 거절되기 전에 새 로그인이 저장됐으면 새 토큰을 지우지 않는다`() {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        store.afterAccessTokenRead = { store.saveBlocking(AuthTokens("new", "r2")) }
        val authenticator = TokenAuthenticator(store, authApi())
        server.enqueue(errorJson(401, "INVALID_REFRESH_TOKEN"))

        assertNull(authenticator.authenticate(null, unauthorized("TOKEN_EXPIRED", bearer = "old")))

        assertEquals("new", store.accessToken)
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `토큰 없이 보낸 요청의 UNAUTHORIZED는 로그인을 건드리지 않는다`() = runTest {
        val store = FakeTokenStore(access = null, refresh = "r1")
        server.enqueue(errorJson(401, "UNAUTHORIZED"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error is ApiError.Unauthorized)
        assertNull(server.takeRequest().headers["Authorization"])
        assertEquals("r1", store.refreshTokenBlocking())
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `세션을 끝내지 않는 401은 그대로 넘긴다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "PIN_MISMATCH"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error is ApiError.Unauthorized)
        assertEquals(1, server.requestCount)
        assertEquals("old", store.accessToken)
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `같은 토큰으로 보낸 요청 여럿이 401을 받아도 한 번만 알린다`() {
        listOf("UNAUTHORIZED" to SessionEnd.EXPIRED, "DEVICE_NOT_REGISTERED" to SessionEnd.DEVICE_REVOKED).forEach { (code, reason) ->
            val store = FakeTokenStore(access = "old", refresh = "r1")
            val authenticator = TokenAuthenticator(store, authApi())

            repeat(3) { assertNull(authenticator.authenticate(null, unauthorized(code, bearer = "old"))) }

            assertEquals(code, listOf(reason), store.ended)
        }
    }

    @Test
    fun `다시 로그인한 뒤 늦게 온 401은 새 로그인을 지우지 않는다`() {
        val store = FakeTokenStore(access = "new", refresh = "r2")
        val authenticator = TokenAuthenticator(store, authApi())

        assertNull(authenticator.authenticate(null, unauthorized("UNAUTHORIZED", bearer = "old")))
        assertNull(authenticator.authenticate(null, unauthorized("DEVICE_NOT_REGISTERED", bearer = "old")))

        assertEquals("new", store.accessToken)
        assertEquals("r2", store.refreshTokenBlocking())
        assertEquals(0, store.expiredCount)
    }

    @Test
    fun `재발급 거절이 여러 요청에 겹쳐도 한 번만 알린다`() {
        val store = FakeTokenStore(access = "old", refresh = "bad")
        val authenticator = TokenAuthenticator(store, authApi())
        server.enqueue(errorJson(401, "INVALID_REFRESH_TOKEN"))

        repeat(2) { assertNull(authenticator.authenticate(null, unauthorized("TOKEN_EXPIRED", bearer = "old"))) }

        assertEquals(1, server.requestCount)                         // 두 번째는 재발급하지 않는다
        assertEquals(listOf(SessionEnd.EXPIRED), store.ended)
    }
}
