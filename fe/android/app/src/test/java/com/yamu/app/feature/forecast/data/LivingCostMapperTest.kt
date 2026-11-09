package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.YamuJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 생활비 예측 상세 — BE LivingCostForecast(-70) 응답을 그대로 읽는다. 기간 문구는 잔고예보와 같게 */
class LivingCostMapperTest {
    /** 계좌 둘 중 하나만 기록이 있는 응답 — 통합 값은 null(INSUFFICIENT), 계좌별 값은 따로 */
    private val partialJson = """
        {"accountIds":[3,4],"projectedAmount":null,"dailyAverage":null,"monthlyAverage":null,"method":null,"dataOrigin":"INSUFFICIENT","confidence":"INSUFFICIENT",
         "period":{"from":"2026-11-08","to":"2026-11-24","days":16,"basis":"NEXT_INCOME"},
         "sources":[{"yearMonth":"2026-10","amount":190000},{"yearMonth":"2026-08","amount":170000},{"yearMonth":"bad","amount":1},{"yearMonth":"2026-09","amount":180000}],
         "excluded":[{"type":"RECURRING","note":"매달 같은 결제는 뺐어요"},{"type":"LARGE","note":" "},{"type":"NEW_TYPE"}],
         "dailyTarget":null,
         "paceCheck":{"thisMonthDaily":8000,"averageDaily":6000,"overRatio":0.333,"flagged":true},
         "accountLivingCosts":[{"accountId":3,"dailyAverage":6000,"monthlyAverage":180000,"projectedAmount":96000,"method":"ML_V1"},
                               {"accountId":4,"dailyAverage":null,"monthlyAverage":null,"projectedAmount":null,"method":null}]}
    """.trimIndent()

    @Test fun `기록이 일부 계좌만 있으면 통합 값은 없고 기록 없는 계좌를 따로 안다`() {
        val d = YamuJson.decodeFromString<LivingCostDetailDto>(partialJson).toDetail(mapOf(3L to "월급 통장"))

        assertNull(d.projectedAmount)
        assertEquals(LivingConfidence.INSUFFICIENT, d.confidence)
        assertEquals(LivingMethod.UNKNOWN, d.method)
        assertEquals(2, d.accounts.size)
        assertEquals("월급 통장", d.accounts[0].name)
        assertTrue(d.accounts[0].hasRecord)
        assertEquals(LivingMethod.ML_V1, d.accounts[0].method)
        assertNull(d.accounts[1].name)             // 연결 목록에 없는 계좌는 이름 없이
        assertEquals(listOf(4L), d.accountsWithoutRecord.map { it.accountId })
    }

    @Test fun `기간 — 월급날 기준이면 '월급 전까지', 아니면 일수`() {
        val d = YamuJson.decodeFromString<LivingCostDetailDto>(partialJson).toDetail()
        assertTrue(d.isNextIncome)
        assertEquals("월급 전까지", d.periodLabel)
        assertEquals(LocalDate.of(2026, 11, 8), d.from)
        assertEquals(LocalDate.of(2026, 11, 24), d.to)

        val days = LivingCostDetailDto(period = LivingPeriodDto("2026-11-08", "2026-11-22", 14, "DAYS")).toDetail()
        assertFalse(days.isNextIncome)
        assertEquals("14일 동안", days.periodLabel)
        assertEquals("30일 동안", LivingCostDetailDto().toDetail().periodLabel)
    }

    @Test fun `달별 합계는 날짜순, 읽을 수 없는 달은 뺀다 · 빈 안내도 뺀다`() {
        val d = YamuJson.decodeFromString<LivingCostDetailDto>(partialJson).toDetail()
        assertEquals(
            listOf(LivingMonth(YearMonth.of(2026, 8), 170_000), LivingMonth(YearMonth.of(2026, 9), 180_000), LivingMonth(YearMonth.of(2026, 10), 190_000)),
            d.months,
        )
        assertEquals(listOf("매달 같은 결제는 뺐어요"), d.excludedNotes)
    }

    @Test fun `이번 달 속도 — 초과 비율은 퍼센트로 반올림`() {
        val d = YamuJson.decodeFromString<LivingCostDetailDto>(partialJson).toDetail()
        assertEquals(33, d.pace?.overPercent)
        assertTrue(d.pace?.flagged == true)

        val half = YamuJson.decodeFromString<LivingCostDetailDto>("""{"paceCheck":{"thisMonthDaily":5000,"averageDaily":6000,"overRatio":-0.165,"flagged":false}}""").toDetail()
        assertEquals(-17, half.pace?.overPercent)
        assertNull(YamuJson.decodeFromString<LivingCostDetailDto>("""{"paceCheck":{"flagged":false}}""").toDetail().pace?.overPercent)
    }

    @Test fun `모의 소비로 낸 예측은 seeded, 하루 목표는 설정 값 그대로`() {
        val d = YamuJson.decodeFromString<LivingCostDetailDto>(
            """{"accountIds":[3],"projectedAmount":180000,"dailyAverage":6000,"method":"TSB","dataOrigin":"SEED","confidence":"HIGH","dailyTarget":5000}""",
        ).toDetail()
        assertTrue(d.seeded)
        assertEquals(LivingMethod.TSB, d.method)
        assertEquals(LivingConfidence.HIGH, d.confidence)
        assertEquals(5_000L, d.dailyTarget)
        assertEquals(emptyList<AccountLivingCost>(), d.accounts)   // 계좌별 값을 안 주면 빈 목록
    }
}
