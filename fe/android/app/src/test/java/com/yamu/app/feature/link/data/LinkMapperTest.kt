package com.yamu.app.feature.link.data

import com.yamu.app.core.format.formatAsOf
import com.yamu.app.core.format.objectParticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 연결 화면 값 — 끝자리 · 색 · 시각 · 조사 */
class LinkMapperTest {
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

    @Test fun `기준 시각과 목적격 조사`() {
        assertEquals("10월 26일 09:52 기준", formatAsOf("2026-10-26T09:52:00+09:00"))
        assertEquals("10월 26일 09:52 기준", formatAsOf("2026-10-26T00:52:00Z"))
        assertNull(formatAsOf("어제"))
        assertEquals("를", "트래블 마일 신용카드".objectParticle())
        assertEquals("을", "생활비 통장".objectParticle())
        assertEquals("를", "BC".objectParticle())
    }
}
