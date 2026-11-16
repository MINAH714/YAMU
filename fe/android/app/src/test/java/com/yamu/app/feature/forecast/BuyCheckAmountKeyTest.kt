package com.yamu.app.feature.forecast

import com.yamu.app.core.designsystem.component.KeypadKey
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
    fun `10자리를 넘으면 더 붙지 않는다`() {
        assertEquals("1234567891", press(KeypadKey.Digit(1), KeypadKey.DoubleZero, from = "123456789"))
        assertEquals("", press(KeypadKey.Delete, from = "1"))
    }
}
