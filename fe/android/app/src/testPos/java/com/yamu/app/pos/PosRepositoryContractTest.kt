package com.yamu.app.pos

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.YamuJson
import com.yamu.app.core.network.errorJson
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 모의 POS가 BE와 주고받는 모양 — PosPaymentResultController · PosPaymentRequestController · MerchantCatalogController */
class PosRepositoryContractTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: PosRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repository = PosRepositoryImpl(server.retrofit().create(PosApi::class.java), "test-pos-key")
    }

    @After fun tearDown() = server.close()

    private val cafe = POS_TERMINALS.first { it.posCode == "POS-YAMU-CAFE" }

    @Test fun `결과 조회 — 결제를 만든 단말의 posCode와 단말 키를 싣는다`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"paymentId":9,"orderNo":"POS-20261109-101500-042","status":"APPROVED",
                  "approvalNo":"Y20261109101530009","approvedAt":"2026-11-09T01:15:30Z","amount":15000,"chargedAmount":13500,
                  "instantDiscount":1500,"card":{"issuerName":"신한카드","cardType":"CREDIT","maskedNumber":"****-****-****-1021",
                  "plan":"INSTALLMENT","installmentMonths":3},"failure":null,"retryable":null}}""",
            ),
        )
        val result = (repository.result(9, "POS-YAMU-CAFE") as ApiResult.Success).data
        val request = server.takeRequest()
        assertEquals("/pos/payment-requests/9", request.url.encodedPath)
        assertEquals("POS-YAMU-CAFE", request.url.queryParameter("posCode"))
        assertEquals("test-pos-key", request.headers[POS_KEY_HEADER])
        assertEquals(PosStatus.APPROVED, result.status)
        assertEquals(13_500L, result.chargedAmount)
        assertEquals(1_500L, result.instantDiscount)
        assertEquals("1021", result.cardLast4)
        assertEquals(3, result.installmentMonths)
    }

    @Test fun `결과 조회 — 승인 전에는 카드가 null이어도 읽는다`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"paymentId":9,"orderNo":null,"status":"REQUESTED","approvalNo":null,"approvedAt":null,
                  "amount":15000,"chargedAmount":null,"instantDiscount":null,"card":null,"failure":null,"retryable":null}}""",
            ),
        )
        val result = (repository.result(9, "POS-YAMU-CAFE") as ApiResult.Success).data
        assertEquals(PosStatus.WAITING, result.status)
        assertNull(result.cardIssuer)
        assertNull(result.instantDiscount)
    }

    @Test fun `결과 조회 — 다른 단말의 결제는 404`() = runTest {
        server.enqueue(errorJson(404, "RESOURCE_NOT_FOUND"))
        val error = (repository.result(9, "POS-GS25-GENERAL") as ApiResult.Failure).error
        assertTrue(error is ApiError.NotFound)
    }

    @Test fun `가맹점 — 업종 목록에서 단말 브랜드의 번호를 찾는다`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":[
                  {"merchantId":3,"brandCode":"STARBUCKS","merchantName":"스타벅스","merchantCategoryCode":"COFFEE_SHOP"},
                  {"merchantId":12,"brandCode":"YAMU_TEST_CAFE","merchantName":"오월커피","merchantCategoryCode":"COFFEE_SHOP"}]}""",
            ),
        )
        val merchant = (repository.merchantFor(cafe) as ApiResult.Success).data
        assertEquals(PosMerchant(12, "POS-YAMU-CAFE", "오월커피"), merchant)
        val request = server.takeRequest()
        assertEquals("/merchants", request.url.encodedPath)
        assertEquals("COFFEE_SHOP", request.url.queryParameter("merchantCategoryCode"))
        assertNull(request.url.queryParameter("size"))
    }

    @Test fun `가맹점 — 서버에 그 브랜드가 없으면 MERCHANT_NOT_FOUND`() = runTest {
        server.enqueue(json(200, """{"success":true,"data":[{"merchantId":3,"brandCode":"STARBUCKS","merchantName":"스타벅스","merchantCategoryCode":"COFFEE_SHOP"}]}"""))
        val error = (repository.merchantFor(cafe) as ApiResult.Failure).error
        assertEquals("MERCHANT_NOT_FOUND", error.code)
    }

    @Test fun `결제 요청 — 멱등 키는 헤더로, 본문은 일시불이라 plan 칸이 없다`() = runTest {
        server.enqueue(json(201, """{"success":true,"data":{"paymentId":41,"orderNo":"POS-1","status":"REQUESTED","confirmDeadline":"2026-11-09T01:16:30"}}"""))
        val attempt = PosAttempt("6f1c9a62-3b1e-4a55-9d0c-1f0e6f7e2a10", PosPaymentRequest("YAMU8F2A", 12, "POS-YAMU-CAFE", 15_000, "POS-1"))
        assertEquals(41L, (repository.request(attempt) as ApiResult.Success).data)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pos/payment-requests", request.url.encodedPath)
        assertEquals(attempt.idempotencyKey, request.headers[YamuHeaders.IDEMPOTENCY_KEY])
        assertEquals("test-pos-key", request.headers[POS_KEY_HEADER])
        val body = YamuJson.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
        assertEquals(setOf("barcodeToken", "merchantId", "posCode", "amount", "orderNo"), body.keys)
    }

    @Test fun `거절 — retryable이 true면 기다리고 false면 멈춘다`() {
        fun declined(retryable: Boolean?) =
            PosResultDto(paymentId = 9, status = "DECLINED", failure = PosFailureDto("INSUFFICIENT_BALANCE", "잔액이 부족해요"), retryable = retryable).toResult()
        assertEquals(PosStatus.RETRYING, declined(true).status)
        assertFalse(declined(true).status.isFinal)
        assertEquals("잔액이 부족해요", declined(true).failureMessage)
        assertEquals(PosStatus.DECLINED, declined(false).status)
        assertTrue(declined(false).status.isFinal)
        assertEquals(PosStatus.RETRYING, declined(null).status)   // 비어 오면 마감까지 기다린다(서버가 EXPIRED로 바꿈)
    }

    @Test fun `출금 미확인으로 끝난 거절 · 만료는 확정 실패가 아니라 출금 확인 중`() {
        fun result(status: String, code: String, retryable: Boolean? = null) =
            PosResultDto(paymentId = 9, status = status, failure = PosFailureDto(code, "결제 결과를 확인하지 못했습니다."), retryable = retryable).toResult()
        assertEquals(PosStatus.CHECKING, result("DECLINED", POS_WITHDRAWAL_NOT_CONFIRMED, retryable = false).status)
        assertEquals(PosStatus.CHECKING, result("EXPIRED", POS_WITHDRAWAL_NOT_CONFIRMED).status)
        assertFalse(PosStatus.CHECKING.isFinal)
        assertEquals("결제 결과를 확인하지 못했습니다.", result("EXPIRED", POS_WITHDRAWAL_NOT_CONFIRMED).failureMessage)
        // 마감 전이면 고객이 다른 카드로 다시 고를 수 있어 지금처럼 RETRYING(BE는 재승인 직전에도 늦은 출금을 본다)
        assertEquals(PosStatus.RETRYING, result("DECLINED", POS_WITHDRAWAL_NOT_CONFIRMED, retryable = true).status)
        assertEquals(PosStatus.DECLINED, result("DECLINED", "INSUFFICIENT_BALANCE", retryable = false).status)
        assertEquals(PosStatus.EXPIRED, result("EXPIRED", "INSUFFICIENT_BALANCE").status)
    }

    @Test fun `상태 — 승인 · 만료 · 취소 · 고객 거절은 최종, 접수 · 처리 중은 기다림`() {
        fun status(s: String) = PosResultDto(paymentId = 9, status = s).toResult().status
        assertEquals(PosStatus.APPROVED, status("APPROVED"))
        assertEquals(PosStatus.EXPIRED, status("EXPIRED"))
        assertEquals(PosStatus.CANCELED, status("CANCELED"))
        assertEquals(PosStatus.REJECTED, status("REJECTED"))
        assertEquals(PosStatus.PROCESSING, status("PROCESSING"))
        assertEquals(PosStatus.WAITING, status("REQUESTED"))
        assertTrue(listOf("APPROVED", "EXPIRED", "CANCELED", "REJECTED").all { status(it).isFinal })
        assertFalse(listOf("REQUESTED", "PROCESSING").any { status(it).isFinal })
    }

    @Test fun `고객 거절 — 앞 카드의 거절 사유가 남아 와도 고객 거절로 본다`() {
        // BE reject()는 DECLINED에서도 REJECTED가 되고 앞 시도의 카드 · 사유를 남긴다(PaymentTransaction)
        val result = PosResultDto(
            paymentId = 9, status = "REJECTED", failure = PosFailureDto("INSUFFICIENT_BALANCE", "잔액이 부족합니다."), retryable = null,
        ).toResult()
        assertEquals(PosStatus.REJECTED, result.status)
        assertTrue(result.status.isFinal)
    }

    @Test fun `단말 목록 — BE yamu pos terminals 네 개와 같다`() {
        assertEquals(
            listOf(
                "POS-YAMU-CAFE" to "YAMU_TEST_CAFE",
                "POS-STARBUCKS-GENERAL" to "STARBUCKS",
                "POS-STARBUCKS-DEPARTMENT_STORE" to "STARBUCKS",
                "POS-GS25-GENERAL" to "GS25",
            ),
            POS_TERMINALS.map { it.posCode to it.brandCode },
        )
    }
}
