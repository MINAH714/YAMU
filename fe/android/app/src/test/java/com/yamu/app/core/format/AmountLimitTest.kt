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
        assertEquals(2_000_000_000L, AmountLimit.ALERT_THRESHOLD.max)
        assert(AmountLimit.ALERT_THRESHOLD.max <= Int.MAX_VALUE)
    }

    @Test fun `경계값 — 빈 칸 · 0원 · 1원 · 상한 · 상한 + 1`() {
        AmountLimit.entries.filter { it != AmountLimit.ALERT_THRESHOLD }.forEach { limit ->
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
        // 앞자리 0은 버린다(S15P31E103-525와 같은 amountDigits) — 자릿수는 0을 뺀 뒤 센다
        assertEquals("", AmountLimit.CASHFLOW.clampInput("000"))
        assertEquals("12000", AmountLimit.CASHFLOW.clampInput("0012,000"))
        assertEquals("100000000000", AmountLimit.CASHFLOW.clampInput("0100000000000"))
    }

    @Test fun `안전 잔액만 0원을 받는다 — 0만 치면 0이 남고, 그 뒤에 숫자를 치면 0은 버린다`() {
        val limit = AmountLimit.ALERT_THRESHOLD
        assertNull(limit.error(0))
        assertEquals("0원 이상 넣어 주세요", limit.error(-1))
        assertEquals("금액을 넣어 주세요", limit.error(null))
        assertNull(limit.error(limit.max))
        assertEquals("20억 원까지 넣을 수 있어요", limit.error(limit.max + 1))
        assertEquals(10, limit.digits)
        assertEquals("0", limit.clampInput("0"))
        assertEquals("0", limit.clampInput("00"))
        assertEquals("5", limit.clampInput("05"))
        assertEquals("", limit.clampInput(""))
        assertEquals("", limit.clampInput("원"))
        assertEquals("1234567890", limit.clampInput("12345678901"))   // 11자리째는 쳐지지 않는다
        // 다른 칸은 그대로 0을 빈 칸으로
        assertEquals("", AmountLimit.DAILY_LIVING_COST.clampInput("0"))
    }
}
