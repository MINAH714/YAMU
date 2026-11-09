package com.yamu.app.core.ui

import com.yamu.app.core.format.KST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ErrorMessagesTest {
    @Test
    fun `마지막 값의 시각을 알면 언제 값인지 붙인다`() {
        val now = OffsetDateTime.now(KST).withHour(9).withMinute(5)
        val line = staleLine("은행 연결이 늦어요", now.toString())
        assertTrue(line, line.startsWith("은행 연결이 늦어요 · 마지막 "))
        assertTrue(line, line.endsWith("09:05 반영"))
    }

    @Test
    fun `시각을 모르면 마지막 값이라고만 한다`() {
        assertEquals("인터넷 연결을 확인해 주세요 · 마지막 값을 보여 드려요", staleLine("인터넷 연결을 확인해 주세요", null))
    }
}
