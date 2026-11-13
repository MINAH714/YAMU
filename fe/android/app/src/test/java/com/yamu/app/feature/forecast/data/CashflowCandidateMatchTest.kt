package com.yamu.app.feature.forecast.data

import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowItemDto
import com.yamu.app.feature.link.data.DetectionDto
import com.yamu.app.feature.link.data.RecurrenceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** F-02 확인 전 후보 — 예보 줄과 후보 규칙 맞추기 · 줄 끝 휴지통을 둘 줄(Task 8.2.73) */
class CashflowCandidateMatchTest {
    private val nov25 = LocalDate.of(2026, 11, 25)
    private val netflixEvent = ForecastEvent(nov25, FlowType.SCHEDULED_OUTFLOW, "넷플릭스", -13_500, FlowConfidence.EXPECTED)
    private val netflix = CashflowCandidate(501, false, "넷플릭스", 13_500, 15, "최근 6개월 중 3달 · 마지막 10월 15일")

    @Test fun `이름 · 금액이 같은 후보가 하나면 날짜가 달라도 맞춘다 — 신용카드 후보는 예보 날짜가 청구 출금일이다`() {
        assertSame(netflix, matchCandidate(netflixEvent, listOf(netflix, netflix.copy(id = 502, title = "유튜브"))))
    }

    @Test fun `수입 · 지출, 이름, 금액 중 하나라도 다르면 맞추지 않는다`() {
        assertNull(matchCandidate(netflixEvent, listOf(netflix.copy(amount = 17_000))))
        assertNull(matchCandidate(netflixEvent, listOf(netflix.copy(isIncome = true))))
        assertNull(matchCandidate(netflixEvent, listOf(netflix.copy(title = "넷플릭스 프리미엄"))))
        // 앞뒤 빈칸은 같은 이름으로 본다
        assertSame(netflix, matchCandidate(netflixEvent.copy(title = "넷플릭스 "), listOf(netflix)))
    }

    @Test fun `같은 이름 · 금액이 둘이면 매월 기준일이 그날인 것 하나만, 그래도 못 가르면 맞추지 않는다`() {
        val on25 = netflix.copy(id = 503, dayOfMonth = 25)
        assertSame(on25, matchCandidate(netflixEvent, listOf(netflix, on25)))
        assertNull(matchCandidate(netflixEvent, listOf(netflix, netflix.copy(id = 504))))
        // 31은 말일 — 11월은 30일
        val monthEnd = netflix.copy(id = 505, dayOfMonth = 31)
        val nov30 = netflixEvent.copy(date = LocalDate.of(2026, 11, 30))
        assertSame(monthEnd, matchCandidate(nov30, listOf(netflix, monthEnd)))
    }

    @Test fun `같은 날짜의 줄 둘이 한 후보에 맞춰지면 둘 다 읽기 전용 — 신용카드 후보의 출금일이 다른 후보의 기준일과 겹친 경우`() {
        // 넷플릭스를 A(신용카드, 5일 사용 · 14일 출금)와 B(체크카드, 14일)로 결제 — 예보에는 14일에 같은 이름 · 금액 줄이 둘
        val nov14 = LocalDate.of(2026, 11, 14)
        val credit = netflix.copy(id = 601, dayOfMonth = 5)
        val check = netflix.copy(id = 602, dayOfMonth = 14)
        val f = testForecast(days = listOf(day(nov14, netflixEvent.copy(date = nov14), netflixEvent.copy(date = nov14))))
        assertSame(check, matchCandidate(netflixEvent.copy(date = nov14), listOf(credit, check)))   // 줄 하나만 보면 B로 간다
        assertEquals(listOf(null, null), cashflowRows(emptyList(), f, listOf(credit, check)).map { it.pending })
        assertEquals(listOf(null, null), forecastOnlyRows(f, listOf(credit, check)).map { it.pending })
        // 후보 목록에 한쪽만 있어도 같다
        assertEquals(listOf(null, null), cashflowRows(emptyList(), f, listOf(credit)).map { it.pending })
        // 같은 후보라도 날짜가 다른 줄(다음 달 회차)은 그대로 맞춘다
        val dec14 = LocalDate.of(2026, 12, 14)
        val twoMonths = testForecast(days = listOf(day(nov14, netflixEvent.copy(date = nov14)), day(dec14, netflixEvent.copy(date = dec14))))
        assertEquals(listOf(check, check), cashflowRows(emptyList(), twoMonths, listOf(check)).map { it.pending })
    }

    @Test fun `확인한 항목 · 카드 청구 줄은 후보와 맞추지 않는다`() {
        assertNull(matchCandidate(netflixEvent.copy(confidence = FlowConfidence.CONFIRMED), listOf(netflix)))
        assertNull(matchCandidate(netflixEvent.copy(type = FlowType.CARD_BILLING), listOf(netflix)))
    }

    @Test fun `F-02 줄 — 맞춘 후보 줄에 후보가 붙고, 후보 목록을 못 받았으면 전처럼 읽기 전용`() {
        val f = testForecast(days = listOf(day(nov25, netflixEvent)))
        val rows = cashflowRows(emptyList(), f, listOf(netflix))
        assertSame(netflix, rows.single().pending)
        assertNull(cashflowRows(emptyList(), f, null).single().pending)
        // 회차를 못 받아 예보 줄만 그릴 때도 같다
        assertSame(netflix, forecastOnlyRows(f, listOf(netflix)).single().pending)
        assertNull(forecastOnlyRows(f).single().pending)
    }

    @Test fun `휴지통 — 오늘부터의 가계부 줄과 맞춘 후보 줄만, 지난 회차 · 카드 청구 · 못 맞춘 후보는 없음`() {
        val today = LocalDate.of(2026, 11, 12)
        fun entry(date: LocalDate) = CashflowEntry(7, false, "월세", 500_000, date, date, CashflowRepeat.MONTHLY, 31, null, 1, null)
        fun row(entry: CashflowEntry?, pending: CashflowCandidate? = null, candidate: Boolean = false) =
            CashflowRow(entry?.date ?: nov25, "월세", -500_000, null, entry, candidate, pending)
        assertTrue(row(entry(today)).deletable(today))
        assertTrue(row(entry(nov25)).deletable(today))
        assertFalse(row(entry(today.minusDays(1))).deletable(today))
        assertTrue(row(null, netflix, candidate = true).deletable(today))
        assertFalse(row(null, candidate = true).deletable(today))   // 못 맞춘 후보
        assertFalse(row(null).deletable(today))                     // 카드 청구
        // 결제와 연결된 회차 — 이번 회차만 · 앞으로 전부 모두 서버가 409로 막는다
        assertFalse(row(entry(nov25).copy(matched = true)).deletable(today))
    }

    @Test fun `회차 목록의 결제 연결 상태 — 승인 · 취소된 연결 모두 연결로, 없으면 연결 없음`() {
        fun dto(status: String?) = CashflowEntryDto(7, "EXPENSE", "넷플릭스", 13_500, "2026-11-15", "2026-11-15", paymentMatchStatus = status)
        assertTrue(dto("ACTIVE").toEntry()!!.matched)
        assertTrue(dto("REVIEW_REQUIRED").toEntry()!!.matched)
        assertFalse(dto(null).toEntry()!!.matched)
    }

    @Test fun `후보 목록 — 카드대금은 빼고 기준일 · 근거를 B-06과 같게 읽는다`() {
        val items = listOf(
            CashflowItemDto(601, "EXPENSE", "넷플릭스", 13_500, "2026-11-15", RecurrenceDto("MONTHLY", 15), status = "CANDIDATE", detection = DetectionDto("넷플릭스", 3, "2026-10-15T03:00:00Z")),
            CashflowItemDto(602, "EXPENSE", "신한카드 대금", 150_000, "2026-11-20", kind = "CARD_BILL"),
            CashflowItemDto(603, "INCOME", "부수입", 50_000, "2026-11-07"),
        )
        val candidates = items.toFixedCandidates()
        assertEquals(listOf(601L, 603L), candidates.map { it.id })
        assertEquals(CashflowCandidate(601, false, "넷플릭스", 13_500, 15, "최근 6개월 중 3달 · 마지막 10월 15일"), candidates[0])
        assertEquals(7, candidates[1].dayOfMonth)   // 반복 규칙이 없으면 첫 회차의 날
        assertTrue(candidates[1].isIncome)
    }
}
