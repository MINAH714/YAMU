package com.yamu.app.feature.home.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈 '월급날을 알려 주면…' 줄 — BE 홈은 잔고예보 기간이 '다음 월급날까지'일 때만 nextIncome을 줘서,
 * 월급을 정해 두고 기간이 30일(DAYS)이면 월급이 없는 것처럼 읽혔다(LIFE QA 시드). 잔고예보 설정에 월급이 있으면 줄을 숨긴다
 */
class HomeIncomeHintTest {
    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()

    private val homeWithoutIncome = """{"success":true,"data":{
        "balanceForecast":{"accountId":3,"currentBalance":620000,"endBalancePredicted":2214500,"horizonDays":30},
        "unreadNotificationCount":0,"partialErrors":[]}}"""
    private val homeWithIncome = """{"success":true,"data":{
        "balanceForecast":{"accountId":3,"currentBalance":620000,"minBalance":312812,"nextIncome":{"date":"2026-11-25"},"horizonDays":8},
        "unreadNotificationCount":0,"partialErrors":[]}}"""

    private fun start(home: String, settings: MockResponse) {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath
                return when (request.url.encodedPath) {
                    "/home" -> json(200, home)
                    "/forecasts/settings" -> settings
                    else -> json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"없음"}}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private suspend fun load(): ForecastSummary =
        (HomeRepositoryImpl(server.retrofit().create(HomeApi::class.java)).loadHome() as ApiResult.Success).data.forecast!!

    @Test fun `월급을 정해 뒀는데 기간이 30일이라 nextIncome이 비면 안내 줄을 숨긴다`() = runTest {
        start(homeWithoutIncome, json(200, """{"success":true,"data":{"income":{"cashflowItemId":41,"title":"월급","amount":2500000,"dayOfMonth":25}}}"""))
        assertFalse(load().needsIncome)
    }

    @Test fun `월급을 정하지 않았으면 안내 줄을 그대로 둔다`() = runTest {
        start(homeWithoutIncome, json(200, """{"success":true,"data":{"income":null}}"""))
        assertTrue(load().needsIncome)
    }

    @Test fun `설정을 못 읽으면 전처럼 안내 줄을 둔다`() = runTest {
        start(homeWithoutIncome, json(500, """{"success":false,"data":null,"error":{"code":"INTERNAL_ERROR","message":"오류"}}"""))
        assertTrue(load().needsIncome)
    }

    @Test fun `홈이 월급날을 주면 설정을 더 부르지 않는다`() = runTest {
        start(homeWithIncome, json(200, """{"success":true,"data":{"income":null}}"""))
        assertFalse(load().needsIncome)
        assertEquals(listOf("/home"), paths)
    }
}
