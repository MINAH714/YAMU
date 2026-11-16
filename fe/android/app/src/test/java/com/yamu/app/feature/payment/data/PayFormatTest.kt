package com.yamu.app.feature.payment.data

import com.yamu.app.core.format.directionParticle
import com.yamu.app.feature.link.data.brandOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/** 결제 코드 · 모의 POS 표기(10 문서 6-3 · 6-5) */
class PayFormatTest {
    @Test fun `결제번호는 4자리씩`() {
        assertEquals("YAMU 8F2A 9C1D 7E", groupCode("YAMU8F2A9C1D7E"))
        assertEquals("6214 8830 2791 4821", groupCode("6214883027914821"))
    }

    @Test fun `남은 시간 mm ss — 음수는 00 00`() {
        assertEquals("02:59", formatRemaining(179))
        assertEquals("00:58", formatRemaining(58))
        assertEquals("00:00", formatRemaining(-3))
    }

    @Test fun `서버 시각까지 남은 초는 올림`() {
        val now = Instant.parse("2026-10-26T05:30:00.400Z")
        assertEquals(180L, secondsUntil("2026-10-26T14:33:00+09:00", now))
        assertEquals(0L, secondsUntil("2026-10-26T14:30:00.400+09:00", now))
        assertNull(secondsUntil("곧", now))
    }

    @Test fun `서버 시각을 epoch 밀리초로 — 못 읽으면 null`() {
        assertEquals(Instant.parse("2026-10-26T05:33:00Z").toEpochMilli(), epochMillisOrNull("2026-10-26T14:33:00+09:00"))
        assertNull(epochMillisOrNull("곧"))
    }

    @Test fun `찍은 값에서 토큰 꺼내기 — QR · 바코드 · 손 입력`() {
        assertEquals("YAMU8F2A9C1D7E", parseScannedCode("yamu://pay/YAMU8F2A9C1D7E"))
        assertEquals("YAMU8F2A9C1D7E", parseScannedCode("YAMU8F2A9C1D7E"))
        assertEquals("YAMU8F2A9C1D7E", parseScannedCode(" yamu 8f2a 9c1d-7e "))
        assertNull(parseScannedCode("   "))
        assertNull(parseScannedCode(null))
    }

    @Test fun `거절 · POS 오류 코드 → 문장`() {
        assertEquals("연결 계좌 잔액이 부족해요", declineMessage("INSUFFICIENT_BALANCE"))
        assertEquals("결제가 승인되지 않았어요", declineMessage("SOMETHING_NEW"))
        assertEquals("이미 결제에 쓴 코드예요", posErrorMessage("BARCODE_ALREADY_USED"))
        assertEquals("결제 요청을 보내지 못했어요 · 다시 시도해 주세요", posErrorMessage(null))
    }

    @Test fun `출금 계좌 · 출금 미확인 거절은 각자 할 일을 알려 준다`() {
        assertEquals("출금 계좌를 쓸 수 없어요 · 계좌를 다시 연결해 주세요", declineMessage("ACCOUNT_UNAVAILABLE"))
        // 출금이 늦게 처리되면 나중에 승인될 수 있어 다시 결제하라고 하지 않는다(리뷰 P1)
        assertEquals("결제 결과를 확인하지 못했어요 · 결제 내역에서 결과를 확인해 주세요", declineMessage("WITHDRAWAL_NOT_CONFIRMED"))
    }

    @Test fun `혜택 금액은 부호 없이 반영되는 때를 붙인다`() {
        assertEquals("660원 할인", ReceiptBenefit("편의점 할인", 660, BenefitKind.INSTANT).amountText())
        assertEquals("3,180원 청구할인", ReceiptBenefit("스타벅스", 3_180, BenefitKind.BILLING).amountText())
        assertEquals("3,180원 환급", ReceiptBenefit("스타벅스", 3_180, BenefitKind.CASHBACK).amountText())
        assertEquals("1,500원 적립", ReceiptBenefit("커피", 1_500, BenefitKind.POINT).amountText())
        assertEquals("500원 할인", ReceiptBenefit("약관 미확인", 500).amountText())
    }

    @Test fun `즉시할인 한 줄은 즉시할인이 있을 때만`() {
        assertEquals("6,600원에서 660원 바로 할인", receipt(amount = 6_600, charged = 5_940).instantDiscountText())
        assertNull(receipt(amount = 31_800, charged = 31_800).instantDiscountText())
        assertNull(receipt(amount = 10_000, charged = null).instantDiscountText())
    }

    @Test fun `취소된 결제는 즉시할인 한 줄을 보이지 않는다`() {
        // 취소 뒤에도 chargedAmount는 승인 당시 값(5,940원)이 그대로 온다
        assertNull(receipt(amount = 6_600, charged = 5_940).copy(status = PayStatus.CANCELED).instantDiscountText())
    }

    private fun receipt(amount: Long, charged: Long?) = PaymentReceipt(
        paymentId = 1, merchantName = "GS25", categoryName = null, amount = amount, paidAtIso = null, cardId = null, cardBrand = null,
        cardName = null, cardLast4 = null, isCheck = false, approvalNo = null, installmentMonths = null, benefits = emptyList(),
        chargedAmount = charged, balanceAfter = null, billDateIso = null, billAmount = null, perfMonth = null, perfRecognized = null,
        perfCounted = true, perfTotalAfter = null, perfTarget = null,
    )

    @Test fun `버튼 조사 로 · 으로`() {
        assertEquals("로", "신한카드".directionParticle())
        assertEquals("로", "신한 생활".directionParticle())          // ㄹ 받침
        assertEquals("으로", "KB국민 적립 신용".directionParticle())  // ㅇ 받침
        assertEquals("으로", "하나 생활 통장".directionParticle())
        assertEquals("로", "BC".directionParticle())
    }

    // ── 결제 확인 카드 칸(S15P31E103-555) ──

    private fun card(
        id: Long, net: Long, kind: BenefitKind = BenefitKind.BILLING, rank: Int = 0,
        charged: Long? = null, unknown: Boolean = false, issuer: String = "삼성카드",
    ) = PayCard(
        userCardId = id, brand = brandOf(issuer), name = "카드$id", last4 = "", isCheck = false, netBenefit = BigDecimal(net),
        benefitName = null, chargedAmount = charged, installmentOptions = emptyList(), reason = null, performanceNote = null,
        benefitUnknown = unknown, rank = rank, benefitKind = kind,
    )

    @Test fun `혜택 방식 태그 — 시점을 모르면 태그 없음`() {
        assertEquals("즉시할인", BenefitKind.INSTANT.tagText())
        assertEquals("청구할인", BenefitKind.BILLING.tagText())
        assertEquals("환급할인", BenefitKind.CASHBACK.tagText())
        assertEquals("적립", BenefitKind.POINT.tagText())
        assertNull(BenefitKind.UNKNOWN.tagText())
    }

    @Test fun `혜택 금액 문구 — 방식마다 붙는 말이 다르다`() {
        assertEquals("1,980원 할인", card(1, 1980, BenefitKind.BILLING).benefitHeadline())
        assertEquals("660원 할인", card(1, 660, BenefitKind.INSTANT).benefitHeadline())
        assertEquals("660원 돌려받아요", card(1, 660, BenefitKind.CASHBACK).benefitHeadline())
        assertEquals("330원 적립", card(1, 330, BenefitKind.POINT).benefitHeadline())
        assertEquals("500원 할인", card(1, 500, BenefitKind.UNKNOWN).benefitHeadline())
    }

    @Test fun `혜택 금액 문구 — 0원 · 비교 못 한 카드는 없음`() {
        assertNull(card(1, 0).benefitHeadline())
        assertNull(card(1, 500, unknown = true).benefitHeadline())
    }

    @Test fun `결제 버튼 — 카드사 이름으로, 즉시할인은 실제로 빠지는 금액만`() {
        assertEquals("삼성카드로 6,600원 결제", card(1, 1980, BenefitKind.BILLING, charged = 6600).payButtonLabel(6600))
        assertEquals("KB국민카드로 6,600원 결제", card(2, 660, BenefitKind.CASHBACK, issuer = "KB국민카드").payButtonLabel(6600))
        assertEquals("5,940원 결제", card(3, 660, BenefitKind.INSTANT, charged = 5940).payButtonLabel(6600))
    }

    @Test fun `즉시할인이어도 빠지는 금액을 모르거나 같으면 카드 이름으로`() {
        assertEquals("삼성카드로 6,600원 결제", card(3, 660, BenefitKind.INSTANT, charged = null).payButtonLabel(6600))
        assertEquals("삼성카드로 6,600원 결제", card(3, 660, BenefitKind.INSTANT, charged = 6600).payButtonLabel(6600))
    }

    @Test fun `야무픽은 1순위이면서 혜택이 있는 카드만`() {
        assertTrue(card(1, 1980, rank = 1).isYamuPick)
        assertFalse(card(1, 0, rank = 1).isYamuPick)
        assertFalse(card(2, 660, rank = 2).isYamuPick)
        assertFalse(card(1, 1980, rank = 1, unknown = true).isYamuPick)
    }

    private val pick = card(1, 1980, rank = 1)
    private val second = card(2, 660, BenefitKind.CASHBACK, rank = 2)
    private val third = card(3, 300, rank = 3)
    private val uncompared = card(9, 0, unknown = true)

    @Test fun `비교 문구 — 야무픽을 고르면 다음으로 혜택이 큰 카드와 견준다`() {
        assertEquals(PickGap(BigDecimal(1320), pickSelected = true), pickGap(listOf(pick, second, third, uncompared), pick))
    }

    @Test fun `비교 문구 — 다른 카드를 고르면 그 카드와 견준다`() {
        assertEquals(PickGap(BigDecimal(1320), pickSelected = false), pickGap(listOf(pick, second, third), second))
        assertEquals(PickGap(BigDecimal(1680), pickSelected = false), pickGap(listOf(pick, second, third), third))
    }

    @Test fun `비교 문구를 숨기는 경우`() {
        assertNull("야무픽 없음", pickGap(listOf(card(1, 0, rank = 1), second), second))
        assertNull("고른 카드 없음", pickGap(listOf(pick, second), null))
        assertNull("비교 못 한 카드를 고름", pickGap(listOf(pick, second, uncompared), uncompared))
        assertNull("야무픽이 거절됨", pickGap(listOf(pick, second), second, declinedIds = setOf(1L)))
        assertNull("견줄 다른 카드 없음", pickGap(listOf(pick, uncompared), pick))
        assertNull("차이 0원", pickGap(listOf(pick, card(2, 1980, rank = 2)), pick))
    }
}
