package com.yamu.app.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
