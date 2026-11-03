package com.yamu.app.feature.my

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.PinPadResult
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpResults
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import com.yamu.app.feature.link.data.AccountLinkResult
import com.yamu.app.feature.link.data.CardInfo
import com.yamu.app.feature.link.data.CardLookup
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedAccount
import com.yamu.app.feature.link.data.LinkedCard
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** 카드 상세 · 해제 — 연타 · 실패 때 되돌리기 · 다른 기기에서 해제 · 응답 유실 · 출금 전 청구 */
class CardDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = StepUpResults()
    private val link = StubLink()

    private val auth = object : AuthRepository by unusedAuth() {
        override suspend fun biometricReady() = false
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.detail(): CardDetailViewModel {
        val vm = CardDetailViewModel(
            SavedStateHandle(mapOf("userCardId" to 21L)),
            link,
            StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results),
        )
        vm.load()
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.passStepUp(vm: CardDetailViewModel) {
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Verified(StepUpPurpose.CARD_DELETE, StepUpToken("su_delete", StepUpPurpose.CARD_DELETE)))
        advanceUntilIdle()
    }

    // ── 카드 상세 ──

    @Test fun `결제 계좌 이름은 계좌 목록에서 찾는다`() = runTest(dispatcher) {
        assertEquals("신한은행 ·5678", detail().state.value.settlementAccountLabel)
    }

    @Test fun `마지막 한 장이면 해제 시트에서 알린다`() = runTest(dispatcher) {
        assertFalse(detail().state.value.lastCard)
        link.cards = link.cards.take(1)
        assertTrue(detail().state.value.lastCard)
    }

    @Test fun `없거나 해제한 카드면 이미 해제로`() = runTest(dispatcher) {
        link.detailResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        assertTrue(detail().state.value.gone)
    }

    @Test fun `별칭을 비우면 빈 문자열을 보낸다 — null이면 서버가 그대로 둔다`() = runTest(dispatcher) {
        val vm = detail()
        vm.rename("   ")
        advanceUntilIdle()
        assertEquals("", link.updates.single().nickname)
    }

    @Test fun `별칭은 앞뒤 공백을 뺀다`() = runTest(dispatcher) {
        val vm = detail()
        vm.rename("  생활비 ")
        advanceUntilIdle()
        assertEquals("생활비", link.updates.single().nickname)
    }

    @Test fun `연타해도 저장은 한 번`() = runTest(dispatcher) {
        val vm = detail()
        vm.setDefault(true)
        vm.setDefault(false)
        advanceUntilIdle()
        assertEquals(1, link.updates.size)
    }

    @Test fun `저장이 거절되면 원래 값으로 되돌리고 이유를 알린다`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Failure(ApiError.InvalidRequest("INVALID_REQUEST"))
        val vm = detail()
        val reads = link.detailReads
        vm.setDefault(true)
        advanceUntilIdle()
        assertFalse(vm.state.value.card!!.isDefault)
        assertFalse(vm.state.value.saving)
        assertEquals("저장하지 못했어요 · 입력한 내용을 다시 확인해 주세요", vm.state.value.message)
        assertEquals(reads, link.detailReads)
    }

    @Test fun `저장 응답을 못 받으면 서버 값을 다시 읽어 맞춘다 — 서버는 끝냈을 수 있다`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        link.detailResult = ApiResult.Success(CARD.copy(isDefault = true))
        vm.setDefault(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.card!!.isDefault)
        assertFalse(vm.state.value.saving)
    }

    @Test fun `저장한 뒤에는 서버 응답을 믿는다 — 추천에서 빼면 기본 지정도 풀린다`() = runTest(dispatcher) {
        link.detailResult = ApiResult.Success(CARD.copy(isDefault = true))
        link.updateResult = ApiResult.Success(CARD.copy(recommended = false, isDefault = false))
        val vm = detail()
        vm.setRecommended(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.card!!.isDefault)
        assertEquals("자동 선택에서 빼면서 기본 카드 지정도 풀었어요", vm.state.value.message)
    }

    @Test fun `저장 중 다른 기기에서 해제됐으면 이미 해제로`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Failure(ApiError.Conflict("CARD_UNREGISTERED"))
        val vm = detail()
        vm.setDefault(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.gone)
    }

    // ── 해제 ──

    @Test fun `본인 인증 토큰으로 해제하고 화면을 닫는다`() = runTest(dispatcher) {
        val vm = detail()
        vm.unregister()
        passStepUp(vm)
        assertEquals("su_delete", link.lastUnregisterToken)
        assertEquals(CARD.productName, vm.state.value.unregisteredName)
    }

    @Test fun `연타해도 본인 인증과 해제는 한 번`() = runTest(dispatcher) {
        val vm = detail()
        vm.unregister()
        vm.unregister()
        passStepUp(vm)
        assertEquals(1, link.unregisters)
    }

    @Test fun `응답을 못 받았는데 카드가 없으면 해제가 끝난 것`() = runTest(dispatcher) {
        link.unregisterResult = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        link.detailResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        vm.unregister()
        passStepUp(vm)
        assertEquals(CARD.productName, vm.state.value.unregisteredName)
        assertEquals(1, link.unregisters)
    }

    @Test fun `응답을 못 받았는데 카드가 그대로면 다시 누를 수 있다`() = runTest(dispatcher) {
        link.unregisterResult = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        vm.unregister()
        passStepUp(vm)
        assertNull(vm.state.value.unregisteredName)
        assertFalse(vm.state.value.unregistering)
        assertEquals("해제됐는지 확인하지 못했어요 · 다시 눌러 주세요", vm.state.value.message)
    }

    @Test fun `카드 확인도 못 하면 해제로 치지 않는다`() = runTest(dispatcher) {
        link.unregisterResult = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        link.detailResult = ApiResult.Failure(ApiError.Network)
        vm.unregister()
        passStepUp(vm)
        assertNull(vm.state.value.unregisteredName)
        assertEquals("해제됐는지 확인하지 못했어요 · 다시 눌러 주세요", vm.state.value.message)
    }

    @Test fun `다시 눌렀을 때 404면 앞선 요청이 끝난 것이라 해제로 본다`() = runTest(dispatcher) {
        link.unregisterResult = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        vm.unregister()
        passStepUp(vm)
        link.unregisterResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        vm.unregister()
        passStepUp(vm)
        assertEquals(CARD.productName, vm.state.value.unregisteredName)
    }

    @Test fun `출금 전 청구가 있으면 막고 이유를 알린다`() = runTest(dispatcher) {
        link.unregisterResult = ApiResult.Failure(ApiError.Conflict("CARD_HAS_OPEN_BILL"))
        val vm = detail()
        vm.unregister()
        passStepUp(vm)
        assertNull(vm.state.value.unregisteredName)
        assertEquals("출금이 끝나지 않은 청구가 있어 해제할 수 없어요", vm.state.value.message)
    }

    @Test fun `본인 인증을 취소하면 다시 누를 수 있다`() = runTest(dispatcher) {
        val vm = detail()
        vm.unregister()
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Cancelled(StepUpPurpose.CARD_DELETE))
        advanceUntilIdle()
        vm.onStepUpIdle()
        assertFalse(vm.state.value.unregistering)
        assertEquals(0, link.unregisters)
    }

    private class Update(val nickname: String?, val recommended: Boolean?, val isDefault: Boolean?)

    private class StubLink : LinkRepository {
        var detailResult: ApiResult<CardInfo> = ApiResult.Success(CARD)
        var updateResult: ApiResult<CardInfo> = ApiResult.Success(CARD)
        var unregisterResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var cards = listOf(linkedCard(1), linkedCard(2), linkedCard(3))
        val updates = mutableListOf<Update>()
        var detailReads = 0
        var unregisters = 0
        var lastUnregisterToken: String? = null

        override suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo> {
            detailReads++
            return detailResult
        }

        override suspend fun updateCard(userCardId: Long, nickname: String?, recommended: Boolean?, isDefault: Boolean?): ApiResult<CardInfo> {
            updates += Update(nickname, recommended, isDefault)
            return updateResult
        }

        override suspend fun unregisterCard(userCardId: Long, stepUpToken: String): ApiResult<Unit> {
            unregisters++
            lastUnregisterToken = stepUpToken
            return unregisterResult
        }

        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> =
            ApiResult.Success(LinkedOverview(listOf(ACCOUNT), cards, null))

        override suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit> = error("안 씀")
        override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup> = error("안 씀")
        override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard> = error("안 씀")
        override suspend fun accountCandidates(): ApiResult<List<AccountCandidate>> = error("안 씀")
        override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult> = error("안 씀")
    }

    private companion object {
        val ACCOUNT = LinkedAccount(5, brandOf("신한은행"), "주거래", "5678", 100_000, null, isPrimary = true)
        val CARD = CardInfo(
            userCardId = 21,
            brand = brandOf("신한카드"),
            nickname = null,
            productName = "Mr.Life",
            isCheck = false,
            last4 = "5096",
            recommended = true,
            stateLabel = null,
            issuerBlocked = false,
            isDefault = false,
            paymentDay = 14,
            settlementAccountId = 5,
            checkAccountLabel = null,
            nextWithdrawal = null,
        )

        fun linkedCard(id: Long) = LinkedCard(id, brandOf("신한카드"), "카드$id", false, "000$id", 14, null, null)

        fun unusedAuth(): AuthRepository = Proxy.newProxyInstance(
            AuthRepository::class.java.classLoader,
            arrayOf(AuthRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as AuthRepository
    }
}
