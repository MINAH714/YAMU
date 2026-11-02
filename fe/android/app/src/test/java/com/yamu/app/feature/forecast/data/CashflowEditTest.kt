package com.yamu.app.feature.forecast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** F-02 · F-02b — 목록 회차 → 수정 요청(바꾼 칸만) · 범위 · F-02 줄 합치기 */
class CashflowEditTest {
    private val nov30 = LocalDate.of(2026, 11, 30)

    /** 매월 말일 월세 — 이번 회차는 11/30(원래 회차도 11/30), 종료일 2027-06-30 */
    private val rent = CashflowEntry(
        itemId = 41, isIncome = false, title = "월세", amount = 500_000, occurrenceDate = nov30, date = nov30,
        repeat = CashflowRepeat.MONTHLY, dayOfMonth = 31, endDate = LocalDate.of(2027, 6, 30), accountId = 3, userCardId = null,
    )

    @Test fun `목록 회차를 화면 값으로 — 기준일 31이면 말일 · 종료일 그대로`() {
        val f = rent.toForm()
        assertTrue(f.isMonthEnd)
        assertEquals("매월 말일", f.repeatLabel())
        assertEquals(LocalDate.of(2027, 6, 30), f.endDate)
        assertEquals(nov30, f.date)
    }

    @Test fun `금액만 고치면 금액 · 범위 · 원래 회차만 보내고 반복은 보내지 않는다(종료일 유지)`() {
        val body = rent.toPatch(rent.toForm().copy(amount = 550_000), CashflowScope.THIS_AND_FUTURE)
        assertEquals(CashflowUpdateRequest(amount = 550_000, scope = "THIS_AND_FUTURE", occurrenceDate = "2026-11-30"), body)
        assertTrue(rent.canEditThisOnly(rent.toForm().copy(amount = 550_000)))
    }

    @Test fun `이번 회차만 날짜를 옮기면 날짜만 · 반복은 보내지 않는다`() {
        // 말일 반복 중 날짜 고르기는 그달 말일로 붙으므로(withDate), 이번 회차만 옮긴 값은 직접 만든다
        val thisOnly = rent.toForm().copy(date = LocalDate.of(2026, 11, 28))
        val body = rent.toPatch(thisOnly, CashflowScope.THIS_ONLY)
        assertEquals("2026-11-28", body.date)
        assertNull(body.recurrence)
        assertEquals("THIS_ONLY", body.scope)
    }

    @Test fun `이후 전부에서 날짜를 옮기면 반복을 같이 보내 말일 기준일과 종료일을 지킨다`() {
        val f = rent.toForm().withDate(LocalDate.of(2026, 12, 15))   // 말일 반복이라 12/31로 붙는다
        val body = rent.toPatch(f, CashflowScope.THIS_AND_FUTURE)
        assertEquals("2026-12-31", body.date)
        assertEquals(RecurrenceRequest("MONTHLY", dayOfMonth = 31, endDate = "2027-06-30"), body.recurrence)
    }

    @Test fun `반복 규칙(말일 · 종료일 · 주기)을 바꾸면 이번 회차만은 고를 수 없다`() {
        assertFalse(rent.canEditThisOnly(rent.toForm().withMonthEnd(false)))
        assertFalse(rent.canEditThisOnly(rent.toForm().withEndDate(null)))
        assertFalse(rent.canEditThisOnly(rent.toForm().withRepeat(CashflowRepeat.WEEKLY)))
    }

    @Test fun `종료일을 없애면 반복을 종료일 없이 보낸다 · 1회로 바꾸면 NONE`() {
        val noEnd = rent.toPatch(rent.toForm().withEndDate(null), CashflowScope.THIS_AND_FUTURE)
        assertEquals(RecurrenceRequest("MONTHLY", dayOfMonth = 31), noEnd.recurrence)
        val once = rent.toPatch(rent.toForm().withRepeat(CashflowRepeat.NONE), CashflowScope.THIS_AND_FUTURE)
        assertEquals(RecurrenceRequest("NONE"), once.recurrence)
    }

    @Test fun `1회 항목은 범위 · 원래 회차를 보내지 않는다 · 계좌를 카드로 바꾸면 카드만`() {
        val once = rent.copy(repeat = CashflowRepeat.NONE, dayOfMonth = null, endDate = null)
        val body = once.toPatch(once.toForm().withCard(16), CashflowScope.THIS_AND_FUTURE)
        assertEquals(CashflowUpdateRequest(userCardId = 16), body)
        assertFalse(once.canEditThisOnly(once.toForm()))
    }

    @Test fun `끝나는 날이 시작일보다 앞이면 막는다 · 반복이 아니면 종료일을 보지 않는다`() {
        val today = LocalDate.of(2026, 11, 1)
        val bad = rent.toForm().withEndDate(LocalDate.of(2026, 11, 29))
        assertEquals(setOf(CashflowField.RECURRENCE), bad.validate(today).keys)
        assertTrue(bad.withRepeat(CashflowRepeat.NONE).validate(today).isEmpty())
    }

    @Test fun `서버 회차를 앱 값으로 — 원래 회차와 실제 날짜를 나눠 둔다`() {
        val dto = CashflowEntryDto(
            cashflowItemId = 41, type = "EXPENSE", title = "월세", amount = 500_000, occurrenceDate = "2026-11-30", date = "2026-12-01",
            recurrence = EntryRecurrenceDto("MONTHLY", 31, null, "2027-06-30"), accountId = 3,
        )
        val e = dto.toEntry()!!
        assertEquals(nov30, e.occurrenceDate)
        assertEquals(LocalDate.of(2026, 12, 1), e.date)
        assertEquals(-500_000L, e.signedAmount)
        assertEquals(CashflowRepeat.MONTHLY, e.repeat)
    }

    @Test fun `F-02 줄 — 가계부 회차는 고칠 수 있고 카드대금 · 목록에 없는 월급은 읽기 전용`() {
        val f = forecast(
            upcoming = listOf(ForecastEvent(LocalDate.of(2026, 11, 5), "하나카드 대금", -150_000, false, null, false, isCardBill = true)),
            incomeDate = LocalDate.of(2026, 11, 26),
        )
        val rows = cashflowRows(listOf(rent.copy(date = LocalDate.of(2026, 11, 20), occurrenceDate = LocalDate.of(2026, 11, 20))), f)
        assertEquals(listOf("하나카드 대금", "월세", "월급"), rows.map { it.title })
        assertEquals(listOf(false, true, false), rows.map { it.entry != null })
        assertEquals("매월 말일", rows[1].repeatLabel)
    }

    @Test fun `F-02 줄 — 월급이 가계부에 있으면 예보 월급을 또 붙이지 않는다`() {
        val salary = rent.copy(itemId = 7, isIncome = true, title = "월급", amount = 2_800_000, date = LocalDate.of(2026, 11, 26), occurrenceDate = LocalDate.of(2026, 11, 26))
        val rows = cashflowRows(listOf(salary), forecast(upcoming = emptyList(), incomeDate = LocalDate.of(2026, 11, 26)))
        assertEquals(1, rows.size)
        assertEquals(2_800_000L, rows.single().amount)
    }

    private fun forecast(upcoming: List<ForecastEvent>, incomeDate: LocalDate?) = Forecast(
        currentBalance = 950_000, balanceAsOfIso = null, minBalance = 350_000, shortfallFrom = null,
        incomeDate = incomeDate, incomeAmount = 2_800_000, incomeTitle = "월급",
        from = LocalDate.of(2026, 11, 1), to = LocalDate.of(2026, 11, 25), fallback = false,
        upcoming = upcoming, plans = emptyList(), scheduledOutflow = 450_000, livingCost = null, points = emptyList(), isEstimate = true,
        accounts = emptyList(),
    )
}
