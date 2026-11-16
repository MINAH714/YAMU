package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.AmountLimit
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** F-02a — 입력 → #20 요청 · 앱 검사 · 서버 fieldErrors 칸 */
class CashflowFormTest {
    private val today = LocalDate.of(2026, 11, 1)
    private fun form(date: LocalDate = LocalDate.of(2026, 11, 15)) =
        CashflowForm(title = " 월세 ", amount = 500_000, date = date, accountId = 3)

    @Test fun `반복 안 함은 recurrence 없이 1회 · 이름은 앞뒤 공백을 뺀다`() {
        val r = form().toRequest()
        assertEquals("EXPENSE", r.type)
        assertEquals("월세", r.title)
        assertEquals("2026-11-15", r.date)
        assertNull(r.recurrence)
        assertEquals(3L, r.accountId)
        assertNull(r.userCardId)
    }

    @Test fun `등록 본문은 BE 필드 이름 그대로 · 없는 칸은 JSON에 없다`() {
        val monthEnd = form(LocalDate.of(2026, 11, 30)).withRepeat(CashflowRepeat.MONTHLY).withMonthEnd(true).withEndDate(LocalDate.of(2027, 6, 30))
        assertEquals(
            """{"type":"EXPENSE","title":"월세","amount":500000,"date":"2026-11-30","recurrence":{"frequency":"MONTHLY","dayOfMonth":31,"endDate":"2027-06-30"},"accountId":3}""",
            YamuJson.encodeToString(monthEnd.toRequest()),
        )
        val weeklyCard = form(LocalDate.of(2026, 11, 3)).withRepeat(CashflowRepeat.WEEKLY).withCard(16)
        assertEquals(
            """{"type":"EXPENSE","title":"월세","amount":500000,"date":"2026-11-03","recurrence":{"frequency":"WEEKLY","dayOfWeek":"TUESDAY"},"userCardId":16}""",
            YamuJson.encodeToString(weeklyCard.toRequest()),
        )
    }

    @Test fun `매주는 첫 발생일의 요일로 보낸다`() {
        val f = form(LocalDate.of(2026, 11, 3)).withRepeat(CashflowRepeat.WEEKLY)   // 화요일
        assertEquals(RecurrenceRequest("WEEKLY", dayOfWeek = "TUESDAY"), f.toRequest().recurrence)
        assertEquals("매주 화요일", f.repeatLabel())
    }

    @Test fun `매월 N일은 시작일의 날짜를 기준일로`() {
        val f = form().withRepeat(CashflowRepeat.MONTHLY)
        assertEquals(RecurrenceRequest("MONTHLY", dayOfMonth = 15), f.toRequest().recurrence)
        assertEquals("매월 15일", f.repeatLabel())
    }

    @Test fun `매월 말일을 고르면 시작일을 그달 말일로 옮기고 기준일 31을 보낸다`() {
        val f = form().withRepeat(CashflowRepeat.MONTHLY).withMonthEnd(true)
        assertEquals(LocalDate.of(2026, 11, 30), f.date)
        assertEquals("2026-11-30", f.toRequest().date)
        assertEquals(RecurrenceRequest("MONTHLY", dayOfMonth = 31), f.toRequest().recurrence)
        assertEquals("매월 말일", f.repeatLabel())
    }

    @Test fun `말일 반복 중에 날짜를 바꾸면 고른 달의 말일로 · 2월은 28일`() {
        val f = form().withRepeat(CashflowRepeat.MONTHLY).withMonthEnd(true).withDate(LocalDate.of(2027, 2, 10))
        assertEquals(LocalDate.of(2027, 2, 28), f.date)
        assertEquals(31, f.toRequest().recurrence?.dayOfMonth)
    }

    @Test fun `31일에 시작하는 매월은 곧 말일 · 말일을 끄면 날짜는 그대로`() {
        val dec31 = form(LocalDate.of(2026, 12, 31)).withRepeat(CashflowRepeat.MONTHLY)
        assertTrue(dec31.isMonthEnd)
        assertEquals("매월 말일", dec31.repeatLabel())
        val off = form().withRepeat(CashflowRepeat.MONTHLY).withMonthEnd(true).withMonthEnd(false)
        assertEquals(LocalDate.of(2026, 11, 30), off.date)
        assertEquals(30, off.toRequest().recurrence?.dayOfMonth)
    }

    @Test fun `말일 칩 하나로 켜고 끈다 — 끄면 옮긴 날짜는 그대로 두고 그날을 기준일로`() {
        val monthly = form().withRepeat(CashflowRepeat.MONTHLY)
        val on = monthly.withMonthEnd(!monthly.isMonthEnd)          // 화면의 말일 칩 누르기
        assertTrue(on.isMonthEnd)
        assertEquals(LocalDate.of(2026, 11, 30), on.date)
        val off = on.withMonthEnd(!on.isMonthEnd)
        assertEquals(false, off.isMonthEnd)
        assertEquals("매월 30일", off.repeatLabel())
        assertEquals(RecurrenceRequest("MONTHLY", dayOfMonth = 30), off.toRequest().recurrence)
    }

    @Test fun `31일에 붙은 말일을 끄면 칩은 켜진 채로 보이지만 다음 날짜는 고른 날 그대로`() {
        val dec = form(LocalDate.of(2026, 12, 10)).withRepeat(CashflowRepeat.MONTHLY)
        val on = dec.withMonthEnd(true)
        assertEquals(LocalDate.of(2026, 12, 31), on.date)
        val off = on.withMonthEnd(!on.isMonthEnd)
        assertTrue(off.isMonthEnd)                                   // 31일은 곧 말일
        assertEquals(31, off.toRequest().recurrence?.dayOfMonth)
        val picked = off.withDate(LocalDate.of(2027, 1, 15))         // 말일에 붙지 않는다
        assertEquals(LocalDate.of(2027, 1, 15), picked.date)
        assertEquals("매월 15일", picked.repeatLabel())
        // 끄지 않았으면 고른 달의 말일로 붙는다
        assertEquals(LocalDate.of(2027, 1, 31), on.withDate(LocalDate.of(2027, 1, 15)).date)
    }

    @Test fun `날짜 줄 이름 — 새 반복은 처음 나가는 날 · 1회와 고칠 때는 나가는 날 · 수입은 들어오는 날`() {
        assertEquals("나가는 날", form().dateLabel(editing = false))
        assertEquals("처음 나가는 날", form().withRepeat(CashflowRepeat.WEEKLY).dateLabel(editing = false))
        assertEquals("처음 나가는 날", form().withRepeat(CashflowRepeat.MONTHLY).dateLabel(editing = false))
        assertEquals("처음 들어오는 날", form().withIncome(true).withRepeat(CashflowRepeat.MONTHLY).dateLabel(editing = false))
        assertEquals("들어오는 날", form().withIncome(true).dateLabel(editing = false))
        // 고칠 때는 그 회차의 날짜다
        assertEquals("나가는 날", form().withRepeat(CashflowRepeat.MONTHLY).dateLabel(editing = true))
    }

    @Test fun `끝나는 날이 앞이면 날짜 줄 이름으로 알린다(시작일이라 쓰지 않는다)`() {
        val f = form().withRepeat(CashflowRepeat.WEEKLY).withEndDate(LocalDate.of(2026, 11, 14))
        assertEquals("끝나는 날은 나가는 날과 같거나 그 뒤여야 해요", f.validate(today)[CashflowField.RECURRENCE])
        assertEquals("끝나는 날은 들어오는 날과 같거나 그 뒤여야 해요", f.withIncome(true).validate(today)[CashflowField.RECURRENCE])
        assertTrue(f.withEndDate(f.date).validate(today).isEmpty())  // 같은 날은 된다
    }

    @Test fun `매월 안내의 기준일은 넘긴 값 · 말일이면 넘긴 값과 상관없이 말일`() {
        val feb = form(LocalDate.of(2027, 2, 28)).withRepeat(CashflowRepeat.MONTHLY)
        assertEquals("매월 28일", feb.repeatLabel())
        assertEquals("매월 30일", feb.repeatLabel(monthlyDay = 30))
        assertEquals("매월 말일", feb.withMonthEnd(true).repeatLabel(monthlyDay = 30))
        assertEquals("매주 일요일", feb.withRepeat(CashflowRepeat.WEEKLY).repeatLabel(monthlyDay = 30))
    }

    @Test fun `매월에서 다른 반복으로 바꾸면 말일 선택이 풀린다`() {
        val f = form().withRepeat(CashflowRepeat.MONTHLY).withMonthEnd(true).withRepeat(CashflowRepeat.WEEKLY)
        assertEquals(false, f.monthEnd)
        assertEquals("WEEKLY", f.toRequest().recurrence?.frequency)
    }

    @Test fun `계좌와 카드는 하나만 · 수입으로 바꾸면 카드를 비운다`() {
        val card = form().withCard(16)
        assertNull(card.accountId)
        assertEquals(16L, card.toRequest().userCardId)
        val income = card.withIncome(true)
        assertNull(income.userCardId)
        assertEquals(mapOf(CashflowField.PAYMENT to "들어올 계좌를 골라 주세요"), income.validate(today))
        assertEquals("INCOME", income.withAccount(3).toRequest().type)
    }

    @Test fun `보내기 전 검사 — 이름 · 금액 범위 · 지난 날짜`() {
        val bad = CashflowForm(title = "  ", amount = 0, date = LocalDate.of(2026, 10, 31), accountId = 3).validate(today)
        assertEquals(setOf(CashflowField.TITLE, CashflowField.AMOUNT, CashflowField.DATE), bad.keys)
        // 빈 칸과 0원은 문구를 나눈다(점검 2026-11-08)
        assertEquals("금액을 넣어 주세요", form().copy(amount = null).validate(today)[CashflowField.AMOUNT])
        assertEquals("1원 이상 넣어 주세요", bad[CashflowField.AMOUNT])
        assertTrue(form().copy(amount = 1).validate(today).isEmpty())
        assertEquals("1,000억 원까지 넣을 수 있어요", form().copy(amount = AmountLimit.CASHFLOW.max + 1).validate(today)[CashflowField.AMOUNT])
        assertTrue(form().copy(amount = AmountLimit.CASHFLOW.max).validate(today).isEmpty())
        assertTrue(form(today).validate(today).isEmpty())   // 오늘은 된다
    }

    @Test fun `서버 fieldErrors는 칸으로 · 반복 안쪽 칸은 반복 칸`() {
        val e = ApiError.InvalidRequest(
            "INVALID_REQUEST",
            buildJsonObject {
                putJsonArray("fieldErrors") {
                    addJsonObject { put("field", "recurrence.dayOfMonth"); put("reason", "매월 반복일은 첫 발생일의 날짜와 같아야 합니다.") }
                    addJsonObject { put("field", "userCardId"); put("reason", "정지된 카드예요") }
                }
            },
        )
        val byField = e.fieldErrors().mapNotNull { (f, reason) -> cashflowFieldOf(f)?.let { it to reason } }.toMap()
        assertEquals(setOf(CashflowField.RECURRENCE, CashflowField.PAYMENT), byField.keys)
        assertEquals("정지된 카드예요", byField[CashflowField.PAYMENT])
        assertTrue(ApiError.Network.fieldErrors().isEmpty())
    }
}
