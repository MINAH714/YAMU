package com.yamu.app.feature.payment.data

import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.link.data.IssuerDto
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.brandOf
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun listed(id: Long, issuer: String, color: String, product: String, nickname: String? = null) = LinkedCard(
        userCardId = id, brand = brandOf(issuer, color = color), productName = product, isCheck = false, last4 = "0000",
        paymentDay = null, recognizedAmount = null, yearMonth = null, nickname = nickname,
    )

    /** Q-19 — 바코드 응답엔 카드사 · 상품명이 없고 nickname이 별명이라, 그대로는 그림을 못 찾고 카드사 칩에 별명 글자가 나왔다 */
    @Test fun `별명 카드 — 카드 목록에서 같은 카드의 카드사 · 상품명을 붙이고 이름은 별명 그대로`() {
        val request = scanned(
            ""","recommendation":{"status":"PARTIAL_READY","candidates":[{"rank":1,"userCardId":11,"nickname":"점심 카드","netBenefit":7500}],"uncomparedCards":[$direct]}""",
        ).request!!
        val toktok = "KB국민 청춘대로 톡톡카드"
        assertTrue(request.cards.all { it.issuerUnknown })
        assertEquals("점심 카드", request.cards[0].brand.name)          // 붙이기 전 — 별명 글자
        assertNull(CardImages.forProduct(request.cards[0].imageName))

        val filled = request.withListedCards(
            mapOf(11L to listed(11, "KB국민카드", "#FFBC00", toktok, nickname = "점심 카드"), 21L to listed(21, "현대카드", "#1A1A1A", "현대카드ZERO Edition3(포인트형)")),
        )
        val card = filled.cards[0]
        assertEquals("점심 카드", card.name)
        assertEquals("KB국민카드", card.brand.name)
        assertEquals(toktok, card.imageName)
        assertNotNull(CardImages.forProduct(card.imageName))
        assertFalse(card.issuerUnknown)
        assertEquals("현대카드", filled.cards[1].brand.name)              // 비교 못 한 카드도
        assertEquals(listOf(true, false), filled.cards.map { it.autoSelectable })   // 고르기 규칙은 그대로
    }

    @Test fun `응답에 카드사 · 상품명이 있으면 목록 값으로 덮지 않는다`() {
        val request = scanned(
            ""","recommendation":{"candidates":[{"rank":1,"userCardId":15,"nickname":"메인","productName":"하나카드 트래블로그 체크카드","issuer":{"code":"HANA","name":"하나카드","color":"#009490"},"netBenefit":0}]}""",
        ).request!!
        val card = request.withListedCards(mapOf(15L to listed(15, "신한카드", "#0046FF", "신한카드 Mr.Life"))).cards.single()
        assertEquals("하나카드", card.brand.name)
        assertEquals("하나카드 트래블로그 체크카드", card.imageName)
    }

    @Test fun `목록에 없는 카드는 응답 값 그대로 — 목록을 못 받았을 때도`() {
        val request = scanned(""","recommendation":{"candidates":[{"rank":1,"userCardId":11,"nickname":"점심 카드","netBenefit":0}]}""").request!!
        assertEquals(request, request.withListedCards(emptyMap()))
        val card = request.withListedCards(mapOf(99L to listed(99, "신한카드", "#0046FF", "신한카드 Mr.Life"))).cards.single()
        assertEquals("점심 카드", card.brand.name)
        assertEquals("점심 카드", card.imageName)
        assertTrue(card.issuerUnknown)
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

    /** 리뷰 P3 — 원과 포인트는 숫자를 견줄 수 없어(응답에 원화 환산값 없음) 원 단위를 먼저 본다 */
    @Test fun `혜택 이름은 원 단위 혜택을 먼저 — 포인트 숫자가 더 커도`() {
        assertEquals(
            "카페 700원 할인",
            nameShown("""{"name":"1,000P 적립","type":"POINT","amount":1000,"realization":"POINT_ACCRUAL"},{"name":"카페 700원 할인","type":"DISCOUNT","amount":700,"realization":"AT_BILLING"}"""),
        )
        assertEquals(
            "캐시백",
            nameShown("""{"name":"적립","type":null,"amount":5000,"realization":"POINT_ACCRUAL"},{"name":"캐시백","type":"CASHBACK","amount":100,"realization":"AFTER_BILLING"}"""),
        )
    }

    @Test fun `원 단위 혜택이 없을 때만 포인트 중 가장 큰 것`() {
        assertEquals(
            "M포인트 1.2% 적립",
            nameShown("""{"name":"교보문고 할인","type":"DISCOUNT","amount":0},{"name":"기본 적립","type":"POINT","amount":30},{"name":"M포인트 1.2% 적립","type":"POINT","amount":180}"""),
        )
    }

    /** 11/10 QA — BE가 후보마다 같은 문장(1순위 선택 이유 · 최대 순혜택)을 reason으로 보낸다(S15P31E103-411) */
    private val sameReason = "예상 순혜택은 3180원입니다. 이번 결제의 순혜택이 가장 큰 카드를 선택했습니다."
    private val starbucks = """
        {"rank":2,"userCardId":32,"nickname":"신한카드 Mr.Life","cardType":"CREDIT","netBenefit":0,
         "benefits":[{"name":"편의점 10% 할인","type":"DISCOUNT","amount":0}],"reason":"$sameReason"},
        {"rank":1,"userCardId":31,"nickname":"KB국민 노리 체크카드","cardType":"CHECK","netBenefit":3180,
         "benefits":[{"name":"스타벅스 환급 할인","type":"CASHBACK","amount":3180}],"reason":"$sameReason","selectionReason":"MAXIMUM_NET_BENEFIT"}
    """.trimIndent()

    @Test fun `서버 사유는 1순위 카드에만 — 2순위 이하에는 1순위 금액 문장이 붙지 않는다`() {
        val cards = scanned(""","recommendation":{"status":"READY","candidates":[$starbucks]}""").request!!.cards
        assertEquals(listOf(31L, 32L), cards.map { it.userCardId })
        assertEquals(sameReason, cards[0].reason)
        assertNull(cards[1].reason)
        // 2순위는 혜택 이름도 사유도 없어 줄 아래가 비고, 오른쪽에 '혜택 없음'이 보인다(순혜택 0원)
        assertNull(cards[1].benefitName)
        assertEquals(0, cards[1].netBenefit.signum())
    }

    @Test fun `비교 못 한 카드의 서버 문구는 순위와 상관없이 남는다`() {
        val cards = scanned(""","recommendation":{"status":"PARTIAL_READY","candidates":[$starbucks],"uncomparedCards":[$direct]}""").request!!.cards
        assertEquals(listOf(31L, 32L, 21L), cards.map { it.userCardId })
        assertEquals("혜택 정보를 확인하지 못해 비교에서 제외했어요.", cards[2].reason)
    }

    private fun declinedOf(code: String) = YamuJson.decodeFromString(
        PaymentResultDto.serializer(),
        """{"paymentId":1,"status":"DECLINED","failure":{"code":"$code","message":"서버 문구"},"retryable":true,"remainingAttempts":2,"suggestedCards":[{"userCardId":12}]}""",
    ).toDeclined()

    @Test fun `거절 응답 — 다시 결제할 수 있으면 서버가 권한 카드를 그대로`() {
        val o = declinedOf("INSUFFICIENT_BALANCE")
        assertTrue(o.retryable)
        assertEquals(12L, o.suggestedCardId)
        assertEquals(2, o.remainingAttempts)
        assertEquals("연결 계좌 잔액이 부족해요", o.message)
    }

    /** 리뷰 P1 — 늦은 출금이면 BE 복구가 이 결제를 승인으로 바꿀 수 있어, 다시 결제(다른 카드 · 새 코드)를 권하지 않는다 */
    @Test fun `출금 미확인 거절은 서버가 retryable이어도 다시 결제하지 않고 카드도 권하지 않는다`() {
        val o = declinedOf("WITHDRAWAL_NOT_CONFIRMED")
        assertFalse(o.retryable)
        assertNull(o.suggestedCardId)
        assertEquals("결제 결과를 확인하지 못했어요 · 결제 내역에서 결과를 확인해 주세요", o.message)
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

    // ── 즉시할인 · 혜택 시점(11/10 QA) — 실제 결제 금액은 서버 chargedAmount(결제 금액 − 즉시할인) ──

    @Test fun `즉시할인 승인 — 실제 결제 금액은 할인 뒤, 즉시할인은 차이`() {
        val dto = PaymentResultDto(
            paymentId = 2001, status = "APPROVED", amount = 6_600, chargedAmount = 5_940,
            benefits = listOf(BenefitDto("편의점 할인", type = "DISCOUNT", amount = 660, realization = "AT_APPROVAL")),
            billItems = listOf(BillItemDto(1, 5_940, expectedWithdrawalDate = "2026-12-15")),
        )
        val r = dto.toReceipt()
        assertEquals(6_600L, r.amount)
        assertEquals(5_940L, r.paidAmount)
        assertEquals(660L, r.instantDiscount)
        assertEquals(listOf(ReceiptBenefit("편의점 할인", 660, BenefitKind.INSTANT)), r.benefits)
    }

    @Test fun `청구할인만 있으면 결제 금액 그대로 — 청구할인은 빼지 않는다`() {
        val dto = PaymentResultDto(
            paymentId = 2002, status = "APPROVED", amount = 31_800, chargedAmount = 31_800,
            benefits = listOf(BenefitDto("스타벅스 10% 청구할인", type = "DISCOUNT", amount = 3_180, realization = "AT_BILLING")),
        )
        val r = dto.toReceipt()
        assertEquals(31_800L, r.paidAmount)
        assertEquals(0L, r.instantDiscount)
        assertEquals(BenefitKind.BILLING, r.benefits.single().kind)
    }

    @Test fun `즉시할인과 청구할인이 같이 있으면 즉시할인만 빠진다`() {
        val dto = PaymentResultDto(
            paymentId = 2003, status = "APPROVED", amount = 20_000, chargedAmount = 19_000,
            benefits = listOf(
                BenefitDto("즉시 5%", amount = 1_000, realization = "AT_APPROVAL"),
                BenefitDto("청구 3%", amount = 600, realization = "AT_BILLING"),
            ),
        )
        val r = dto.toReceipt()
        assertEquals(19_000L, r.paidAmount)
        assertEquals(1_000L, r.instantDiscount)
        assertEquals(listOf(BenefitKind.INSTANT, BenefitKind.BILLING), r.benefits.map { it.kind })
    }

    @Test fun `chargedAmount가 없거나 결제 금액보다 크면 결제 금액`() {
        val none = PaymentResultDto(paymentId = 2004, status = "APPROVED", amount = 10_000).toReceipt()
        assertEquals(10_000L, none.paidAmount)
        assertEquals(0L, none.instantDiscount)
        val odd = PaymentResultDto(paymentId = 2005, status = "APPROVED", amount = 10_000, chargedAmount = 12_000).toReceipt()
        assertEquals(10_000L, odd.paidAmount)
    }

    // ── 결제 상세 추천 순위 · 직접 고른 카드 · 업종(-394, BE -381 selectedCard.recommendedRank · userSelected) ──

    private fun detailOf(selectedCard: String, merchant: String = """{"merchantId":3,"name":"스타벅스 역삼점","categoryCode":"COFFEE_SHOP","categoryName":"카페"}""") =
        YamuJson.decodeFromString(
            PaymentResultDto.serializer(),
            """{"paymentId":3001,"source":"YAMU","status":"APPROVED","merchant":$merchant,"amount":15900,"selectedCard":$selectedCard}""",
        ).toReceipt()

    @Test fun `결제 카드 표시 — 순위가 있으면 추천 N순위(앱은 늘 카드를 보내 userSelected가 true여도)`() {
        val r = detailOf("""{"userCardId":31,"nickname":"KB국민 노리 체크카드","maskedNumber":"****-****-****-8823","cardType":"CHECK","recommendedRank":1,"userSelected":true}""")
        assertEquals(1, r.recommendedRank)
        assertEquals(true, r.userSelected)
        assertEquals("추천 1순위", r.cardPickLabel)
        assertEquals("추천 2순위", detailOf("""{"userCardId":32,"recommendedRank":2,"userSelected":true}""").cardPickLabel)
    }

    @Test fun `순위 없이 직접 골랐으면(비교 못 한 카드) 직접 고른 카드`() {
        assertEquals("직접 고른 카드", detailOf("""{"userCardId":21,"recommendedRank":null,"userSelected":true}""").cardPickLabel)
    }

    @Test fun `칸이 생기기 전 결제 · SEED(둘 다 null) · 칸이 없는 옛 응답은 비운다`() {
        assertNull(detailOf("""{"userCardId":21,"recommendedRank":null,"userSelected":null}""").cardPickLabel)
        val old = detailOf("""{"userCardId":21,"nickname":"생활비 카드"}""")
        assertNull(old.recommendedRank)
        assertNull(old.userSelected)
        assertNull(old.cardPickLabel)
        assertNull(detailOf("""{"userCardId":21,"recommendedRank":0,"userSelected":false}""").cardPickLabel)   // 0 이하는 순위가 아니다
    }

    @Test fun `상세 업종 이름 — 있으면 받고 목록에 없는 코드(null)면 비운다`() {
        val r = detailOf("""{"userCardId":31,"recommendedRank":1,"userSelected":true}""")
        assertEquals("카페", r.categoryName)
        val unknown = detailOf("""{"userCardId":31}""", merchant = """{"merchantId":9,"name":"새 가맹점","categoryCode":"NEW_CODE","categoryName":null}""")
        assertNull(unknown.categoryName)
    }

    @Test fun `상세(#3) 혜택 — 시점이 있으면 시점, 없으면 종류로 캐시백 · 적립을 가르고 모르면 UNKNOWN`() {
        val dto = PaymentResultDto(
            paymentId = 2006, status = "APPROVED", amount = 15_900, chargedAmount = 15_900,
            benefits = listOf(
                BenefitDto("스타벅스 환급", type = "CASHBACK", actualAmount = 3_180, realization = "AFTER_BILLING"),
                BenefitDto("커피 적립", type = "POINT", actualAmount = 300, realization = "POINT_ACCRUAL"),
                BenefitDto("영화 캐시백", type = "CASHBACK", actualAmount = 1_000),
                BenefitDto("약관 미확인 할인", type = "DISCOUNT", actualAmount = 500),
            ),
        )
        assertEquals(
            listOf(BenefitKind.CASHBACK, BenefitKind.POINT, BenefitKind.CASHBACK, BenefitKind.UNKNOWN),
            dto.toReceipt().benefits.map { it.kind },
        )
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

    /** 승인 응답 selectedCard엔 아직 카드사 칸이 없다(S15P31E103-389) — 결제 요청 때 붙인 카드사를 쓴다 */
    @Test fun `승인 응답에 카드사가 없으면 결제 요청 때 고른 같은 카드의 카드사로`() {
        val dto = PaymentResultDto(paymentId = 1031, status = "APPROVED", amount = 15_000, selectedCard = SelectedCardDto(11, "점심 카드"))
        val picked = PayCard(
            userCardId = 11, brand = brandOf("KB국민카드", color = "#FFBC00"), name = "점심 카드", last4 = "1021", isCheck = false,
            netBenefit = BigDecimal.ZERO, benefitName = null, chargedAmount = null, installmentOptions = emptyList(), reason = null,
            performanceNote = null, productName = "KB국민 청춘대로 톡톡카드",
        )
        assertEquals("KB국민카드", dto.toReceipt(card = picked).cardBrand?.name)
        assertEquals("점심 카드", dto.toReceipt(card = picked).cardName)
        // 다른 카드가 승인됐다면 그 카드사를 빌려 오지 않는다
        assertEquals("점심 카드", dto.toReceipt(card = picked.copy(userCardId = 12)).cardBrand?.name)
    }
}
