package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.link.data.CashflowApi
import com.yamu.app.feature.link.data.LinkApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 잔고예보 마지막 값 — 지금 선택으로 받은 것만 [ForecastRepository.cached]로 준다(!319 리뷰, S15P31E103-484) */
class ForecastRepositoryCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ForecastRepositoryImpl
    @Volatile private var balance = 100_000L

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/forecasts/balance" -> json(200, """{"success":true,"data":{"accountIds":[1],"currentTotalBalance":$balance}}""")
                "/accounts" -> json(
                    200,
                    """{"success":true,"data":{"accounts":[
                        {"accountId":1,"bank":{"code":"004","name":"KB국민은행"},"maskedAccountNo":"****-1234","balance":300000,"isPrimary":true},
                        {"accountId":2,"bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-5678","balance":200000,"isPrimary":false}]}}""",
                )
                else -> json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"없음"}}""")
            }
        }
        server.start()
        val retrofit = server.retrofit()
        repository = ForecastRepositoryImpl(retrofit.create(ForecastApi::class.java), retrofit.create(LinkApi::class.java), retrofit.create(CashflowApi::class.java))
    }

    @After fun tearDown() = server.close()

    @Test fun `선택을 바꾸면 앞 선택의 예보를 마지막 값으로 주지 않는다 — 다시 받으면 그 값`() = runTest {
        repository.select(ForecastSelection(all = true))
        repository.forecast()
        assertEquals(100_000L, repository.cached()?.currentBalance)

        repository.select(ForecastSelection(ForecastPeriod.DAYS_30, setOf(1L)))   // 홈 '잔고예보' — 30일 · 기준 계좌
        assertNull("새 칩 밑에 전체 계좌 숫자를 두지 않는다", repository.cached())

        balance = 70_000
        repository.forecast()
        assertEquals(70_000L, repository.cached()?.currentBalance)

        repository.select(ForecastSelection(ForecastPeriod.DAYS_30, setOf(1L)))   // 같은 선택이면 그대로
        assertEquals(70_000L, repository.cached()?.currentBalance)
    }
}
