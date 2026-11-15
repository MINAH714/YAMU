package com.yamu.app.feature.home.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** v4 홈 위쪽(S15P31E103-480) — 확정 · 확정 전 나누기(HOME-04), 인사 · 결제 칸 문구(HOME-08 · 09), 동전 수 */
class HomeBenefitTest {

    private val today = LocalDate.of(2026, 11, 13)

    private fun dto(expected: Long?, actual: Long?, spent: Long = 100_000, yearMonth: String? = "2026-11", periodTo: String? = "2026-11-13") =
        HomeMonthlyDto(yearMonth, periodTo, spent, HomeBenefitSummaryDto(expected, actual))

    // ── 확정 · 확정 전 ──

    @Test fun `큰 숫자는 확정만이고 확정 전은 예상에서 확정을 뺀 금액`() {
        val b = dto(expected = 25_500, actual = 12_400).toBenefit(today)!!
        assertEquals(12_400, b.confirmed)
        assertEquals(13_100, b.pending)
    }

    @Test fun `확정이 예상보다 크게 와도 확정 전은 0원 아래로 내려가지 않는다`() {
        val b = dto(expected = 5_000, actual = 6_000).toBenefit(today)!!
        assertEquals(6_000, b.confirmed)
        assertEquals(0, b.pending)
    }

    @Test fun `확정 합계가 없으면 확정 0원 · 예상 전부 확정 전`() {
        val b = dto(expected = 7_000, actual = null).toBenefit(today)!!
        assertEquals(0, b.confirmed)
        assertEquals(7_000, b.pending)
    }

    @Test fun `혜택 합계 칸이 없으면 0원으로 지어내지 않는다`() {
        assertNull(HomeMonthlyDto("2026-11", totalSpent = 100_000, benefitSummary = null).toBenefit(today))
    }

    @Test fun `기간은 1일부터 서버 끝 날까지 · 끝 날이 없으면 이번 달은 오늘 · 지난 달은 말일`() {
        val now = dto(1, 1, periodTo = "2026-11-12").toBenefit(today)!!
        assertEquals(LocalDate.of(2026, 11, 1), now.from)
        assertEquals(LocalDate.of(2026, 11, 12), now.to)
        assertEquals(today, dto(1, 1, periodTo = null).toBenefit(today)!!.to)
        val past = dto(1, 1, yearMonth = "2026-10", periodTo = null).toBenefit(today)!!
        assertEquals(YearMonth.of(2026, 10), past.month)
        assertEquals(LocalDate.of(2026, 10, 31), past.to)
    }

    @Test fun `0원 안내는 결제가 없을 때와 혜택이 없을 때를 나눈다`() {
        assertEquals("이번 달 YAMU 결제가 아직 없어요", dto(0, 0, spent = 0).toBenefit(today)!!.emptyNote())
        assertEquals("이번 달 결제로 생긴 혜택이 아직 없어요", dto(0, 0, spent = 15_000).toBenefit(today)!!.emptyNote())
        assertNull(dto(3_000, 0).toBenefit(today)!!.emptyNote())   // 확정 전만 있어도 칩이 말한다
        assertNull(dto(3_000, 3_000).toBenefit(today)!!.emptyNote())
    }

    // ── 인사 ──

    @Test fun `인사는 혜택 달과 기간만 — 집계 범위를 설명하는 문장은 두지 않는다`() {
        val g = homeGreeting("김야무", needsLinking = false, needsCard = false, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 1), today)
        assertEquals("김야무님, 11월 혜택을 모아 봤어요", g.title)
        assertEquals("11/1~11/13", g.sub)
    }

    @Test fun `연결 전 · 카드 없음은 반가워요와 할 일`() {
        val month = YearMonth.of(2026, 11)
        val linking = homeGreeting("김야무", needsLinking = true, needsCard = false, month, month.atDay(1), today)
        assertEquals("김야무님, 반가워요", linking.title)
        assertEquals("계좌와 카드를 연결하면 혜택과 잔고를 모아 보여 드려요", linking.sub)
        val noCard = homeGreeting(" ", needsLinking = false, needsCard = true, month, month.atDay(1), today)
        assertEquals("야무님, 반가워요", noCard.title)   // 이름이 비면 '야무님'
        assertEquals("카드를 등록하면 혜택을 모아 보여 드려요", noCard.sub)
    }

    @Test fun `1일 하루면 기간은 날짜 하나`() {
        assertEquals("11/1", periodText(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)))
        assertEquals("10/1~10/31", periodText(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
    }

    // ── 결제 설정 ──

    @Test fun `서버 결제 설정 칸이 없으면 null`() {
        assertNull(HomeMeDto(paymentSettings = null).toPaySettings())
        assertEquals(PaySettings(false, 3), HomeMeDto(HomePaymentSettingsDto(autoSelect = false, defaultUserCardId = 3)).toPaySettings())
    }

    // ── 동전 ──

    @Test fun `동전은 약 2,500원에 한 개 · 올림`() {
        assertEquals(5 to 6, benefitCoins(12_400, 13_100))
        assertEquals(0 to 0, benefitCoins(0, 0))
        assertEquals(1 to 0, benefitCoins(1, 0))
        assertEquals(0 to 1, benefitCoins(0, 2_500))
        assertEquals(0 to 0, benefitCoins(-500, -1))
    }

    @Test fun `많으면 둘 합이 최대에 맞춰 줄고 0원이 아닌 쪽은 한 개 이상 남는다`() {
        val (s, g) = benefitCoins(1_000_000, 1)
        assertEquals(MAX_BENEFIT_COINS, s + g)
        assertEquals(1, g)
        val (s2, g2) = benefitCoins(1, 1_000_000)
        assertEquals(1, s2)
        assertEquals(MAX_BENEFIT_COINS - 1, g2)
        val (s3, g3) = benefitCoins(60_000, 60_000)
        assertEquals(MAX_BENEFIT_COINS / 2, s3)
        assertEquals(MAX_BENEFIT_COINS / 2, g3)
    }
}
