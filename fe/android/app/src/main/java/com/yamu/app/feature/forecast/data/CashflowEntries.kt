package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.feature.link.data.monthlyDayLabel
import kotlinx.serialization.Serializable
import java.time.LocalDate

// F-02 목록 · F-02b 수정 · 삭제 — #20 가계부 항목 목록 · 수정 · 삭제(GET · PATCH · DELETE /cashflow-items). docs/be/cashflow.md

@Serializable data class CashflowListResponse(val items: List<CashflowEntryDto> = emptyList(), val candidateCount: Int = 0)

@Serializable data class CashflowEntryDto(
    val cashflowItemId: Long,
    val type: String,                             // INCOME · EXPENSE
    val title: String,
    val amount: Long? = null,
    val occurrenceDate: String? = null,           // 원래 회차 — 이번 회차 수정 · 삭제에 그대로 돌려보낸다
    val date: String? = null,                     // 실제 예정일(이번 회차만 옮겼을 때만 다름)
    val recurrence: EntryRecurrenceDto? = null,
    val accountId: Long? = null,
    val userCardId: Long? = null,
)

@Serializable data class EntryRecurrenceDto(val frequency: String? = null, val dayOfMonth: Int? = null, val dayOfWeek: String? = null, val endDate: String? = null)

/** PATCH 본문 — 모두 선택. 보내지 않은 칸은 그대로(서버가 null과 '안 보냄'을 같게 본다) */
@Serializable data class CashflowUpdateRequest(
    val title: String? = null,
    val amount: Long? = null,
    val date: String? = null,
    val recurrence: RecurrenceRequest? = null,    // 보내면 반복 규칙을 통째로 교체
    val accountId: Long? = null,
    val userCardId: Long? = null,
    val scope: String? = null,                    // THIS_ONLY · THIS_AND_FUTURE(생략하면 이후 전부)
    val occurrenceDate: String? = null,
)

/** 수정 범위 — 반복 항목만 고른다 */
enum class CashflowScope { THIS_ONLY, THIS_AND_FUTURE }

/** 삭제 범위 — 서버 ALL은 '오늘부터 앞으로 전부'다 */
enum class CashflowDeleteScope { THIS_ONLY, ALL }

/** 가계부 회차 한 줄 — 고칠 때 [itemId] · [occurrenceDate]를 그대로 돌려보낸다. [date]는 화면에 보이는 실제 날짜 */
data class CashflowEntry(
    val itemId: Long,
    val isIncome: Boolean,
    val title: String,
    val amount: Long,
    val occurrenceDate: LocalDate,
    val date: LocalDate,
    val repeat: CashflowRepeat,
    val dayOfMonth: Int?,
    val endDate: LocalDate?,
    val accountId: Long?,
    val userCardId: Long?,
) {
    val isRecurring: Boolean get() = repeat != CashflowRepeat.NONE
    val signedAmount: Long get() = if (isIncome) amount else -amount

    /** 수정 화면의 첫 값 — 시작일은 이 회차의 실제 날짜, 매월 기준일 31이면 말일 */
    fun toForm() = CashflowForm(
        isIncome = isIncome, title = title, amount = amount, date = date, repeat = repeat,
        monthEnd = repeat == CashflowRepeat.MONTHLY && dayOfMonth == 31, endDate = endDate,
        accountId = accountId, userCardId = userCardId,
    )

    /** F-02 반복 표시 — 짧은 달 회차(2/28)여도 규칙의 기준일로 */
    fun repeatLabel(): String? = if (repeat == CashflowRepeat.MONTHLY && dayOfMonth != null) "매월 ${monthlyDayLabel(dayOfMonth)}" else toForm().repeatLabel()

    /**
     * 날짜를 안 옮겼으면 기준일을 지킨다. 서버는 date가 없으면 원래 회차를 규칙의 첫날로 본다.
     * 2/28 회차에서 규칙만 바꿔도 매월 30일이 28일로 밀리지 않고, 이번 회차만 옮겨 둔 회차(25일 → 27일)도 첫날과 반복일이 어긋나 400이 나지 않게
     */
    private fun RecurrenceRequest.keepBaseDay(dateChanged: Boolean, form: CashflowForm): RecurrenceRequest = when {
        dateChanged -> this
        frequency == "WEEKLY" -> copy(dayOfWeek = occurrenceDate.dayOfWeek.name)
        frequency == "MONTHLY" && !form.isMonthEnd -> copy(dayOfMonth = this@CashflowEntry.dayOfMonth ?: occurrenceDate.dayOfMonth)
        else -> this
    }

    /** '이번 회차만'을 고를 수 있는지 — 반복 규칙(주기 · 말일 · 종료일)을 바꾸면 서버가 받지 않는다 */
    fun canEditThisOnly(form: CashflowForm): Boolean = isRecurring && !form.ruleChangedFrom(toForm())

    /**
     * 수정 요청 — 바꾼 칸만 보낸다. 반복(recurrence)은 통째로 교체되는 칸이라 규칙을 안 바꿨으면 보내지 않는다(종료일 유지).
     * 이후 전부에서 날짜를 옮기면 서버는 새 날짜를 기준일로 삼으므로, 매월 말일 · 매주 요일이 흐트러지지 않게 반복을 같이 보낸다
     */
    fun toPatch(form: CashflowForm, scope: CashflowScope): CashflowUpdateRequest {
        val before = toForm()
        val dateChanged = form.date != before.date
        val sendRecurrence = scope == CashflowScope.THIS_AND_FUTURE && (form.ruleChangedFrom(before) || (dateChanged && form.repeat != CashflowRepeat.NONE))
        return CashflowUpdateRequest(
            title = form.title.trim().takeIf { it != before.title },
            amount = form.amount.takeIf { it != before.amount },
            date = form.date.takeIf { dateChanged }?.toString(),
            recurrence = if (!sendRecurrence) null else form.recurrenceRequest()?.keepBaseDay(dateChanged, form) ?: RecurrenceRequest("NONE"),
            accountId = form.accountId.takeIf { it != null && it != before.accountId },
            userCardId = form.userCardId.takeIf { it != null && it != before.userCardId },
            scope = scope.name.takeIf { isRecurring },
            occurrenceDate = occurrenceDate.toString().takeIf { isRecurring },
        )
    }
}

/** 반복 규칙을 바꿨는지 — 주기 · 말일 · 종료일(1회면 종료일은 보지 않는다) */
fun CashflowForm.ruleChangedFrom(before: CashflowForm): Boolean =
    repeat != before.repeat || isMonthEnd != before.isMonthEnd || effectiveEndDate != before.effectiveEndDate

fun CashflowEntryDto.toEntry(): CashflowEntry? {
    val occurrence = parseDate(occurrenceDate) ?: parseDate(date) ?: return null
    val repeat = when (recurrence?.frequency?.uppercase()) {
        "WEEKLY" -> CashflowRepeat.WEEKLY
        "MONTHLY" -> CashflowRepeat.MONTHLY
        else -> CashflowRepeat.NONE
    }
    return CashflowEntry(
        itemId = cashflowItemId,
        isIncome = type.equals("INCOME", ignoreCase = true),
        title = title,
        amount = amount ?: 0,                     // 금액 미정 회차(구매 계획 등)는 0으로 보이고 고칠 때 넣는다
        occurrenceDate = occurrence,
        date = parseDate(date) ?: occurrence,
        repeat = repeat,
        dayOfMonth = recurrence?.dayOfMonth,
        endDate = parseDate(recurrence?.endDate),
        accountId = accountId,
        userCardId = userCardId,
    )
}

/** F-02 한 줄 — [entry]가 있으면 눌러서 고친다. 카드대금 · 월급 미리보기처럼 가계부 항목이 아니면 null(읽기 전용) */
data class CashflowRow(val date: LocalDate?, val title: String, val amount: Long, val repeatLabel: String?, val entry: CashflowEntry?)

/**
 * F-02 줄 합치기 — 가계부 회차(고칠 수 있음) + 예보의 카드대금(읽기 전용). 다음 월급이 가계부에 없으면 예보 값으로 맨 끝에.
 * 날짜는 서버가 준 값 그대로 쓰고 다시 계산하지 않는다
 */
fun cashflowRows(entries: List<CashflowEntry>, forecast: Forecast?): List<CashflowRow> {
    val fromItems = entries.map { e -> CashflowRow(e.date, e.title, e.signedAmount, e.repeatLabel(), e) }
    val bills = forecast?.upcoming.orEmpty().filter { it.isCardBill }.map { CashflowRow(it.date, it.title, it.amount, null, null) }
    val incomeDate = forecast?.incomeDate
    val incomeListed = incomeDate != null && entries.any { it.isIncome && it.date == incomeDate }
    val income = if (forecast != null && incomeDate != null && !incomeListed && (forecast.incomeAmount ?: 0) > 0) {
        listOf(CashflowRow(incomeDate, forecast.incomeTitle, forecast.incomeAmount ?: 0, null, null))
    } else emptyList()
    return (fromItems + bills + income).sortedWith(compareBy({ it.date }, { it.entry?.itemId ?: Long.MAX_VALUE }))
}
