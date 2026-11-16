package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.link.data.CashflowApi
import com.yamu.app.feature.link.data.LinkApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 가상 구매는 연결 계좌 전체 · 오늘부터 30일로 한 번 부른다(S15P31E103-589) — 잔고예보에서 고른 계좌 · 기간과 상관없다.
 * 카드 출금 계좌 하나만 보면 모자라는지는 계좌별 전후(accountComparisons)로 본다.
 * 전에는(-548) 카드 출금 계좌 하나로, 추천 카드는 응답 뒤 한 번 더 불렀다
 */
class SimulationAccountsTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ForecastRepositoryImpl
    private val sent = CopyOnWriteArrayList<JsonObject>()

    /** 기준 계좌 1(카드 11) · 계좌 2(카드 22). 추천하면 카드 22. 계좌 2는 사고 나면 −40,000원 */
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
                    sent += body
                    val card = body["userCardId"]?.jsonPrimitive?.long ?: 22L   // null은 보내지 않는다(explicitNulls = false)
                    json(
                        200,
                        """{"success":true,"data":{"card":{"userCardId":$card,"nickname":"노리","cardType":"CHECK","recommended":${body["userCardId"] == null}},
                            "comparison":{"before":{"minBalance":500000,"minBalanceDate":"2026-11-17","status":"SAFE"},"after":{"minBalance":260000,"minBalanceDate":"2026-11-17","status":"SAFE"}},
                            "accountComparisons":[
                              {"accountId":1,"before":{"minBalance":300000,"minBalanceDate":"2026-11-17","status":"SAFE"},"after":{"minBalance":300000,"minBalanceDate":"2026-11-17","status":"SAFE"}},
                              {"accountId":2,"before":{"minBalance":200000,"minBalanceDate":"2026-11-17","status":"SAFE"},"after":{"minBalance":-40000,"minBalanceDate":"2026-11-17","status":"DANGER"}}]}}""",
                    )
                }
                else -> json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"없음"}}""")
            }
        }
        server.start()
        val retrofit = server.retrofit()
        repository = ForecastRepositoryImpl(retrofit.create(ForecastApi::class.java), retrofit.create(LinkApi::class.java), retrofit.create(CashflowApi::class.java))
    }

    @After fun tearDown() = server.close()

    private fun query(card: Long?) = SimulationQuery(240_000, null, "HYPERMARKET", "대형마트", null, null, PaymentChannel.OFFLINE, card, null, LocalDate.now())

    private fun JsonObject.accountIds() = getValue("accountIds").jsonArray.map { it.jsonPrimitive.long }

    @Test fun `카드를 고르면 연결 계좌 전체 · 30일로 한 번 — 잔고예보에서 고른 계좌 · 기간과 상관없이`() = runTest {
        repository.select(ForecastSelection(period = ForecastPeriod.NEXT_INCOME, accountIds = setOf(1)))
        val sim = (repository.simulate(query(22)) as ApiResult.Success).data
        assertEquals(listOf(listOf(1L, 2L)), sent.map { it.accountIds() })
        assertEquals(JsonPrimitive("DAYS"), sent.single()["until"])
        assertEquals(JsonPrimitive(30), sent.single()["days"])
        assertEquals(JsonPrimitive(true), sent.single()["includeAccountDetails"])
        assertEquals(listOf(1L, 2L), sim.accounts.map { it.accountId })
    }

    @Test fun `추천 카드도 한 번만 부르고, 그 카드의 출금 계좌가 모자라면 어느 계좌에 얼마`() = runTest {
        val sim = (repository.simulate(query(null)) as ApiResult.Success).data
        assertEquals(1, sent.size)   // 전에는 추천 카드 계좌로 한 번 더 불렀다
        assertEquals(true, sim.card.recommendedTop)
        assertEquals("이 카드로 결제하면 신한은행 ··5678 계좌에 40,000원이 부족해요", sim.cardAccountShortfall!!.text)
        assertEquals("계좌 2개 합", sim.accountsLabel)
    }

    @Test fun `카드 출금 계좌가 남으면 안내하지 않는다`() = runTest {
        val sim = (repository.simulate(query(11)) as ApiResult.Success).data
        assertEquals(1L, sim.cardAccount!!.accountId)
        assertNull(sim.cardAccountShortfall)
    }
}
