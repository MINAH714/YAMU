package com.yamu.app.core.format

/**
 * 원 단위 금액을 억 · 만으로 끊어 읽기 쉽게 → "20만 원" · "123만 4,500원" · "12억 3,456만 7,890원" · "1,000억 원".
 * 끝까지 정확히 쓴다(반올림 · '약' 없음). 끝이 억 · 만이면 '원'을 띄우고, 남는 원이 있으면 붙인다. 0은 "0원".
 * 0 이상 금액용 — 음수가 오면 단위 없이 [toWon] 그대로 "−15,000원"
 */
fun Long.toWonUnit(): String {
    if (this < 0) return "${toWon()}원"
    if (this == 0L) return "0원"
    val eok = this / 100_000_000
    val man = this % 100_000_000 / 10_000
    val rest = this % 10_000
    val parts = buildList {
        if (eok > 0) add("${eok.toWon()}억")
        if (man > 0) add("${man.toWon()}만")
        if (rest > 0) add(rest.toWon())
    }
    return parts.joinToString(" ") + if (rest > 0) "원" else " 원"
}
