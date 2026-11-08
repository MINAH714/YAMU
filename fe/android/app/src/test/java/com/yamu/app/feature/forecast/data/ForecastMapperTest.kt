package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.forecast.noticeLines
import com.yamu.app.feature.forecast.periodLabel
import com.yamu.app.feature.forecast.riskLine
import com.yamu.app.feature.forecast.statusTag
import com.yamu.app.core.designsystem.component.TagTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 잔고예보 — BE BalanceForecast 응답(2026-11-06 develop) 그대로 읽고, 앱은 정리 · 표기만(12 문서 8-3) */
class ForecastMapperTest {
    /** BE 안내 예시(결제일 25일 · 마감 14일 카드, 오늘 11/6)에 진행 중 청구 · 모르는 종류 · 모르는 이유를 더한 응답 */
    private val json = """
        {"accountIds":[3,4],"selectionCount":2,"currentTotalBalance":680000,
         "horizon":{"type":"NEXT_INCOME","from":"2026-11-06","to":"2026-11-24","days":18},
         "alertThreshold":100000,
         "summary":{"status":"WARNING","aggregateStatus":"WARNING","minBalance":40000,"minBalanceDate":"2026-11-24","endBalance":40000,
                    "hasAccountRisk":false,"atRiskAccountCount":0,"largestOutflow":{"date":"2026-11-25","title":"생활카드 청구","amount":-150000}},
         "series":[
           {"date":"2026-11-06","confirmed":680000,"expected":680000,"conservative":680000,"events":[]},
           {"date":"2026-11-10","confirmed":430000,"expected":430000,"conservative":390000,"events":[
              {"type":"SCHEDULED_OUTFLOW","title":"월세","amount":-250000,"confidence":"CONFIRMED"},
              {"type":"SOMETHING_NEW","title":"새 종류","amount":-1000,"confidence":"WHATEVER"}]},
           {"date":"2026-11-20","confirmed":430000,"expected":280000,"conservative":160000,"events":[
              {"type":"CARD_BILLING","title":"생활카드 청구","amount":-150000,"confidence":"EXPECTED"},
              {"type":"CARD_SPENDING_ESTIMATE","title":"생활카드 · 11/7~11/11 5일 × 일평균 12,000원","amount":-60000,"confidence":"CONSERVATIVE"}]},
           {"date":"2026-11-24","confirmed":430000,"expected":280000,"conservative":40000,"events":[]}
         ],
         "riskWindows":[{"from":"2026-11-21","to":"2026-11-24","level":"WARNING","line":"CONSERVATIVE","lowestBalance":40000,"shortfall":60000,
           "causes":[{"type":"CARD_SPENDING_ESTIMATE","title":"생활카드 · 11/7~11/11 5일 × 일평균 12,000원","amount":-60000,"refId":null,"date":"2026-11-20"},
                     {"type":"LIVING_COST_ESTIMATE","title":"예상 생활비","amount":-180000,"refId":null,"date":"2026-11-24"}]}],
         "livingCost":{"projectedAmount":180000,"dailyAverage":7000,"monthlyAverage":210000,"method":"ML_V1","dataOrigin":"REAL","confidence":"HIGH"},
         "accountForecasts":null,
         "excluded":[{"cashflowItemId":null,"cardBillId":null,"occurrenceDate":null,"date":"2026-11-25","title":"여행카드","amount":null,"reason":"CREDIT_SPENDING_UNKNOWN"},
                     {"cashflowItemId":12,"date":"2026-11-15","title":"관리비","amount":null,"reason":"AMOUNT_UNKNOWN"},
                     {"cashflowItemId":13,"date":"2026-11-16","title":"모르는 항목","amount":-5000,"reason":"BRAND_NEW_REASON"}],
         "isEstimate":true,"calculatedAt":"2026-11-06T03:00:00","newField":1}
    """.trimIndent()

    private fun parse(text: String = json) = YamuJson.decodeFromString(BalanceForecastDto.serializer(), text).toForecast()

    @Test fun `예보 — 기간 · 월급날 · 가장 나쁜 선 최저 · 상태`() {
        val f = parse()
        assertEquals(listOf(3L, 4L), f.accountIds)
        assertEquals(680_000L, f.currentBalance)
        assertTrue(f.isNextIncome)
        assertEquals(LocalDate.parse("2026-11-25"), f.incomeDate)          // 끝 날(월급 전날) 다음 날
        assertEquals(40_000L, f.minBalance)
        assertEquals(LocalDate.parse("2026-11-24"), f.minBalanceDate)
        assertEquals(RiskLevel.WARNING, f.status)
        assertEquals(100_000L, f.alertThreshold)
        assertTrue(f.hasConservative)
        assertEquals(4, f.days.size)
    }

    @Test fun `새 확신도 CONSERVATIVE · 새 종류 CARD_SPENDING_ESTIMATE — 추정 태그와 근거 문장`() {
        val estimate = parse().upcoming.single { it.type == FlowType.CARD_SPENDING_ESTIMATE }
        assertEquals(FlowConfidence.CONSERVATIVE, estimate.confidence)
        assertTrue(estimate.isEstimate)
        assertEquals("카드 사용 예상", estimate.label)
        assertEquals("생활카드 · 11/7~11/11 5일 × 일평균 12,000원", estimate.basis)   // 서버 title 그대로
        val bill = parse().upcoming.single { it.type == FlowType.CARD_BILLING }
        assertTrue(bill.isCardBill)
        assertTrue(bill.isExpected)
        assertFalse(bill.isEstimate)
    }

    @Test fun `모르는 종류 · 확신도도 깨지지 않고 서버 title로 한 줄`() {
        val unknown = parse().upcoming.single { it.title == "새 종류" }
        assertEquals(FlowType.UNKNOWN, unknown.type)
        assertEquals(FlowConfidence.UNKNOWN, unknown.confidence)
        assertEquals("새 종류", unknown.label)
    }

    @Test fun `예정 출금 합 — 보수적 선 전용 추정은 세지 않는다`() {
        assertEquals(250_000L + 1_000L + 150_000L, parse().scheduledOutflow)
    }

    @Test fun `위험 구간 — 원인에 카드 사용 예상 · 예상 생활비, 문장은 선 기준`() {
        val w = parse().riskWindows.single()
        assertEquals(FlowConfidence.CONSERVATIVE, w.line)
        assertEquals(listOf(FlowType.CARD_SPENDING_ESTIMATE, FlowType.LIVING_COST_ESTIMATE), w.causes.map { it.type })
        assertEquals(listOf("카드 사용 예상", "예상 생활비"), w.causes.map { it.label })
        assertEquals("11월 24일까지 · AI 예측", w.causes[1].sub(LivingMethod.ML_V1))
        assertEquals("보수적으로 보면 안전 잔액보다 최대 60,000원 모자라요", riskLine(w))
        assertEquals(LocalDate.parse("2026-11-21"), parse().riskFrom)
    }

    @Test fun `세 선 설명 — 예상 생활비는 projectedAmount만, 끝 잔액은 서버 선 그대로`() {
        val (confirmed, expected, conservative) = parse().lineGroups()
        assertEquals(listOf(LineStep("예정 지출", -250_000)), confirmed.steps)
        assertEquals(430_000L, confirmed.end)
        assertEquals(listOf("진행 중인 카드 청구", "기타"), expected.steps.map { it.label })   // 모르는 확신도는 예상 선의 기타로
        assertEquals(280_000L, expected.end)
        assertEquals(LineStep("예상 생활비", -180_000, "AI 예측"), conservative.steps.first())   // dailyAverage × 일수(126,000)가 아님
        assertEquals(LineStep("앞으로 쓸 신용카드 금액", -60_000), conservative.steps[1])
        assertEquals(40_000L, conservative.end)
    }

    @Test fun `빼고 계산한 것 — 새 이유 CREDIT_SPENDING_UNKNOWN과 모르는 이유`() {
        val notes = parse().excluded.map { it.text }
        assertEquals("여행카드는 사용 기록이 부족해 앞으로 쓸 금액을 반영하지 못했어요.", notes[0])
        assertEquals("금액을 정하지 않은 관리비는 빼고 계산했어요.", notes[1])
        assertEquals("모르는 항목 1건은 빼고 계산했어요.", notes[2])
    }

    /** 신용카드만 쓰는 사람이 이번 달 처음 등록 — 보수적 선 null, 생활비 INSUFFICIENT */
    private val noConservative = """
        {"accountIds":[3],"currentTotalBalance":300000,"horizon":{"type":"DAYS","from":"2026-11-06","to":"2026-12-06","days":30},
         "alertThreshold":100000,"summary":{"status":"SAFE","minBalance":250000,"minBalanceDate":"2026-12-06","endBalance":250000},
         "series":[{"date":"2026-11-06","confirmed":300000,"expected":300000,"conservative":null,"events":[]},
                   {"date":"2026-12-06","confirmed":250000,"expected":250000,"events":[]}],
         "riskWindows":[],"livingCost":{"projectedAmount":null,"dailyAverage":null,"method":"TSB","dataOrigin":"INSUFFICIENT","confidence":"INSUFFICIENT"},
         "excluded":[],"isEstimate":true}
    """.trimIndent()

    @Test fun `보수적 선이 null이어도 읽고, 생활비 미반영 안내는 dataOrigin 기준`() {
        val f = parse(noConservative)
        assertFalse(f.hasConservative)
        assertNull(f.days.first().conservative)
        assertNull(f.incomeDate)                                    // DAYS는 월급날이 없다
        assertTrue(f.livingCost!!.insufficient)
        assertNull(f.lineGroups()[2].end)
        assertEquals(listOf("생활비 기록이 아직 없어 생활비는 빼고 계산했어요. 체크카드로 결제하면 다음 예보부터 들어가요."), noticeLines(f, ForecastSelection()))
        assertEquals("30일 동안 안전 잔액 위" to TagTone.Success, statusTag(f))
        assertEquals("30일", periodLabel(f, ForecastSelection()))
    }

    @Test fun `신용카드만 쓰는 계좌 — 보수적 선은 숫자지만 생활비는 여전히 미반영`() {
        val dto = YamuJson.decodeFromString(BalanceForecastDto.serializer(), noConservative)
        val f = dto.copy(series = dto.series.map { it.copy(conservative = (it.expected ?: 0) - 50_000) }).toForecast()
        assertTrue(f.hasConservative)
        assertTrue(f.livingCost!!.insufficient)
        assertEquals(listOf("생활비 기록이 없어 보수적 선에는 카드 사용 예상만 넣었어요."), noticeLines(f, ForecastSelection()))
    }

    @Test fun `월급날까지를 골랐는데 서버가 30일로 계산했으면 알린다`() {
        val f = parse(noConservative)
        assertTrue(noticeLines(f, ForecastSelection(ForecastPeriod.NEXT_INCOME)).contains("월급날을 찾지 못해 30일로 계산했어요."))
        assertEquals("월급날까지", periodLabel(f, ForecastSelection(ForecastPeriod.NEXT_INCOME)))
    }

    @Test fun `상태 태그 — 사실만, 처음 안전 잔액 아래인 날`() {
        assertEquals("11월 21일부터 안전 잔액 아래" to TagTone.Warning, statusTag(parse()))
        assertNull(statusTag(testForecast(status = RiskLevel.UNKNOWN)))
    }

    @Test fun `계좌 고르기 — 해제된 계좌는 빼고, 남은 게 없으면 전체`() {
        val accounts = listOf(ForecastAccountOption(1, "a", null, "", null, true), ForecastAccountOption(2, "b", null, "", null, false))
        assertEquals(listOf(1L, 2L), ForecastSelection().accountIdsFrom(accounts))
        assertEquals(listOf(2L), ForecastSelection(accountIds = setOf(2, 9)).accountIdsFrom(accounts))
        assertEquals(listOf(1L, 2L), ForecastSelection(accountIds = setOf(9)).accountIdsFrom(accounts))
    }

    @Test fun `받침 따라 은 · 는`() {
        assertEquals("은", "월세 관리금".topicParticle())
        assertEquals("는", "관리비".topicParticle())
        assertEquals("는", "Netflix".topicParticle())
    }

    @Test fun `날짜 표기`() {
        assertEquals("10/20 (화)", LocalDate.parse("2026-10-20").toMonthDayWeek())
    }

    // ── #33 판단 — S15P31E103-326에서 가상 구매 시뮬레이션으로 바꾸며 지운다 ──

    private val decision = SpendingDecisionDto(
        verdict = "CAUTION",
        headline = "지금 사면 월급 전에 5만 원 모자라요",
        methods = listOf(
            MethodDto("CHECK_LUMP_SUM", 12, "F 체크카드", withdrawals = listOf(WithdrawalDto("2026-10-18", 400_000)), minBalance = -50_000, shortfallFrom = "2026-11-01", status = "DANGER"),
            MethodDto("CREDIT_INSTALLMENT", 15, "A 생활 신용카드", months = 3, withdrawals = listOf(WithdrawalDto("2026-11-16", 133_400)), minBalance = 350_000),
            MethodDto("SOMETHING_NEW", 1),
        ),
        alternatives = listOf(AlternativeDto("DELAY", until = "2026-11-10", minBalanceAfter = 350_000, message = "월급 뒤로 미루면 부족 없음")),
    )

    @Test fun `판단 — 모르는 방식은 빼고 미루기는 추천`() {
        val c = decision.toBuyCheck("태블릿", 400_000, LocalDate.parse("2026-10-18"))
        assertEquals(BuyVerdict.CAUTION, c.verdict)
        assertEquals(3, c.options.size)
        assertTrue(c.delay!!.recommended)
        assertEquals("구매 계획", c.delay!!.toPlanRequest(null, 400_000).title)
    }
}
