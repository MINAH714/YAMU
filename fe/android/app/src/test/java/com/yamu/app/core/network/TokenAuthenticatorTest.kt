package com.yamu.app.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 401 TOKEN_EXPIRED → 재발급(#21) → 원래 요청 다시(07 문서 3-3) */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api(store: FakeTokenStore): TestApi {
        val authApi = server.retrofit().create(AuthApi::class.java)
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenAuthenticator(store, authApi))
            .build()
        return server.retrofit(client).create(TestApi::class.java)
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
        assertEquals(1, store.expiredCount)
    }

    @Test
    fun `TOKEN_EXPIRED가 아닌 401은 재발급하지 않는다`() = runTest {
        val store = FakeTokenStore(access = "old", refresh = "r1")
        server.enqueue(errorJson(401, "UNAUTHORIZED"))

        val result = apiCall { api(store).sample() }

        assertTrue(result is ApiResult.Failure && result.error is ApiError.Unauthorized)
        assertEquals(1, server.requestCount)
        assertEquals("old", store.accessToken)
    }
}
