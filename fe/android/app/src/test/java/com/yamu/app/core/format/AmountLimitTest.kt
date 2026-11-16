package com.yamu.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountLimitTest {

    @Test fun `묶음마다 상한 — 예정 돈은 서버와 같은 1,000억, 하루 생활비는 서버 Integer 상한 안쪽`() {
        assertEquals(100_000_000_000L, AmountLimit.CASHFLOW.max)
        assertEquals(2_000_000_000L, AmountLimit.DAILY_LIVING_COST.max)
        assert(AmountLimit.DAILY_LIVING_COST.max <= Int.MAX_VALUE)
        assertEquals(999_999_999L, AmountLimit.POS_PAYMENT.max)
    }

    @Test fun `경계값 — 빈 칸 · 0원 · 1원 · 상한 · 상한 + 1`() {
        AmountLimit.entries.forEach { limit ->
            assertEquals("금액을 넣어 주세요", limit.error(null))
            assertEquals("1원 이상 넣어 주세요", limit.error(0))
            assertEquals("1원 이상 넣어 주세요", limit.error(-1))
            assertNull(limit.error(1))
            assertNull(limit.error(limit.max))
        }
        assertEquals("1,000억 원까지 넣을 수 있어요", AmountLimit.CASHFLOW.error(AmountLimit.CASHFLOW.max + 1))
        assertEquals("20억 원까지 넣을 수 있어요", AmountLimit.DAILY_LIVING_COST.error(AmountLimit.DAILY_LIVING_COST.max + 1))
        assertEquals("999,999,999원까지 넣을 수 있어요", AmountLimit.POS_PAYMENT.error(AmountLimit.POS_PAYMENT.max + 1))
    }

    @Test fun `칸은 숫자만, 상한 자릿수까지만 받는다`() {
        assertEquals(12, AmountLimit.CASHFLOW.digits)
        assertEquals(10, AmountLimit.DAILY_LIVING_COST.digits)
        assertEquals(9, AmountLimit.POS_PAYMENT.digits)
        assertEquals("250000", AmountLimit.CASHFLOW.clampInput("250,000원"))
        assertEquals("123456789012", AmountLimit.CASHFLOW.clampInput("1234567890123"))
        assertEquals("123456789", AmountLimit.POS_PAYMENT.clampInput("1234567890"))
        assertEquals("", AmountLimit.CASHFLOW.clampInput("원"))
    }
}
