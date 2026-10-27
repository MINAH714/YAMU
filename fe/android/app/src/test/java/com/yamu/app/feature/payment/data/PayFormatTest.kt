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

    @Test fun `버튼 조사 로 · 으로`() {
        assertEquals("로", "신한카드".directionParticle())
        assertEquals("로", "신한 생활".directionParticle())          // ㄹ 받침
        assertEquals("으로", "KB국민 적립 신용".directionParticle())  // ㅇ 받침
        assertEquals("으로", "하나 생활 통장".directionParticle())
        assertEquals("로", "BC".directionParticle())
    }
}
