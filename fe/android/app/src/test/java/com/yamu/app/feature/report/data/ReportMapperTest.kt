package com.yamu.app.feature.report.data

import com.yamu.app.core.network.YamuJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 소비분석 — 숫자는 서버 값, 앱은 기간 이름 · 문장 · 묶음만(13 문서 9·10-1) */
class ReportMapperTest {
    private val today = LocalDate.parse("2026-10-27")

    @Test fun `기간 이름 — 이번 달은 오늘까지, 서버가 끝 날을 주면 그 날, 끝난 달은 달만`() {
        assertEquals("10월 1~27일", periodLabel("2026-10", partial = true, today = today))
        assertEquals("10월 1~16일", periodLabel("2026-10", partial = true, periodTo = "2026-10-16", today = today))
        assertEquals("9월 1~30일", periodLabel("2026-09", partial = true, today = today))
        assertEquals("9월", periodLabel("2026-09", partial = false, today = today))
        assertEquals("10월 1~27일", periodLabel(null, partial = true, today = today))
    }

    @Test fun `월간 소비 — 생활 소비가 있으면 그 값과 비교 줄, 없으면 카드 결제 합 · 비교 없음`() {
        val withLiving = YamuJson.decodeFromString(
            MonthlyReportDto.serializer(),
            """{"yearMonth":"2026-10","isPartialMonth":true,"totalSpent":2164200,"livingSpent":842000,"prevSamePeriodSpent":794000,"periodTo":"2026-10-16",
               "byCategory":[{"categoryName":"카페","amount":96000,"ratio":11.4},{"categoryName":"식비","amount":248000,"ratio":29.45},
                             {"categoryName":"쇼핑","amount":172000,"ratio":20.43},{"categoryName":"주유·마트","amount":138000,"ratio":16.39}],
               "benefitSummary":{"expected":32400,"actual":30000},"newField":true}""",
        ).toSpend(paymentCount = 67)
        assertTrue(withLiving.isLiving)
        assertEquals(842_000L, withLiving.spent)
        assertEquals(48_000L, withLiving.diffFromPrev)
        assertEquals("10월 1~16일", withLiving.periodLabel)
        assertEquals(10, withLiving.month)
        assertEquals(listOf("식비", "쇼핑", "주유·마트"), withLiving.categories.map { it.name })   // 금액 순 3곳
        assertEquals(listOf(29, 20, 16), withLiving.categories.map { it.ratio })
        assertEquals(32_400L, withLiving.benefit)
        assertEquals(67, withLiving.paymentCount)

        val cardOnly = YamuJson.decodeFromString(MonthlyReportDto.serializer(), """{"yearMonth":"2026-10","totalSpent":2164200}""").toSpend(null)
        assertFalse(cardOnly.isLiving)
        assertEquals(2_164_200L, cardOnly.spent)
        assertNull(cardOnly.diffFromPrev)
        assertNull(cardOnly.paymentCount)
    }

    @Test fun `비교 문장`() {
        assertEquals("지난달 같은 기간보다 48,000원 더 썼어요", spendDiffSentence(48_000))
        assertEquals("지난달 같은 기간보다 12,500원 덜 썼어요", spendDiffSentence(-12_500))
        assertEquals("지난달 같은 기간과 같아요", spendDiffSentence(0))
    }

    @Test fun `끝난 달 — 기간 없이 '10월', 앞 달이 통째로 들어가면 '9월보다'`() {
        val past = YamuJson.decodeFromString(
            MonthlyReportDto.serializer(),
            """{"yearMonth":"2026-10","isPartialMonth":false,"totalSpent":900000,"livingSpent":800000,"prevSamePeriodSpent":850000,"periodTo":"2026-10-31"}""",
        ).toSpend(paymentCount = 40)
        assertEquals("10월", past.periodLabel)
        assertEquals("9월", past.prevPeriod)
        assertEquals("9월보다 50,000원 덜 썼어요", spendDiffSentence(past.diffFromPrev!!, past.prevPeriod))
        val january = YamuJson.decodeFromString(MonthlyReportDto.serializer(), """{"yearMonth":"2026-01","isPartialMonth":false}""").toSpend(null)
        assertEquals("12월", january.prevPeriod)   // 1월을 보면 앞 달은 작년 12월(31일 · 31일)
        assertEquals("12월과 같아요", spendDiffSentence(0, january.prevPeriod))
        val now = YamuJson.decodeFromString(MonthlyReportDto.serializer(), """{"yearMonth":"2026-10","isPartialMonth":true}""").toSpend(null)
        assertNull(now.prevPeriod)   // 이번 달은 '지난달 같은 기간'
    }

    @Test fun `끝난 달 — 앞 달이 더 길면 서버가 센 날까지만 '8월 1~30일보다'(!288 리뷰 1)`() {
        // 서버는 앞 달 1일 ~ min(그달 끝 날짜, 앞 달 길이)일과 비교한다 — 9월(30일)을 열면 8/31은 빠진다
        val september = YamuJson.decodeFromString(
            MonthlyReportDto.serializer(),
            """{"yearMonth":"2026-09","isPartialMonth":false,"livingSpent":700000,"prevSamePeriodSpent":712000,"periodTo":"2026-09-30"}""",
        ).toSpend(null)
        assertEquals("8월 1~30일", september.prevPeriod)
        assertEquals("8월 1~30일보다 12,000원 덜 썼어요", spendDiffSentence(september.diffFromPrev!!, september.prevPeriod))
        assertEquals("8월 1~30일과 같아요", spendDiffSentence(0, september.prevPeriod))
        // 서버가 끝 날을 안 줘도 그달 길이로 같게
        assertEquals("1월 1~28일", prevPeriodLabel(YearMonth.of(2026, 2)))
        assertEquals("1월 1~29일", prevPeriodLabel(YearMonth.of(2028, 2)))   // 윤년 2월
        assertEquals("3월 1~30일", prevPeriodLabel(YearMonth.of(2026, 4)))
        // 앞 달이 같거나 짧으면 통째로 — 3월을 열면 2월 전체
        assertEquals("2월", prevPeriodLabel(YearMonth.of(2026, 3), periodTo = "2026-03-31"))
        assertEquals("7월", prevPeriodLabel(YearMonth.of(2026, 8)))
    }

    @Test fun `혜택 리포트 기간 — 이번 달 PARTIAL은 오늘까지, 지난 달은 확정 전(PARTIAL)이어도 '9월'`() {
        fun label(json: String) = YamuJson.decodeFromString(BenefitReportDto.serializer(), json).toReport(today).periodLabel
        assertEquals("10월 1~27일", label("""{"yearMonth":"2026-10","status":"PARTIAL"}"""))
        assertEquals("9월", label("""{"yearMonth":"2026-09","status":"PARTIAL"}"""))
        assertEquals("9월", label("""{"yearMonth":"2026-09","status":"CONFIRMED"}"""))
    }

    @Test fun `혜택 리포트 — 놓친 혜택은 예상보다 적게 받은 줄만, 합은 서버 값이 먼저`() {
        val dto = YamuJson.decodeFromString(
            BenefitReportDto.serializer(),
            """{"yearMonth":"2026-10","status":"PARTIAL",
               "summary":{"expectedBenefit":32400,"confirmedBenefit":30000,"yamuPickExtra":5200},
               "differences":[
                 {"paymentId":1,"approvedAt":"2026-10-11T20:15:00+09:00","merchantName":"동네마트 역삼점","expectedAmount":3680,"actualAmount":1600,"reason":"AUTO_SELECT_OFF"},
                 {"paymentId":2,"approvedAt":"2026-10-08T12:00:00+09:00","merchantName":"편의점 테헤란점","expectedAmount":900,"actualAmount":0,"reason":"USER_SELECTED"},
                 {"paymentId":3,"merchantName":"더 받은 곳","expectedAmount":100,"actualAmount":300}
               ],
               "byCategory":[{"categoryName":"카페","amount":5280},{"categoryName":"생활·렌탈","amount":10000}]}""",
        )
        val r = dto.toReport()
        assertEquals(32_400L, r.received)
        assertEquals(5_200L, r.yamuExtra)
        assertEquals(listOf("동네마트 역삼점", "편의점 테헤란점"), r.missed.map { it.merchant })
        assertEquals(listOf(2_080L, 900L), r.missed.map { it.amount })
        assertEquals(listOf("자동 선택 꺼짐", "직접 고른 카드"), r.missed.map { it.reason })
        assertEquals(LocalDate.parse("2026-10-11"), r.missed.first().date)
        assertEquals(2_980L, r.missedTotal)                      // 서버 합이 없으면 줄 합(표시용)
        assertEquals(listOf("생활·렌탈", "카페"), r.byPlace.map { it.first })

        val withServerTotal = dto.copy(summary = dto.summary.copy(missedBenefit = 3_100)).toReport()
        assertEquals(3_100L, withServerTotal.missedTotal)
        assertNull(dto.copy(summary = dto.summary.copy(yamuPickExtra = 0)).toReport().yamuExtra)
    }

    @Test fun `놓친 이유 — 모르는 코드는 혜택 안 됨`() {
        assertEquals("한도 소진", missedReason("LIMIT_EXHAUSTED"))
        assertEquals("실적 미달", missedReason("tier_not_met"))
        assertEquals("혜택 안 됨", missedReason("SOMETHING_NEW"))
        assertEquals("혜택 안 됨", missedReason(null))
    }

    @Test fun `결제 내역 줄 — KST 날짜 · 시각, 혜택 종류, 실적 제외 · 취소, 카드사가 없으면 별명 첫 단어`() {
        val page = YamuJson.decodeFromString(
            PaymentsPageDto.serializer(),
            """{"summary":{"count":67,"amount":2164200},"page":1,"hasNext":true,"items":[
                 {"paymentId":938,"status":"APPROVED","approvedAt":"2026-10-16T00:38:00Z","merchant":{"name":"야무카페 역삼점"},"amount":15000,
                  "card":{"userCardId":11,"issuer":{"name":"신한카드","color":"#0046FF"}},"benefitAmount":1500,"benefitType":"INSTANT_DISCOUNT"},
                 {"paymentId":935,"approvedAt":"2026-10-15T18:50:00+09:00","merchant":{"name":"역삼 생활용품점"},"amount":39000,
                  "card":{"nickname":"KB국민 적립"},"benefitAmount":390,"benefitType":"POINT"},
                 {"paymentId":930,"approvedAt":"2026-10-14T12:10:00+09:00","merchant":{"name":"모바일 문화상품권"},"amount":50000,"benefitAmount":0,"performanceRecognized":false},
                 {"paymentId":900,"status":"CANCELED","amount":8000}
               ]}""",
        ).toPage()
        assertEquals(67, page.count)
        assertEquals(2_164_200L, page.amount)
        assertTrue(page.hasNext)
        val (cafe, store, voucher, canceled) = page.items
        assertEquals(LocalDate.parse("2026-10-16"), cafe.date)
        assertEquals("09:38", cafe.time)
        assertEquals("신한", cafe.brand?.shortName)
        assertEquals("할인", cafe.benefitLabel)
        assertEquals(1_500L, cafe.benefit)
        assertEquals("KB국민", store.brand?.shortName)
        assertEquals("적립", store.benefitLabel)
        assertTrue(voucher.excluded)
        assertNull(voucher.benefit)                              // 0원은 표시하지 않음
        assertNull(voucher.brand)
        assertTrue(canceled.canceled)
        assertEquals("결제", canceled.merchant)
        assertNull(canceled.date)
    }

    @Test fun `날짜 머리 — 오늘만 표시`() {
        assertEquals("10월 27일 (화, 오늘)", paymentDayLabel(today, today))
        assertEquals("10월 26일 (월)", paymentDayLabel(today.minusDays(1), today))
        assertEquals("날짜 없음", paymentDayLabel(null, today))
    }

    @Test fun `쪽 합치기 — 같은 결제는 한 번, 새 결제가 맨 위`() {
        fun p(id: Long) = PaymentItem(id, today, "12:00", "가맹점 $id", null, 1_000, null, "혜택", excluded = false, canceled = false)
        val loaded = listOf(p(5), p(4), p(3), p(2))
        val refreshedFirstPage = listOf(p(6), p(5), p(4))
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), mergePayments(refreshedFirstPage, loaded).map { it.paymentId })
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), mergePayments(loaded, listOf(p(2), p(1))).map { it.paymentId })
    }
}
