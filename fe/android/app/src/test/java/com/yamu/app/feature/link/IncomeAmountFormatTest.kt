package com.yamu.app.feature.link

import androidx.compose.ui.text.AnnotatedString
import com.yamu.app.core.format.AmountLimit
import org.junit.Assert.*
import org.junit.Test

class IncomeAmountFormatTest {
    @Test fun `금액 안내는 만 단위와 나머지 원을 생략하거나 반올림하지 않는다`() {
        mapOf(
            1L to "1원",
            9_999L to "9,999원",
            10_000L to "1만 원",
            10_001L to "1만 1원",
            2_500_000L to "250만 원",
            2_543_210L to "254만 3,210원",
            AmountLimit.CASHFLOW.max to "10,000,000만 원",
        ).forEach { (amount, expected) -> assertEquals(expected, incomeAmountCaption(amount)) }
        assertNull(incomeAmountCaption(null))
        assertNull(incomeAmountCaption(0))
        assertNull(incomeAmountCaption(-1))
    }

    @Test fun `입력 원문은 유지하고 표시만 쉼표로 구분`() {
        mapOf("" to "", "0" to "0", "999" to "999", "1000" to "1,000", "2500000" to "2,500,000", "100000000000" to "100,000,000,000").forEach { (raw, expected) ->
            val input = AnnotatedString(raw)
            assertEquals(expected, IncomeAmountFormat.filter(input).text.text)
            assertEquals(raw, input.text)
        }
    }

    @Test fun `모든 입력 길이의 중간 커서와 선택 범위가 원문으로 돌아온다`() {
        for (length in 0..AmountLimit.CASHFLOW.digits) {
            val raw = "123456789012".take(length)
            val transformed = IncomeAmountFormat.filter(AnnotatedString(raw))
            val mapping = transformed.offsetMapping
            for (offset in 0..length) {
                val display = mapping.originalToTransformed(offset)
                assertTrue(display in 0..transformed.text.length)
                assertEquals(offset, mapping.transformedToOriginal(display))
            }
            val positions = (0..transformed.text.length).map(mapping::transformedToOriginal)
            assertEquals(positions.sorted(), positions)
            assertTrue(positions.all { it in 0..length })
        }
    }

    @Test fun `쉼표 양쪽 터치는 같은 원문 위치이고 붙여넣기는 숫자로 정규화`() {
        val mapping = IncomeAmountFormat.filter(AnnotatedString("2500000")).offsetMapping
        assertEquals(1, mapping.transformedToOriginal(1))
        assertEquals(1, mapping.transformedToOriginal(2))
        assertEquals("2500000", AmountLimit.CASHFLOW.clampInput("2,500,000"))
    }
}
