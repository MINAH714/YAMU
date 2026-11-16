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
              "merchant":{"merchantId":3,"name":"오월커피","categoryCode":"COFFEE_SHOP","channel":"OFFLINE"},
              "amount":15000,"plan":"INSTALLMENT","installmentMonths":3,"confirmDeadline":"2026-11-03T01:01:00Z",
              "recommendation":{"recommendationId":"r1","calculatedAt":"2026-11-03T01:00:00Z","status":"READY",
                "candidates":[{"rank":1,"userCardId":7,"nickname":"Mr.Life","cardType":"CREDIT","maskedNumber":"****-1234",
                  "netBenefit":500,"benefits":[{"benefitId":1,"name":"카페 할인","type":"BILLING_DISCOUNT","amount":500,"realization":"EXPECTED"}],
                  "chargedAmount":15000,"reason":"혜택이 가장 커요","selectionReason":"MAX_BENEFIT"}],
                "noBenefit":false,"excludedCount":0,"uncomparedCards":[],"noBenefitNotice":null,"defaultCardApplied":false}}}""",
        )
        assertEquals(3, status.payment?.installmentMonths)
        val request = status.toStatus().request!!
        assertEquals("오월커피", request.merchantName)
        assertNull(request.categoryName)
        assertEquals(7L, request.cards.single().userCardId)
        assertEquals("1234", request.cards.single().last4)
    }

    /** BE PaymentRequestView.Recommendation · UncomparedCard 그대로 — 공유 한도 배분 미정 혜택은 amount null */
    @Test fun `일부 준비 응답 — 비교 못 한 카드 · 혜택 없음 문구 · 금액 null 혜택`() {
        val status = YamuJson.decodeFromString<BarcodeStatusResponse>(
            """{"status":"SCANNED","expiresAt":"2026-11-09T01:01:00Z","payment":{
              "paymentId":10,"status":"REQUESTED",
              "merchant":{"merchantId":3,"name":"스타벅스 역삼점","categoryCode":"COFFEE_SHOP","channel":"OFFLINE"},
              "amount":15000,"plan":"LUMP_SUM","installmentMonths":null,"confirmDeadline":"2026-11-09T01:01:00Z",
              "recommendation":{"recommendationId":"rec_1","calculatedAt":"2026-11-09T01:00:00","status":"PARTIAL_READY",
                "candidates":[{"rank":1,"userCardId":7,"nickname":"톡톡","cardType":"CREDIT","maskedNumber":"****-****-****-1021",
                  "netBenefit":7500,"benefits":[
                    {"benefitId":1,"name":"교보문고 10% 할인","type":"DISCOUNT","amount":0,"realization":"AT_BILLING"},
                    {"benefitId":2,"name":"스타벅스 50% 청구할인","type":"DISCOUNT","amount":7500,"realization":"AT_BILLING"},
                    {"benefitId":3,"name":"영화 할인","type":"DISCOUNT","amount":null,"realization":"AT_BILLING"},
                    {"benefitId":4,"name":"편의점 할인","type":"DISCOUNT","amount":null,"realization":"AT_BILLING"}],
                  "chargedAmount":15000,"reason":"스타벅스 50% 청구할인 7,500원","selectionReason":"MAXIMUM_NET_BENEFIT",
                  "unallocatedBenefitGroups":[{"benefitIds":[3,4],"limitGroupId":9,"totalAmount":0,"unit":"KRW","reasonCode":"SHARED_LIMIT_ALLOCATION_UNRESOLVED"}]}],
                "noBenefit":false,"excludedCount":1,
                "uncomparedCards":[
                  {"userCardId":8,"nickname":"ZERO","cardType":"CREDIT","maskedNumber":"****-****-****-3310",
                   "reason":"BENEFIT_DATA_INSUFFICIENT","message":"혜택 정보를 확인하지 못해 비교에서 제외했어요.","directPaymentAllowed":true},
                  {"userCardId":9,"nickname":"노리2","cardType":"CHECK","maskedNumber":"****-****-****-8823",
                   "reason":"PAYMENT_AVAILABILITY_UNKNOWN","message":"결제 가능 여부를 확인하지 못해 비교에서 제외했어요.","directPaymentAllowed":false}],
                "noBenefitNotice":"혜택 정보를 확인할 수 없어 혜택 없이 결제됩니다.","defaultCardApplied":false}}}""",
        )
        val request = status.toStatus().request!!
        assertEquals(listOf(7L, 8L, 9L), request.cards.map { it.userCardId })
        assertEquals("스타벅스 50% 청구할인", request.cards[0].benefitName)
        val (zero, nori) = request.cards.drop(1)
        assertEquals(listOf(true, true), listOf(zero.benefitUnknown, nori.benefitUnknown))
        assertEquals(listOf(true, false), listOf(zero.selectable, nori.selectable))
        assertEquals("3310", zero.last4)
        assertEquals(true, nori.isCheck)
        assertEquals("결제 가능 여부를 확인하지 못해 비교에서 제외했어요.", nori.reason)
        assertEquals("혜택 정보를 확인할 수 없어 혜택 없이 결제됩니다.", request.noBenefitNotice)
    }

    @Test fun `가맹점 이름이 null이어도 응답 전체가 실패하지 않는다`() {
        val merchant = YamuJson.decodeFromString<MerchantRefDto>("""{"merchantId":3,"name":null}""")
        assertEquals("가맹점", merchant.name)
    }
}
