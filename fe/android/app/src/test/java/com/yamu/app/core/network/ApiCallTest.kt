package com.yamu.app.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 응답 { success, data, error } → ApiResult 바꾸기(07 문서 3-2) */
class ApiCallTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TestApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = server.retrofit().create(TestApi::class.java)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `성공 응답은 data를 돌려준다 - 모르는 필드는 무시`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"name":"야무","newField":1},"error":null,"extra":true}"""))
        assertEquals(ApiResult.Success(Sample("야무")), apiCall { api.sample() })
    }

    @Test
    fun `본인 인증 필요는 StepUpRequired`() = runTest {
        server.enqueue(errorJson(403, "STEP_UP_REQUIRED"))
        val result = apiCall { api.sample() }
        assertTrue(result is ApiResult.Failure && result.error is ApiError.StepUpRequired)
    }

    @Test
    fun `403 기기 잠금은 Locked - 본인 확인 필요와 나눈다`() = runTest {
        server.enqueue(json(403, """{"success":false,"data":null,"error":{"code":"DEVICE_LOCKED","message":"m","details":{"lockedUntil":"2026-11-09T03:30:00Z"}}}"""))
        val error = (apiCall { api.sample() } as ApiResult.Failure).error
        assertTrue(error is ApiError.Locked)
        assertEquals("2026-11-09T03:30:00Z", error.detailString("lockedUntil"))
        assertTrue(ApiError.from(403, "STEP_UP_FAILED") is ApiError.StepUpRequired)
    }

    @Test
    fun `서버에 없는 API는 NotSupported - 데이터가 없는 404와 나눈다`() = runTest {
        server.enqueue(errorJson(404, "API_NOT_FOUND"))
        server.enqueue(errorJson(405, "METHOD_NOT_ALLOWED"))
        server.enqueue(errorJson(404, "RESOURCE_NOT_FOUND"))

        assertEquals(ApiResult.Failure(ApiError.NotSupported("API_NOT_FOUND")), apiCall { api.sample() })
        assertEquals(ApiResult.Failure(ApiError.NotSupported("METHOD_NOT_ALLOWED")), apiCall { api.sample() })
        assertEquals(ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND")), apiCall { api.sample() })
    }

    @Test
    fun `409는 코드와 상관없이 Conflict로 두고 코드를 그대로 넘긴다`() = runTest {
        server.enqueue(errorJson(409, "CARD_HAS_OPEN_BILL"))
        assertEquals(ApiResult.Failure(ApiError.Conflict("CARD_HAS_OPEN_BILL")), apiCall { api.sample() })
    }

    @Test
    fun `금융망 오류 502 · 504는 BankUnavailable`() = runTest {
        server.enqueue(errorJson(502, "FIN_API_UNAVAILABLE"))
        server.enqueue(errorJson(504, "UPSTREAM_TIMEOUT"))
        repeat(2) {
            val result = apiCall { api.sample() }
            assertTrue(result is ApiResult.Failure && result.error is ApiError.BankUnavailable)
        }
    }

    @Test
    fun `데이터 부족 422는 DataInsufficient`() = runTest {
        server.enqueue(errorJson(422, "DATA_INSUFFICIENT"))
        val result = apiCall { api.sample() }
        assertTrue(result is ApiResult.Failure && result.error is ApiError.DataInsufficient)
    }

    @Test
    fun `본문 없는 500은 Server`() = runTest {
        server.enqueue(json(500, ""))
        val result = apiCall { api.sample() }
        assertTrue(result is ApiResult.Failure && result.error is ApiError.Server)
    }

    @Test
    fun `연결이 끊기면 Network`() = runTest {
        server.close()
        assertEquals(ApiResult.Failure(ApiError.Network), apiCall { api.sample() })
    }
}
