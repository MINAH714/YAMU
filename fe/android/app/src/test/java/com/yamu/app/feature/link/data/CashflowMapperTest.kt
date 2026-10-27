package com.yamu.app.feature.link.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B-06 — #19 후보 → 화면 줄, 사용자 고름 → #8 · #17 요청 */
class CashflowMapperTest {
    private fun item(id: Long, type: String, title: String, amount: Long, day: Int, kind: String? = null) =
        CashflowItemDto(id, type, title, amount, recurrence = RecurrenceDto("MONTHLY", day), kind = kind, status = "CANDIDATE")

    @Test fun `월급은 가장 큰 수입 · 고정 지출은 날짜순 · 카드대금은 따로`() {
        val draft = listOf(
            item(304, "EXPENSE", "구독료", 15_000, 1),
            item(301, "INCOME", "월급", 2_800_000, 10),
            item(305, "INCOME", "중고 판매", 30_000, 3),
            item(302, "EXPENSE", "월세", 250_000, 25),
            item(900, "EXPENSE", "하나카드 대금", 150_000, 20, kind = "CARD_BILL"),
        ).toDraft(dailyLivingCost = 6_000)

        assertEquals(301L, draft.income?.id)
        assertEquals(listOf("구독료", "월세"), draft.expenses.map { it.title })
        assertEquals(listOf("하나카드 대금"), draft.bills.map { it.title })
        assertEquals(6_000L, draft.dailyLivingCost)
    }

    @Test fun `날짜가 반복이 아니면 발생일에서 일자`() {
        val draft = listOf(CashflowItemDto(1, "EXPENSE", "보험", 40_000, occurrenceDate = "2026-11-05")).toDraft(null)
        assertEquals(5, draft.expenses.single().dayOfMonth)
    }

    @Test fun `뺀 항목은 IGNORED · 고친 금액은 함께 · 월급 지정`() {
        val (patches, settings) = CashflowPlan(
            decisions = listOf(
                CashflowDecision(301, include = true, amount = null),
                CashflowDecision(302, include = true, amount = 260_000),
                CashflowDecision(304, include = false, amount = null),
            ),
            incomeId = 301,
            dailyLivingCost = 6_000,
        ).toPatches()

        assertEquals(CashflowPatchRequest("CONFIRMED"), patches[0].second)
        assertEquals(CashflowPatchRequest("CONFIRMED", 260_000), patches[1].second)
        assertEquals(CashflowPatchRequest("IGNORED"), patches[2].second)
        assertEquals(ForecastSettingsPatch("NEXT_INCOME", 301, 6_000), settings)
    }

    @Test fun `월급을 빼면 다음 월급까지가 아니라 날짜 기준`() {
        val (_, settings) = CashflowPlan(listOf(CashflowDecision(301, include = false, amount = null)), incomeId = 301, dailyLivingCost = null).toPatches()
        assertEquals("DAYS", settings.horizon)
        assertNull(settings.incomeCashflowItemId)
    }
}
