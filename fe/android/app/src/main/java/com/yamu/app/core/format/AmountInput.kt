package com.yamu.app.core.format

/**
 * 금액 칸에 남길 글자 — 숫자만 남기고 앞자리 0은 버린 뒤 [maxDigits]자리까지 자른다.
 * 빈 칸에서 0 · 00을 눌러도 비어 있고, '0'이 남은 칸에 숫자를 누르면 0 대신 그 숫자가 된다. 붙여넣기('0012,000')도 같다.
 * 그래서 0원은 칸에 남지 않고 빈 칸이 된다. 숫자 패드 · 시스템 키보드 금액 칸이 모두 이 함수를 지난다.
 */
fun amountDigits(input: String, maxDigits: Int): String =
    input.filter(Char::isDigit).trimStart('0').take(maxDigits)
