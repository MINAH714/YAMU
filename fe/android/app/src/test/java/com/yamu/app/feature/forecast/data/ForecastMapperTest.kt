package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.network.YamuJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 잔고예보 · 이거 사도 될까? — 서버 값 그대로 그리고, 앱은 정리 · 표기만(12 문서 8-3) */
class ForecastMapperTest {
    private val json = """
        {"account":{"accountId":3,"bankName":"KB국민은행","maskedAccountNo":"****-1234","balance":950000,"syncedAt":"2026-10-16T08:31:00+09:00"},
         "horizon":{"type":"NEXT_INCOME","from":"2026-10-16","to":"2026-11-09","nextIncome":{"date":"2026-11-10","amount":2800000,"title":"월급"}},
         "summary":{"status":"DANGER","minBalance":-50000,"minBalanceDate":"2026-11-09"},
         "series":[
           {"date":"2026-10-18","expected":538000,"events":[{"type":"PURCHASE_PLAN","title":"태블릿","amount":-400000,"refId":901}]},
           {"date":"2026-10-20","expected":376000,"events":[{"type":"CARD_BILLING","title":"하나카드 대금","amount":-150000}]},
           {"date":"2026-10-25","expected":96000,"events":[{"type":"EXPENSE","title":"월세","amount":-250000}]},
           {"date":"2026-11-01","expected":-2000,"events":[{"type":"RECURRING_EXPENSE","title":"구독료","amount":-15000}]}
         ],
         "beyondHorizon":[{"date":"2026-11-14","type":"CARD_BILLING","title":"신한카드 11월 청구","amount":-120000}],
         "livingCost":{"projectedAmount":150000,"dailyAverage":6000},
         "accounts":[{"accountId":3,"nickname":"생활비 통장","category":"생활비","maskedAccountNo":"****-1234","balance":950000,"minBalance":-50000,"shortfallFrom":"2026-11-01"}],
         "isEstimate":true,"newField":1}
    """.trimIndent()

    @Test fun `예보 — 계획은 따로, 예정된 돈 합, 부족 시작일은 예상 선이 처음 0 아래`() {
        val f = YamuJson.decodeFromString(ForecastDto.serializer(), json).toForecast()
        assertEquals(950_000L, f.currentBalance)
        assertEquals(-50_000L, f.minBalance)
        assertEquals(LocalDate.parse("2026-11-01"), f.shortfallFrom)
        assertEquals(LocalDate.parse("2026-11-10"), f.incomeDate)
        assertEquals(listOf("하나카드 대금", "월세", "구독료"), f.upcoming.map { it.title })
        assertEquals(415_000L, f.scheduledOutflow)                 // 계획(태블릿) 빼고, 월급 뒤 청구도 빼고
        assertEquals(listOf(901L), f.plans.map { it.planId })
        assertFalse(f.plans.single().afterIncome)
        assertEquals(150_000L, f.livingCost)
        assertEquals(4, f.points.size)
        val a = f.accounts.single()
        assertEquals("생활비 통장", a.name)
        assertEquals("1234", a.last4)
        assertEquals(LocalDate.parse("2026-11-01"), a.shortfallFrom)
    }

    @Test fun `서버가 부족 시작일 · 예정 지출 합을 주면 그걸`() {
        val dto = YamuJson.decodeFromString(ForecastDto.serializer(), json)
        val f = dto.copy(summary = dto.summary.copy(shortfallFrom = "2026-10-30", scheduledOutflow = 450_000)).toForecast()
        assertEquals(LocalDate.parse("2026-10-30"), f.shortfallFrom)
        assertEquals(450_000L, f.scheduledOutflow)
    }

    @Test fun `부족 없으면 시작일 없음`() {
        val f = ForecastDto(series = listOf(SeriesPointDto("2026-10-20", expected = 500_000)), summary = ForecastSummaryDto(minBalance = 350_000)).toForecast()
        assertNull(f.shortfallFrom)
        assertNull(f.incomeDate)
    }

    private val decision = SpendingDecisionDto(
        verdict = "CAUTION",
        headline = "지금 사면 월급 전에 5만 원 모자라요",
        methods = listOf(
            MethodDto("CHECK_LUMP_SUM", 12, "F 체크카드", withdrawals = listOf(WithdrawalDto("2026-10-18", 400_000)), minBalance = -50_000, shortfallFrom = "2026-11-01", status = "DANGER"),
            MethodDto("CREDIT_LUMP_SUM", 15, "A 생활 신용카드", withdrawals = listOf(WithdrawalDto("2026-11-16", 400_000)), minBalance = 350_000, status = "SAFE"),
            MethodDto("CREDIT_INSTALLMENT", 15, "A 생활 신용카드", months = 3, withdrawals = listOf(WithdrawalDto("2026-11-16", 133_400)), minBalance = 350_000),
            MethodDto("SOMETHING_NEW", 1),
        ),
        reasons = listOf(ReasonDto("BALANCE", "RISK", "월급 전 쓸 수 있는 돈 350,000원보다 50,000원 많아요")),
        alternatives = listOf(AlternativeDto("DELAY", until = "2026-11-10", minBalanceAfter = 350_000, message = "월급 뒤로 미루면 부족 없음")),
        monthlyBaseline = BaselineDto(averageMonthly = 1_180_000, overRatio = 33.9, periodLabel = "5~9월 평균"),
    )

    @Test fun `판단 — 방법 셋 + 미루기(추천), 이름 · 값 줄`() {
        val c = decision.toBuyCheck("태블릿", 400_000, LocalDate.parse("2026-10-18"))
        assertEquals(BuyVerdict.CAUTION, c.verdict)
        assertEquals("미루는 게 좋아요", c.verdict.label)
        assertEquals("체크카드", c.firstMethodLabel)
        assertEquals(34, c.baselineRatio)
        assertEquals(4, c.options.size)                            // 모르는 방식은 뺀다
        val (check, credit, installment, delay) = c.options
        assertEquals("10월 18일에 사기", check.title)
        assertEquals("−50,000원 월급 전", check.valueLabel)
        assertEquals("신용카드 일시불", credit.methodLabel)
        assertEquals("3개월 할부", installment.title)
        assertEquals("133,400원 매달", installment.valueLabel)
        assertEquals(BuyMethod.DELAY, delay.method)
        assertTrue(delay.recommended)
        assertEquals("11월 10일에 사기", delay.title)
        assertEquals(12L, delay.userCardId)                         // 미룬 날에는 처음 방식의 카드로
        assertEquals(delay, c.delay)
        assertEquals(2, c.reasons.size)                            // 근거 + 대안 문장
        assertEquals(BuyVerdict.UNKNOWN, BuyVerdict.from("SOMETHING"))
    }

    @Test fun `계획 저장 본문 — 할부 · 미루기 · 이름 없음`() {
        val c = decision.toBuyCheck(null, 400_000, LocalDate.parse("2026-10-18"))
        val installment = c.options.first { it.method == BuyMethod.CREDIT_INSTALLMENT }.toPlanRequest("태블릿", 400_000)
        assertEquals("EXPENSE", installment.type)
        assertEquals(installment.userCardId != null, installment.accountId == null)
        val delay = c.delay!!.toPlanRequest(null, 400_000)
        assertEquals("구매 계획", delay.title)
        assertEquals("2026-11-10", delay.date)
    }

    @Test fun `날짜 표기`() {
        assertEquals("10/20 (화)", LocalDate.parse("2026-10-20").toMonthDayWeek())
    }
}
