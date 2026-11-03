package com.yamu.app.feature.my

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.link.data.AccountDto
import com.yamu.app.feature.link.data.AccountLinkResult
import com.yamu.app.feature.link.data.AccountUpdateRequest
import com.yamu.app.feature.link.data.CardInfo
import com.yamu.app.feature.link.data.CardLookup
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedAccount
import com.yamu.app.feature.link.data.LinkedOverview
import com.yamu.app.feature.link.data.RegisteredCard
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.link.data.toLinked
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 계좌 관리 — 기준 계좌 바꾸기 · 별칭 지우기 · 연타 · 실패 · 없는 계좌 */
class AccountManageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val link = StubLink()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): AccountManageViewModel {
        val vm = AccountManageViewModel(link)
        vm.load()
        advanceUntilIdle()
        return vm
    }

    @Test fun `기준 계좌를 바꾸면 전에 있던 기준 계좌는 풀린다`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Success(SECOND.copy(isPrimary = true))
        val vm = viewModel()
        vm.makePrimary(2)
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.state.value.accounts.filter { it.isPrimary }.map { it.accountId })
        assertEquals(Update(2, null, true), link.updates.single())
    }

    @Test fun `별칭을 비우면 빈 문자열을 보낸다 — null이면 서버가 그대로 둔다`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.rename(2, "   ")
        advanceUntilIdle()
        assertEquals(Update(2, "", false), link.updates.single())
    }

    @Test fun `별칭만 바꾸면 기준 계좌는 그대로`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Success(SECOND.copy(name = "생활비", alias = "생활비"))
        val vm = viewModel()
        vm.rename(2, " 생활비 ")
        advanceUntilIdle()
        assertEquals(Update(2, "생활비", false), link.updates.single())
        assertEquals("생활비", vm.state.value.accounts[1].name)
        assertTrue(vm.state.value.accounts[0].isPrimary)
    }

    @Test fun `저장을 시작하면 진행 중인 읽기는 버린다 — 늦게 와서 저장한 값을 덮으면 안 된다`() = runTest(dispatcher) {
        val vm = viewModel()
        val gate = CompletableDeferred<Unit>()
        link.listGate = gate
        vm.load()
        advanceUntilIdle()
        link.updateResult = ApiResult.Success(SECOND.copy(isPrimary = true))
        vm.makePrimary(2)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.state.value.accounts.filter { it.isPrimary }.map { it.accountId })
        assertFalse(vm.state.value.loading)
    }

    @Test fun `연타해도 저장은 한 번`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.makePrimary(2)
        vm.makePrimary(2)
        advanceUntilIdle()
        assertEquals(1, link.updates.size)
    }

    @Test fun `저장이 실패하면 목록은 그대로 두고 이유를 알린다`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        vm.makePrimary(2)
        advanceUntilIdle()
        assertTrue(vm.state.value.accounts[0].isPrimary)
        assertFalse(vm.state.value.saving)
        assertEquals("저장하지 못했어요 · 인터넷 연결을 확인해 주세요", vm.state.value.message)
    }

    @Test fun `없는 계좌면 지금 목록으로 다시 읽는다`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val vm = viewModel()
        link.list = listOf(FIRST)
        vm.rename(2, "생활비")
        advanceUntilIdle()
        assertEquals(listOf(1L), vm.state.value.accounts.map { it.accountId })
    }

    @Test fun `목록을 못 읽으면 오류 — 이미 읽은 목록은 지우지 않는다`() = runTest(dispatcher) {
        val vm = viewModel()
        link.listResult = ApiResult.Failure(ApiError.Network)
        vm.load()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.accounts.size)
        assertEquals("인터넷 연결을 확인해 주세요", vm.state.value.error)
    }

    // ── 요청 · 응답 모양 ──

    @Test fun `수정 요청은 넘긴 값만`() {
        fun fields(value: AccountUpdateRequest) = YamuJson.parseToJsonElement(YamuJson.encodeToString(value)).jsonObject.keys
        assertEquals(setOf("isPrimary"), fields(AccountUpdateRequest(isPrimary = true)))
        assertEquals(setOf("accountName"), fields(AccountUpdateRequest(accountName = "")))
    }

    @Test fun `응답 — 별칭이 없으면 기본 이름 · 연결 카드 수`() {
        val account = YamuJson.decodeFromString<AccountDto>(
            """
            {"accountId":5,"bank":{"code":"088","name":"신한은행"},"accountName":null,"maskedAccountNo":"****-****-****-1234",
             "balance":950000,"balanceAsOf":"2026-11-03T00:00:00Z","isPrimary":true,
             "linkedCards":[{"userCardId":4,"nickname":"생활비","role":"CREDIT_SETTLEMENT"},{"userCardId":6,"nickname":"처음","role":"CHECK_LINKED"}],
             "upcomingWithdrawals":[],"linkedAt":"2026-11-01T00:00:00Z"}
            """.trimIndent(),
        ).toLinked()
        assertNull(account.alias)
        assertEquals("입출금", account.name)
        assertEquals(2, account.cardCount)
        assertEquals("신한은행 ·1234 · 카드 2장", accountRowSub(account))
    }

    @Test fun `연결 카드가 없으면 줄에 쓰지 않는다`() {
        assertEquals("국민은행 ·5678", accountRowSub(SECOND))
    }

    private data class Update(val accountId: Long, val alias: String?, val makePrimary: Boolean)

    private class StubLink : LinkRepository {
        var list = listOf(FIRST, SECOND)
        var listResult: ApiResult<List<LinkedAccount>>? = null
        var listGate: CompletableDeferred<Unit>? = null
        var updateResult: ApiResult<LinkedAccount> = ApiResult.Success(SECOND)
        val updates = mutableListOf<Update>()

        override suspend fun accounts(): ApiResult<List<LinkedAccount>> {
            val result = listResult ?: ApiResult.Success(list)
            listGate?.await()
            return result
        }

        /** 카드 목록이 실패해도 계좌 화면이 열리도록 이 화면은 부르지 않는다 */
        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> = error("안 씀")

        override suspend fun updateAccount(accountId: Long, alias: String?, makePrimary: Boolean): ApiResult<LinkedAccount> {
            updates += Update(accountId, alias, makePrimary)
            return updateResult
        }

        override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup> = error("안 씀")
        override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard> = error("안 씀")
        override suspend fun accountCandidates(): ApiResult<List<AccountCandidate>> = error("안 씀")
        override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult> = error("안 씀")
        override suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo> = error("안 씀")
        override suspend fun updateCard(userCardId: Long, nickname: String?, recommended: Boolean?, isDefault: Boolean?): ApiResult<CardInfo> = error("안 씀")
        override suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit> = error("안 씀")
        override suspend fun unregisterCard(userCardId: Long, stepUpToken: String): ApiResult<Unit> = error("안 씀")
    }

    private companion object {
        val FIRST = LinkedAccount(1, brandOf("신한은행"), "주거래", "1234", 100_000, null, isPrimary = true, alias = "주거래", cardCount = 2)
        val SECOND = LinkedAccount(2, brandOf("국민은행"), "입출금", "5678", 50_000, null, isPrimary = false)
    }
}
