package com.yamu.app.core.format

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** 서버 금액(원 단위 정수) → "15,000" · "−50,000" · "+15,000". '원'은 화면에서 붙인다 */
fun Long.toWon(withPlusSign: Boolean = false): String {
    val digits = NumberFormat.getNumberInstance(Locale.KOREA).format(abs(this))
    return when {
        this < 0 -> "−$digits"
        withPlusSign && this > 0 -> "+$digits"
        else -> digits
    }
}

/** 소수가 올 수 있는 금액(추천 순혜택) → "1,500" · "1.5". 반올림하지 않아 서버 추천 문구의 금액과 같다 */
fun BigDecimal.toWon(): String {
    val plain = stripTrailingZeros()
    val format = NumberFormat.getNumberInstance(Locale.KOREA).apply { maximumFractionDigits = plain.scale().coerceAtLeast(0) }
    val digits = format.format(plain.abs())
    return if (signum() < 0) "−$digits" else digits
}

/** 실적 기준처럼 만 원 단위 금액 → "40만 원". 만 원으로 안 나눠지면 "125,000원" */
fun Long.toManWon(): String = if (this != 0L && this % 10_000 == 0L) "${this / 10_000}만 원" else "${toWon()}원"
