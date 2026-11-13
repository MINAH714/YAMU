package com.yamu.app.feature.report.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.payment.data.KnownMemo
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/** 결제 내역 CSV(#63) — 파일 응답은 감싸지 않고, 실패는 공통 JSON. 파일 이름은 Content-Disposition */
class PaymentExportTest {

    private lateinit var server: MockWebServer
    private lateinit var edits: PaymentEdits
    private lateinit var repository: ReportRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        edits = PaymentEdits()
        repository = ReportRepositoryImpl(server.retrofit().create(ReportApi::class.java), edits)
    }

    @After fun tearDown() = server.close()

    @Test fun `성공 — 바이트(BOM 포함)를 그대로 받고 서버가 준 이름을 쓴다`() = runTest {
        val body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "결제일시,가맹점\r\n".toByteArray()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/csv;charset=UTF-8")
                .addHeader("Content-Disposition", "attachment; filename=\"=?UTF-8?Q?yamu-payments-2026-10.csv?=\"; filename*=UTF-8''yamu-payments-2026-10.csv")
                .body(okio.Buffer().write(body))
                .build(),
        )
        val file = (repository.exportCsv(YearMonth.of(2026, 10)) as ApiResult.Success).data
        assertEquals("yamu-payments-2026-10.csv", file.name)
        assertArrayEquals(body, file.bytes)
        val request = server.takeRequest()
        assertEquals("/payments/export", request.url.encodedPath)
        assertEquals("2026-10", request.url.queryParameter("yearMonth"))   // 저장 창 이름과 같은 달(서버 기본값에 맡기지 않는다)
    }

    @Test fun `서버가 이름을 주지 않으면 넘긴 달로 짓는다 — 지금 달이 아니라`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/csv").body("a\r\n").build())
        val file = (repository.exportCsv(YearMonth.of(2026, 10)) as ApiResult.Success).data
        assertEquals("yamu-payments-2026-10.csv", file.name)
    }

    @Test fun `429 — 공통 JSON 오류에서 남은 초를 읽는다`() = runTest {
        server.enqueue(json(429, """{"success":false,"data":null,"error":{"code":"RATE_LIMITED","message":"m","details":{"retryAfterSeconds":17}}}"""))
        val error = (repository.exportCsv(YearMonth.of(2026, 11)) as ApiResult.Failure).error
        assertEquals("RATE_LIMITED", error.code)
        assertEquals("내려받기는 1분에 3번까지예요. 17초 뒤 다시 눌러 주세요", exportErrorMessage(error))
    }

    @Test fun `기간 · 행 수 초과 400 RANGE_TOO_LARGE`() = runTest {
        server.enqueue(json(400, """{"success":false,"data":null,"error":{"code":"RANGE_TOO_LARGE","message":"m","details":{"maxRows":10000}}}"""))
        val error = (repository.exportCsv(YearMonth.of(2026, 11)) as ApiResult.Failure).error
        assertTrue(error is ApiError.InvalidRequest)
        assertEquals("한 번에 내려받기엔 결제가 너무 많아요", exportErrorMessage(error))
    }

    @Test fun `목록을 받으면 메모를 앱 메모리에 둔다 — 상세 응답엔 memo가 없다`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"summary":{"count":2,"amount":3000},"items":[
                  {"paymentId":1,"status":"APPROVED","merchant":{"name":"a"},"amount":1000,"memo":"정산 예정"},
                  {"paymentId":2,"status":"CANCELED","merchant":{"name":"b"},"amount":2000,"memo":null}],"page":1,"hasNext":false}}""",
            ),
        )
        edits.rememberMemo(2, "지워진 메모")
        repository.payments(1, YearMonth.of(2026, 10))
        assertEquals("정산 예정", edits.memo(1)?.text)
        assertEquals(KnownMemo(null), edits.memo(2))   // 없음을 확인함(모름과 다르다)
    }

    @Test fun `파일 이름 — RFC 5987을 먼저, 없으면 따옴표 이름, 둘 다 못 쓰면 고른 달`() {
        val fallback = defaultCsvName(YearMonth.of(2026, 11))
        assertEquals("yamu-payments-2026-11.csv", fallback)
        assertEquals("yamu-payments-2026-10-01_2026-10-15.csv", csvFileName("attachment; filename*=UTF-8''yamu-payments-2026-10-01_2026-10-15.csv", fallback))
        assertEquals("결제.csv", csvFileName("attachment; filename*=UTF-8''%EA%B2%B0%EC%A0%9C.csv", fallback))
        assertEquals("yamu-payments-2026-09.csv", csvFileName("attachment; filename=\"yamu-payments-2026-09.csv\"", fallback))
        assertEquals(fallback, csvFileName("attachment; filename=\"=?UTF-8?Q?x.csv?=\"", fallback))
        assertEquals(fallback, csvFileName("attachment; filename=\"../../etc/x.csv\"", fallback))
        assertEquals(fallback, csvFileName(null, fallback))
    }
}
