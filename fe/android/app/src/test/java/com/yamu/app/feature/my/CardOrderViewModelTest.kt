package com.yamu.app.feature.my

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.link.data.AccountLinkResult
import com.yamu.app.feature.link.data.CardInfo
import com.yamu.app.feature.link.data.CardLookup
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.LinkedAccount
import com.yamu.app.feature.link.data.LinkedOverview
import com.yamu.app.feature.link.data.RegisteredCard
import com.yamu.app.feature.link.data.brandOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 카드 순서 — 끝 넘기 · 연타 · 다른 기기 변경 · 응답 유실 */
class CardOrderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val link = StubLink()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.order(): CardOrderViewModel {
        val vm = CardOrderViewModel(link)
        advanceUntilIdle()
        return vm
    }

    @Test fun `옮기면 바뀐 순서를 카드 전체로 보낸다`() = runTest(dispatcher) {
        val vm = order()
        assertFalse(vm.state.value.changed)
        vm.move(2, -1)
        assertTrue(vm.state.value.changed)
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf(1L, 3L, 2L), link.savedOrders.single())
        assertTrue(vm.state.value.saved)
    }

    @Test fun `끝을 넘어 옮기면 그대로`() = runTest(dispatcher) {
        val vm = order()
        vm.move(0, -1)
        vm.move(2, 1)
        assertFalse(vm.state.value.changed)
    }

    @Test fun `되돌려 놓으면 저장할 게 없다`() = runTest(dispatcher) {
        val vm = order()
        vm.move(0, 1)
        vm.move(1, -1)
        vm.save()
        advanceUntilIdle()
        assertTrue(link.savedOrders.isEmpty())
    }

    @Test fun `연타해도 저장은 한 번`() = runTest(dispatcher) {
        val vm = order()
        vm.move(0, 1)
        vm.save()
        vm.save()
        advanceUntilIdle()
        assertEquals(1, link.savedOrders.size)
    }

    @Test fun `다른 기기에서 카드가 바뀌었으면 지금 목록으로 다시 읽고 알린다`() = runTest(dispatcher) {
        val vm = order()
        vm.move(0, 1)
        link.saveResult = ApiResult.Failure(ApiError.InvalidRequest("ORDER_INCOMPLETE"))
        link.cards = link.cards.take(2)
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.state.value.cards.map { it.userCardId })
        assertFalse(vm.state.value.changed)
        assertFalse(vm.state.value.saved)
        assertEquals("다른 기기에서 카드가 바뀌었어요 · 순서를 다시 정해 주세요", vm.state.value.message)
    }

    @Test fun `응답을 못 받으면 고친 순서를 그대로 두어 다시 누를 수 있다`() = runTest(dispatcher) {
        val vm = order()
        vm.move(0, 1)
        link.saveResult = ApiResult.Failure(ApiError.Network)
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.changed)
        assertFalse(vm.state.value.saving)
        link.saveResult = ApiResult.Success(Unit)
        vm.save()
        advanceUntilIdle()
        assertEquals(2, link.savedOrders.size)
        assertEquals(link.savedOrders[0], link.savedOrders[1])
    }

    private class StubLink : LinkRepository {
        var saveResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var cards = listOf(linkedCard(1), linkedCard(2), linkedCard(3))
        val savedOrders = mutableListOf<List<Long>>()

        override suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit> {
            savedOrders += userCardIds
            return saveResult
        }

        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> =
            ApiResult.Success(LinkedOverview(emptyList(), cards, null))

        override suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo> = error("안 씀")
        override suspend fun updateCard(userCardId: Long, nickname: String?, recommended: Boolean?, isDefault: Boolean?): ApiResult<CardInfo> = error("안 씀")
        override suspend fun unregisterCard(userCardId: Long, stepUpToken: String): ApiResult<Unit> = error("안 씀")
        override suspend fun updateAccount(accountId: Long, alias: String?, makePrimary: Boolean): ApiResult<LinkedAccount> = error("안 씀")
        override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup> = error("안 씀")
        override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard> = error("안 씀")
        override suspend fun accountCandidates(): ApiResult<List<AccountCandidate>> = error("안 씀")
        override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult> = error("안 씀")
    }

    private companion object {
        fun linkedCard(id: Long) = LinkedCard(id, brandOf("신한카드"), "카드$id", false, "000$id", 14, null, null)
    }
}
