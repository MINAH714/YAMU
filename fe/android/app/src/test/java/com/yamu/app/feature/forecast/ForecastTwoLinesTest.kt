package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastLivingCost
import com.yamu.app.feature.forecast.data.LineStep
import com.yamu.app.feature.forecast.data.LivingMethod
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.RiskWindow
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import org.junit.Assert.assertEquals
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

    @Test fun `두 선 설명 — 같은 이름은 확신도가 달라도 한 줄, 카드 청구는 확정 · 진행 중으로 나눈다`() {
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
                    ForecastEvent(nov20, FlowType.SCHEDULED_INCOME, "환급 후보", 30_000, FlowConfidence.EXPECTED),   // 확인 전 후보
                    ForecastEvent(nov20, FlowType.SCHEDULED_OUTFLOW, "관리비 후보", -40_000, FlowConfidence.EXPECTED),
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
    }
}
