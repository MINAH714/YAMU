package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * debug 가짜 사용자(S15P31E103-453) — 앱을 다시 켜도 같은 사용자이고, 새 사용자는 실서버처럼 계좌 1 · 카드 0,
 * 결제할 카드가 없으면 코드 발급이 NO_ACTIVE_CARD(BE BarcodeService)인지
 */
class FakeUserTest {
    /** 기기 저장소 대신 — 같은 값을 넘기면 '앱을 다시 켠 것' */
    private class MemoryStorage : FakeUser.Storage {
        var json: String? = null
        override fun load(): String? = json
        override fun save(json: String) { this.json = json }
    }

    private val storage = MemoryStorage()
    private val bank = FakeBank(FakeUser(storage))

    @After fun tearDown() {
        DevSession.homeScenario = HomeScenario.NORMAL
    }

    private suspend fun FakeBank.registerToktok() {
        val lookup = lookup("9410000000001021", "12", "30") as ApiResult.Success
        assertTrue(register(lookup.data.verificationToken) is ApiResult.Success)
    }

    private fun issueCode(bank: FakeBank) = FakePaymentRepository(FakePerformanceStore(bank), PaymentEdits(), bank)

    @Test fun `저장한 값이 없으면 시드 사용자 — 계좌 2 · 카드 6장`() {
        assertTrue(bank.seeded)
        assertEquals(2, bank.linkedAccountCount())
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L, 16L), bank.registeredCardIds())
    }

    @Test fun `새 사용자는 계좌 1 · 카드 0이고 코드 발급은 NO_ACTIVE_CARD`() = runTest {
        bank.reset()
        assertFalse(bank.seeded)
        assertEquals(1, bank.linkedAccountCount())
        assertTrue(bank.registeredCardIds().isEmpty())
        val r = issueCode(bank).issueCode("sut_fake")
        assertEquals("NO_ACTIVE_CARD", (r as ApiResult.Failure).error.code)
        assertTrue(r.error is ApiError.Conflict)   // 409
    }

    @Test fun `등록한 카드는 앱을 다시 켜도 남고 결제 코드가 나온다`() = runTest {
        bank.reset()
        bank.registerToktok()
        assertEquals(HomeScenario.FIRST_DAY, DevSession.homeScenario)   // 새 사용자의 첫 카드 — 가짜 홈 '가입 첫날'

        val restarted = FakeBank(FakeUser(storage))
        assertFalse(restarted.seeded)
        assertEquals(listOf(11L), restarted.registeredCardIds())
        assertTrue(issueCode(restarted).issueCode("sut_fake") is ApiResult.Success)
    }

    @Test fun `자동 선택에서 뺀 카드만 남으면 결제할 카드가 없다`() = runTest {
        bank.reset()
        bank.registerToktok()
        assertTrue(bank.updateCard(11, nickname = null, recommended = false, isDefault = null) is ApiResult.Success)
        assertEquals(listOf(11L), bank.registeredCardIds())
        assertTrue(bank.activeCardIds().isEmpty())
        assertEquals("NO_ACTIVE_CARD", (issueCode(bank).issueCode("sut_fake") as ApiResult.Failure).error.code)
    }

    @Test fun `가입 1원 때 고른 계좌를 전부 연결하고 1원 받은 계좌가 기준`() = runTest {
        bank.signedUp(listOf(FakeSignupRepository.CANDIDATE_KB, FakeSignupRepository.CANDIDATE_SHINHAN), FakeSignupRepository.CANDIDATE_SHINHAN)
        val accounts = (bank.linkedAccountList() as ApiResult.Success).data
        assertEquals(listOf("1234", "5678"), accounts.map { it.last4 })
        assertEquals("5678", accounts.single { it.isPrimary }.last4)
        assertTrue(bank.registeredCardIds().isEmpty())
    }

    @Test fun `다시 켜도 별명 · 기본 카드 · 순서가 남는다`() = runTest {
        assertTrue(bank.updateCard(13, nickname = "생활카드", recommended = null, isDefault = true) is ApiResult.Success)
        assertTrue(bank.saveCardOrder(listOf(16L, 15L, 14L, 13L, 12L, 11L)) is ApiResult.Success)

        val restarted = FakeBank(FakeUser(storage))
        val info = (restarted.cardDetail(13) as ApiResult.Success).data
        assertEquals("생활카드", info.nickname)
        assertTrue(info.isDefault)
        assertEquals(listOf(16L, 15L, 14L, 13L, 12L, 11L), restarted.registeredCardIds())
    }

    @Test fun `저장한 값을 못 읽으면 시드 사용자로`() {
        storage.json = "{망가진 값"
        assertTrue(FakeBank(FakeUser(storage)).seeded)
    }

    // ── 시드 사용자 월급은 저장하지 않고 늘 오늘 + 25일(!294 리뷰 2) ──

    @Test fun `시드 사용자가 무엇을 바꿔 저장해도 월급날은 저장되지 않고 읽는 날 + 25일`() = runTest {
        assertTrue(bank.updateCard(13, nickname = "생활카드", recommended = null, isDefault = null) is ApiResult.Success)   // 저장이 일어남
        assertFalse(storage.json!!.contains("salary"))
        val restarted = FakeUser(storage).state
        val later = LocalDate.now(KST).plusDays(3)   // 며칠 뒤에 켠 것
        assertEquals(later.plusDays(25), restarted.salaryOn(later)!!.nextAfter(later))
    }

    @Test fun `월급 칸이 없던 -453 저장값의 시드 사용자도 월급이 있다`() {
        storage.json = """{"seeded":true,"accounts":["A1","A2"],"cards":["C1","C2","C3","C4","C5","C6"],"primaryAccount":"A1","defaultCard":"C2"}"""
        val today = LocalDate.now(KST)
        assertEquals(today.plusDays(25), FakeBank(FakeUser(storage)).salary!!.nextAfter(today))
    }

    @Test fun `새 사용자는 월급이 없고 B-06에서 넣은 월급은 저장된다`() {
        bank.reset()
        assertNull(bank.salary)
        bank.setSalary(25, 2_500_000, accountId = 1)
        assertEquals(FakeUser.Salary(25, 2_500_000, "A1"), FakeUser(storage).state.salaryOn(LocalDate.now(KST)))
    }
}
