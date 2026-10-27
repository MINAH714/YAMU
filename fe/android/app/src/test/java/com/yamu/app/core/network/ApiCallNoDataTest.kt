package com.yamu.app.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.POST

private interface NoDataApi {
    @POST("auth/pin")
    suspend fun setPin(): Response<ApiEnvelope<JsonElement>>
}

/** data 없는 응답(204) · 오류 details 꺼내기 */
class ApiCallNoDataTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NoDataApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = server.retrofit().create(NoDataApi::class.java)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `204 No Content는 성공`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(ApiResult.Success(Unit), apiCallNoData { api.setPin() })
    }

    @Test
    fun `success true에 data null도 성공`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":null,"error":null}"""))
        assertEquals(ApiResult.Success(Unit), apiCallNoData { api.setPin() })
    }

    @Test
    fun `오류 details에서 남은 횟수와 잠금 시각을 꺼낸다`() = runTest {
        server.enqueue(json(401, """{"success":false,"error":{"code":"STEP_UP_FAILED","details":{"remainingAttempts":2}}}"""))
        server.enqueue(json(423, """{"success":false,"error":{"code":"DEVICE_LOCKED","details":{"unlockAt":"2026-10-22T15:00:00+09:00"}}}"""))

        val failed = (apiCallNoData { api.setPin() } as ApiResult.Failure).error
        assertTrue(failed is ApiError.Unauthorized)
        assertEquals("STEP_UP_FAILED", failed.code)
        assertEquals(2, failed.detailInt("remainingAttempts"))

        val locked = (apiCallNoData { api.setPin() } as ApiResult.Failure).error
        assertTrue(locked is ApiError.Locked)
        assertEquals("2026-10-22T15:00:00+09:00", locked.detailString("unlockAt"))
    }
}
