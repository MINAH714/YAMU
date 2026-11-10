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

    @Test fun `예정 출금 합 — AI 생활비 반영 선 전용 추정은 세지 않는다`() {
        assertEquals(250_000L + 1_000L + 150_000L, parse().scheduledOutflow)
    }

    @Test fun `위험 구간 — 원인에 카드 사용 예상 · 예상 생활비, 문장은 선 기준`() {
        val w = parse().riskWindows.single()
        assertEquals(FlowConfidence.CONSERVATIVE, w.line)
        assertEquals(listOf(FlowType.CARD_SPENDING_ESTIMATE, FlowType.LIVING_COST_ESTIMATE), w.causes.map { it.type })
        assertEquals(listOf("카드 사용 예상", "예상 생활비"), w.causes.map { it.label })
        assertEquals("11월 24일까지 · AI 예측", w.causes[1].sub(LivingMethod.ML_V1))
        assertEquals("AI 생활비를 반영하면 안전 잔액보다 최대 60,000원 모자라요", riskLine(w))
        assertEquals(LocalDate.parse("2026-11-21"), parse().riskFrom)
    }

    @Test fun `두 선 설명 — 확정 묶음 없이 두 묶음, 예상 생활비는 projectedAmount만, 끝 잔액은 서버 선 그대로`() {
        val groups = parse().lineGroups()
        assertEquals(listOf(FlowConfidence.EXPECTED, FlowConfidence.CONSERVATIVE), groups.map { it.line })
        val (withoutLiving, withLiving) = groups
        // 확정(월세) · 진행 중 청구 · 모르는 확신도(기타, 맨 끝)가 한 묶음
        assertEquals(
            listOf(LineStep("예정 지출", -250_000), LineStep("진행 중인 카드 청구", -150_000), LineStep("기타", -1_000)),
            withoutLiving.steps,
        )
        assertEquals(280_000L, withoutLiving.end)                                                  // last.expected — 확정 선 끝(430,000)이 아님
        assertEquals(LineStep("예상 생활비", -180_000, "AI 예측"), withLiving.steps.first())       // dailyAverage × 일수(126,000)가 아님
        assertEquals(LineStep("앞으로 쓸 신용카드 금액", -60_000), withLiving.steps[1])
        assertEquals(40_000L, withLiving.end)
        // 둘째 선은 첫 선에서 더 뺀 값 — 서버 숫자로도 맞는다
        assertEquals(withoutLiving.end!! + withLiving.steps.sumOf { it.amount }, withLiving.end)
    }

    /**
     * !244 안내 예시 — 계좌 A(3)는 체크카드 생활비 150,000원, B(4)는 기록 없이 신용카드 추정 48,000원만.
     * 통합 projectedAmount는 null이지만 appliedAmount(150,000)가 'AI 생활비 반영' 선에 실제로 빠진 생활비다
     */
    private val partialLiving = """
        {"accountIds":[3,4],"currentTotalBalance":1500000,
         "horizon":{"type":"DAYS","from":"2026-11-10","to":"2026-12-10","days":30},
         "alertThreshold":100000,"summary":{"status":"SAFE","minBalance":1302000,"minBalanceDate":"2026-12-10","endBalance":1302000},
         "series":[{"date":"2026-11-10","confirmed":1500000,"expected":1500000,"conservative":1500000,"events":[]},
                   {"date":"2026-11-25","confirmed":1500000,"expected":1500000,"conservative":1377000,"events":[
                      {"type":"CARD_SPENDING_ESTIMATE","title":"생활카드 · 추정","amount":-48000,"confidence":"CONSERVATIVE"}]},
                   {"date":"2026-12-10","confirmed":1500000,"expected":1500000,"conservative":1302000,"events":[]}],
         "riskWindows":[],
         "livingCost":{"projectedAmount":null,"appliedAmount":150000,"dailyAverage":null,"monthlyAverage":null,"method":"TSB","dataOrigin":"INSUFFICIENT","confidence":"INSUFFICIENT"},
         "accountForecasts":[{"accountId":3,"livingCost":{"projectedAmount":150000,"appliedAmount":150000,"method":"TSB","dataOrigin":"REAL","confidence":"HIGH"}},
                             {"accountId":4,"livingCost":{"projectedAmount":null,"appliedAmount":0,"method":null,"dataOrigin":"INSUFFICIENT","confidence":"INSUFFICIENT"}}],
         "excluded":[],"isEstimate":true}
    """.trimIndent()

    @Test fun `두 선 설명 — 일부 계좌만 생활비 기록이 있으면 appliedAmount로 생활비 줄을 그려 끝 잔액과 맞춘다(-395 !244)`() {
        val f = parse(partialLiving)
        assertTrue(f.livingCost!!.insufficient)
        assertNull(f.livingCost!!.projectedAmount)
        val (withoutLiving, withLiving) = f.lineGroups()
        assertEquals(1_500_000L, withoutLiving.end)
        assertEquals(
            listOf(LineStep("예상 생활비", -150_000, "최근 소비 기준 · 기록 있는 계좌만"), LineStep("앞으로 쓸 신용카드 금액", -48_000)),
            withLiving.steps,
        )
        assertEquals(1_302_000L, withLiving.end)
        assertEquals(withoutLiving.end!! + withLiving.steps.sumOf { it.amount }, withLiving.end)   // 1,500,000 − 150,000 − 48,000
        assertFalse(f.hasPartialLiving)                                                         // 금액을 받았으니 '끝 잔액에만 들어 있다' 안내는 없다
        assertNull(withLiving.note)
        assertEquals(listOf("생활비 기록이 없는 계좌는 'AI 생활비 반영' 선에 앞으로 쓸 신용카드 금액만 넣었어요."), noticeLines(f, ForecastSelection()))
    }

    @Test fun `두 선 설명 — 옛 서버(appliedAmount 없음)면 생활비 줄 없이 끝 잔액에만 들어 있다고 알린다`() {
        val f = parse(partialLiving.replace(""""appliedAmount":150000,""", ""))
        assertNull(f.livingCost!!.appliedAmount)
        val withLiving = f.lineGroups()[1]
        assertEquals(listOf(LineStep("앞으로 쓸 신용카드 금액", -48_000)), withLiving.steps)
        assertEquals(1_302_000L, withLiving.end)
        assertTrue(f.hasPartialLiving)
        assertEquals(PARTIAL_LIVING_NOTE, withLiving.note)
    }

    @Test fun `기간 뒤 카드값 — 출금일순으로 읽고 선 · 예정 출금 합에는 넣지 않는다(-348 !199)`() {
        val withBeyond = json.replace(
            """"accountForecasts":null,""",
            """"beyondHorizon":[
                 {"date":"2026-12-14","type":"CARD_SPENDING_ESTIMATE","title":"생활카드 · 11/12~12/13 이용분 추정","amount":-90000,"confidence":"CONSERVATIVE","accountId":4,"userCardId":7},
                 {"date":"2026-11-25","type":"CARD_BILLING","title":"생활카드 11월 청구","amount":-150000,"confidence":"EXPECTED","accountId":4,"userCardId":7},
                 {"date":"2026-12-01","type":"CARD_BILLING","title":"0원 청구","amount":0,"confidence":"CONFIRMED"},
                 {"date":null,"type":"CARD_BILLING","title":"날짜 없음","amount":-1}],
               "accountForecasts":null,""",
        )
        val f = parse(withBeyond)
        assertEquals(listOf("생활카드 11월 청구", "카드 사용 예상"), f.beyondHorizon.map { it.label })   // 출금일순, 0원 · 날짜 없는 줄은 뺌
        assertTrue(f.beyondHorizon[0].isExpected)
        assertTrue(f.beyondHorizon[1].isEstimate)
        assertEquals("생활카드 · 11/12~12/13 이용분 추정", f.beyondHorizon[1].basis)
        assertEquals(parse().scheduledOutflow, f.scheduledOutflow)   // 기간 안 합은 그대로
        assertEquals(parse().days, f.days)
        assertEquals("11월 24일 뒤에 빠져서 위 그래프에는 넣지 않았어요", com.yamu.app.feature.forecast.beyondHorizonNote(f))
        assertTrue(parse().beyondHorizon.isEmpty())   // 옛 서버 응답(칸 없음)
    }

    @Test fun `빼고 계산한 것 — 새 이유 CREDIT_SPENDING_UNKNOWN과 모르는 이유`() {
        val notes = parse().excluded.map { it.text }
        assertEquals("여행카드는 사용 기록이 부족해 앞으로 쓸 금액을 반영하지 못했어요.", notes[0])
        assertEquals("금액을 정하지 않은 관리비는 빼고 계산했어요.", notes[1])
        assertEquals("모르는 항목 1건은 빼고 계산했어요.", notes[2])
    }

    /** 신용카드만 쓰는 사람이 이번 달 처음 등록 — conservative('AI 생활비 반영' 선) null, 생활비 INSUFFICIENT */
    private val noConservative = """
        {"accountIds":[3],"currentTotalBalance":300000,"horizon":{"type":"DAYS","from":"2026-11-06","to":"2026-12-06","days":30},
         "alertThreshold":100000,"summary":{"status":"SAFE","minBalance":250000,"minBalanceDate":"2026-12-06","endBalance":250000},
         "series":[{"date":"2026-11-06","confirmed":300000,"expected":300000,"conservative":null,"events":[]},
                   {"date":"2026-12-06","confirmed":250000,"expected":250000,"events":[]}],
         "riskWindows":[],"livingCost":{"projectedAmount":null,"dailyAverage":null,"method":"TSB","dataOrigin":"INSUFFICIENT","confidence":"INSUFFICIENT"},
         "excluded":[],"isEstimate":true}
    """.trimIndent()

    @Test fun `AI 생활비 반영 선이 null이어도 읽고, 생활비 미반영 안내는 dataOrigin 기준`() {
        val f = parse(noConservative)
        assertFalse(f.hasConservative)
        assertNull(f.days.first().conservative)
        assertNull(f.incomeDate)                                    // DAYS는 월급날이 없다
        assertTrue(f.livingCost!!.insufficient)
        val (withoutLiving, withLiving) = f.lineGroups()
        assertEquals(250_000L, withoutLiving.end)
        assertTrue(withLiving.steps.isEmpty())                      // 생활비 기록이 없으면 예상 생활비 줄도 없다
        assertNull(withLiving.end)
        assertEquals(listOf("생활비 기록이 아직 없어 생활비는 빼고 계산했어요. 체크카드로 결제하면 다음 예보부터 들어가요."), noticeLines(f, ForecastSelection()))
        assertEquals("30일 동안 안전 잔액 위" to TagTone.Success, statusTag(f))
        assertEquals("30일", periodLabel(f, ForecastSelection()))
    }

    @Test fun `신용카드만 쓰는 계좌 — AI 생활비 반영 선은 숫자지만 생활비는 여전히 미반영`() {
        val dto = YamuJson.decodeFromString(BalanceForecastDto.serializer(), noConservative)
        val f = dto.copy(series = dto.series.map { it.copy(conservative = (it.expected ?: 0) - 50_000) }).toForecast()
        assertTrue(f.hasConservative)
        assertTrue(f.livingCost!!.insufficient)
        assertEquals(listOf("생활비 기록이 없어 'AI 생활비 반영' 선에는 앞으로 쓸 신용카드 금액만 넣었어요."), noticeLines(f, ForecastSelection()))
    }

    @Test fun `월급날까지를 골랐는데 서버가 30일로 계산했으면 알린다`() {
        val f = parse(noConservative)
        assertTrue(noticeLines(f, ForecastSelection(ForecastPeriod.NEXT_INCOME)).contains("월급날을 찾지 못해 30일로 계산했어요."))
        assertEquals("월급날까지", periodLabel(f, ForecastSelection(ForecastPeriod.NEXT_INCOME)))
    }

    @Test fun `계좌가 여럿인데 생활비가 빠졌으면 고르면 된다고 약속하지 않는다`() {
        // 로컬 BE 확인: 두 계좌 모두 기록이 없을 때도 '기록이 있는 계좌만 고르면'이라고 했다
        val f = parse(noConservative.replace("\"accountIds\":[3]", "\"accountIds\":[3,4]"))
        val line = noticeLines(f, ForecastSelection()).single()
        assertEquals("생활비 기록이 없는 계좌가 있어 생활비는 빼고 계산했어요. 계좌를 따로 골라 보면 기록이 있는 계좌는 생활비까지 넣어 계산해요.", line)
        assertFalse("고르면 생활비까지 넣어 볼 수 있어요" in line)
    }

    @Test fun `예정 수입·지출 머리 — 금액과 건수는 같은 기준(예보가 있으면 기간 안 출금)`() {
        val nov8 = LocalDate.of(2026, 11, 8)
        // 카드 예정 지출(사용일 11/8)은 가계부 줄로 보이지만 출금은 기간 밖 청구일 — 예보에는 없다
        val cardPlan = CashflowEntry(9, false, "구매 계획", 120_000, nov8, nov8, CashflowRepeat.NONE, null, null, null, 11)
        val rows = cashflowRows(listOf(cardPlan), testForecast())
        assertEquals(0L to 0, com.yamu.app.feature.forecast.cashflowOutflow(testForecast(), rows))   // 전에는 '0원 · 출금 1건'
        assertEquals("카드 결제", rows.single().cardNote)
        // 예보가 없으면 가계부 줄 기준
        assertEquals(120_000L to 1, com.yamu.app.feature.forecast.cashflowOutflow(null, rows))
        // 기간 안 출금은 예보 기준으로 세고, 'AI 생활비 반영' 선 전용 추정은 빼고 센다
        val withBill = testForecast(
            days = listOf(
                day(
                    nov8,
                    ForecastEvent(nov8, FlowType.CARD_BILLING, "신한카드 청구", -150_000, FlowConfidence.EXPECTED),
                    ForecastEvent(nov8, FlowType.CARD_SPENDING_ESTIMATE, "신한카드 · 추정", -36_000, FlowConfidence.CONSERVATIVE),
                ),
            ),
        )
        assertEquals(150_000L to 1, com.yamu.app.feature.forecast.cashflowOutflow(withBill, rows))
    }

    @Test fun `계좌를 일부만 고르면 예정 목록도 그 계좌 · 그 계좌 카드 줄만(!176 리뷰)`() {
        val nov8 = LocalDate.of(2026, 11, 8)
        val accounts = listOf(
            ForecastAccountOption(1, "월급 통장", null, "", null, true, cardIds = setOf(16)),
            ForecastAccountOption(2, "생활비 통장", null, "", null, false, cardIds = setOf(11)),
        )
        val scope = ForecastSelection(accountIds = setOf(2)).scopeFrom(accounts)
        assertEquals(AccountScope(setOf(2), setOf(11)), scope)
        fun entry(id: Long, account: Long?, card: Long?) = CashflowEntry(id, false, "줄$id", 1_000, nov8, nov8, CashflowRepeat.NONE, null, null, account, card)
        val entries = listOf(entry(1, 1, null), entry(2, 2, null), entry(3, null, 16), entry(4, null, 11), entry(5, null, null))
        val rows = cashflowRows(entries, testForecast(accountIds = listOf(2)).copy(scope = scope))
        assertEquals(listOf("줄2", "줄4", "줄5"), rows.map { it.title })   // 계좌도 카드도 없는 줄은 어느 계좌인지 몰라 둔다
        // 전체를 고르면(또는 고른 계좌가 모두 남아 있으면) 거르지 않는다
        assertNull(ForecastSelection().scopeFrom(accounts))
        assertNull(ForecastSelection(accountIds = setOf(1, 2)).scopeFrom(accounts))
        assertEquals(5, cashflowRows(entries, testForecast()).size)
    }

    @Test fun `예정 목록 — 회차를 받은 뒤에도 확인 전 고정 지출 후보가 남고 머리 건수와 맞는다(11월 10일 QA 넷플릭스)`() {
        val nov5 = LocalDate.of(2026, 11, 5)
        val nov12 = LocalDate.of(2026, 11, 12)
        val netflix = ForecastEvent(nov5, FlowType.SCHEDULED_OUTFLOW, "넷플릭스", -13_500, FlowConfidence.EXPECTED)
        val snack = ForecastEvent(nov12, FlowType.SCHEDULED_OUTFLOW, "간식", -1_000, FlowConfidence.CONFIRMED)
        val f = testForecast(days = listOf(day(nov5, netflix), day(nov12, snack)))
        // 가계부 목록은 status를 빼고 불러 확인한 항목만 온다(BE 기본값 CONFIRMED) — 후보 넷플릭스는 없다
        val confirmed = CashflowEntry(7, false, "간식", 1_000, nov12, nov12, CashflowRepeat.NONE, null, null, 1, null)
        val rows = cashflowRows(listOf(confirmed), f)
        assertEquals(listOf("넷플릭스", "간식"), rows.map { it.title })
        assertEquals(listOf(true, false), rows.map { it.candidate })
        assertNull(rows.first().entry)   // 후보는 고치는 화면으로 가지 않는다
        // 머리 '나갈 돈 · 출금 N건'에 든 줄이 목록에 모두 있다
        val (out, count) = com.yamu.app.feature.forecast.cashflowOutflow(f, rows)
        assertEquals(14_500L to 2, out to count)
        assertEquals(out, -rows.sumOf { it.amount })
        // 회차를 받기 전(예보 줄만)에도 같은 줄 · 같은 태그
        assertEquals(rows.map { it.title to it.candidate }, f.upcoming.filter { !it.isEstimate }.map { it.toCashflowRow() }.map { it.title to it.candidate })
    }

    @Test fun `예정 목록 — 확인한 고정 지출은 가계부 줄 하나로만, 진행 중 청구는 후보로 보지 않는다`() {
        val nov5 = LocalDate.of(2026, 11, 5)
        val nov8 = LocalDate.of(2026, 11, 8)
        // 확인하면 서버가 예보에도 CONFIRMED로 넣는다
        val f = testForecast(
            days = listOf(
                day(nov5, ForecastEvent(nov5, FlowType.SCHEDULED_OUTFLOW, "넷플릭스", -13_500, FlowConfidence.CONFIRMED)),
                day(nov8, ForecastEvent(nov8, FlowType.CARD_BILLING, "신한카드 청구", -150_000, FlowConfidence.EXPECTED)),
            ),
        )
        val netflix = CashflowEntry(31, false, "넷플릭스", 13_500, nov5, nov5, CashflowRepeat.MONTHLY, 5, null, null, 16)
        val rows = cashflowRows(listOf(netflix), f)
        assertEquals(listOf("넷플릭스", "신한카드 청구"), rows.map { it.title })
        assertEquals(listOf(false, false), rows.map { it.candidate })
        assertEquals(netflix, rows.first().entry)
    }

    @Test fun `계좌 · 카드 없는 줄과 수입 줄에는 카드 결제 표시가 없다`() {
        val nov8 = LocalDate.of(2026, 11, 8)
        val byAccount = CashflowEntry(1, false, "월세", 500_000, nov8, nov8, CashflowRepeat.NONE, null, null, 3, null)
        val income = CashflowEntry(2, true, "환급", 10_000, nov8, nov8, CashflowRepeat.NONE, null, null, null, 11)
        assertEquals(listOf(null, null), cashflowRows(listOf(byAccount, income), null).map { it.cardNote })
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
}
