package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.link.data.IssuerDto
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

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

    private fun netBenefitOf(field: String) = YamuJson.decodeFromString(
        CandidateDto.serializer(), """{"userCardId":1$field}""",
    ).toPayCard().netBenefit

    @Test fun `순혜택은 소수도 정수도 서버 값 그대로 읽는다`() {
        assertEquals(BigDecimal("1.5"), netBenefitOf(""","netBenefit":1.5"""))
        assertEquals(BigDecimal("1500"), netBenefitOf(""","netBenefit":1500"""))
        assertEquals(BigDecimal("-1.5"), netBenefitOf(""","netBenefit":-1.5"""))
    }

    @Test fun `순혜택이 없거나 null이면 0원`() {
        assertEquals(BigDecimal.ZERO, netBenefitOf(""))
        assertEquals(BigDecimal.ZERO, netBenefitOf(""","netBenefit":null"""))
    }

    @Test fun `순혜택이 숫자가 아니면 파싱 오류로 처리된다`() {
        assertThrows(SerializationException::class.java) { netBenefitOf(""","netBenefit":"abc"""") }
        assertThrows(SerializationException::class.java) { netBenefitOf(""","netBenefit":{}""") }
    }

    @Test fun `소수 순혜택 카드가 섞여도 결제 요청 화면 값으로 바뀐다`() {
        val status = scanned(""","recommendation":{"candidates":[{"rank":1,"userCardId":7,"netBenefit":1.5},{"rank":2,"userCardId":8,"netBenefit":1}]}""")
        val cards = status.request!!.cards
        assertEquals(listOf(7L, 8L), cards.map { it.userCardId })
        assertEquals(BigDecimal("0.5"), cards[0].netBenefit - cards[1].netBenefit)   // "0.5원 더 받아요"
    }

    @Test fun `찍혔다는데 요청이 없으면 아직 안 찍힌 것으로`() {
        val status = BarcodeStatusResponse(status = "SCANNED", payment = null).toStatus()
        assertEquals(CodeState.ISSUED, status.state)
        assertNull(status.request)
        assertEquals(CodeState.EXPIRED, BarcodeStatusResponse(status = "EXPIRED").toStatus().state)
    }

    private fun scanned(recommendation: String) = YamuJson.decodeFromString(
        BarcodeStatusResponse.serializer(),
        """{"status":"SCANNED","payment":{"paymentId":1024,"merchant":{"merchantId":301,"name":"야무카페 역삼점"},"amount":15000$recommendation}}""",
    ).toStatus()

    @Test fun `찍혔는데 쓸 수 있는 카드가 없으면 요청은 두고 카드만 비운다`() {
        val status = scanned(""","recommendation":{"status":"NO_ELIGIBLE_CARD","candidates":[],"excludedCount":2}""")
        assertEquals(CodeState.SCANNED, status.state)
        assertTrue(status.request!!.cards.isEmpty())
        assertFalse(status.request.cardsUnknown)
    }

    @Test fun `데이터 부족인데 비교 못 한 카드도 안 오면(옛 응답) 카드를 확인하지 못한 것으로`() {
        val status = scanned(""","recommendation":{"status":"DATA_INSUFFICIENT","candidates":[]}""")
        assertTrue(status.request!!.cards.isEmpty())
        assertTrue(status.request.cardsUnknown)
    }

    private val direct = """{"userCardId":21,"nickname":"ZERO","cardType":"CREDIT","maskedNumber":"****-3310","reason":"BENEFIT_DATA_INSUFFICIENT","message":"혜택 정보를 확인하지 못해 비교에서 제외했어요.","directPaymentAllowed":true}"""
    private val unknown = """{"userCardId":22,"nickname":"노리2","cardType":"CHECK","maskedNumber":"****-8823","reason":"PAYMENT_AVAILABILITY_UNKNOWN","message":"결제 가능 여부를 확인하지 못해 비교에서 제외했어요.","directPaymentAllowed":false}"""
    private val ranked = """{"rank":2,"userCardId":12,"netBenefit":180},{"rank":1,"userCardId":11,"netBenefit":7500}"""

    @Test fun `준비됨 — 추천 순위 카드만, 앱이 고를 수 있는 건 모두`() {
        val request = scanned(""","recommendation":{"status":"READY","candidates":[$ranked],"uncomparedCards":[]}""").request!!
        assertEquals(listOf(11L, 12L), request.cards.map { it.userCardId })
        assertTrue(request.cards.all { it.autoSelectable && !it.benefitUnknown })
        assertFalse(request.cardsUnknown)
        assertEquals(NO_BENEFIT_NOTICE, request.noBenefitNotice)   // 서버가 안 보내면 BE 문구와 같은 기본값
    }

    @Test fun `일부 준비 — 추천 카드 뒤에 비교 못 한 카드, 결제 가능 여부를 모르는 카드는 고를 수 없다`() {
        val request = scanned(
            ""","recommendation":{"status":"PARTIAL_READY","candidates":[$ranked],"uncomparedCards":[$direct,$unknown],"noBenefitNotice":"혜택 없이 결제됩니다."}""",
        ).request!!
        assertEquals(listOf(11L, 12L, 21L, 22L), request.cards.map { it.userCardId })
        assertEquals(listOf(true, true, false, false), request.cards.map { it.autoSelectable })
        assertEquals(listOf(true, true, true, false), request.cards.map { it.selectable })
        val zero = request.cards[2]
        assertTrue(zero.benefitUnknown)
        assertEquals(BigDecimal.ZERO, zero.netBenefit)
        assertNull(zero.benefitName)
        assertEquals("혜택 정보를 확인하지 못해 비교에서 제외했어요.", zero.reason)
        assertEquals("혜택 없이 결제됩니다.", request.noBenefitNotice)
    }

    @Test fun `데이터 부족 — 1순위 없이 비교 못 한 카드를 보이고 확인하지 못한 것으로 두지 않는다`() {
        val request = scanned(""","recommendation":{"status":"DATA_INSUFFICIENT","candidates":[],"uncomparedCards":[$direct,$unknown]}""").request!!
        assertEquals(listOf(21L, 22L), request.cards.map { it.userCardId })
        assertTrue(request.cards.none { it.autoSelectable })
        assertFalse(request.cardsUnknown)
    }

    @Test fun `데이터 부족인데 결제 가능 여부를 모르는 카드만 — 보이되 고를 카드는 없다`() {
        val request = scanned(""","recommendation":{"status":"DATA_INSUFFICIENT","candidates":[],"uncomparedCards":[$unknown]}""").request!!
        assertEquals(listOf(22L), request.cards.map { it.userCardId })
        assertTrue(request.cards.none { it.selectable })
        assertFalse(request.cardsUnknown)
    }

    private fun nameShown(benefits: String) = YamuJson.decodeFromString(
        CandidateDto.serializer(), """{"userCardId":1,"benefits":[$benefits]}""",
    ).toPayCard().benefitName

    @Test fun `혜택 이름은 금액이 붙은 혜택 중 가장 큰 것 — 0원 혜택이 앞에 와도`() {
        assertEquals(
            "스타벅스 50% 청구할인",
            nameShown("""{"name":"교보문고 10% 할인","amount":0},{"name":"1.2% 적립","amount":180},{"name":"스타벅스 50% 청구할인","amount":7500}"""),
        )
    }

    @Test fun `금액이 붙은 혜택이 없으면 혜택 이름을 비운다 — 0원 · 배분 미정(null)`() {
        assertNull(nameShown("""{"name":"교보문고 10% 할인","amount":0},{"name":"영화 할인","amount":null}"""))
        assertNull(nameShown(""))
    }

    @Test fun `추천 스냅샷이 없어도 찍힌 것으로 두고 카드를 확인하지 못한 것으로`() {
        val status = scanned("")
        assertEquals(CodeState.SCANNED, status.state)
        assertTrue(status.request!!.cardsUnknown)
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
