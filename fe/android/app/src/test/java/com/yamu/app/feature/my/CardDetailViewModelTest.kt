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
import com.yamu.app.feature.my.data.MyInfo
import com.yamu.app.feature.my.data.MyRepository
import kotlinx.coroutines.CompletableDeferred
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
    private val my = StubMy()

    private val auth = object : AuthRepository by unusedAuth() {
        override suspend fun biometricReady() = false
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.detail(): CardDetailViewModel {
        val vm = CardDetailViewModel(
            SavedStateHandle(mapOf("userCardId" to 21L)),
            link,
            my,
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

    @Test fun `다른 카드가 자동 선택에 있으면 마지막 카드가 아니다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21), linkedCard(2))
        assertFalse(detail().state.value.isLastActive)
    }

    @Test fun `다른 카드가 모두 추천 제외면 카드가 여러 장이어도 마지막이다 — BE는 ACTIVE만 센다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21), linkedCard(2).copy(recommended = false))
        assertTrue(detail().state.value.isLastActive)
    }

    @Test fun `추천에서 뺀 카드는 마지막 카드가 아니다 — 다시 켤 수 있어야 한다`() = runTest(dispatcher) {
        link.detailResult = ApiResult.Success(CARD.copy(recommended = false))
        link.cards = listOf(linkedCard(21).copy(recommended = false))
        val vm = detail()
        assertFalse(vm.state.value.isLastActive)
        link.updateResult = ApiResult.Success(CARD)
        vm.setRecommended(true)
        advanceUntilIdle()
        assertEquals(true, link.updates.single().recommended)
    }

    @Test fun `목록을 못 읽으면 마지막 카드로 단정하지 않는다`() = runTest(dispatcher) {
        link.overviewResult = ApiResult.Failure(ApiError.Network)
        assertFalse(detail().state.value.isLastActive)
    }

    @Test fun `마지막 카드는 자동 선택에서 뺄 수 없다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21))
        val vm = detail()
        vm.setRecommended(false)
        advanceUntilIdle()
        assertTrue(link.updates.isEmpty())
        assertTrue(vm.state.value.card!!.recommended)
    }

    @Test fun `다른 카드가 있으면 자동 선택에서 뺄 수 있다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21), linkedCard(2))
        link.updateResult = ApiResult.Success(CARD.copy(recommended = false))
        val vm = detail()
        vm.setRecommended(false)
        advanceUntilIdle()
        assertEquals(false, link.updates.single().recommended)
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

    @Test fun `저장을 시작하면 진행 중인 읽기는 버린다 — 늦게 와서 저장한 값을 덮으면 안 된다`() = runTest(dispatcher) {
        val vm = detail()
        val gate = CompletableDeferred<Unit>()
        link.detailGate = gate
        vm.load()
        advanceUntilIdle()
        link.updateResult = ApiResult.Success(CARD.copy(isDefault = true))
        vm.setDefault(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.card!!.isDefault)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.card!!.isDefault)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `저장 중 다른 기기에서 해제됐으면 이미 해제로`() = runTest(dispatcher) {
        link.updateResult = ApiResult.Failure(ApiError.Conflict("CARD_UNREGISTERED"))
        val vm = detail()
        vm.setDefault(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.gone)
    }

    // ── 결제 카드 자동 선택(카드 관리)이 꺼졌을 때 ──

    @Test fun `자동 선택이 꺼져 있으면 자동 선택에 포함을 바꾸지 않는다`() = runTest(dispatcher) {
        my.autoSelect = false
        link.cards = listOf(linkedCard(21), linkedCard(2))
        val vm = detail()
        assertTrue(vm.state.value.autoSelectOff)
        vm.setRecommended(false)
        advanceUntilIdle()
        assertTrue(link.updates.isEmpty())
        assertTrue(vm.state.value.card!!.recommended)   // 카드의 포함 값은 그대로 둔다
    }

    @Test fun `사용자 정보를 못 읽으면 자동 선택이 켜진 것으로 본다`() = runTest(dispatcher) {
        my.info = ApiResult.Failure(ApiError.Network)
        link.cards = listOf(linkedCard(21), linkedCard(2))
        val vm = detail()
        assertFalse(vm.state.value.autoSelectOff)
        vm.setRecommended(false)
        advanceUntilIdle()
        assertEquals(false, link.updates.single().recommended)
    }

    @Test fun `다시 읽을 때 사용자 정보를 못 읽으면 마지막 값을 둔다`() = runTest(dispatcher) {
        my.autoSelect = false
        val vm = detail()
        my.info = ApiResult.Failure(ApiError.Network)
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.autoSelectOff)
    }

    @Test fun `자동 선택이 꺼진 채 기본 카드를 풀면 다시 읽어 자동 선택이 켜진 것을 알린다`() = runTest(dispatcher) {
        my.autoSelect = false
        link.detailResult = ApiResult.Success(CARD.copy(isDefault = true))
        link.updateResult = ApiResult.Success(CARD.copy(isDefault = false))
        val vm = detail()
        my.autoSelect = true   // 서버가 기본 카드가 없어져 자동 선택을 다시 켰다
        vm.setDefault(false)
        vm.confirmDefault()
        advanceUntilIdle()
        assertEquals(false, link.updates.single().isDefault)
        assertFalse(vm.state.value.autoSelectOff)
        assertFalse(vm.state.value.saving)
        assertEquals("기본 카드를 풀어서 결제 카드 자동 선택이 다시 켜졌어요", vm.state.value.message)
    }

    @Test fun `자동 선택이 켜진 채 기본 카드를 풀면 사용자 정보를 다시 읽지 않는다`() = runTest(dispatcher) {
        link.detailResult = ApiResult.Success(CARD.copy(isDefault = true))
        link.updateResult = ApiResult.Success(CARD.copy(isDefault = false))
        val vm = detail()
        val reads = my.reads
        vm.setDefault(false)
        advanceUntilIdle()
        assertEquals(reads, my.reads)
        assertNull(vm.state.value.message)
    }

    // ── 기본 카드 바꾸기 확인(11/10 QA) ──

    @Test fun `다른 카드가 기본 카드면 바꾸기 전에 묻고 확인하면 저장한다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21), linkedCard(2).copy(isDefault = true, nickname = "노리2"))
        link.updateResult = ApiResult.Success(CARD.copy(isDefault = true))
        val vm = detail()
        vm.setDefault(true)
        advanceUntilIdle()
        assertTrue(link.updates.isEmpty())
        assertEquals(DefaultConfirm.Switch(to = "Mr.Life", from = "노리2"), vm.state.value.confirm)
        vm.confirmDefault()
        advanceUntilIdle()
        assertEquals(true, link.updates.single().isDefault)
        assertNull(vm.state.value.confirm)
        assertNull(vm.state.value.otherDefaultName)   // 서버가 앞선 기본 카드를 풀었다
    }

    @Test fun `물은 뒤 닫으면 저장하지 않는다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21), linkedCard(2).copy(isDefault = true))
        val vm = detail()
        vm.setDefault(true)
        vm.dismissDefaultConfirm()
        advanceUntilIdle()
        assertTrue(link.updates.isEmpty())
        assertNull(vm.state.value.confirm)
        assertFalse(vm.state.value.card!!.isDefault)
    }

    @Test fun `확인창이 떠 있는 동안 다시 눌러도 한 번만 묻고 한 번만 저장한다`() = runTest(dispatcher) {
        link.cards = listOf(linkedCard(21), linkedCard(2).copy(isDefault = true))
        val vm = detail()
        vm.setDefault(true)
        vm.setDefault(true)
        vm.confirmDefault()
        vm.confirmDefault()
        advanceUntilIdle()
        assertEquals(1, link.updates.size)
    }

    @Test fun `기본 카드가 없으면 묻지 않고 바로 정한다`() = runTest(dispatcher) {
        val vm = detail()
        vm.setDefault(true)
        advanceUntilIdle()
        assertNull(vm.state.value.confirm)
        assertEquals(true, link.updates.single().isDefault)
    }

    @Test fun `카드 목록을 못 읽으면 지금 기본 카드 이름 없이 묻는다`() = runTest(dispatcher) {
        link.overviewResult = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        vm.setDefault(true)
        advanceUntilIdle()
        assertTrue(link.updates.isEmpty())
        assertEquals(DefaultConfirm.Switch(to = "Mr.Life", from = null), vm.state.value.confirm)
    }

    @Test fun `자동 선택이 켜진 채 기본 카드를 풀 때는 묻지 않는다`() = runTest(dispatcher) {
        link.detailResult = ApiResult.Success(CARD.copy(isDefault = true))
        val vm = detail()
        vm.setDefault(false)
        advanceUntilIdle()
        assertNull(vm.state.value.confirm)
        assertEquals(false, link.updates.single().isDefault)
    }

    @Test fun `자동 선택이 꺼진 채 기본 카드를 풀 때는 자동 선택이 다시 켜진다고 묻는다`() = runTest(dispatcher) {
        my.autoSelect = false
        link.detailResult = ApiResult.Success(CARD.copy(isDefault = true))
        val vm = detail()
        vm.setDefault(false)
        advanceUntilIdle()
        assertTrue(link.updates.isEmpty())
        assertEquals(DefaultConfirm.Release, vm.state.value.confirm)
    }

    @Test fun `확인창 문장 끝 — 받침이 있으면 이에요`() {
        assertEquals("예요", "KB국민 노리2 체크카드".copula())
        assertEquals("이에요", "KB국민 적립".copula())
        assertEquals("예요", "신한카드 Mr.Life".copula())
        assertEquals("이에요", "카드1".copula())
        assertEquals("예요", "카드2".copula())
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

    @Test fun `이미 아는 청구가 있으면 본인 인증을 받지 않는다`() = runTest(dispatcher) {
        link.detailResult = ApiResult.Success(CARD.copy(hasOpenBill = true))
        val vm = detail()
        vm.unregister()
        advanceUntilIdle()
        assertFalse(vm.state.value.unregistering)
        assertFalse(vm.stepUp.busy)
        assertEquals(0, link.unregisters)
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
        var overviewResult: ApiResult<LinkedOverview>? = null
        var detailGate: CompletableDeferred<Unit>? = null
        val updates = mutableListOf<Update>()
        var detailReads = 0
        var unregisters = 0
        var lastUnregisterToken: String? = null

        override suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo> {
            detailReads++
            val result = detailResult
            detailGate?.await()
            return result
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
            overviewResult ?: ApiResult.Success(LinkedOverview(listOf(ACCOUNT), cards, null))

        override suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit> = error("안 씀")
        override suspend fun accounts(): ApiResult<List<LinkedAccount>> = error("안 씀")
        override suspend fun updateAccount(accountId: Long, alias: String?, makePrimary: Boolean): ApiResult<LinkedAccount> = error("안 씀")
        override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup> = error("안 씀")
        override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard> = error("안 씀")
        override suspend fun accountCandidates(): ApiResult<List<AccountCandidate>> = error("안 씀")
        override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult> = error("안 씀")
    }

    /** 카드 상세는 사용자 정보에서 결제 카드 자동 선택 값만 읽는다 */
    private class StubMy : MyRepository by unusedMy() {
        var autoSelect = true
        var info: ApiResult<MyInfo>? = null
        var reads = 0

        override suspend fun me(): ApiResult<MyInfo> {
            reads++
            return info ?: ApiResult.Success(MyInfo("야무", null, 60, false, false, null, autoSelect))
        }
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

        fun unusedMy(): MyRepository = Proxy.newProxyInstance(
            MyRepository::class.java.classLoader,
            arrayOf(MyRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as MyRepository
    }
}
