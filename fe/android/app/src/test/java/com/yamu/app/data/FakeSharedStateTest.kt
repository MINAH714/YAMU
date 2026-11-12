package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.forecast.data.CashflowCreateRequest
import com.yamu.app.feature.forecast.data.RecurrenceRequest
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * debug 가짜 저장소들이 같은 가짜 사용자(카드 · 계좌 · 월급 · 잔액)를 읽는지(S15P31E103-454, 11/12 QA P1-03).
 * QA에서 본 어긋남: 카드 1장 등록 뒤 내 카드 · 실적 6장, 잔고예보 계좌가 연결 계좌와 다름, 월급 25일인데 D-25, 홈과 잔고예보 금액이 다름
 */
class FakeSharedStateTest {
    private val bank = FakeBank(FakeUser(storage = null))
    private val perf = FakePerformanceRepository(FakePerformanceStore(bank), bank)
    private val forecast = FakeForecastRepository(bank)
    private val cashflow = FakeCashflowRepository(bank)
    private val payments = FakePaymentRepository(FakePerformanceStore(bank), PaymentEdits(), bank)
    private val today = LocalDate.now(KST)

    @After fun tearDown() {
        DevSession.homeScenario = HomeScenario.NORMAL
    }

    private suspend fun register(last4: String) {
        val lookup = bank.lookup("941000000000$last4", "12", "30") as ApiResult.Success
        assertTrue(bank.register(lookup.data.verificationToken) is ApiResult.Success)
    }

    /** B-06 월급 시트가 보내는 것과 같은 요청 */
    private suspend fun salary(day: Int, amount: Long, accountId: Long) {
        val body = CashflowCreateRequest("INCOME", "월급", amount, today.plusDays(1).toString(), RecurrenceRequest("MONTHLY", dayOfMonth = day), accountId = accountId)
        assertTrue(cashflow.createIncome(body, "key-$day") is ApiResult.Success)
    }

    private fun nextDay(day: Int): LocalDate {
        val ym = YearMonth.from(today)
        val thisMonth = ym.atDay(minOf(day, ym.lengthOfMonth()))
        return if (thisMonth.isAfter(today)) thisMonth else ym.plusMonths(1).let { it.atDay(minOf(day, it.lengthOfMonth())) }
    }

    @Test fun `새 사용자가 카드 1장을 등록하면 내 카드 · 실적 · 혜택 한도도 그 1장`() = runTest {
        bank.reset()
        register("1021")
        val overview = (perf.overview() as ApiResult.Success).data
        assertEquals(listOf(11L), overview.all.map { it.userCardId })
        assertEquals(listOf(11L), (perf.limits() as ApiResult.Success).data.cards.map { it.userCardId })
        assertEquals(0L, (perf.limits() as ApiResult.Success).data.received)
    }

    @Test fun `목록에 없는 새 카드(트래블로그)도 실적관리에 0원으로 나온다`() = runTest {
        bank.reset()
        register("5527")
        val card = (perf.overview() as ApiResult.Success).data.all.single()
        assertEquals(17L, card.userCardId)
        assertEquals(0L, card.recognized)
    }

    @Test fun `시드 사용자는 그대로 6장`() = runTest {
        assertEquals(6, (perf.overview() as ApiResult.Success).data.all.size)
    }

    @Test fun `잔고예보 계좌는 연결한 계좌와 같은 번호 · 끝자리 · 잔액`() = runTest {
        bank.signedUp(listOf(FakeSignupRepository.CANDIDATE_KB, FakeSignupRepository.CANDIDATE_SHINHAN), FakeSignupRepository.CANDIDATE_KB)
        val linked = (bank.linkedAccountList() as ApiResult.Success).data
        val options = (forecast.accounts() as ApiResult.Success).data
        assertEquals(linked.map { Triple(it.accountId, it.last4, it.balance) }, options.map { Triple(it.accountId, it.last4, it.balance) })
        assertEquals(linked.single { it.isPrimary }.accountId, options.single { it.isPrimary }.accountId)
    }

    @Test fun `월급을 넣기 전 새 사용자 잔고예보는 30일이고 홈에는 다음 월급이 없다`() = runTest {
        bank.reset()
        val f = (forecast.forecast() as ApiResult.Success).data
        assertFalse(f.isNextIncome)
        assertEquals(today.plusDays(30), f.to)
        assertNull(forecast.homeForecast()!!.nextIncome)
        assertTrue(f.excluded.isEmpty())        // 시드 사용자의 카드 · 관리비 줄이 섞이지 않는다
        assertTrue(f.beyondHorizon.isEmpty())
    }

    @Test fun `월급 25일을 넣으면 홈과 잔고예보가 같은 다음 월급날`() = runTest {
        bank.reset()
        salary(25, 2_500_000, accountId = 1)
        val payday = nextDay(25)
        val f = (forecast.forecast() as ApiResult.Success).data
        assertTrue(f.isNextIncome)
        assertEquals(payday, f.incomeDate)
        assertEquals(payday to 2_500_000L, forecast.homeForecast()!!.nextIncome)
        assertTrue(DevSession.fakeUser.any { it.startsWith("월급: 매월 25일") })
    }

    @Test fun `계좌가 하나면 홈 예상 잔액이 잔고예보 첫 화면과 같다`() = runTest {
        bank.reset()
        salary(25, 2_500_000, accountId = 1)
        val f = (forecast.forecast() as ApiResult.Success).data
        val home = forecast.homeForecast()!!
        assertEquals(f.currentBalance, home.currentBalance)
        assertEquals(f.minBalance, home.minBalance)
        assertEquals(f.endBalance, home.endBalance)
    }

    @Test fun `계좌가 둘이면 홈은 기준 계좌만 — 실서버 HomeService와 같다`() = runTest {
        val all = (forecast.forecast() as ApiResult.Success).data
        val home = forecast.homeForecast()!!
        assertEquals(680_000L, all.currentBalance)
        assertEquals(412_000L, home.currentBalance)
    }

    @Test fun `B-06 월급 계좌 목록은 연결한 계좌, 카드 대금은 등록한 카드만`() = runTest {
        bank.reset()
        val draft = (cashflow.draft() as ApiResult.Success).data
        assertEquals(listOf("입출금 ··1234"), draft.incomeAccounts.map { it.label })
        assertTrue(draft.expenses.isEmpty())
        assertTrue(draft.bills.isEmpty())
    }

    @Test fun `새 사용자는 지난 결제 기록이 없다 — 결제 내역 · 소비분석 · 알림이 이 값을 본다`() {
        assertTrue(payments.hasHistory)
        bank.reset()
        assertFalse(payments.hasHistory)
    }

    @Test fun `디버그 메뉴 요약은 지금 가짜 사용자`() = runTest {
        bank.reset()
        register("1021")
        assertTrue(DevSession.fakeUser.first().startsWith("새 사용자"))
        assertTrue(DevSession.fakeUser.any { it == "카드 1장: KB국민 청춘대로 톡톡카드" })
        assertTrue(DevSession.fakeUser.any { it.startsWith("계좌 1개: KB국민은행 ··1234 412,000원(기준)") })
    }
}
