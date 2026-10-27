package com.yamu.app.core.format

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

/** 실적 기준처럼 만 원 단위 금액 → "40만 원". 만 원으로 안 나눠지면 "125,000원" */
fun Long.toManWon(): String = if (this != 0L && this % 10_000 == 0L) "${this / 10_000}만 원" else "${toWon()}원"
