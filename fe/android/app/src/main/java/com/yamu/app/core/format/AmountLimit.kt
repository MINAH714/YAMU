package com.yamu.app.core.format

/**
 * 사용자가 넣는 금액의 상한. 같은 종류의 칸은 같은 상한 · 같은 문구를 쓴다(큰 금액 QA, 11/16).
 * 입력 칸은 상한의 자릿수까지만 받아 그보다 긴 숫자는 쳐지지 않고, 같은 자릿수에서 상한을 넘는 값은 [error]가 막는다
 */
enum class AmountLimit(val max: Long, private val label: String) {
    /** 예정 수입·지출 — 등록 · 수정 · 온보딩 항목 · 월급. 서버 Create/UpdateCashflowItemRequest와 같다 */
    CASHFLOW(100_000_000_000L, "1,000억 원"),

    /** 하루 생활비 목표 — 서버는 Integer.MAX_VALUE(2,147,483,647원)까지 받는다. 그 안쪽 떨어지는 값으로 막는다 */
    DAILY_LIVING_COST(2_000_000_000L, "20억 원"),

    /** 모의 POS 결제 금액 — 서버에는 상한이 없어(1원 이상만) 앱이 9자리로 막는다 */
    POS_PAYMENT(999_999_999L, "999,999,999원"),
    ;

    /** 칸이 받는 자릿수 */
    val digits: Int get() = max.toString().length

    /** 입력 칸에 친 글자 → 숫자만, 앞자리 0은 버리고(amountDigits, S15P31E103-525) 상한 자릿수까지 */
    fun clampInput(text: String): String = amountDigits(text, digits)

    /** 보내기 전 검사. 비어 있으면 보내도 된다 */
    fun error(amount: Long?): String? = when {
        amount == null -> "금액을 넣어 주세요"
        amount <= 0 -> "1원 이상 넣어 주세요"
        amount > max -> "${label}까지 넣을 수 있어요"
        else -> null
    }
}
