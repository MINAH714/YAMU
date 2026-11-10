package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.errorJson
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.link.CashflowConfirmViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * B-06 미리 채울 값 · 저장 — 후보 · 설정 · 계좌 → 생활비 상세를 한꺼번에 부르므로 순서 대신 경로마다 응답을 정해 둔다.
 * 응답 모양은 BE CashflowItemResponse · ForecastSettingsResponse · AccountController · LivingCostForecast와 같다
 */
class CashflowRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: CashflowRepositoryImpl
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val responses = ConcurrentHashMap<String, MockResponse>()   // "GET /path" → 응답

    /** 켜면 생활비 상세는 [release]까지 답하지 않고, 설정 조회는 생활비 상세가 서버에 닿은 뒤에 답한다(이미 시작한 호출) */
    @Volatile private var slowLivingCost = false
    private val livingCostArrived = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (slowLivingCost && path == "/forecasts/living-cost") {
                    livingCostArrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                if (slowLivingCost && path == "/forecasts/settings" && request.method == "GET") livingCostArrived.await(5, TimeUnit.SECONDS)
                return responses["${request.method} $path"] ?: errorJson(404, "API_NOT_FOUND")
            }
        }
        server.start()
        val retrofit = server.retrofit()
        repository = CashflowRepositoryImpl(retrofit.create(CashflowApi::class.java), retrofit.create(LinkApi::class.java))
        respond("/cashflow-items", ok(ITEMS))
        respond("/forecasts/settings", ok(settings()))
        respond("/accounts", ok(accounts(1, 2)))
        respond("/forecasts/living-cost", ok(LIVING_COST))
    }

    @After fun tearDown() {
        release.countDown()
        server.close()
    }

    private fun respond(path: String, response: MockResponse, method: String = "GET") {
        responses["$method $path"] = response
    }

    private fun ok(data: String) = json(200, """{"success":true,"data":$data}""")

    /** BE ForecastSettingsResponse — 기본값은 NEXT_INCOME · 30일 · 월급 · 하루 목표 없음 */
    private fun settings(incomeId: Long? = null, target: Long? = null) =
        """{"horizon":"NEXT_INCOME","defaultDays":30,"alertThreshold":100000,
            "income":${incomeId?.let { """{"cashflowItemId":$it,"title":"월급","dayOfMonth":25}""" } ?: "null"},
            "dailyLivingCostTarget":${target ?: "null"},"includeCandidates":true,"conservativeLine":true,"updatedAt":null}"""

    private fun accounts(vararg ids: Long) = ids.joinToString(prefix = """{"accounts":[""", postfix = "]}") { id ->
        """{"accountId":$id,"bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-${1000 + id}","balance":500000,"isPrimary":${id == 1L}}"""
    }

    private fun livingCostRequests() = requests.filter { it.url.encodedPath == "/forecasts/living-cost" }

    private suspend fun draft(): CashflowDraft = (repository.draft() as ApiResult.Success).data

    @Test fun `성공 — 연결 계좌를 모두 넣어 생활비 상세를 묻고 하루 평균을 제안한다`() = runTest {
        val draft = draft()

        assertEquals(18_000L, draft.dailyLivingCost)
        assertEquals(301L, draft.income?.id)
        assertEquals(listOf("월세"), draft.expenses.map { it.title })
        assertEquals(listOf("1", "2"), livingCostRequests().single().url.queryParameterValues("accountIds"))
        assertTrue(requests.none { it.url.encodedPath == "/forecasts/balance" })   // 계좌 없이 불러 400이 나던 경로
    }

    @Test fun `정해 둔 하루 목표가 있으면 평균보다 먼저 — 그대로 저장해도 목표가 바뀌지 않는다`() = runTest {
        respond("/forecasts/settings", ok(settings(incomeId = 290, target = 7_000)))

        assertEquals(7_000L, draft().dailyLivingCost)
    }

    @Test fun `정해 둔 목표가 있으면 이미 보낸 생활비 상세의 응답을 기다리지 않는다`() = runBlocking {
        slowLivingCost = true
        respond("/forecasts/settings", ok(settings(target = 7_000)))

        val draft = withTimeout(3_000) { draft() }   // 고치기 전에는 생활비 상세가 답할 때까지(여기서는 10초) 기다렸다

        assertEquals(7_000L, draft.dailyLivingCost)
        assertEquals(1, livingCostRequests().size)
    }

    @Test fun `찾은 후보가 없으면 빈 목록 — 하루 평균은 그대로 받는다`() = runTest {
        respond("/cashflow-items", ok("""{"items":[],"candidateCount":0}"""))

        val draft = draft()

        assertTrue(draft.isEmpty)
        assertEquals(18_000L, draft.dailyLivingCost)
    }

    @Test fun `고른 계좌 모두 생활비 기록이 없으면(422) 제안만 비우고 화면은 연다`() = runTest {
        respond("/forecasts/living-cost", errorJson(422, "DATA_INSUFFICIENT"))

        val draft = draft()

        assertNull(draft.dailyLivingCost)
        assertEquals(301L, draft.income?.id)
    }

    @Test fun `생활비 상세가 실패해도(500) 제안만 비운다`() = runTest {
        respond("/forecasts/living-cost", errorJson(500, "INTERNAL_ERROR"))

        assertNull(draft().dailyLivingCost)
    }

    @Test fun `하루 평균이 0원이면 제안하지 않는다`() = runTest {
        respond("/forecasts/living-cost", ok("""{"accountIds":[1,2],"projectedAmount":0,"dailyAverage":0,"monthlyAverage":0}"""))

        assertNull(draft().dailyLivingCost)
    }

    @Test fun `연결 계좌가 없으면 생활비 상세를 부르지 않는다 — 빈 accountIds는 400`() = runTest {
        respond("/accounts", ok(accounts()))

        assertNull(draft().dailyLivingCost)
        assertTrue(livingCostRequests().isEmpty())
    }

    @Test fun `계좌 목록을 못 읽으면 생활비 상세를 부르지 않고 제안 없음`() = runTest {
        respond("/accounts", errorJson(502, "FIN_API_UNAVAILABLE"))

        assertNull(draft().dailyLivingCost)
        assertTrue(livingCostRequests().isEmpty())
    }

    @Test fun `계좌가 20개를 넘으면 앞 20개만 보낸다 — 서버 상한`() = runTest {
        respond("/accounts", ok(accounts(*(1L..25L).toList().toLongArray())))

        draft()

        assertEquals((1..20).map { it.toString() }, livingCostRequests().single().url.queryParameterValues("accountIds"))
    }

    @Test fun `설정을 못 읽으면 화면 실패 — 지금 월급을 모른 채 저장하면 정해 둔 월급을 지울 수 있다`() = runTest {
        respond("/forecasts/settings", errorJson(500, "INTERNAL_ERROR"))

        val error = (repository.draft() as ApiResult.Failure).error

        assertTrue(error is ApiError.Server)
    }

    @Test fun `설정을 못 읽으면 생활비 상세 응답을 기다리지 않고 바로 실패를 보여 준다`() = runBlocking {
        slowLivingCost = true
        respond("/forecasts/settings", errorJson(500, "INTERNAL_ERROR"))

        val result = withTimeout(3_000) { repository.draft() }

        assertTrue(result is ApiResult.Failure)
    }

    @Test fun `후보가 없고 정한 월급도 없을 때 하루 생활비만 저장하면 PATCH 본문은 그 필드 하나 — 기간 기준을 덮지 않는다`() = runBlocking {
        respond("/cashflow-items", ok("""{"items":[],"candidateCount":0}"""))
        respond("/forecasts/settings", ok(settings()))
        respond("/forecasts/settings", ok(settings(target = 18_000)), method = "PATCH")
        // 화면이 만드는 저장 요청 그대로 — ViewModel이 실제 저장소로 진짜 HTTP를 보낸다
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val vm = CashflowConfirmViewModel(repository)
            withTimeout(5_000) { vm.state.first { !it.loading } }
            assertEquals(18_000L, vm.state.value.dailyCost)

            vm.confirm()
            withTimeout(5_000) { vm.state.first { it.saved } }
        } finally {
            Dispatchers.resetMain()
        }

        val patch = requests.single { it.method == "PATCH" }
        assertEquals("/forecasts/settings", patch.url.encodedPath)
        assertEquals("""{"dailyLivingCostTarget":18000}""", patch.body?.utf8())
    }

    @Test fun `정해 둔 월급과 월급 받을 계좌(주 계좌 먼저)를 같이 채운다`() = runTest {
        respond("/forecasts/settings", ok(settings(incomeId = 290)))

        val draft = draft()

        assertEquals(SavedIncome(290, "월급", null, 25), draft.savedIncome)
        assertEquals(listOf("신한은행 입출금 ··1001", "신한은행 입출금 ··1002"), draft.incomeAccounts.map { it.label })
        assertEquals(1L, draft.defaultIncomeAccount?.accountId)
    }

    @Test fun `월급 등록은 Idempotency-Key를 붙여 보내고 받은 ID를 설정의 월급으로 보낸다`() = runBlocking {
        respond("/cashflow-items", ok("""{"items":[],"candidateCount":0}"""))
        respond("/cashflow-items", json(201, """{"success":true,"data":{"cashflowItemId":901,"type":"INCOME","title":"월급","amount":2500000,"occurrenceDate":"2026-11-25"}}"""), method = "POST")
        respond("/forecasts/settings", ok(settings(incomeId = 901)), method = "PATCH")
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val vm = CashflowConfirmViewModel(repository)
            withTimeout(5_000) { vm.state.first { !it.loading } }
            assertTrue(vm.state.value.draft!!.canEnterIncome)

            vm.saveIncome(IncomeInput(2_500_000, 25, 2))
            vm.confirm()
            withTimeout(5_000) { vm.state.first { it.saved } }
        } finally {
            Dispatchers.resetMain()
        }

        val post = requests.single { it.method == "POST" }
        assertEquals("/cashflow-items", post.url.encodedPath)
        val key = post.headers["Idempotency-Key"]
        assertTrue(key != null && java.util.UUID.fromString(key).toString() == key)
        assertTrue(post.body!!.utf8().contains(""""recurrence":{"frequency":"MONTHLY","dayOfMonth":25}"""))
        val patch = requests.single { it.method == "PATCH" }
        assertEquals("""{"horizon":"NEXT_INCOME","incomeCashflowItemId":901,"dailyLivingCostTarget":18000}""", patch.body?.utf8())
    }

    private companion object {
        val ITEMS = """
            {"items":[
              {"cashflowItemId":301,"type":"INCOME","title":"월급","amount":2800000,"occurrenceDate":"2026-11-10",
               "recurrence":{"frequency":"MONTHLY","dayOfMonth":10},"source":"DETECTED","status":"CANDIDATE"},
              {"cashflowItemId":302,"type":"EXPENSE","title":"월세","amount":500000,"occurrenceDate":"2026-11-30",
               "recurrence":{"frequency":"MONTHLY","dayOfMonth":31},"source":"DETECTED","status":"CANDIDATE"}
            ],"candidateCount":2}
        """.trimIndent()

        val LIVING_COST = """
            {"accountIds":[1,2],"projectedAmount":540000,"dailyAverage":18000,"monthlyAverage":540000,
             "method":"TSB","dataOrigin":"SEED","confidence":"HIGH",
             "period":{"from":"2026-11-09","to":"2026-12-09","days":30,"basis":"DAYS"},
             "sources":[{"yearMonth":"2026-10","amount":550000}],
             "excluded":[{"type":"RECURRING","note":"월세·통신비·구독료는 예정 지출로 별도 반영"}],
             "dailyTarget":null,"paceCheck":null,"dataSyncedAt":"2026-11-09T01:00:00","accountLivingCosts":null}
        """.trimIndent()
    }
}
