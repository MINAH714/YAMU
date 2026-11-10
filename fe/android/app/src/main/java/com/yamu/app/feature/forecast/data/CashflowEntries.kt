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
     * 말일이 아닌 매월 기준일. 날짜를 안 옮겼으면 회차 날짜(2/28)가 아니라 규칙의 기준일(30)이고,
     * 규칙이 말일(31)이면 말일을 끈 것이라 원래 회차의 날짜(11/30 → 30)다
     */
    fun monthlyDayOn(form: CashflowForm): Int =
        if (form.date != date) form.date.dayOfMonth else dayOfMonth?.takeIf { it < 31 } ?: occurrenceDate.dayOfMonth

    /**
     * 날짜를 안 옮겼으면 기준일을 지킨다. 서버는 date가 없으면 원래 회차를 규칙의 첫날로 본다.
     * 2/28 회차에서 규칙만 바꿔도 매월 30일이 28일로 밀리지 않고, 이번 회차만 옮겨 둔 회차(25일 → 27일)도 첫날과 반복일이 어긋나 400이 나지 않게
     */
    private fun RecurrenceRequest.keepBaseDay(dateChanged: Boolean, form: CashflowForm): RecurrenceRequest = when {
        dateChanged -> this
        frequency == "WEEKLY" -> copy(dayOfWeek = occurrenceDate.dayOfWeek.name)
        frequency == "MONTHLY" && !form.isMonthEnd -> copy(dayOfMonth = monthlyDayOn(form))
        else -> this
    }

    /** '이번 회차만'을 고를 수 있는지 — 반복 규칙(주기 · 말일 · 종료일)을 바꾸면 서버가 받지 않는다 */
    fun canEditThisOnly(form: CashflowForm): Boolean = isRecurring && !form.ruleChangedFrom(toForm())

    /** '이후 전부'를 고를 수 있는지 — 서버는 원래 회차부터 바꾸므로 그 날짜가 지났으면 옮긴 날짜가 남아 있어도 400을 준다 */
    fun canEditFuture(today: LocalDate): Boolean = !occurrenceDate.isBefore(today)

    /** '이후 전부'로 저장하면 이 회차가 원래 날짜로 돌아가는지 — 옮겨 둔 회차(25일 → 27일)에서 날짜를 그대로 둔 경우 */
    fun revertsToOccurrence(form: CashflowForm): Boolean = date != occurrenceDate && form.date == date

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

/**
 * F-02 한 줄 — [entry]가 있으면 눌러서 고친다. 카드대금 · 월급 미리보기처럼 가계부 항목이 아니면 null(읽기 전용).
 * [candidate]는 확인 전 고정 지출 후보(예보에서 가져온 읽기 전용 줄, '예상' 태그)
 */
data class CashflowRow(
    val date: LocalDate?,
    val title: String,
    val amount: Long,
    val repeatLabel: String?,
    val entry: CashflowEntry?,
    val candidate: Boolean = false,
) {
    /**
     * 카드 예정 지출 표시 — 줄의 날짜는 사용일이다. 신용카드는 돈이 청구 출금일에 빠져 머리의 '나갈 돈'(기간 안 출금)과 날짜가 다를 수 있고,
     * 체크카드는 사용일에 빠진다(BE CashflowEvents). 줄에서는 카드 결제라는 것만 밝힌다
     */
    val cardNote: String? get() = "카드 결제".takeIf { entry?.userCardId != null && amount < 0 }
}

/**
 * F-02 줄 합치기 — 가계부 회차(고칠 수 있음) + 예보의 카드 청구와 확인 전 고정 지출 후보(읽기 전용).
 * 가계부 목록은 확인한 항목만 받아(status 기본값 CONFIRMED) 후보는 예보에서 가져온다. 전에는 회차 응답이 오면 후보가 목록에서 빠지는데
 * 머리 '나갈 돈'(예보 기준)은 계속 세어 둘이 어긋났다(11/10 QA, 넷플릭스). 확인한 후보는 예보에서 CONFIRMED로 바뀌어 두 번 나오지 않는다.
 * 카드 사용 예상('AI 생활비 반영' 선 전용 추정)은 예정된 돈이 아니라 넣지 않는다. 날짜는 서버가 준 값 그대로 쓰고 다시 계산하지 않는다
 */
fun cashflowRows(entries: List<CashflowEntry>, forecast: Forecast?): List<CashflowRow> {
    // 계좌를 일부만 골랐으면 가계부 줄도 그 계좌 것만 — 머리(예보 기준)와 목록이 같은 계좌를 말하게 한다
    val scope = forecast?.scope
    val fromItems = entries.filter { scope?.includes(it) ?: true }.map { e -> CashflowRow(e.date, e.title, e.signedAmount, e.repeatLabel(), e) }
    val fromForecast = forecast?.upcoming.orEmpty().filter { it.isCardBill || it.isCandidate }.map { it.toCashflowRow() }
    return (fromItems + fromForecast).sortedWith(compareBy({ it.date }, { it.entry?.itemId ?: Long.MAX_VALUE }))
}

/** 예보 이벤트 → F-02 읽기 전용 줄(카드 청구 · 확인 전 후보). 회차를 못 받았을 때도 같은 모양으로 그린다 */
fun ForecastEvent.toCashflowRow(): CashflowRow = CashflowRow(date, title, amount, null, null, candidate = isCandidate)
