package com.yamu.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 하단바 · 고른 탭 규칙(S15P31E103-521) — 보는 화면은 모두 하단바, 입력 · 확정 흐름만 숨김 */
class BottomBarRulesTest {

    @Test
    fun `하단바를 숨기는 화면은 입력 확정 흐름뿐이다`() {
        val expected = setOf(
            PayCode::class, PayDone::class, CancelCode::class,
            BuyCheckInput::class, BuyCheckConditions::class, BuyCheckResult::class, BuyCheckSaved::class,
            CashflowCreate::class, CashflowEdit::class,
            AddCard::class, CardOrder::class, Withdraw::class, PinChange::class,
        )
        assertEquals(expected, BAR_HIDDEN_ROUTES)
    }

    @Test
    fun `보는 화면과 탭 첫 화면은 숨기는 목록에 없다`() {
        val viewing = listOf(
            Home::class, Report::class, PerfOverview::class, My::class,
            Forecast::class, CashflowList::class, LivingCostDetail::class, Notifications::class, MyCards::class,
            CardPerformance::class, PerfAnalysis::class, BenefitLimits::class,
            BenefitReport::class, Payments::class, PaymentDetail::class,
            CardManage::class, CardDetail::class, AccountManage::class, NotificationSettings::class, Profile::class,
            Security::class, Billing::class, CardBills::class, BillDetail::class, PolicyDoc::class,
        )
        viewing.forEach { assertFalse("$it", it in BAR_HIDDEN_ROUTES) }
    }

    @Test
    fun `홈만 쌓여 있으면 홈이 골라진다`() {
        assertEquals(TopTab.HOME, selectedTab { it == TopTab.HOME })
    }

    @Test
    fun `다른 탭 첫 화면이 홈 위에 있으면 그 탭이 골라진다`() {
        // 마이에서 연 잔고예보(홈 그래프 화면)도 스택에는 홈 · 마이 첫 화면이 있어 마이
        assertEquals(TopTab.MY, selectedTab { it == TopTab.HOME || it == TopTab.MY })
        assertEquals(TopTab.REPORT, selectedTab { it == TopTab.HOME || it == TopTab.REPORT })
        assertEquals(TopTab.PERF, selectedTab { it == TopTab.HOME || it == TopTab.PERF })
    }

    @Test
    fun `탭 첫 화면이 하나도 없으면 고른 탭이 없다`() {
        assertNull(selectedTab { false })
    }
}
