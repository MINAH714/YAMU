package com.yamu.app.feature.forecast

import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.SimBenefit
import com.yamu.app.feature.forecast.data.SimCard
import com.yamu.app.feature.forecast.data.SimDay
import com.yamu.app.feature.forecast.data.SimSnapshot
import com.yamu.app.feature.forecast.data.SimWithdrawal
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.SimulationQuery
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/** 가상 구매 결과 그래프(Task 8.2.72) — 처음 고른 날, 구매 · 출금 표시, 잔고예보 입출금 빌려 오기, 말풍선 · 읽어 주기, 범례 색(Task 8.2.178) */
class BuyCheckChartTest {

    @Test fun `처음 고른 날은 구매일, 구매일이 기간 밖이면 사고 나서 가장 낮은 날, 그것도 모르면 마지막 날`() {
        assertEquals(3, simInitialDay(sim(purchase = NOV15)))
        assertEquals(6, simInitialDay(sim(purchase = LocalDate.of(2026, 12, 1), afterMin = NOV12.plusDays(6))))
        assertEquals(8, simInitialDay(sim(purchase = LocalDate.of(2026, 12, 1), afterMin = null)))
    }

    @Test fun `체크카드는 구매일에 출금 표시, 신용 일시불은 기간 밖 날짜만 축 끝에`() {
        val check = sim(schedule = listOf(CHECK_WD))
        assertEquals(setOf(3), simWithdrawDays(check))
        assertNull(beyondLabel(check))

        val credit = sim(schedule = listOf(CREDIT_WD))
        assertEquals(emptySet<Int>(), simWithdrawDays(credit))
        assertEquals("12/14 출금", beyondLabel(credit))
    }

    @Test fun `할부가 여러 번 기간 밖이면 첫 날과 횟수, 기간 안 회차는 그날 동전`() {
        val installment = sim(
            schedule = listOf(
                SimWithdrawal(NOV12.plusDays(8), -150_000, "1/3회차 (11월 청구)", beyondPeriod = false),
                SimWithdrawal(LocalDate.of(2027, 1, 14), -150_000, "3/3회차 (1월 청구)", beyondPeriod = true),
                SimWithdrawal(LocalDate.of(2026, 12, 14), -150_000, "2/3회차 (12월 청구)", beyondPeriod = true),
            ),
        )
        assertEquals(setOf(8), simWithdrawDays(installment))
        assertEquals("12/14부터 2번 출금", beyondLabel(installment))
    }

    @Test fun `출금 계좌가 고른 계좌 밖이면 선이 같으니 그래프에 출금 표시를 하지 않고 칸에는 계좌를 밝힌다`() {
        val outside = sim(schedule = listOf(CHECK_WD), outsideNote = "생활비 통장은 잔고예보에서 고르지 않은 계좌라 그래프에 넣지 않았어요.")
        assertEquals(emptySet<Int>(), simWithdrawDays(outside))
        assertNull(beyondLabel(sim(schedule = listOf(CREDIT_WD), outsideNote = "…")))
        val events = simDayEvents(outside, null)
        assertEquals("체크카드 즉시 출금 (고르지 않은 계좌)", events[3][1].label)
        assertEquals(emptySet<Int>(), simEventDays(events, outside))
    }

    @Test fun `잔고예보는 날짜와 사기 전 잔액이 모두 같을 때만 빌린다`() {
        val s = sim()
        val same = testForecast(days = DATES.map { day(it) })   // AI 생활비 반영 400,000 = 사기 전
        assertSame(same, matchingForecast(s, same))
        // 'AI 생활비 반영' 선이 없으면 '제외' 선으로 맞춘다
        val noCons = testForecast(days = DATES.map { day(it, expected = 400_000, conservative = null) })
        assertSame(noCons, matchingForecast(s, noCons))
        // 그사이 결제가 생겨 하루라도 다르면, 날짜가 다르면, 날 수가 다르면 빌리지 않는다
        assertNull(matchingForecast(s, testForecast(days = DATES.mapIndexed { i, d -> day(d, conservative = if (i == 5) 390_000 else 400_000) })))
        assertNull(matchingForecast(s, testForecast(days = DATES.map { day(it.plusDays(1)) })))
        assertNull(matchingForecast(s, testForecast(days = DATES.dropLast(1).map { day(it) })))
        assertNull(matchingForecast(s, null))
    }

    @Test fun `고른 날 칸 — 구매, 출금, 잔고예보 입출금 순, 신용카드 구매일은 동전 없이 점선만`() {
        val rent = ForecastEvent(NOV15, FlowType.SCHEDULED_OUTFLOW, "월세", -500_000, FlowConfidence.CONFIRMED)
        val f = testForecast(days = DATES.map { if (it == NOV15) day(it, rent) else day(it) })
        val check = sim(schedule = listOf(CHECK_WD))
        val events = simDayEvents(check, f)
        assertEquals(
            listOf(
                SimDayEvent(SimEventKind.PURCHASE, "구매 (알뜰체크)", null),
                SimDayEvent(SimEventKind.WITHDRAWAL, "체크카드 즉시 출금", -450_000),
                SimDayEvent(SimEventKind.FLOW, "월세", -500_000),
            ),
            events[3],
        )
        assertEquals(setOf(3), simEventDays(events, check))

        val credit = sim(schedule = listOf(CREDIT_WD))
        val creditEvents = simDayEvents(credit, null)
        assertEquals(listOf(SimDayEvent(SimEventKind.PURCHASE, "구매 (알뜰체크)", null)), creditEvents[3])
        assertEquals(emptySet<Int>(), simEventDays(creditEvents, credit))
    }

    @Test fun `말풍선 윗줄과 읽어 주기`() {
        val events = listOf(
            SimDayEvent(SimEventKind.PURCHASE, "구매 (알뜰체크)", null),
            SimDayEvent(SimEventKind.WITHDRAWAL, "체크카드 즉시 출금", -450_000),
        )
        assertEquals("11/15 (일) · 구매 외 1건", simDayLabel(NOV15, events, today = NOV12))
        assertEquals("11/15 (일) · 출금", simDayLabel(NOV15, events.drop(1), today = NOV12))
        assertEquals("11월 12일 (목, 오늘)", simDayLabel(NOV12, emptyList(), today = NOV12))
        assertEquals(
            "11월 15일 (일), 구매 전 400,000원, 구매 후 −50,000원, 구매 (알뜰체크) 카드 결제, 체크카드 즉시 출금 −450,000원",
            simDayState(SimDay(NOV15, 400_000, -50_000), events),
        )
    }

    @Test fun `그래프 읽어 주기에 기간 밖 출금을 붙인다`() {
        assertEquals("구매 전과 후 잔액 그래프, 구매 후 가장 낮을 때 25,000원", simChartDescription(sim(schedule = listOf(CHECK_WD))))
        assertEquals(
            "구매 전과 후 잔액 그래프, 구매 후 가장 낮을 때 25,000원, 12월 14일 출금은 예보 기간 밖이라 선에 넣지 않았어요",
            simChartDescription(sim(schedule = listOf(CREDIT_WD))),
        )
    }

    @Test fun `범례 — 안전 잔액을 알면 주황, 구매 후 선이 0원 아래로 내려갈 때만 빨강`() {
        val above = listOf(400_000L, null, 25_000L)
        val belowZero = listOf(400_000L, null, -50_000L)
        assertEquals(listOf(BalanceZone.BELOW_SAFE), simLegendZones(above, threshold = 100_000))
        assertEquals(listOf(BalanceZone.BELOW_SAFE, BalanceZone.BELOW_ZERO), simLegendZones(belowZero, threshold = 100_000))
        // 잔고예보를 아직 못 받아 안전 잔액을 모르면 주황은 그리지 않으니 범례에도 없다
        assertEquals(emptyList<BalanceZone>(), simLegendZones(above, threshold = null))
        assertEquals(listOf(BalanceZone.BELOW_ZERO), simLegendZones(belowZero, threshold = null))
        // 안전 잔액이 0원이면 그 아래는 곧 0원 아래라 주황 구간이 없다
        assertEquals(emptyList<BalanceZone>(), simLegendZones(above, threshold = 0))
        // 0원은 0원 아래가 아니다
        assertEquals(emptyList<BalanceZone>(), simLegendZones(listOf(0L), threshold = null))
    }

    private companion object {
        val NOV12: LocalDate = LocalDate.of(2026, 11, 12)
        val NOV15: LocalDate = LocalDate.of(2026, 11, 15)
        val DATES: List<LocalDate> = (0L..8L).map { NOV12.plusDays(it) }
        val CHECK_WD = SimWithdrawal(NOV15, -450_000, "체크카드 즉시 출금", beyondPeriod = false)
        val CREDIT_WD = SimWithdrawal(LocalDate.of(2026, 12, 14), -450_000, "일시불 (12월 청구)", beyondPeriod = true)

        fun sim(
            purchase: LocalDate = NOV15,
            schedule: List<SimWithdrawal> = emptyList(),
            afterMin: LocalDate? = NOV12.plusDays(8),
            outsideNote: String? = null,
        ) = Simulation(
            query = SimulationQuery(450_000, "태블릿", "ONLINE_MARKETPLACE", "온라인 쇼핑", null, null, PaymentChannel.ONLINE, 16, null, purchase),
            card = SimCard(16, "알뜰체크", isCredit = false, recommendedTop = false),
            benefit = SimBenefit(null, emptyList(), 450_000),
            missing = emptyList(),
            schedule = schedule,
            before = SimSnapshot(400_000, afterMin, RiskLevel.SAFE),
            after = SimSnapshot(25_000, afterMin, RiskLevel.WARNING),
            beyondPeriodImpact = 0,
            days = DATES.map { SimDay(it, 400_000, 400_000) },
            alternatives = emptyList(),
            assumptions = emptyList(),
            creditLimitSkipped = true,
            outsideNote = outsideNote,
            message = null,
            isEstimate = true,
        )
    }
}
