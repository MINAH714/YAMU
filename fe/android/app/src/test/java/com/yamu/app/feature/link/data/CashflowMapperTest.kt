package com.yamu.app.feature.link.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

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

    @Test fun `후보가 하나도 없을 때 하루 생활비만 저장하면 그 필드 하나만 보낸다 — 기간 기준을 덮지 않는다(!205 리뷰)`() {
        val (patches, settings) = CashflowPlan(emptyList(), incomeId = null, dailyLivingCost = 18_000).toPatches()
        assertEquals(emptyList<Pair<Long, CashflowPatchRequest>>(), patches)
        assertEquals("""{"dailyLivingCostTarget":18000}""", settings.toBody().toString())
    }

    @Test fun `월급 후보가 없으면 고정 지출만 확정해도 기간 기준 · 월급을 보내지 않는다 — 사용자가 일수 기준으로 정해 뒀을 수 있다`() {
        val (_, settings) = CashflowPlan(listOf(CashflowDecision(302, true, null)), incomeId = null, dailyLivingCost = null).toPatches()
        assertEquals(emptySet<String>(), settings.toBody().keys)
    }

    @Test fun `월급 후보를 빼면 기간 기준 · 월급을 보내지 않는다 — 정해 둔 월급과 기간이 그대로 남는다`() {
        val (_, settings) = CashflowPlan(listOf(CashflowDecision(305, false, null)), incomeId = 305, dailyLivingCost = null).toPatches()
        assertEquals(emptySet<String>(), settings.toBody().keys)
    }

    @Test fun `고정 지출을 확정하며 하루 생활비만 바꾸면 그것만 보낸다`() {
        val (_, settings) = CashflowPlan(listOf(CashflowDecision(302, true, null)), incomeId = null, dailyLivingCost = 6_000).toPatches()
        assertEquals(setOf("dailyLivingCostTarget"), settings.toBody().keys)
    }

    @Test fun `월급 후보를 넣으면 그 항목이 월급이고 기간은 다음 월급까지`() {
        val (_, settings) = CashflowPlan(listOf(CashflowDecision(305, true, null)), incomeId = 305, dailyLivingCost = null).toPatches()
        assertEquals(305L, settings.incomeCashflowItemId)
        assertEquals("NEXT_INCOME", settings.horizon)
        assertEquals(setOf("horizon", "incomeCashflowItemId"), settings.toBody().keys)
    }

    @Test fun `설정 응답 — 하루 목표와 정해 둔 월급을 읽는다`() {
        val settings = YamuJson.decodeFromString<ForecastSettingsDto>(
            """{"baseAccountId":5,"horizon":"NEXT_INCOME","defaultDays":14,"alertThreshold":100000,
                "income":{"cashflowItemId":301,"title":"월급","amount":null,"dayOfMonth":25,"accountId":5},
                "dailyLivingCostTarget":7000,"includeCandidates":false,"conservativeLine":false,"updatedAt":null}""",
        )
        assertEquals(7_000L, settings.dailyLivingCostTarget)
        assertEquals(SettingsIncomeDto(301, "월급", null, 25), settings.income)
        val none = YamuJson.decodeFromString<ForecastSettingsDto>("""{"income":null,"dailyLivingCostTarget":null}""")
        assertNull(none.dailyLivingCostTarget)
        assertNull(none.income)
    }

    @Test fun `새로 넣은 월급은 등록한 ID를 월급으로 · 기간은 다음 월급까지`() {
        val (_, settings) = CashflowPlan(listOf(CashflowDecision(302, true, null)), incomeId = null, dailyLivingCost = null, createdIncomeId = 900).toPatches()
        assertEquals(900L, settings.incomeCashflowItemId)
        assertEquals("""{"horizon":"NEXT_INCOME","incomeCashflowItemId":900}""", settings.toBody().toString())
    }

    @Test fun `월급 첫 회차 — 오늘 뒤 첫 그날, 짧은 달은 말일(서버 기준일 규칙)`() {
        val nov9 = LocalDate.of(2026, 11, 9)
        assertEquals(LocalDate.of(2026, 11, 25), nextMonthlyDate(25, nov9))
        assertEquals(LocalDate.of(2026, 12, 9), nextMonthlyDate(9, nov9))     // 오늘이 월급날이면 다음 달부터(이미 들어왔을 수 있다)
        assertEquals(LocalDate.of(2026, 11, 30), nextMonthlyDate(31, nov9))   // 31 = 말일
        assertEquals(LocalDate.of(2027, 2, 28), nextMonthlyDate(30, LocalDate.of(2027, 1, 31)))
        assertEquals(LocalDate.of(2027, 1, 31), nextMonthlyDate(31, LocalDate.of(2026, 12, 31)))
    }

    @Test fun `월급 등록 본문 — 매월 반복 · 기준일은 넣은 날 그대로 · 수입은 계좌로`() {
        val body = IncomeInput(2_500_000, 31, accountId = 5).toRequest(LocalDate.of(2027, 2, 3))
        assertEquals(
            """{"type":"INCOME","title":"월급","amount":2500000,"date":"2027-02-28","recurrence":{"frequency":"MONTHLY","dayOfMonth":31},"accountId":5}""",
            YamuJson.encodeToString(com.yamu.app.feature.forecast.data.CashflowCreateRequest.serializer(), body),
        )
    }

    @Test fun `후보 근거 — 몇 달에 나왔는지와 마지막 결제일(한국 날짜)`() {
        assertEquals("최근 6개월 중 3달 · 마지막 11월 1일", DetectionDto("넷플릭스", 3, "2026-10-31T16:30:00Z").basisLine())   // UTC 10/31 16:30 = 한국 11/1
        assertEquals("최근 6개월 중 4달", DetectionDto(sampleCount = 4, lastPaidAt = "엉뚱한 값").basisLine())
        assertNull(DetectionDto().basisLine())
        val draft = listOf(
            CashflowItemDto(302, "EXPENSE", "넷플릭스", 17_000, recurrence = RecurrenceDto("MONTHLY", 5), source = "DETECTED", status = "CANDIDATE",
                detection = DetectionDto("넷플릭스", 3, "2026-10-05T01:00:00Z")),
        ).toDraft(null)
        assertEquals("최근 6개월 중 3달 · 마지막 10월 5일", draft.expenses.single().basis)
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

    @Test fun `월급은 기간 기준과 함께 보내고 생활비 목표는 없으면 안 보낸다`() {
        val incomeOnly = ForecastSettingsPatch("NEXT_INCOME", 301, null).toBody()
        assertEquals(setOf("horizon", "incomeCashflowItemId"), incomeOnly.keys)
        assertFalse("dailyLivingCostTarget" in incomeOnly)
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
