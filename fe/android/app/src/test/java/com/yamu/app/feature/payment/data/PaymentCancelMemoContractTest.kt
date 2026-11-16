package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.YamuJson
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.link.data.brandOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** 결제 취소 · 메모 · 상세를 BE 모양과 맞춘다 — PaymentCancelController · PaymentMemoController · PaymentDetailResponse */
class PaymentCancelMemoContractTest {

    private lateinit var server: MockWebServer
    private lateinit var edits: PaymentEdits
    private lateinit var repository: PaymentRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        edits = PaymentEdits()
        repository = PaymentRepositoryImpl(server.retrofit().create(PaymentApi::class.java), edits)
    }

    @After fun tearDown() = server.close()

    @Test fun `거절은 서버에 결제 번호와 멱등 키를 전송한다`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"paymentId":1024,"status":"REJECTED"}}"""))
        assertTrue(repository.reject(1024, "reject-key") is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/payments/1024/reject", request.url.encodedPath)
        assertEquals("reject-key", request.headers[YamuHeaders.IDEMPOTENCY_KEY])
    }

    @Test fun `거절 API 오류를 가짜 성공으로 바꾸지 않는다`() = runTest {
        server.enqueue(json(409, """{"success":false,"error":{"code":"PAYMENT_ALREADY_PROCESSED","message":"처리된 결제"}}"""))
        val result = repository.reject(1024, "reject-key")
        assertTrue(result is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `취소 본문은 빈 객체 — 금액 칸이 있으면 BE가 400 PARTIAL_CANCEL_NOT_SUPPORTED`() {
        assertEquals("{}", YamuJson.encodeToString(CancelRequest()))
    }

    @Test fun `메모를 지울 때도 memo 칸을 보낸다 — 빠지면 BE가 400`() {
        assertEquals("""{"memo":""}""", YamuJson.encodeToString(MemoRequest("")))
    }

    @Test fun `취소 — 멱등 키를 헤더로 · 경로에 결제 번호, 신용카드 회차 결과와 환급`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"paymentId":1024,"status":"CANCELED","canceledAt":"2026-12-02T06:02:00Z",
                  "restored":{"performance":{"yearMonth":"2026-10","amount":90000},"limitImpacts":[]},
                  "billItems":[{"billItemId":7001,"seq":1,"amount":30000,"before":"PAID","after":"REFUND_PENDING"},
                    {"billItemId":7003,"seq":3,"amount":30000,"before":"PENDING","after":"EXCLUDED"}],
                  "refunds":[{"refundId":91,"billItemId":7001,"amount":30000,"status":"PENDING","dueDate":"2026-12-05","toAccountId":3},
                    {"refundId":92,"billItemId":7002,"amount":30000,"status":"PENDING","dueDate":null,"toAccountId":3}],
                  "accountBalanceAfter":null}}""",
            ),
        )
        val before = edits.version.value
        val r = repository.cancel(1024, "6f1c2a8e-1111-4c2b-9a77-1d2e3f4a5b6c")
        val done = (r as ApiResult.Success).data
        assertEquals(1, done.excludedBills)
        assertEquals(listOf(RefundLine(30_000, "2026-12-05", false), RefundLine(30_000, null, false)), done.refunds)
        assertNull(done.balanceAfter)
        assertEquals(before + 1, edits.version.value)   // 결제 내역이 첫 쪽부터 다시 받게
        assertTrue(edits.isCanceled(1024))               // 결제 완료(P-07)가 영수증에 취소만 반영하게
        assertEquals("2026-12-02T06:02:00Z", edits.canceledAt(1024))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/payments/1024/cancel", request.url.encodedPath)
        assertEquals("6f1c2a8e-1111-4c2b-9a77-1d2e3f4a5b6c", request.headers[YamuHeaders.IDEMPOTENCY_KEY])
        assertEquals("{}", request.body?.utf8())
    }

    @Test fun `체크카드 취소 — 입금 뒤 잔액`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"paymentId":9,"status":"CANCELED","canceledAt":"2026-11-08T07:00:00Z","restored":{"performance":null,"limitImpacts":[]},"billItems":[],"refunds":[],"accountBalanceAfter":965000}}"""))
        val done = (repository.cancel(9, "k") as ApiResult.Success).data
        assertEquals(965_000L, done.balanceAfter)
        assertEquals(0, done.excludedBills)
    }

    @Test fun `취소 409 — 코드와 details reason을 그대로 받아 사유별 문장으로`() = runTest {
        server.enqueue(json(409, """{"success":false,"data":null,"error":{"code":"PAYMENT_NOT_CANCELABLE","message":"m","details":{"reason":"ACCOUNT_UNAVAILABLE"}}}"""))
        val before = edits.version.value
        val error = (repository.cancel(9, "k") as ApiResult.Failure).error
        assertTrue(error is ApiError.Conflict)
        assertEquals(CancelProblem("돌려받을 계좌가 연결돼 있지 않아 취소할 수 없어요", recheck = false), error.toCancelProblem())
        assertEquals(before, edits.version.value)   // 실패면 목록을 버리지 않는다
    }

    @Test fun `메모 — PATCH 본문에 정리한 한 줄, 저장 값을 상세가 쓴다`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"paymentId":12,"memo":"팀 회식 1/N","memoUpdatedAt":"2026-11-08T07:00:00Z"}}"""))
        val saved = repository.saveMemo(12, "팀 회식 1/N")
        assertEquals("팀 회식 1/N", (saved as ApiResult.Success).data)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/payments/12/memo", request.url.encodedPath)
        assertEquals("""{"memo":"팀 회식 1/N"}""", request.body?.utf8())
        assertEquals("팀 회식 1/N", edits.memo(12)?.text)
    }

    @Test fun `메모 지우기 — 빈 문자열을 보내고 서버가 null을 주면 메모를 비운다`() = runTest {
        edits.rememberMemo(12, "옛 메모")
        server.enqueue(json(200, """{"success":true,"data":{"paymentId":12,"memo":null,"memoUpdatedAt":"2026-11-08T07:00:00Z"}}"""))
        assertNull((repository.saveMemo(12, null) as ApiResult.Success).data)
        assertEquals("""{"memo":""}""", server.takeRequest().body?.utf8())
        assertEquals(KnownMemo(null), edits.memo(12))   // 지운 것도 확인한 값
    }

    @Test fun `메모 100자 넘음 400 MEMO_TOO_LONG`() = runTest {
        server.enqueue(json(400, """{"success":false,"data":null,"error":{"code":"MEMO_TOO_LONG","message":"m","details":{"maxLength":100}}}"""))
        val error = (repository.saveMemo(12, "x".repeat(101)) as ApiResult.Failure).error
        assertEquals("메모는 100자까지 적을 수 있어요", error.toMemoProblem())
    }

    @Test fun `상세 — BE 모양(회차 금액 null · 혜택 이름 null · 취소 · 환급 · 메모)을 읽는다`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"paymentId":31,"source":"YAMU","status":"CANCELED","channel":"OFFLINE",
                  "merchant":{"merchantId":3,"name":"야무마트 강남점","categoryCode":"MART","categoryName":null},
                  "amount":90000,"plan":"LUMP_SUM","installmentMonths":null,"chargedAmount":90000,
                  "selectedCard":{"userCardId":15,"nickname":"Mr.Life","maskedNumber":"****-5096","cardType":"CREDIT","recommendedRank":null,"userSelected":null},
                  "approvalNo":"Y1","approvedAt":"2026-11-01T03:00:00Z","canceledAt":"2026-11-08T07:00:00Z",
                  "benefits":[{"paymentBenefitId":1,"benefitId":2,"name":null,"type":"DISCOUNT","expectedAmount":null,"actualAmount":3000,"realization":"AT_BILLING","confirmed":null,"confirmedAt":null}],
                  "performance":{"yearMonth":"2026-11","recognizedAmount":0,"recognized":false,"exclusionReason":null},
                  "limitImpacts":[],
                  "billItems":[{"billItemId":1,"seq":1,"amount":null,"billId":null,"billingMonth":"2026-12","withdrawalDate":null,"status":"EXCLUDED","principalAmount":90000,"feeAmount":null}],
                  "refunds":[{"refundId":5,"billItemId":1,"principalAmount":90000,"feeAmount":0,"refundAmount":87000,"dueDate":"2026-11-13","status":"PENDING","completedAt":null}],
                  "recommendationSnapshot":null,"statusHistory":[],"failure":null,"installmentPolicy":null,"memo":"정산 예정"}}""",
            ),
        )
        val r = (repository.detail(31) as ApiResult.Success).data
        assertEquals(PayStatus.CANCELED, r.status)
        assertTrue(r.canceled)
        assertFalse(r.cancelable)
        assertEquals("2026-11-08T07:00:00Z", r.canceledAtIso)
        assertEquals(listOf(RefundLine(87_000, "2026-11-13", false)), r.refunds)
        assertEquals(90_000L, r.billAmount)                 // 수수료 미산정 → 원금
        assertEquals("혜택", r.benefits.single().name)
        assertEquals("정산 예정", r.memo)
    }

    // ── 상세에서 알게 된 취소 — 응답이 끊긴 뒤 다시 읽음 · 다른 기기에서 먼저 취소 ──

    private fun canceledDetail(id: Long, canceledAt: String?) =
        """{"success":true,"data":{"paymentId":$id,"source":"YAMU","status":"CANCELED","amount":15000,"approvedAt":"2026-11-08T03:00:00Z"""" +
            (canceledAt?.let { ""","canceledAt":"$it"""" } ?: "") + "}}"

    @Test fun `상세가 취소면 취소 응답 때처럼 알린다 — 결제 완료 · 결제 내역이 승인으로 남지 않게`() = runTest {
        val approved = """{"success":true,"data":{"paymentId":50,"status":"APPROVED","amount":15000,"approvedAt":"2026-11-08T03:00:00Z","accountBalanceAfter":985000}}"""
        server.enqueue(json(200, approved))
        val request = PaymentRequest(50, "카페", null, 15_000, null, emptyList())
        val card = PayCard(1, brandOf("신한카드"), "카드", "0001", true, BigDecimal.ZERO, null, null, emptyList(), null, null)
        repository.approve(request, card, null, "k")
        assertNotNull(repository.recentReceipt(50))
        val before = edits.version.value

        server.enqueue(json(200, canceledDetail(50, "2026-11-08T07:00:00Z")))
        assertTrue((repository.detail(50) as ApiResult.Success).data.canceled)
        assertTrue(edits.isCanceled(50))
        assertEquals("2026-11-08T07:00:00Z", edits.canceledAt(50))
        assertEquals(before + 1, edits.version.value)
        assertNull(repository.recentReceipt(50))   // 결제 완료를 다시 열어도 승인 영수증이 나오지 않게
    }

    @Test fun `이미 알린 취소는 다시 알리지 않는다 — 상세를 또 읽거나 취소 응답이 늦게 와도`() = runTest {
        server.enqueue(json(200, canceledDetail(51, null)))
        server.enqueue(json(200, canceledDetail(51, "2026-11-08T07:00:00Z")))
        server.enqueue(json(200, """{"success":true,"data":{"paymentId":51,"status":"CANCELED","canceledAt":"2026-11-08T07:00:00Z","billItems":[],"refunds":[]}}"""))
        val before = edits.version.value
        repository.detail(51)
        assertNull(edits.canceledAt(51))           // 시각을 모르는 취소
        repository.detail(51)
        assertEquals("2026-11-08T07:00:00Z", edits.canceledAt(51))   // 시각만 채운다
        assertTrue(repository.cancel(51, "k") is ApiResult.Success)   // 같은 키로 다시 보낸 취소(BE가 처음 결과를 준다)
        assertEquals(before + 1, edits.version.value)
    }

    @Test fun `승인 상태 상세는 아무것도 알리지 않는다`() = runTest {
        server.enqueue(json(200, detail(memo = null)))
        val before = edits.version.value
        repository.detail(40)
        assertFalse(edits.isCanceled(40))
        assertEquals(before, edits.version.value)
    }

    @Test fun `가입 전 거래(SEED)는 승인 상태여도 취소 버튼을 보이지 않는다`() {
        val dto = YamuJson.decodeFromString<PaymentResultDto>("""{"paymentId":1,"status":"APPROVED","source":"SEED","amount":1000}""")
        assertFalse(dto.toReceipt().cancelable)
        val yamu = YamuJson.decodeFromString<PaymentResultDto>("""{"paymentId":1,"status":"APPROVED","source":"YAMU","amount":1000}""")
        assertTrue(yamu.toReceipt().cancelable)
    }

    // ── 상세의 메모 — BE가 memo 칸을 늘 내려준다(없으면 null, S15P31E103-579) ──

    private fun detail(memo: String?): String {
        val memoJson = if (memo == null) "null" else "\"$memo\""
        return """{"success":true,"data":{"paymentId":40,"source":"YAMU","status":"APPROVED","amount":15000,"approvedAt":"2026-11-07T15:30:00Z","memo":$memoJson}}"""
    }

    @Test fun `상세의 메모를 그대로 보이고 결제 내역에도 기억한다 — 결제 내역을 다시 부르지 않는다`() = runTest {
        server.enqueue(json(200, detail(memo = "정산 예정")))
        val r = (repository.detail(40) as ApiResult.Success).data
        assertEquals("정산 예정", r.memo)
        assertEquals(KnownMemo("정산 예정"), edits.memo(40))
        assertEquals(1, server.requestCount)
        assertEquals("/payments/40", server.takeRequest().url.encodedPath)
    }

    @Test fun `상세의 memo가 null이면 '메모 없음'이고 전에 기억한 메모보다 상세 값을 쓴다`() = runTest {
        edits.rememberMemo(40, "다른 기기에서 지운 메모")
        server.enqueue(json(200, detail(memo = null)))
        val r = (repository.detail(40) as ApiResult.Success).data
        assertNull(r.memo)
        assertEquals(KnownMemo(null), edits.memo(40))
    }

    @Test fun `로그아웃하면 받아 둔 메모를 비운다`() {
        edits.rememberMemo(1, "남의 메모")
        val before = edits.version.value
        edits.clear()
        assertNull(edits.memo(1))
        assertEquals(before + 1, edits.version.value)
    }
}
