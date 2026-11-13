package com.yamu.app.data

import android.content.Context
import android.content.ContextWrapper
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.home.data.HomeSummary
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
    private val payments = FakePaymentRepository(FakePerformanceStore(), PaymentEdits(), bank)
    private val home = FakeHomeRepository(FakeMyRepository(SessionStore(noFilesContext), payments), bank)

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
}
