package com.yamu.app.feature.forecast

import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.feature.forecast.data.SimulationQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class BuyCheckAmountKeyTest {

    private fun press(vararg keys: KeypadKey, from: String = ""): String = keys.fold(from) { text, key -> text.applyAmountKey(key) }

    @Test
    fun `빈 칸에서 0 · 00은 눌리지 않는다`() {
        assertEquals("", press(KeypadKey.Digit(0), KeypadKey.Digit(0), KeypadKey.DoubleZero, KeypadKey.Digit(0)))
    }

    @Test
    fun `숫자 뒤 0 · 00은 붙는다`() {
        assertEquals("5000", press(KeypadKey.Digit(5), KeypadKey.DoubleZero, KeypadKey.Digit(0)))
    }

    @Test
    fun `지우다가 0만 남으면 0을 갈아 끼운다`() {
        assertEquals("7", press(KeypadKey.Digit(7), from = "0"))
        assertEquals("", press(KeypadKey.Digit(0), from = "0"))
    }

    @Test
    fun `1,000억 원까지 받고 넘기는 키는 받지 않는다(BE 상한, QA)`() {
        // 99억(10자리)을 넘어 1,000억까지
        assertEquals("100000000000", press(KeypadKey.DoubleZero, from = "1000000000"))
        assertEquals(SimulationQuery.AMOUNT_MAX, press(KeypadKey.Digit(0), from = "10000000000").toLong())
        // 13자리 · 1,000억을 넘는 12자리는 그대로
        assertEquals("100000000000", press(KeypadKey.Digit(1), KeypadKey.Digit(0), KeypadKey.DoubleZero, from = "100000000000"))
        assertEquals("99999999999", press(KeypadKey.Digit(9), from = "99999999999"))
        // 00을 반만 붙여 자릿수를 바꾸지 않는다(99,999,999,999 + 00 → 그대로)
        assertEquals("99999999999", press(KeypadKey.DoubleZero, from = "99999999999"))
        assertEquals("", press(KeypadKey.Delete, from = "1"))
    }
}
