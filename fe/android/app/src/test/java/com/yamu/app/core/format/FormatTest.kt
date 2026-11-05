package com.yamu.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.ZonedDateTime

class FormatTest {

    @Test
    fun `금액 표기`() {
        assertEquals("15,000", 15_000L.toWon())
        assertEquals("−50,000", (-50_000L).toWon())
        assertEquals("+15,000", 15_000L.toWon(withPlusSign = true))
        assertEquals("0", 0L.toWon(withPlusSign = true))
    }

    @Test
    fun `소수가 올 수 있는 금액은 반올림 없이 표기`() {
        assertEquals("1.5", BigDecimal("1.5").toWon())
        assertEquals("1.5", BigDecimal("1.500000").toWon())
        assertEquals("1,500", BigDecimal("1500.000000").toWon())
        assertEquals("1,800.5", BigDecimal("1800.5").toWon())
        assertEquals("0.3333", BigDecimal("0.3333").toWon())       // 기본 표기의 소수 셋째 자리에서 잘리지 않게
        assertEquals("−1.5", BigDecimal("-1.5").toWon())
        assertEquals("0", BigDecimal("0.000000").toWon())
    }

    @Test
    fun `기준 시각 표기`() {
        val now = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, KST)
        assertEquals("오늘 09:38 반영", formatSyncedAt("2026-10-16T09:38:00+09:00", now))
        assertEquals("오늘 09:38 반영", formatSyncedAt("2026-10-16T00:38:00Z", now))      // UTC로 와도 KST로
        assertEquals("어제 21:10 반영", formatSyncedAt("2026-10-15T21:10:00+09:00", now))
        assertEquals("10월 13일 반영", formatSyncedAt("2026-10-13T08:00:00+09:00", now))
        assertNull(formatSyncedAt("이상한 값", now))
        assertNull(formatSyncedAt(null, now))
    }
}
