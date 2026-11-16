package com.yamu.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputTest {

    @Test
    fun `빈 칸에서 0을 연달아 눌러도 비어 있다`() {
        var text = ""
        repeat(5) { text = amountDigits(text + "0", 12) }
        assertEquals("", text)
        assertEquals("", amountDigits("00", 12))
    }

    @Test
    fun `0이 남은 칸에 숫자를 누르면 0을 갈아 끼운다`() {
        assertEquals("5", amountDigits("0" + "5", 12))
        assertEquals("", amountDigits("0" + "0", 12))
    }

    @Test
    fun `앞자리가 아닌 0은 그대로 둔다`() {
        var text = ""
        listOf("1", "0", "0", "0").forEach { text = amountDigits(text + it, 12) }
        assertEquals("1000", text)
    }

    @Test
    fun `붙여넣기도 숫자만 남기고 앞자리 0을 버린다`() {
        assertEquals("12000", amountDigits("0012,000", 12))
        assertEquals("15000", amountDigits("₩ 015,000원", 12))
        assertEquals("", amountDigits("원", 12))
    }

    @Test
    fun `자릿수는 앞자리 0을 버린 뒤에 센다`() {
        assertEquals("1234567890", amountDigits("12345678901", 10))
        assertEquals("123", amountDigits("000000000000123", 10))
    }

    @Test
    fun `맨 앞 숫자를 지워 0만 남으면 빈 칸이 된다`() {
        assertEquals("", amountDigits("000", 12))      // 1000에서 1을 지운 경우
        assertEquals("5", amountDigits("05", 12))      // 105에서 1을 지운 경우
    }

    @Test
    fun `저장된 0원은 빈 칸으로 연다`() {
        assertEquals("", amountDigits(0L.toString(), 10))
        assertEquals("500000", amountDigits(500_000L.toString(), 10))
    }
}
