package com.yamu.app.feature.report.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.splitByKind
import com.yamu.app.feature.report.assetDayLabel
import com.yamu.app.feature.report.panelValue
import com.yamu.app.feature.forecast.data.ForecastApi
import com.yamu.app.feature.link.data.LinkApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth
import java.time.LocalDate
import java.time.ZonedDateTime

/** GET asset-flows(S15P31E103-492) — 기준 계좌라 계좌를 적지 않고, 옛 서버의 404 API_NOT_FOUND는 실패가 아니라 없는 API(데이터 404는 실패) */
class AssetFlowRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: AssetFlowRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = server.retrofit()
        repository = AssetFlowRepositoryImpl(retrofit.create(ForecastApi::class.java), retrofit.create(LinkApi::class.java), retrofit.create(AssetFlowApi::class.java))
    }

    @After fun tearDown() = server.close()

    @Test fun `실제 계좌 API의 기준 계좌로 예보를 요청하고 받은 잔액을 사용한다`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"accounts":[
            {"accountId":41,"bank":{"code":"088","name":"신한"},"maskedAccountNo":"***1234","balance":777777,"isPrimary":true},
            {"accountId":42,"bank":{"code":"004","name":"국민"},"maskedAccountNo":"***5678","balance":999999,"isPrimary":false}]}}"""))
        server.enqueue(json(200, """{"success":true,"data":{"accountIds":[41],"currentTotalBalance":777777,"series":[]}}"""))
        val flow = (repository.load() as ApiResult.Success).data
        assertEquals(listOf(41L), flow.accounts.map { it.accountId })
        assertEquals(777777L, flow.currentBalance)
        assertEquals("/accounts", server.takeRequest().url.encodedPath)
        val request = server.takeRequest()
        assertEquals("/forecasts/balance", request.url.encodedPath)
        assertEquals(listOf("41"), request.url.queryParameterValues("accountIds"))
        assertEquals("30", request.url.queryParameter("days"))
    }

    @Test fun `계좌 조회 실패를 가짜 계좌나 잔액으로 대체하지 않는다`() = runTest {
        server.enqueue(json(502, """{"success":false,"error":{"code":"FIN_API_UNAVAILABLE","message":"은행"}}"""))
        assertTrue(repository.load() is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `서버 QA 합성 0원과 실제 경계가 그래프와 선택 날짜에 구분되어 전달된다`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"yearMonth":"2026-11","accountIds":[41],
            "series":[{"date":"2026-11-11","kind":"SIMULATED","dataStatus":"AVAILABLE","displayBalance":0,"actualBalance":null},
                      {"date":"2026-11-12","kind":"ACTUAL","dataStatus":"AVAILABLE","displayBalance":300000,"actualBalance":300000}],
            "partialErrors":[]}}"""))
        val month = (repository.month(YearMonth.of(2026, 11)) as MonthFlowResult.Loaded).flow
        val today = LocalDate.of(2026, 11, 16)
        val days = assetDays(month.month, today, null, month)
        val synthetic = days[10]
        val actual = days[11]
        assertEquals(DayKind.SIMULATED, synthetic.kind)
        assertEquals(0L, synthetic.value)
        assertTrue(synthetic.simulated)
        assertTrue(assetDayLabel(synthetic).contains("QA 합성"))
        assertEquals(DayKind.ACTUAL, actual.kind)
        assertFalse(actual.simulated)
        assertEquals(300000L, actual.value)
        val graph = splitByKind(days.map { it.value }, days.map { it.kind })
        assertEquals(0L, graph.first[10])
        assertEquals(300000L, graph.first[11])
        val at = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, KST)
        val flow = AssetFlow(emptyList(), null, loadedAt = at)
        val panel = panelValue(synthetic, flow, month, today, null, at)
        assertEquals("0원", panel.first)
        assertTrue(panel.second.contains("QA 합성"))
        assertTrue(panelValue(actual, flow, month, today, null, at).second.contains("실제 잔고"))
    }

    @Test fun `그달을 yearMonth로 묻고 계좌는 적지 않는다(서버가 기준 계좌를 고른다)`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"yearMonth":"2026-11","accountIds":[1],"currentTotalBalance":3000001,"balanceAsOf":"2026-11-13T03:00:00Z",
                "series":[{"date":"2026-11-12","kind":"ACTUAL","dataStatus":"AVAILABLE","displayBalance":3100001,"actualBalance":3100001,"forecast":null,"events":[]},
                          {"date":"2026-11-13","kind":"CURRENT","dataStatus":"AVAILABLE","displayBalance":3000001,"actualBalance":3000001,"forecast":null,"events":[]},
                          {"date":"2026-11-14","kind":"FORECAST","dataStatus":"AVAILABLE","displayBalance":2900001,"actualBalance":null,
                           "forecast":{"confirmed":2950001,"expected":2950001,"conservative":2900001},
                           "events":[{"type":"SCHEDULED_OUTFLOW","title":"예정 지출","amount":-50000,"confidence":"CONFIRMED"}]},
                          {"date":"2026-11-15","kind":"FORECAST","dataStatus":"NO_DATA","displayBalance":null,"newField":1,"events":[]}],
                "forecastFrom":"2026-11-14","forecastTo":"2026-12-13","partialErrors":[],"calculatedAt":"2026-11-13T03:00:01Z"},"error":null}""",
            ),
        )
        val r = repository.month(YearMonth.of(2026, 11))
        val request = server.takeRequest()
        assertEquals("/asset-flows", request.url.encodedPath)
        assertEquals("2026-11", request.url.queryParameter("yearMonth"))
        assertNull("기준 계좌면 빼고 보낸다", request.url.queryParameter("accountIds"))
        val flow = (r as MonthFlowResult.Loaded).flow
        assertEquals(listOf(DayKind.ACTUAL, DayKind.TODAY, DayKind.EXPECTED, DayKind.NO_DATA), flow.points.map { it.kind })
        assertEquals(listOf(3_100_001L, 3_000_001L, 2_900_001L, null), flow.points.map { it.value })
        assertEquals(3_000_001L, flow.currentBalance)
    }

    @Test fun `asset-flows가 없는 옛 서버(404 API_NOT_FOUND)면 Unsupported — 예보만으로 그린다`() = runTest {
        // BE GlobalExceptionHandler가 매핑 없는 경로에 주는 본문(yamu.it.kr 11/14)
        server.enqueue(json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"요청한 API를 찾을 수 없습니다."}}"""))
        assertEquals(MonthFlowResult.Unsupported, repository.month(YearMonth.of(2026, 11)))
    }

    @Test fun `프록시가 본문 없이 준 404도 없는 API로 본다`() = runTest {
        server.enqueue(json(404, ""))
        assertEquals(MonthFlowResult.Unsupported, repository.month(YearMonth.of(2026, 11)))
    }

    @Test fun `새 서버의 데이터 404(RESOURCE_NOT_FOUND)는 옛 서버로 묻지 않고 실패로`() = runTest {
        server.enqueue(json(404, """{"success":false,"data":null,"error":{"code":"RESOURCE_NOT_FOUND","message":"없음"}}"""))
        val r = repository.month(YearMonth.of(2026, 11))
        assertEquals(ApiError.NotFound("RESOURCE_NOT_FOUND"), (r as MonthFlowResult.Failed).error)
    }

    @Test fun `은행 · 서버 오류는 실패로 — 다시 시도를 보인다`() = runTest {
        server.enqueue(json(502, """{"success":false,"data":null,"error":{"code":"FIN_API_UNAVAILABLE","message":"은행"}}"""))
        assertTrue(repository.month(YearMonth.of(2026, 11)) is MonthFlowResult.Failed)
    }
}
