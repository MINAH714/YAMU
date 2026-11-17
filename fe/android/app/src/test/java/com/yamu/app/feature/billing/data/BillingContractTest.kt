package com.yamu.app.feature.billing.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.errorJson
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** 청구 · 환급 조회를 BE 응답 모양(BillListResponse · BillDetailResponse · RefundListResponse)과 맞춘다 */
class BillingContractTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: BillingRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        // 명세 예시 날짜(11/16 출금 완료 · 12/14 다음 출금) 사이로 고정한다 — 실제 오늘을 쓰면 12/14가 지나 결과가 바뀐다
        repository = BillingRepositoryImpl(server.retrofit().create(BillingApi::class.java)) { LocalDate.of(2026, 11, 17) }
    }

    @After fun tearDown() = server.close()

    @Test fun `카드를 고르지 않으면 userCardId를 보내지 않는다`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"items":[],"summary":{"nextWithdrawal":null,"unpaidCount":0}}}"""))
        repository.bills()
        val request = server.takeRequest()
        assertEquals("/billings", request.url.encodedPath)
        assertNull(request.url.queryParameter("userCardId"))
    }

    @Test fun `카드 상세에서 오면 그 카드 번호만 쿼리로`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"items":[],"summary":null}}"""))
        repository.bills(userCardId = 15)
        assertEquals("15", server.takeRequest().url.queryParameter("userCardId"))
    }

    @Test fun `목록 응답 — 명세 예시 그대로 읽는다`() = runTest {
        server.enqueue(json(200, BE_LIST))
        val list = (repository.bills() as ApiResult.Success).data
        assertEquals(2, list.bills.size)
        assertEquals(BillState.ACCUMULATING, list.bills[0].state)
        assertEquals(176_500L, list.bills[0].amount)
        assertEquals("싸피은행 ·1234", list.bills[0].account)
        assertEquals(87_000L, list.bills[1].amount)
        assertEquals(95L, list.next?.billId)
        assertTrue(list.nextIsEstimate)
    }

    @Test fun `남의 청구서 · 없는 청구서는 404 — 둘을 가르지 않는다`() = runTest {
        server.enqueue(errorJson(404, "RESOURCE_NOT_FOUND"))
        val r = repository.bill(999)
        assertEquals("/billings/999", server.takeRequest().url.encodedPath)
        assertTrue((r as ApiResult.Failure).error is ApiError.NotFound)
    }

    @Test fun `남의 카드로 목록을 부르면 404`() = runTest {
        server.enqueue(errorJson(404, "RESOURCE_NOT_FOUND"))
        assertTrue((repository.bills(userCardId = 77) as ApiResult.Failure).error is ApiError.NotFound)
    }

    @Test fun `상세 응답 — 회차 · 청구할인 · 출금 · 환급`() = runTest {
        server.enqueue(json(200, BE_DETAIL))
        val d = (repository.bill(88) as ApiResult.Success).data
        assertEquals(BillState.PAID, d.bill.state)
        assertEquals("1021", d.cardLast4)
        assertEquals(listOf("할부 2/3회차", "일시불"), d.usages.map { it.planLabel })
        assertEquals(UsageState.REFUND_PENDING, d.usages[1].state)
        assertEquals("스타벅스 역삼점", d.refunds.single().merchant)
        assertEquals(RefundState.PENDING, d.refunds.single().state)
        assertEquals(3_000L, d.discounts.single().amount)
        assertTrue(!d.discountsEstimated)
        // 출금 성공 시각(UTC 22:00) → 한국 날짜로 다음 날
        assertEquals("2026-11-16", d.bill.paidAt?.toLocalDate().toString())
    }

    @Test fun `환급 — 합계 옆에 펼쳐진 페이지 칸을 읽고 page · size를 보낸다`() = runTest {
        server.enqueue(json(200, BE_REFUNDS))
        val page = (repository.refunds(page = 2) as ApiResult.Success).data
        val request = server.takeRequest()
        assertEquals("/refunds", request.url.encodedPath)
        assertEquals("2", request.url.queryParameter("page"))
        assertEquals("20", request.url.queryParameter("size"))
        assertNull(request.url.queryParameter("from"))   // 기간은 BE 기본(최근 3개월)
        assertEquals(2, page.items.size)
        assertEquals(true, page.hasNext)
        assertEquals(42_000L, page.summary.pendingAmount)
        assertEquals(12_000L, page.summary.estimatedAmount)
        assertEquals("p1017", page.items[1].key)          // 체크카드 환불은 환급 번호가 없다
        assertTrue(page.items[1].isCheckCancel)
    }

    @Test fun `서버 오류는 Server로 · 화면은 다시 시도`() = runTest {
        server.enqueue(errorJson(500, "INTERNAL_ERROR"))
        assertTrue((repository.refunds(1) as ApiResult.Failure).error is ApiError.Server)
    }

    @Test fun `서버에 닿지 않으면 Network`() = runTest {
        server.close()
        assertEquals(ApiError.Network, (repository.bills() as ApiResult.Failure).error)
    }

    @Test fun `응답을 읽지 못하면 PARSE_ERROR — 앱이 죽지 않는다`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":{"items":"이상한 값"}}"""))
        assertEquals("PARSE_ERROR", (repository.bills() as ApiResult.Failure).error.code)
    }

    private companion object {
        // 노션 API 명세 V2 「청구서 목록」 예시 + BE가 더 내는 칸(unpaidAmount)
        const val BE_LIST = """{"success":true,"error":null,"data":{"items":[
          {"billId":95,"userCardId":15,"nickname":"메인 신용카드","billingMonth":"2026-12","usagePeriod":{"from":"2026-11-01","to":"2026-11-30"},
           "closingDate":"2026-11-30","paymentDate":"2026-12-14","withdrawalDate":"2026-12-14","status":"ACCUMULATING","itemCount":4,
           "itemAmount":182000,"billingDiscount":5500,"expectedAmount":176500,"finalAmount":null,"unpaidAmount":null,"confirmedAt":null,"paidAt":null,
           "withdrawalAccount":{"accountId":3,"bankName":"싸피은행","maskedAccountNo":"****-****-****-1234"}},
          {"billId":88,"userCardId":15,"nickname":"메인 신용카드","billingMonth":"2026-11","usagePeriod":{"from":"2026-10-01","to":"2026-10-31"},
           "closingDate":"2026-10-31","paymentDate":"2026-11-14","withdrawalDate":"2026-11-16","status":"PAID","itemCount":9,
           "itemAmount":90000,"billingDiscount":3000,"expectedAmount":null,"finalAmount":87000,"unpaidAmount":null,
           "confirmedAt":"2026-10-31T15:30:00Z","paidAt":"2026-11-15T22:00:12Z",
           "withdrawalAccount":{"accountId":3,"bankName":"싸피은행","maskedAccountNo":"****-****-****-1234"}}],
          "summary":{"nextWithdrawal":{"billId":95,"date":"2026-12-14","expectedAmount":176500},"unpaidCount":0}}}"""

        const val BE_DETAIL = """{"success":true,"data":{"billId":88,"userCardId":15,"nickname":"메인 신용카드","maskedNumber":"****-****-****-1021",
          "billingMonth":"2026-11","usagePeriod":{"from":"2026-10-01","to":"2026-10-31"},"closingDate":"2026-10-31","paymentDate":"2026-11-14",
          "withdrawalDate":"2026-11-16","status":"PAID",
          "amounts":{"itemAmount":90000,"billingDiscount":3000,"expectedAmount":null,"finalAmount":87000,"unpaidAmount":null},
          "withdrawalAccount":{"accountId":3,"bankName":"싸피은행","maskedAccountNo":"****-****-****-1234"},
          "items":[
            {"billItemId":7002,"paymentId":1030,"approvedAt":"2026-10-20T03:00:00Z","merchantName":"하이마트","plan":"INSTALLMENT","seq":2,"totalSeq":3,
             "amount":40000,"status":"PAID","principalAmount":40000,"feeAmount":0,"installmentPolicy":"YAMU_INTEREST_FREE"},
            {"billItemId":7001,"paymentId":1024,"approvedAt":"2026-10-05T06:02:00Z","merchantName":"스타벅스 역삼점","plan":"LUMP_SUM","seq":1,"totalSeq":1,
             "amount":30000,"status":"REFUND_PENDING","principalAmount":30000,"feeAmount":null,"installmentPolicy":null}],
          "discounts":[{"paymentBenefitId":5,"paymentId":1024,"name":"스타벅스 청구할인","amount":3000,"confirmedAt":"2026-10-31T15:30:00Z"}],
          "withdrawal":{"attemptedAt":"2026-11-15T22:00:12Z","result":"SUCCESS","failureReason":null,"finTransactionNo":"T1","accountBalanceAfter":null},
          "refunds":[{"refundId":91,"billItemId":7001,"paymentId":1024,"amount":30000,"status":"PENDING","completedAt":null}],
          "history":[{"status":"ACCUMULATING","at":"2026-10-01T00:00:00Z"}]}}"""

        const val BE_REFUNDS = """{"success":true,"data":{"summary":{"pendingAmount":42000,"pendingCount":2,"estimatedAmount":12000,"estimatedCount":1},
          "items":[
            {"refundId":92,"paymentId":1024,"billItemId":7002,"billId":95,"userCardId":15,"nickname":"메인 신용카드","merchantName":"스타벅스 역삼점",
             "reason":"CANCEL_AFTER_CONFIRM","amount":30000,"estimated":false,"status":"PENDING","createdAt":"2026-12-02T06:02:00Z",
             "scheduledAfter":null,"dueDate":"2026-12-17","completedAt":null,"finTransactionNo":null,
             "toAccount":{"accountId":3,"bankName":"싸피은행","maskedAccountNo":"****-****-****-1234"}},
            {"refundId":null,"paymentId":1017,"billItemId":null,"billId":null,"userCardId":12,"nickname":"생활비 카드","merchantName":"올리브영 강남점",
             "reason":"CHECK_CANCEL","amount":14250,"estimated":false,"status":"COMPLETED","createdAt":"2026-10-18T02:20:41Z",
             "scheduledAfter":null,"dueDate":"2026-10-18","completedAt":"2026-10-18T02:20:42Z","finTransactionNo":null,"toAccount":null}],
          "page":2,"size":20,"totalElements":42,"totalPages":3,"hasNext":true}}"""
    }
}
