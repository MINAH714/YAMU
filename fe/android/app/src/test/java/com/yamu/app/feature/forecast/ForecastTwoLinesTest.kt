package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastLivingCost
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.LineStep
import com.yamu.app.feature.forecast.data.LivingMethod
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.RiskWindow
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 잔고예보 두 선(점검 B-05, BE -356 !223) — expected 'AI 생활비 제외' · conservative 'AI 생활비 반영', confirmed는 그리지 않는다 */
class ForecastTwoLinesTest {
    private val nov10 = LocalDate.of(2026, 11, 10)
    private val nov20 = LocalDate.of(2026, 11, 20)

    private fun window(line: FlowConfidence, shortfall: Long? = 70_000, level: RiskLevel = RiskLevel.WARNING) =
        RiskWindow(nov20, nov20, level, line, lowestBalance = 30_000, shortfall = shortfall, causes = emptyList())

    @Test fun `위험 문장 — 두 선은 선 이름으로, 부족액이 없으면 아래로 내려간다고만`() {
        assertEquals("AI 생활비를 빼지 않아도 안전 잔액보다 최대 70,000원 모자라요", riskLine(window(FlowConfidence.EXPECTED, level = RiskLevel.DANGER)))
        assertEquals("AI 생활비를 반영하면 안전 잔액보다 최대 70,000원 모자라요", riskLine(window(FlowConfidence.CONSERVATIVE)))
        assertEquals("AI 생활비를 반영하면 안전 잔액 아래로 내려가요", riskLine(window(FlowConfidence.CONSERVATIVE, shortfall = null)))
        assertEquals("AI 생활비를 빼지 않아도 안전 잔액 아래로 내려가요", riskLine(window(FlowConfidence.EXPECTED, shortfall = 0)))
    }

    @Test fun `위험 문장 — 옛 서버의 CONFIRMED · 모르는 선은 그리지 않는 선이라 앞말 없이`() {
        // 앱이 서버 !223보다 먼저 나가면 line CONFIRMED가 올 수 있다
        assertEquals("안전 잔액보다 최대 70,000원 모자라요", riskLine(window(FlowConfidence.CONFIRMED, level = RiskLevel.DANGER)))
        assertEquals("안전 잔액보다 최대 70,000원 모자라요", riskLine(window(FlowConfidence.UNKNOWN)))
        assertEquals("안전 잔액 아래로 내려가요", riskLine(window(FlowConfidence.CONFIRMED, shortfall = null)))
    }

    @Test fun `범례 — 생활비 선이 없을 때만 'AI 생활비 반영'을 취소선으로`() {
        assertEquals(emptyList<String>(), missingLines(testForecast(days = listOf(day(nov10), day(nov20)))))
        assertEquals(listOf("AI 생활비 반영"), missingLines(testForecast(days = listOf(day(nov10, conservative = null), day(nov20, conservative = null)))))
    }

    @Test fun `맨 위 가장 낮을 때 금액 — 생활비 선이 있으면 반영 선, 없으면 제외 선 기준(!223 답)`() {
        assertEquals("AI 생활비 반영 선 기준", minBalanceBasis(testForecast(days = listOf(day(nov10), day(nov20)))))
        assertEquals("AI 생활비 제외 선 기준", minBalanceBasis(testForecast(days = listOf(day(nov10, conservative = null), day(nov20, conservative = null)))))
        assertNull(minBalanceBasis(testForecast(days = emptyList())))   // 날마다 값이 없으면 어느 선인지 모른다
    }

    @Test fun `그래프 접근성 설명 — 두 선 이름과 가장 낮은 선, '예상 잔액'이라 부르지 않는다`() {
        val both = testForecast(
            days = listOf(day(nov10), day(nov20)),
            riskWindows = listOf(window(FlowConfidence.CONSERVATIVE)),
        )
        assertEquals(
            "잔액 그래프, AI 생활비 제외 선과 AI 생활비 반영 선, AI 생활비 반영 선이 가장 낮을 때 350,000원, 11월 20일부터 안전 잔액 아래",
            chartDescription(both),
        )
        val one = testForecast(days = listOf(day(nov10, conservative = null), day(nov20, conservative = null)))
        assertEquals(
            "잔액 그래프, AI 생활비 제외 선만(생활비 기록이 없어 AI 생활비 반영 선은 그리지 않았어요), 가장 낮을 때 350,000원",
            chartDescription(one),
        )
        assertTrue(listOf(both, one).none { "예상" in chartDescription(it) })
    }

    @Test fun `두 선 설명 — 같은 이름은 합치고(예정 지출은 확정 · 후보가 섞여도 한 줄), 카드 청구는 확정 · 진행 중으로 나눈다`() {
        val f = testForecast(
            days = listOf(
                day(
                    nov10,
                    ForecastEvent(nov10, FlowType.SCHEDULED_INCOME, "용돈", 100_000, FlowConfidence.CONFIRMED),
                    ForecastEvent(nov10, FlowType.SCHEDULED_OUTFLOW, "월세", -250_000, FlowConfidence.CONFIRMED),
                    ForecastEvent(nov10, FlowType.CARD_BILLING, "알뜰카드 10월 청구", -80_000, FlowConfidence.CONFIRMED),
                ),
                day(
                    nov20,
                    ForecastEvent(nov20, FlowType.SCHEDULED_INCOME, "환급", 30_000, FlowConfidence.CONFIRMED),
                    ForecastEvent(nov20, FlowType.SCHEDULED_OUTFLOW, "관리비 후보", -40_000, FlowConfidence.EXPECTED),   // 확인 전 지출 후보 — BE는 수입 후보를 만들지 않는다
                    ForecastEvent(nov20, FlowType.CARD_BILLING, "생활카드 11월 청구", -150_000, FlowConfidence.EXPECTED),
                    ForecastEvent(nov20, FlowType.SCHEDULED_OUTFLOW, "새 확신도", -5_000, FlowConfidence.UNKNOWN),
                    ForecastEvent(nov20, FlowType.CARD_SPENDING_ESTIMATE, "생활카드 · 추정", -36_000, FlowConfidence.CONSERVATIVE),
                    expected = 455_000,
                    conservative = 299_000,
                ),
            ),
            livingCost = ForecastLivingCost(120_000, LivingMethod.TSB, insufficient = false),
        )
        val (withoutLiving, withLiving) = f.lineGroups()
        assertEquals(
            listOf(
                LineStep("예정 수입 2건", 130_000),
                LineStep("예정 지출 2건", -290_000),
                LineStep("확정된 카드 청구", -80_000),
                LineStep("진행 중인 카드 청구", -150_000),
                LineStep("기타", -5_000),               // 확신도를 모르면 종류와 상관없이 기타(맨 끝)
            ),
            withoutLiving.steps,
        )
        assertEquals(455_000L, withoutLiving.end)
        assertEquals(listOf(LineStep("예상 생활비", -120_000, "최근 소비 기준"), LineStep("앞으로 쓸 신용카드 금액", -36_000)), withLiving.steps)
        assertEquals(299_000L, withLiving.end)
    }

    @Test fun `두 선 설명 — 생활비 기록이 없으면 둘째 묶음은 신용카드 금액만`() {
        val f = testForecast(
            days = listOf(
                day(nov10),
                day(nov20, ForecastEvent(nov20, FlowType.CARD_SPENDING_ESTIMATE, "생활카드 · 추정", -36_000, FlowConfidence.CONSERVATIVE), conservative = 464_000),
            ),
            livingCost = ForecastLivingCost(null, LivingMethod.UNKNOWN, insufficient = true),
        )
        val (withoutLiving, withLiving) = f.lineGroups()
        assertTrue(withoutLiving.steps.isEmpty())
        assertEquals(listOf(LineStep("앞으로 쓸 신용카드 금액", -36_000)), withLiving.steps)
        assertEquals(464_000L, withLiving.end)
        // 계좌 하나면 줄 합계(500,000 − 36,000)가 끝 잔액과 맞아 안내를 붙이지 않는다
        assertFalse(f.hasPartialLiving)
        assertNull(withLiving.note)
        assertEquals(listOf("생활비 기록이 없어 'AI 생활비 반영' 선에는 앞으로 쓸 신용카드 금액만 넣었어요."), noticeLines(f, ForecastSelection()))
    }

    /**
     * !223 리뷰 — 계좌 A(생활비 있음) + B(신용카드 추정만). BE는 통합 livingCost를 INSUFFICIENT로 주지만 conservative에는 A의 생활비 120,000이 들어 있다.
     * 옛 서버(appliedAmount 없음)는 금액을 주지 않아 줄 합계(−36,000)로는 끝 잔액(344,000)이 나오지 않으니 앱이 금액을 만들지 말고 까닭만 적는다
     */
    @Test fun `옛 서버 — 여러 계좌에서 생활비 기록이 일부만 있으면 끝 잔액에만 들어 있다고 알린다`() {
        val f = testForecast(
            accountIds = listOf(1, 2),
            days = listOf(
                day(nov10),
                day(nov20, ForecastEvent(nov20, FlowType.CARD_SPENDING_ESTIMATE, "생활카드 · 추정", -36_000, FlowConfidence.CONSERVATIVE), conservative = 344_000),
            ),
            livingCost = ForecastLivingCost(null, LivingMethod.UNKNOWN, insufficient = true),
        )
        assertTrue(f.hasPartialLiving)
        val withLiving = f.lineGroups()[1]
        assertEquals(listOf(LineStep("앞으로 쓸 신용카드 금액", -36_000)), withLiving.steps)   // 생활비 줄은 만들지 않는다
        assertEquals(344_000L, withLiving.end)                                                  // 서버 끝 잔액 그대로
        assertEquals("생활비 기록이 있는 계좌가 있으면 그 계좌의 예상 생활비는 금액을 따로 받지 못해 끝 잔액에만 들어 있어요", withLiving.note)
        assertEquals(listOf("생활비 기록이 없는 계좌는 'AI 생활비 반영' 선에 앞으로 쓸 신용카드 금액만 넣었어요."), noticeLines(f, ForecastSelection()))
    }

    @Test fun `여러 계좌라도 생활비 금액을 받았거나 생활비 선이 없으면 안내를 붙이지 않는다`() {
        val two = listOf(1L, 2L)
        val withAmount = testForecast(accountIds = two, days = listOf(day(nov10), day(nov20)), livingCost = ForecastLivingCost(120_000, LivingMethod.TSB, insufficient = false))
        assertFalse(withAmount.hasPartialLiving)
        assertNull(withAmount.lineGroups()[1].note)
        val noLine = testForecast(
            accountIds = two,
            days = listOf(day(nov10, conservative = null), day(nov20, conservative = null)),
            livingCost = ForecastLivingCost(null, LivingMethod.UNKNOWN, insufficient = true),
        )
        assertFalse(noLine.hasPartialLiving)
        assertNull(noLine.lineGroups()[1].note)
    }

    @Test fun `새 서버 — 일부 계좌만 기록이 있으면 선에 빠진 생활비(appliedAmount) 줄로 끝 잔액까지 맞춘다(-395 !244)`() {
        val f = testForecast(
            accountIds = listOf(1, 2),
            days = listOf(
                day(nov10),
                day(nov20, ForecastEvent(nov20, FlowType.CARD_SPENDING_ESTIMATE, "생활카드 · 추정", -36_000, FlowConfidence.CONSERVATIVE), conservative = 344_000),
            ),
            livingCost = ForecastLivingCost(null, LivingMethod.TSB, insufficient = true, appliedAmount = 120_000),
        )
        val (withoutLiving, withLiving) = f.lineGroups()
        assertEquals(listOf(LineStep("예상 생활비", -120_000, "최근 소비 기준, 기록 있는 계좌만"), LineStep("앞으로 쓸 신용카드 금액", -36_000)), withLiving.steps)
        assertEquals(withLiving.end, withoutLiving.end!! + withLiving.steps.sumOf { it.amount })   // 500,000 − 120,000 − 36,000 = 344,000
        assertFalse(f.hasPartialLiving)
        assertNull(withLiving.note)
    }

    @Test fun `새 서버 — 여러 계좌 모두 기록이 없으면(appliedAmount 0) 생활비 줄 없이 기록이 없다고만`() {
        val f = testForecast(
            accountIds = listOf(1, 2),
            days = listOf(
                day(nov10),
                day(nov20, ForecastEvent(nov20, FlowType.CARD_SPENDING_ESTIMATE, "생활카드 · 추정", -36_000, FlowConfidence.CONSERVATIVE), conservative = 464_000),
            ),
            livingCost = ForecastLivingCost(null, LivingMethod.UNKNOWN, insufficient = true, appliedAmount = 0),
        )
        val withLiving = f.lineGroups()[1]
        assertEquals(listOf(LineStep("앞으로 쓸 신용카드 금액", -36_000)), withLiving.steps)   // '예상 생활비 0원' 줄은 만들지 않는다
        assertEquals(464_000L, withLiving.end)
        assertFalse(f.hasPartialLiving)
        assertNull(withLiving.note)
        assertEquals(listOf("생활비 기록이 없어 'AI 생활비 반영' 선에는 앞으로 쓸 신용카드 금액만 넣었어요."), noticeLines(f, ForecastSelection()))
    }

    @Test fun `모든 계좌에 기록이 있어 예상 생활비가 0원이면 0원 줄도 그대로 보인다`() {
        val f = testForecast(
            days = listOf(day(nov10), day(nov20, conservative = 500_000)),
            livingCost = ForecastLivingCost(0, LivingMethod.TSB, insufficient = false, appliedAmount = 0),
        )
        assertEquals(listOf(LineStep("예상 생활비", 0, "최근 소비 기준")), f.lineGroups()[1].steps)
    }

    @Test fun `이름 뒤 붙임말은 가운데 점 대신 괄호로(11월 10일 QA)`() {
        assertEquals("넷플릭스 (예상)", dayEventLabel(ForecastEvent(nov20, FlowType.SCHEDULED_OUTFLOW, "넷플릭스", -13_500, FlowConfidence.EXPECTED)))
        assertEquals("카드 사용 예상 (추정)", dayEventLabel(ForecastEvent(nov20, FlowType.CARD_SPENDING_ESTIMATE, "생활카드 사용 추정", -36_000, FlowConfidence.CONSERVATIVE)))
        assertEquals("월세", dayEventLabel(ForecastEvent(nov20, FlowType.SCHEDULED_OUTFLOW, "월세", -500_000, FlowConfidence.CONFIRMED)))
        assertEquals("예상 생활비 (최근 소비 기준, 기록 있는 계좌만)", stepLabel(LineStep("예상 생활비", -120_000, "최근 소비 기준, 기록 있는 계좌만")))
        assertEquals("예정 지출", stepLabel(LineStep("예정 지출", -13_500)))
        assertEquals("11월 20일 (금, 오늘)", nov20.toKoreanDateWeek(markToday = true, today = nov20))
        assertEquals("11월 20일 (금)", nov20.toKoreanDateWeek(markToday = true, today = nov10))
        assertEquals("11월 20일 (금)", nov20.toKoreanDateWeek())
    }
}
