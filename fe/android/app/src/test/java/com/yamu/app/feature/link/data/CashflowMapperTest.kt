package com.yamu.app.feature.link.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `매월 말일 반복(31)은 기준일 31 그대로 오고 '말일'로 보인다`() {
        val draft = listOf(item(306, "EXPENSE", "월세", 500_000, 31), item(304, "EXPENSE", "구독료", 15_000, 1)).toDraft(null)
        assertEquals(listOf("구독료", "월세"), draft.expenses.map { it.title })   // 31은 날짜순 맨 뒤
        assertEquals(31, draft.expenses.last().dayOfMonth)
        assertEquals("말일", monthlyDayLabel(31))
        assertEquals("30일", monthlyDayLabel(30))   // 30일 반복은 2월만 서버가 말일로 맞추고 표시는 그대로
        assertEquals("1일", monthlyDayLabel(1))
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

    @Test fun `월급을 빼면 본문에 null을 명시해 서버가 지운다 · 생활비 목표는 없으면 안 보낸다`() {
        val cleared = ForecastSettingsPatch("DAYS", null, null).toBody()
        assertEquals(JsonNull, cleared["incomeCashflowItemId"])
        assertFalse("dailyLivingCostTarget" in cleared)
        val set = ForecastSettingsPatch("NEXT_INCOME", 301, 6_000).toBody()
        assertEquals("301", set["incomeCashflowItemId"].toString())
        assertEquals("6000", set["dailyLivingCostTarget"].toString())
    }

    @Test fun `매월 반복이 아닌 수입은 월급 후보에서 뺀다 · 서버가 400을 준다`() {
        val draft = listOf(
            CashflowItemDto(1, "INCOME", "보너스", 5_000_000, occurrenceDate = "2026-11-05"),
            item(301, "INCOME", "월급", 2_800_000, 25),
        ).toDraft(null)
        assertEquals(301L, draft.income?.id)
    }

    @Test fun `금액이 null인 항목이 있어도 목록 전체가 실패하지 않는다`() {
        val dto = YamuJson.decodeFromString<CashflowItemDto>("""{"cashflowItemId":1,"type":"EXPENSE","title":"미정","amount":null}""")
        assertEquals(0L, dto.amount)
    }
}
