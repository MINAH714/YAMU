package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.link.data.IssuerDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #12 · #1 · #3 응답 → 화면 값(서버 값 그대로 — 앱이 빼고 더하지 않음) */
class PaymentMapperTest {
    @Test fun `찍힘 — 추천 2장을 순서대로, 카드사는 issuer가 있으면 그걸`() {
        val json = """
            {"status":"SCANNED","expiresAt":"2026-10-22T14:33:00+09:00",
             "payment":{"paymentId":1024,"status":"REQUESTED","merchant":{"merchantId":301,"name":"야무카페 역삼점","categoryName":"카페"},
              "amount":15000,"confirmDeadline":"2026-10-22T14:31:40+09:00",
              "recommendation":{"candidates":[
                {"rank":2,"userCardId":12,"nickname":"생활비 카드","cardType":"CHECK","maskedNumber":"9410-****-****-5678","netBenefit":750,
                 "benefits":[{"name":"카페 5% 즉시할인","type":"INSTANT_DISCOUNT","amount":750}],"chargedAmount":14250,"installmentOptions":[]},
                {"rank":1,"userCardId":15,"nickname":"메인 신용카드","issuer":{"code":"HANA","name":"하나카드","color":"#009490"},"cardType":"CREDIT",
                 "maskedNumber":"4570-****-****-1234","netBenefit":1500,"benefits":[{"name":"카페 10% 청구할인","amount":1500}],
                 "chargedAmount":15000,"installmentOptions":[2,3,6],"newField":"무시"}
              ]}}}
        """.trimIndent()
        val status = YamuJson.decodeFromString(BarcodeStatusResponse.serializer(), json).toStatus()
        assertEquals(CodeState.SCANNED, status.state)
        val request = status.request!!
        assertEquals(1024L, request.paymentId)
        assertEquals(listOf(15L, 12L), request.cards.map { it.userCardId })
        val best = request.cards.first()
        assertEquals("하나카드", best.brand.name)
        assertEquals("1234", best.last4)
        assertEquals(listOf(2, 3, 6), best.installmentOptions)
        assertTrue(request.cards[1].isCheck)
        assertEquals("생활비 카드", request.cards[1].brand.name)   // issuer가 없으면 별명으로
    }

    @Test fun `찍혔다는데 요청이 없으면 아직 안 찍힌 것으로`() {
        val status = BarcodeStatusResponse(status = "SCANNED", payment = null).toStatus()
        assertEquals(CodeState.ISSUED, status.state)
        assertNull(status.request)
        assertEquals(CodeState.EXPIRED, BarcodeStatusResponse(status = "EXPIRED").toStatus().state)
    }

    @Test fun `신용 할부 승인 — 첫 회차 청구일 · 금액, 실적`() {
        val dto = PaymentResultDto(
            paymentId = 1024, status = "APPROVED", amount = 90_000, plan = "INSTALLMENT", installmentMonths = 3, chargedAmount = 90_000,
            selectedCard = SelectedCardDto(15, "메인 신용카드", issuer = IssuerDto("HANA", "하나카드", "#009490"), maskedNumber = "4570-****-****-1234"),
            approvalNo = "Y20261022143105001", approvedAt = "2026-10-22T14:31:05+09:00",
            benefits = listOf(BenefitDto("카페 10% 청구할인", amount = 3_000)),
            billItems = listOf(BillItemDto(2, 30_000, expectedWithdrawalDate = "2026-12-14"), BillItemDto(1, 30_000, expectedWithdrawalDate = "2026-11-16")),
            performance = PaidPerformanceDto("2026-10", 90_000, recognized = true),
        )
        val request = PaymentRequest(1024, "야무마트 강남점", "대형마트", 90_000, null, emptyList())
        val r = dto.toReceipt(request)
        assertEquals("야무마트 강남점", r.merchantName)          // 승인 응답엔 가맹점이 없어 요청에서
        assertEquals("하나카드", r.cardBrand?.name)
        assertEquals(3, r.installmentMonths)
        assertEquals("2026-11-16", r.billDateIso)               // 1회차
        assertEquals(30_000L, r.billAmount)
        assertEquals(10, r.perfMonth)
        assertEquals(listOf(ReceiptBenefit("카페 10% 청구할인", 3_000)), r.benefits)
    }

    @Test fun `체크 승인 — 청구 줄 없이 출금 · 잔액, 실적 제외`() {
        val dto = PaymentResultDto(
            paymentId = 1030, status = "APPROVED", amount = 15_000, chargedAmount = 14_250, accountBalanceAfter = 935_750,
            selectedCard = SelectedCardDto(12, "생활비 카드", issuerName = "신한카드", cardType = "CHECK"),
            billItems = emptyList(), performance = PaidPerformanceDto("2026-10", 0, recognized = false),
        )
        val r = dto.toReceipt()
        assertTrue(r.isCheck)
        assertNull(r.billAmount)
        assertEquals(14_250L, r.chargedAmount)
        assertEquals(935_750L, r.balanceAfter)
        assertFalse(r.perfCounted)
        assertNull(r.installmentMonths)
    }
}
