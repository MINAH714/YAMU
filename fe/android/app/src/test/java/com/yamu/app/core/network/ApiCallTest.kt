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
