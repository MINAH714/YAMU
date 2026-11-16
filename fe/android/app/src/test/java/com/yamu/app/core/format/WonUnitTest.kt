package com.yamu.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class WonUnitTest {
    @Test fun `만 원으로 떨어지면 끝의 원을 띄운다`() {
        assertEquals("1만 원", 10_000L.toWonUnit())
        assertEquals("20만 원", 200_000L.toWonUnit())
        assertEquals("1,234만 원", 12_340_000L.toWonUnit())
    }

    @Test fun `만 원 아래가 남으면 반올림하지 않고 정확히 붙여 쓴다`() {
        assertEquals("123만 4,500원", 1_234_500L.toWonUnit())
        assertEquals("1만 1원", 10_001L.toWonUnit())
        assertEquals("9,999만 9,999원", 99_999_999L.toWonUnit())
    }

    @Test fun `억 단위 — 빈 자리(0만 · 0원)는 건너뛴다`() {
        assertEquals("1억 원", 100_000_000L.toWonUnit())
        assertEquals("1억 2,000만 원", 120_000_000L.toWonUnit())
        assertEquals("1억 500원", 100_000_500L.toWonUnit())
        assertEquals("12억 3,456만 7,890원", 1_234_567_890L.toWonUnit())
    }

    @Test fun `가상 구매 상한 1,000억 원까지 억에도 쉼표`() {
        assertEquals("1,000억 원", 100_000_000_000L.toWonUnit())
        assertEquals("999억 9,999만 9,999원", 99_999_999_999L.toWonUnit())
    }

    @Test fun `만 원 아래와 0 · 음수`() {
        assertEquals("9,999원", 9_999L.toWonUnit())
        assertEquals("1원", 1L.toWonUnit())
        assertEquals("0원", 0L.toWonUnit())
        assertEquals("−15,000원", (-15_000L).toWonUnit())
    }
}
