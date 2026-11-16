package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.link.data.CashflowApi
import com.yamu.app.feature.link.data.LinkApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 가상 구매는 카드의 출금 계좌 하나로 계산한다(S15P31E103-548). 체크카드가 잔고예보에서 고른 계좌 밖에 묶여 있으면
 * BE가 전후를 같게 두어, 2,000원을 넣어도 사기 전 · 후가 같게 보였다(11/16 에뮬레이터)
 */
class SimulationAccountsTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ForecastRepositoryImpl
    private val sent = CopyOnWriteArrayList<List<Long>>()

    /** 기준 계좌 1(카드 11) · 계좌 2(카드 22). 추천하면 카드 22 */
    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/accounts" -> json(
                    200,
                    """{"success":true,"data":{"accounts":[
                        {"accountId":1,"bank":{"code":"004","name":"KB국민은행"},"maskedAccountNo":"****-1234","balance":300000,"isPrimary":true,"linkedCards":[{"userCardId":11}]},
                        {"accountId":2,"bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-5678","balance":200000,"isPrimary":false,"linkedCards":[{"userCardId":22}]}]}}""",
                )
                "/forecasts/simulations" -> {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    sent += body.getValue("accountIds").jsonArray.map { it.jsonPrimitive.long }
                    val card = body["userCardId"]?.jsonPrimitive?.long ?: 22L   // null은 보내지 않는다(explicitNulls = false)
                    json(200, """{"success":true,"data":{"card":{"userCardId":$card,"nickname":"노리","cardType":"CHECK","recommended":${body["userCardId"] == null}}}}""")
                }
                else -> json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"없음"}}""")
            }
        }
        server.start()
        val retrofit = server.retrofit()
        repository = ForecastRepositoryImpl(retrofit.create(ForecastApi::class.java), retrofit.create(LinkApi::class.java), retrofit.create(CashflowApi::class.java))
    }

    @After fun tearDown() = server.close()

    private fun query(card: Long?) = SimulationQuery(2_000, null, "HYPERMARKET", "대형마트", null, null, PaymentChannel.OFFLINE, card, null, LocalDate.now())

    @Test fun `카드를 고르면 그 카드의 출금 계좌로만 부른다 — 잔고예보에서 고른 계좌와 상관없이`() = runTest {
        repository.select(ForecastSelection(accountIds = setOf(1)))
        val sim = (repository.simulate(query(22)) as ApiResult.Success).data
        assertEquals(listOf(listOf(2L)), sent)
        assertEquals(listOf(2L), sim.accounts.map { it.accountId })
    }

    @Test fun `추천 카드는 잔고예보 선택으로 먼저 부르고, 카드 계좌가 다르면 그 계좌로 한 번 더`() = runTest {
        val sim = (repository.simulate(query(null)) as ApiResult.Success).data
        assertEquals(listOf(listOf(1L, 2L), listOf(2L)), sent)   // 기본은 전체 계좌 → 추천 카드 22의 계좌 2
        assertEquals(listOf(2L), sim.accounts.map { it.accountId })
        assertEquals(true, sim.card.recommendedTop)
    }

    @Test fun `추천 카드 계좌가 처음 보낸 계좌와 같으면 다시 부르지 않는다`() = runTest {
        repository.select(ForecastSelection(accountIds = setOf(2)))
        repository.simulate(query(null))
        assertEquals(listOf(listOf(2L)), sent)
    }
}
