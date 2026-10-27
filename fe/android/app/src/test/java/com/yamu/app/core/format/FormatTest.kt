package com.yamu.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
