package com.yamu.app.core.sms

/**
 * 1원 입금 문자에서 숫자 꺼내기 — '[KB국민은행] *1234 입금 1원 YAMU 1004' → "1004".
 * 서버가 준 [prefix](depositorNamePrefix, 'YAMU') 바로 뒤 [length](codeLength)자리만. 못 찾으면 null(직접 입력).
 */
fun parseOneWonCode(message: String?, prefix: String, length: Int): String? {
    if (message.isNullOrBlank() || prefix.isBlank() || length <= 0) return null
    val pattern = Regex("""${Regex.escape(prefix)}\s*(\d{$length})(?!\d)""", RegexOption.IGNORE_CASE)
    return pattern.find(message)?.groupValues?.get(1)
}
