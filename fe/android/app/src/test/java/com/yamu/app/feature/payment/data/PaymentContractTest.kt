package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 결제 요청 · 승인을 BE 모양과 맞춘다 — PaymentRequestView · PaymentApprovalRequest */
class PaymentContractTest {

    @Test fun `승인 본문에는 카드만 — plan이 오면 BE가 400`() {
        val json = YamuJson.parseToJsonElement(YamuJson.encodeToString(ApproveRequest(7))).jsonObject
        assertEquals(setOf("userCardId"), json.keys)
    }

    @Test fun `POS가 찍은 뒤 상태 응답 — 카드사 없는 후보 · 할부`() {
        val status = YamuJson.decodeFromString<BarcodeStatusResponse>(
            """{"status":"SCANNED","expiresAt":"2026-11-03T01:01:00Z","payment":{
              "paymentId":9,"status":"REQUESTED",
              "merchant":{"merchantId":3,"name":"야무 테스트 카페","categoryCode":"COFFEE_SHOP","channel":"OFFLINE"},
              "amount":15000,"plan":"INSTALLMENT","installmentMonths":3,"confirmDeadline":"2026-11-03T01:01:00Z",
              "recommendation":{"recommendationId":"r1","calculatedAt":"2026-11-03T01:00:00Z","status":"READY",
                "candidates":[{"rank":1,"userCardId":7,"nickname":"Mr.Life","cardType":"CREDIT","maskedNumber":"****-1234",
                  "netBenefit":500,"benefits":[{"benefitId":1,"name":"카페 할인","type":"BILLING_DISCOUNT","amount":500,"realization":"EXPECTED"}],
                  "chargedAmount":15000,"reason":"혜택이 가장 커요","selectionReason":"MAX_BENEFIT"}],
                "noBenefit":false,"excludedCount":0,"uncomparedCards":[],"noBenefitNotice":null,"defaultCardApplied":false}}}""",
        )
        assertEquals(3, status.payment?.installmentMonths)
        val request = status.toStatus().request!!
        assertEquals("야무 테스트 카페", request.merchantName)
        assertNull(request.categoryName)
        assertEquals(7L, request.cards.single().userCardId)
        assertEquals("1234", request.cards.single().last4)
    }

    @Test fun `가맹점 이름이 null이어도 응답 전체가 실패하지 않는다`() {
        val merchant = YamuJson.decodeFromString<MerchantRefDto>("""{"merchantId":3,"name":null}""")
        assertEquals("가맹점", merchant.name)
    }
}
