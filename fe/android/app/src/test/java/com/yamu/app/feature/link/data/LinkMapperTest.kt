package com.yamu.app.feature.link.data

import com.yamu.app.core.format.formatAsOf
import com.yamu.app.core.format.objectParticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 연결 화면 값 — 버튼 글자 · 끝자리 · 색 · 세션 · 시각 · 조사 */
class LinkMapperTest {
    @Test fun `연결 버튼은 고른 수대로`() {
        assertEquals("계좌 1 · 카드 6 연결하기", linkButtonLabel(1, 6))
        assertEquals("계좌 1 · 카드 5 연결하기", linkButtonLabel(1, 5))
        assertEquals("카드 1 연결하기", linkButtonLabel(0, 1))
        assertEquals("계좌 1 연결하기", linkButtonLabel(1, 0))
        assertEquals("연결할 것을 골라 주세요", linkButtonLabel(0, 0))
    }

    @Test fun `가린 번호에서 끝 네 자리`() {
        assertEquals("1234", "4570-****-****-1234".lastDigits())
        assertEquals("6547", "****-****-****-6547".lastDigits())
        assertEquals("1234", "0012-***-1234".lastDigits())
    }

    @Test fun `카드사 색과 짧은 이름`() {
        assertEquals(0xFF0046FFL, parseColorArgb("#0046FF"))
        assertEquals(0x800046FFL, parseColorArgb("800046FF"))
        assertNull(parseColorArgb("blue"))
        assertNull(parseColorArgb(null))
        assertEquals("신한", shortBrandName("신한카드"))
        assertEquals("KB국민", shortBrandName("KB국민은행"))
    }

    @Test fun `세션 응답 — 1원 대상과 약관`() {
        val dto = LinkSessionDto(
            linkSessionId = "ls_1",
            status = "SELECTED",
            institutionIds = listOf("BANK_KB", "CARD_SHINHAN", "CARD_KB", "CARD_WOORI", "CARD_NH", "CARD_HANA"),
            ownAccountCheck = OwnAccountCheckDto(required = true, bank = BankDto("004", "KB국민은행"), maskedAccountNo = "123-**-***1234"),
            terms = listOf(TermsDto("FIN_INFO", "1.0", "금융 정보 조회 · 제공")),
        )
        val s = dto.toSession()
        assertEquals(6, s.institutionCount)
        assertEquals("KB국민은행 ·1234", s.ownAccount?.label)
        assertTrue(s.ownAccount!!.required)
        assertFalse(s.ownAccount!!.verified)
        assertEquals(1, s.terms.size)
    }

    @Test fun `기준 시각과 목적격 조사`() {
        assertEquals("10월 26일 09:52 기준", formatAsOf("2026-10-26T09:52:00+09:00"))
        assertEquals("10월 26일 09:52 기준", formatAsOf("2026-10-26T00:52:00Z"))
        assertNull(formatAsOf("어제"))
        assertEquals("를", "트래블 마일 신용카드".objectParticle())
        assertEquals("을", "생활비 통장".objectParticle())
        assertEquals("를", "BC".objectParticle())
    }
}
