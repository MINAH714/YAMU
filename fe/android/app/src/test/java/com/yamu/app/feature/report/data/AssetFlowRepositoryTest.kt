package com.yamu.app.feature.report.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.data.ForecastApi
import com.yamu.app.feature.link.data.LinkApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/** GET asset-flows(S15P31E103-492) — 연결 계좌 전체를 적고(-548), 옛 서버의 404 API_NOT_FOUND는 실패가 아니라 없는 API(데이터 404는 실패) */
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

    /** 계좌 목록 — month()가 load() 전이면 먼저 부른다 */
    private fun enqueueAccounts() = server.enqueue(json(200, """{"success":true,"data":{"accounts":[{"accountId":1,"bank":{"code":"004","name":"KB국민은행"},"maskedAccountNo":"****-1234","balance":300000,"isPrimary":true}]}}"""))

    @Test fun `그달을 yearMonth로 묻고 연결 계좌 전체를 적는다(-548, 빼면 서버가 기준 계좌 하나만 준다)`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"accounts":[
                    {"accountId":1,"bank":{"code":"004","name":"KB국민은행"},"maskedAccountNo":"****-1234","balance":300000,"isPrimary":true},
                    {"accountId":2,"bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-5678","balance":200000,"isPrimary":false}]}}""",
            ),
        )
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
        assertEquals("/accounts", server.takeRequest().url.encodedPath)   // load() 전이라 계좌부터
        val request = server.takeRequest()
        assertEquals("/asset-flows", request.url.encodedPath)
        assertEquals("2026-11", request.url.queryParameter("yearMonth"))
        assertEquals(listOf("1", "2"), request.url.queryParameterValues("accountIds"))
        val flow = (r as MonthFlowResult.Loaded).flow
        assertEquals(listOf(DayKind.ACTUAL, DayKind.TODAY, DayKind.EXPECTED, DayKind.NO_DATA), flow.points.map { it.kind })
        assertEquals(listOf(3_100_001L, 3_000_001L, 2_900_001L, null), flow.points.map { it.value })
        assertEquals(3_000_001L, flow.currentBalance)
    }

    @Test fun `asset-flows가 없는 옛 서버(404 API_NOT_FOUND)면 Unsupported — 예보만으로 그린다`() = runTest {
        enqueueAccounts()
        // BE GlobalExceptionHandler가 매핑 없는 경로에 주는 본문(yamu.it.kr 11/14)
        server.enqueue(json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"요청한 API를 찾을 수 없습니다."}}"""))
        assertEquals(MonthFlowResult.Unsupported, repository.month(YearMonth.of(2026, 11)))
    }

    @Test fun `프록시가 본문 없이 준 404도 없는 API로 본다`() = runTest {
        enqueueAccounts()
        server.enqueue(json(404, ""))
        assertEquals(MonthFlowResult.Unsupported, repository.month(YearMonth.of(2026, 11)))
    }

    @Test fun `새 서버의 데이터 404(RESOURCE_NOT_FOUND)는 옛 서버로 묻지 않고 실패로`() = runTest {
        enqueueAccounts()
        server.enqueue(json(404, """{"success":false,"data":null,"error":{"code":"RESOURCE_NOT_FOUND","message":"없음"}}"""))
        val r = repository.month(YearMonth.of(2026, 11))
        assertEquals(ApiError.NotFound("RESOURCE_NOT_FOUND"), (r as MonthFlowResult.Failed).error)
    }

    @Test fun `은행 · 서버 오류는 실패로 — 다시 시도를 보인다`() = runTest {
        enqueueAccounts()
        server.enqueue(json(502, """{"success":false,"data":null,"error":{"code":"FIN_API_UNAVAILABLE","message":"은행"}}"""))
        assertTrue(repository.month(YearMonth.of(2026, 11)) is MonthFlowResult.Failed)
    }
}
