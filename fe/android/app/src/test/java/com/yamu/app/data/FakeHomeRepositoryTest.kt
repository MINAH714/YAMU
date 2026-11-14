package com.yamu.app.data

import android.content.Context
import android.content.ContextWrapper
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.home.data.HomeBenefitType
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.NewHomeArea
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/**
 * debug 가짜 홈 '정상'이 가짜 사용자를 따르는지(S15P31E103-453) — 진짜와 같은 변환(toSummary)을 거친 화면 값으로 본다.
 * 실서버 BE HomeService: 계좌가 있으면 onboarding DONE, 카드 0장이면 cards.count 0 · 할 일 · 오늘의 카드 없음
 */
class FakeHomeRepositoryTest {
    /** SessionStore는 만들 때 파일을 열지 않는다. 가짜 홈은 세션을 읽지 않는다 */
    private val noFilesContext = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val bank = FakeBank(FakeUser(storage = null))
    private val payments = FakePaymentRepository(FakePerformanceStore(bank), PaymentEdits(), bank)
    private val forecast = FakeForecastRepository(bank)
    private val home = FakeHomeRepository(
        FakeMyRepository(SessionStore(noFilesContext), payments), bank, forecast, payments, FakePerformanceRepository(FakePerformanceStore(bank), bank),
    )

    @Before fun setUp() {
        DevSession.homeScenario = HomeScenario.NORMAL
        DevSession.faultScenario = FaultScenario.NONE
    }

    @After fun tearDown() {
        DevSession.homeScenario = HomeScenario.NORMAL
        DevSession.faultScenario = FaultScenario.NONE
    }

    private suspend fun load(): HomeSummary = (home.loadHome() as ApiResult.Success).data

    @Test fun `카드 0장 새 사용자 홈은 카드 수 0이고 할 일 · 오늘의 카드가 없다`() = runTest {
        bank.reset()
        val h = load()
        assertFalse(h.needsLinking)   // 계좌가 있어 연결 전이 아니다(BE onboarding DONE)
        assertEquals(0, h.cardCount)
        assertTrue(h.needsCard)
        assertTrue(h.todos.isEmpty())
        assertNull(h.todayCard)
    }

    @Test fun `시드 사용자 홈은 카드 6장 · 할 일 둘 · 오늘의 카드`() = runTest {
        val h = load()
        assertEquals(6, h.cardCount)
        assertFalse(h.needsCard)
        assertEquals(2, h.todos.size)
        assertNotNull(h.todayCard)
    }

    @Test fun `계좌도 없으면 정상이 연결 전 홈`() = runTest {
        bank.reset(accounts = emptyList())
        assertTrue(load().needsLinking)
    }

    // ── 예상 잔액은 가짜 잔고예보와 같은 계산(S15P31E103-454, !294 리뷰 4) ──

    @Test fun `시드 사용자 정상 홈은 잔고예보 기준 계좌의 월급 전 가장 낮을 때`() = runTest {
        val h = load().forecast!!
        val f = forecast.homeForecast()!!
        assertEquals("월급 전 가장 낮을 때", h.label)
        assertEquals(f.minBalance, h.amount)
        assertEquals(f.nextIncome!!.first, h.incomeDate)
    }

    @Test fun `결제 반영 직후는 정상보다 7,500원 낮다`() = runTest {
        val normal = load().forecast!!.amount
        DevSession.homeScenario = HomeScenario.AFTER_PAYMENT
        val after = load()
        assertEquals(normal - 7_500, after.forecast!!.amount)
        assertNotNull(after.recentPayment)
    }

    @Test fun `월급 등록 전은 기준 계좌 지금 잔액 + 월급 안내`() = runTest {
        DevSession.homeScenario = HomeScenario.BEFORE_INCOME
        val h = load().forecast!!
        assertTrue(h.needsIncome)
        assertEquals(412_000L, h.amount)   // KB ·1234
    }

    @Test fun `월급을 넣기 전 새 사용자는 정상도 지금 잔액 + 월급 안내`() = runTest {
        bank.reset()
        val h = load().forecast!!
        assertTrue(h.needsIncome)
        assertEquals(412_000L, h.amount)
    }

    @Test fun `계좌가 없으면 예상 잔액 칸이 없다`() = runTest {
        bank.reset(accounts = emptyList())
        DevSession.homeScenario = HomeScenario.AFTER_PAYMENT   // 정상은 연결 전 홈이 되므로 다른 상태로 본다
        assertNull(load().forecast)
    }

    // ── v4 혜택 히어로 · 결제 칸(S15P31E103-480) ──

    private val thisMonth get() = YearMonth.now(KST)

    @Test fun `시드 사용자 혜택은 확정과 확정 전이 나뉜다`() = runTest {
        val b = (home.loadBenefit(thisMonth) as ApiResult.Success).data
        assertEquals(19_300L, b.confirmed)
        assertEquals(13_100L, b.pending)
        assertEquals(true, b.hasPayments)
    }

    @Test fun `이번 달 결제 없음은 0원 · 결제 없음`() = runTest {
        DevSession.homeScenario = HomeScenario.NO_PAYMENT
        val b = (home.loadBenefit(thisMonth) as ApiResult.Success).data
        assertEquals(0L, b.confirmed)
        assertEquals(0L, b.pending)
        assertEquals(false, b.hasPayments)
    }

    @Test fun `혜택만 실패는 혜택 칸만 실패하고 홈 요약은 받는다`() = runTest {
        DevSession.homeScenario = HomeScenario.BENEFIT_ERROR
        assertTrue(home.loadBenefit(thisMonth) is ApiResult.Failure)
        assertTrue(home.loadHome() is ApiResult.Success)
    }

    @Test fun `자동 선택 꺼짐이면 꺼짐과 기본 카드(없으면 첫 카드)`() = runTest {
        DevSession.homeScenario = HomeScenario.AUTO_SELECT_OFF
        val s = (home.loadPaySettings() as ApiResult.Success).data
        assertFalse(s.autoSelect)
        assertNotNull(s.defaultUserCardId)
        assertTrue(load().cards.any { it.userCardId == s.defaultUserCardId })   // 결제 칸이 이름을 찾을 수 있다
    }

    @Test fun `정상은 자동 선택이 켜져 있다`() = runTest {
        assertTrue((home.loadPaySettings() as ApiResult.Success).data.autoSelect)
    }

    // ── v4 카드 현황 · 잔고예보 요약(S15P31E103-481) ──

    @Test fun `카드 현황은 등록한 카드 순서이고 금액을 모르는 한도가 하나 있다`() = runTest {
        val data = (home.loadCards() as ApiResult.Success).data
        assertEquals(bank.registeredCardIds(), data.cards.map { it.userCardId })
        assertTrue(data.limits!!.flatMap { it.limits }.any { it.remainingAmount == null })
    }

    @Test fun `잔고예보 요약은 기준 계좌 · 30일 예보와 같은 최저 잔고`() = runTest {
        val accountId = load().forecast!!.accountId!!
        val brief = (home.loadForecastBrief(accountId) as ApiResult.Success).data
        val dto = forecast.homeBrief(accountId)!!
        assertEquals(dto.summary?.minBalance, brief.minBalance)
        assertEquals(dto.currentTotalBalance, brief.currentBalance)
        assertNotNull(brief.accountLabel)
    }

    @Test fun `잔고예보만 실패면 요약만 실패하고 홈은 기준 계좌를 준다`() = runTest {
        DevSession.homeScenario = HomeScenario.FORECAST_ERROR
        val accountId = load().forecast!!.accountId!!
        assertTrue(home.loadForecastBrief(accountId) is ApiResult.Failure)
    }

    @Test fun `기준 계좌 없음은 잔고 영역이 비었지만 실패는 아니다`() = runTest {
        DevSession.homeScenario = HomeScenario.NO_ACCOUNT
        val h = load()
        assertNull(h.forecast)
        assertTrue(h.failedAreas.isEmpty())
        assertEquals(NewHomeArea.None, h.forecastPreview)
    }

    // ── 새 홈 칸(BE -490) · 카드별 받은 혜택(BE -489, S15P31E103-491) ──

    @Test fun `새 홈 칸 혜택은 옛 방식(reports-monthly)과 같은 숫자이고 종류 합이 확정과 같다`() = runTest {
        val b = (load().yamuBenefit as NewHomeArea.Value).data
        val old = (home.loadBenefit(thisMonth) as ApiResult.Success).data
        assertEquals(old.confirmed, b.confirmed)
        assertEquals(old.pending, b.pending)
        assertEquals(b.confirmed, b.byType.sumOf { it.confirmed })
        assertEquals(b.pending, b.byType.sumOf { it.pending })
    }

    @Test fun `카드별 받은 혜택을 다 더하면 히어로와 같다`() = runTest {
        val b = (load().yamuBenefit as NewHomeArea.Value).data
        val cards = (home.loadCardBenefits(thisMonth) as ApiResult.Success).data
        assertEquals(b.confirmed, cards.values.sumOf { it.confirmed })
        assertEquals(b.pending, cards.values.sumOf { it.pending })
    }

    @Test fun `새 홈 칸 잔고 요약은 기준 계좌 30일 예보에서 오늘을 뺀 최저`() = runTest {
        val h = load()
        val p = (h.forecastPreview as NewHomeArea.Value).data
        val series = forecast.homeBrief(p.accountId)!!.series.drop(1)
        val min = series.minOf { it.conservative ?: it.expected!! }
        assertEquals(h.forecast!!.accountId, p.accountId)
        assertEquals(min, p.minBalance)
        assertEquals(series.first { (it.conservative ?: it.expected) == min }.date, p.minDate.toString())
        assertTrue(p.fromTomorrow)
    }

    @Test fun `옛 서버 상태는 두 칸이 없어 옛 방식으로 돌아간다`() = runTest {
        DevSession.homeScenario = HomeScenario.OLD_SERVER
        val h = load()
        assertEquals(NewHomeArea.OldServer, h.yamuBenefit)
        assertEquals(NewHomeArea.OldServer, h.forecastPreview)
        assertTrue(home.loadBenefit(thisMonth) is ApiResult.Success)
    }

    @Test fun `혜택만 실패 · 잔고예보만 실패는 그 새 칸만 실패`() = runTest {
        DevSession.homeScenario = HomeScenario.BENEFIT_ERROR
        load().let {
            assertEquals(NewHomeArea.Failed, it.yamuBenefit)
            assertTrue(it.forecastPreview is NewHomeArea.Value)
        }
        DevSession.homeScenario = HomeScenario.FORECAST_ERROR
        load().let {
            assertEquals(NewHomeArea.Failed, it.forecastPreview)
            assertTrue(it.yamuBenefit is NewHomeArea.Value)
        }
    }

    @Test fun `원화로 못 바꾼 혜택 상태는 적립 칸과 카드 한 장에 한 건`() = runTest {
        DevSession.homeScenario = HomeScenario.UNCONVERTED
        val b = (load().yamuBenefit as NewHomeArea.Value).data
        assertEquals(1L, b.unconvertedCount)
        assertEquals(1L, b.byType.first { it.type == HomeBenefitType.POINT }.unconvertedCount)
        assertEquals(1L, (home.loadCardBenefits(thisMonth) as ApiResult.Success).data.values.sumOf { it.unconvertedCount })
    }

    @Test fun `이번 달 결제 없음은 새 칸도 0원`() = runTest {
        DevSession.homeScenario = HomeScenario.NO_PAYMENT
        val b = (load().yamuBenefit as NewHomeArea.Value).data
        assertEquals(0L, b.confirmed)
        assertEquals(0L, b.pending)
        assertTrue((home.loadCardBenefits(thisMonth) as ApiResult.Success).data.isEmpty())
    }
}
