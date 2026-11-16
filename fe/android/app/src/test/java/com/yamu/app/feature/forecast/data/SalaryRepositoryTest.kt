package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.*
import com.yamu.app.feature.link.data.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class SalaryRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: SalaryRepositoryImpl
    private var accounts: ApiResult<List<IncomeAccount>> = ApiResult.Success(listOf(IncomeAccount(7, "계좌", true)))
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val retrofit = server.retrofit()
        val cashflow = object : CashflowRepository {
            override suspend fun incomeAccounts() = accounts
            override suspend fun draft(): ApiResult<CashflowDraft> = error("not used")
            override suspend fun createIncome(body: CashflowCreateRequest, idempotencyKey: String): ApiResult<Long> = error("not used")
            override suspend fun confirm(plan: CashflowPlan): ApiResult<Unit> = error("not used")
        }
        repo = SalaryRepositoryImpl(retrofit.create(CashflowApi::class.java), retrofit.create(ForecastApi::class.java), cashflow)
    }
    @After fun teardown() { server.close() }
    private fun ok(data: String) = json(200, """{"success":true,"data":$data}""")

    @Test fun `월급 계좌를 읽고 지정된 ID의 미래 회차만 고른다`() = runTest {
        server.enqueue(ok("""{"income":{"cashflowItemId":123,"title":"월급","amount":3000000,"dayOfMonth":25,"accountId":7}}"""))
        fun row(id: Long, date: String) = """{"cashflowItemId":$id,"type":"INCOME","title":"월급","amount":3000000,"date":"$date","occurrenceDate":"$date","accountId":7,"recurrence":{"frequency":"MONTHLY","dayOfMonth":25}}"""
        server.enqueue(ok("""{"items":[${row(999,"2026-11-18")},${row(123,"2026-11-17")},${row(123,"2026-11-25")}]}"""))
        val result = repo.load(LocalDate.of(2026, 11, 17)) as ApiResult.Success
        assertEquals(7L, result.data.income?.accountId)
        assertEquals(LocalDate.of(2026, 11, 25), result.data.next?.occurrenceDate)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("2026-11-18", request.url.queryParameter("from"))
        assertEquals("true", request.url.queryParameter("expand"))
    }

    @Test fun `계좌 오류는 계좌 없음으로 바꾸지 않는다`() = runTest {
        server.enqueue(ok("""{"income":null}"""))
        accounts = ApiResult.Failure(ApiError.Network)
        assertTrue(repo.load(LocalDate.of(2026, 11, 17)) is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `월급 지정은 생활비나 안전잔액을 덮지 않는다`() = runTest {
        server.enqueue(ok("null"))
        assertTrue(repo.designate(123) is ApiResult.Success)
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/forecasts/settings", r.url.encodedPath)
        val body = r.body!!.utf8()
        assertTrue(body.contains("\"incomeCashflowItemId\":123"))
        assertFalse(body.contains("dailyLivingCostTarget")); assertFalse(body.contains("alertThreshold"))
    }
}
