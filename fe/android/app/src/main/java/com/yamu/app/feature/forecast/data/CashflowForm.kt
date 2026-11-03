package com.yamu.app.feature.forecast.data

import com.yamu.app.feature.link.data.monthlyDayLabel
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// F-02a 예정 수입·지출 추가 — #20 가계부 항목 등록(POST /cashflow-items). 반복 회차 날짜는 서버가 계산하고, 앱은 첫 발생일과 반복 기준만 보낸다

@Serializable data class CashflowCreateRequest(
    val type: String,                             // INCOME · EXPENSE
    val title: String,
    val amount: Long,
    val date: String,                             // 첫 발생일(오늘 이후)
    val recurrence: RecurrenceRequest? = null,    // 없으면 1회
    val accountId: Long? = null,                  // 계좌 · 카드 중 정확히 하나(수입은 계좌만)
    val userCardId: Long? = null,
)

@Serializable data class RecurrenceRequest(
    val frequency: String,                        // NONE(1회로 바꿀 때만) · WEEKLY · MONTHLY
    val dayOfMonth: Int? = null,
    val dayOfWeek: String? = null,
    val endDate: String? = null,                  // 없으면 끝없이(수정에서는 통째로 교체라 빼면 종료일이 지워진다)
)

enum class CashflowRepeat { NONE, WEEKLY, MONTHLY }

/** 입력 칸 — 앱 검사와 서버 fieldErrors를 같은 칸 아래에 보인다 */
enum class CashflowField { TITLE, AMOUNT, DATE, RECURRENCE, PAYMENT }

const val CASHFLOW_TITLE_MAX = 30
const val CASHFLOW_AMOUNT_MAX = 100_000_000_000L     // 1,000억 원(서버 상한)
private const val MONTH_END = 31                      // 서버 규칙(7.1.6): 31 = 31일이 없는 달은 말일 → 매월 말일

/** 등록 화면 입력 그대로. 고치는 일은 with…로만 — 말일 반복 · 수입 계좌처럼 칸끼리 묶인 규칙을 한곳에서 지킨다 */
data class CashflowForm(
    val isIncome: Boolean = false,
    val title: String = "",
    val amount: Long? = null,
    val date: LocalDate,
    val repeat: CashflowRepeat = CashflowRepeat.NONE,
    val monthEnd: Boolean = false,                // 매월 말일
    val endDate: LocalDate? = null,               // 반복이 끝나는 날(그날 포함) — 반복일 때만
    val accountId: Long? = null,
    val userCardId: Long? = null,
) {
    /** 시작일이 31일이면 '매월 N일'이 곧 말일이다 */
    val isMonthEnd: Boolean get() = repeat == CashflowRepeat.MONTHLY && (monthEnd || date.dayOfMonth == MONTH_END)

    fun withIncome(income: Boolean) = copy(isIncome = income, userCardId = if (income) null else userCardId)   // 수입은 카드로 받지 않는다
    fun withRepeat(r: CashflowRepeat) = copy(repeat = r, monthEnd = monthEnd && r == CashflowRepeat.MONTHLY)

    /** 매월 말일 — 서버는 시작일이 그달 말일이 아니면 31을 거절하므로 시작일을 그달 말일로 옮긴다 */
    fun withMonthEnd(on: Boolean) = copy(monthEnd = on, date = if (on) YearMonth.from(date).atEndOfMonth() else date)

    /** 말일 반복 중에 날짜를 바꾸면 고른 달의 말일로 */
    fun withDate(d: LocalDate) = copy(date = if (monthEnd && repeat == CashflowRepeat.MONTHLY) YearMonth.from(d).atEndOfMonth() else d)

    fun withAccount(id: Long) = copy(accountId = id, userCardId = null)
    fun withCard(id: Long) = copy(accountId = null, userCardId = id)
    fun withEndDate(d: LocalDate?) = copy(endDate = d)

    /** 실제로 보낼 종료일 — 반복이 아니면 없다 */
    val effectiveEndDate: LocalDate? get() = endDate.takeIf { repeat != CashflowRepeat.NONE }

    /** 보내기 전 검사 — 서버 검사(7.1.2)와 같은 범위. 비어 있으면 보내도 된다 */
    fun validate(today: LocalDate): Map<CashflowField, String> = buildMap {
        val t = title.trim()
        when {
            t.isEmpty() -> put(CashflowField.TITLE, "이름을 넣어 주세요")
            t.length > CASHFLOW_TITLE_MAX -> put(CashflowField.TITLE, "이름은 ${CASHFLOW_TITLE_MAX}자까지예요")
        }
        val a = amount
        when {
            a == null || a <= 0 -> put(CashflowField.AMOUNT, "금액을 넣어 주세요")
            a > CASHFLOW_AMOUNT_MAX -> put(CashflowField.AMOUNT, "1,000억 원까지 넣을 수 있어요")
        }
        if (date.isBefore(today)) put(CashflowField.DATE, "오늘이나 그 뒤 날짜를 골라 주세요")
        if (effectiveEndDate?.isBefore(date) == true) put(CashflowField.RECURRENCE, "끝나는 날은 시작일과 같거나 그 뒤여야 해요")
        when {
            accountId == null && userCardId == null -> put(CashflowField.PAYMENT, if (isIncome) "들어올 계좌를 골라 주세요" else "나갈 계좌나 카드를 골라 주세요")
            isIncome && userCardId != null -> put(CashflowField.PAYMENT, "수입은 계좌로만 받을 수 있어요")
        }
    }

    fun toRequest(): CashflowCreateRequest = CashflowCreateRequest(
        type = if (isIncome) "INCOME" else "EXPENSE",
        title = title.trim(),
        amount = amount ?: 0,
        date = date.toString(),
        recurrence = recurrenceRequest(),
        accountId = accountId,
        userCardId = userCardId,
    )

    /** 반복 규칙 요청 — 1회면 null(수정에서 1회로 바꿀 때는 [CashflowEntry.toPatch]가 NONE을 보낸다) */
    fun recurrenceRequest(): RecurrenceRequest? = when (repeat) {
        CashflowRepeat.NONE -> null
        CashflowRepeat.WEEKLY -> RecurrenceRequest("WEEKLY", dayOfWeek = date.dayOfWeek.name, endDate = effectiveEndDate?.toString())   // 첫 발생일의 요일과 같아야 한다
        CashflowRepeat.MONTHLY -> RecurrenceRequest("MONTHLY", dayOfMonth = if (isMonthEnd) MONTH_END else date.dayOfMonth, endDate = effectiveEndDate?.toString())
    }

    /** '매주 화요일' · '매월 25일' · '매월 말일' — 반복 안 함이면 null */
    fun repeatLabel(): String? = when (repeat) {
        CashflowRepeat.NONE -> null
        CashflowRepeat.WEEKLY -> "매주 ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"
        CashflowRepeat.MONTHLY -> "매월 ${monthlyDayLabel(if (isMonthEnd) MONTH_END else date.dayOfMonth)}"
    }
}

/** 서버 fieldErrors의 칸 이름 → 화면 칸('recurrence.dayOfMonth'처럼 반복 안쪽 칸은 반복 칸으로) */
fun cashflowFieldOf(serverField: String): CashflowField? = when {
    serverField == "title" -> CashflowField.TITLE
    serverField == "amount" -> CashflowField.AMOUNT
    serverField == "date" -> CashflowField.DATE
    serverField.startsWith("recurrence") -> CashflowField.RECURRENCE
    serverField == "accountId" || serverField == "userCardId" -> CashflowField.PAYMENT
    else -> null
}
