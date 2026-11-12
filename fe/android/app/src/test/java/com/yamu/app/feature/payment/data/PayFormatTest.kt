package com.yamu.app.feature.payment.data

import com.yamu.app.core.format.directionParticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
}
