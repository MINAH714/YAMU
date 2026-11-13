package com.yamu.app.feature.report.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/** 소비분석 · 혜택 리포트 · 결제 내역 — 고른 달을 늘 yearMonth로 넘긴다(서버 기본값 이번 달 · 지난달에 맡기지 않는다) */
class ReportRepositoryMonthTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ReportRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repository = ReportRepositoryImpl(server.retrofit().create(ReportApi::class.java), PaymentEdits())
    }

    @After fun tearDown() = server.close()

    @Test fun `월간 소비 — 리포트와 건수(결제 내역 1건짜리) 둘 다 같은 달`() = runTest {
        // 두 요청이 동시에 나가 순서가 정해지지 않아, 두 응답 모양을 한 본문에 담는다(모르는 칸은 무시)
        val body = """{"success":true,"data":{"yearMonth":"2026-10","isPartialMonth":false,"totalSpent":5000,"livingSpent":4000,"prevSamePeriodSpent":3000,
            "summary":{"count":7,"amount":5000},"items":[],"page":1,"hasNext":false}}"""
        server.enqueue(json(200, body))
        server.enqueue(json(200, body))
        val spend = (repository.monthly(YearMonth.of(2026, 10)) as ApiResult.Success).data
        val requests = listOf(server.takeRequest(), server.takeRequest())
        assertEquals(setOf("/reports/monthly", "/payments"), requests.map { it.url.encodedPath }.toSet())
        requests.forEach { assertEquals("2026-10", it.url.queryParameter("yearMonth")) }
        assertEquals("1", requests.first { it.url.encodedPath == "/payments" }.url.queryParameter("size"))
        assertEquals(7, spend.paymentCount)
        assertEquals("10월", spend.periodLabel)
        assertEquals("9월", spend.prevPeriod)   // 끝난 달은 '9월보다'
    }

    @Test fun `혜택 리포트 — 고른 달`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"yearMonth":"2026-09","status":"CONFIRMED","summary":{"expectedBenefit":100,"confirmedBenefit":100}}}"""))
        repository.benefits(YearMonth.of(2026, 9))
        val request = server.takeRequest()
        assertEquals("/reports/monthly/benefits", request.url.encodedPath)
        assertEquals("2026-09", request.url.queryParameter("yearMonth"))
    }

    @Test fun `결제 내역 — 쪽 · 크기 · 달`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"summary":{"count":0,"amount":0},"items":[],"page":2,"hasNext":false}}"""))
        repository.payments(2, YearMonth.of(2025, 12))
        val request = server.takeRequest()
        assertEquals("/payments", request.url.encodedPath)
        assertEquals("2", request.url.queryParameter("page"))
        assertEquals("20", request.url.queryParameter("size"))
        assertEquals("2025-12", request.url.queryParameter("yearMonth"))
    }
}
