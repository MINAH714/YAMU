package com.yamu.app.feature.link

import android.content.ContextWrapper
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
import com.yamu.app.feature.link.data.AccountRef
import com.yamu.app.feature.link.data.CardLookup
import com.yamu.app.feature.link.data.IssuerAccount
import com.yamu.app.feature.link.data.LinkRepository
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** 카드 등록 흐름 — 번호 지우기 · 토큰 만료 · 결제 계좌 연결 · 연타 · 멱등 키. 본인 인증은 진짜 컨트롤러에 간편 비밀번호 결과를 넣는다 */
class CardRegisterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = StepUpResults()
    private val link = StubLink()
    private var onboardingCompleted = 0

    /** 지문을 안 쓰는 기기 — 쓰는 메서드만 채운다. 나머지를 부르면 테스트가 실패해야 하므로 Proxy는 예외를 던진다 */
    private val auth = object : AuthRepository by unusedAuth() {
        override suspend fun biometricReady() = false
        override suspend fun completeOnboarding() {
            onboardingCompleted++
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CardRegisterViewModel(
        link,
        auth,
        StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results),
    )

    /** 화면이 간편 비밀번호를 열고 사용자가 통과한 것처럼 */
    private fun TestScope.passStepUp(vm: CardRegisterViewModel, purpose: String) {
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Verified(purpose, StepUpToken("su_$purpose", purpose)))
        advanceUntilIdle()
    }

    private fun TestScope.toConfirm(vm: CardRegisterViewModel) {
        vm.onNumber(NUMBER)
        vm.onExpiry("1299")
        vm.lookup()
        advanceUntilIdle()
    }

    @Test fun `형식이 틀리면 서버를 부르지 않는다`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNumber("1234")
        vm.onExpiry("1299")
        vm.lookup()
        advanceUntilIdle()
        assertEquals(0, link.lookups)
        assertTrue(vm.state.value.showErrors)
        assertEquals(CardRegisterStep.Input, vm.state.value.step)
    }

    @Test fun `입력은 숫자만 · 길이까지`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNumber("9410-1234 5678 5678 99")
        vm.onExpiry("12/999")
        assertEquals("9410123456785678", vm.state.value.number)
        assertEquals("1299", vm.state.value.expiry)
    }

    @Test fun `확인이 끝나면 번호 · 기간을 지운다`() = runTest(dispatcher) {
        val vm = viewModel()
        toConfirm(vm)
        val s = vm.state.value
        assertEquals(CardRegisterStep.Confirm, s.step)
        assertEquals("", s.number)
        assertEquals("", s.expiry)
        assertEquals("cvt_1", s.lookup?.verificationToken)
        assertEquals(Triple(NUMBER, "12", "99"), link.lastLookup)
    }

    @Test fun `확인이 실패하면 고쳐 쓰게 번호를 남기고 문장을 보여 준다`() = runTest(dispatcher) {
        link.lookupResult = ApiResult.Failure(ApiError.Conflict("CARD_ALREADY_REGISTERED"))
        val vm = viewModel()
        toConfirm(vm)
        val s = vm.state.value
        assertEquals(CardRegisterStep.Input, s.step)
        assertEquals(NUMBER, s.number)
        assertEquals("이미 등록한 카드예요", s.inputError)
    }

    @Test fun `등록하면 완료로 · 토큰을 버리고 초기 설정 끝`() = runTest(dispatcher) {
        val vm = viewModel()
        toConfirm(vm)
        vm.onNickname("  생활비 ")
        vm.register()
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        val s = vm.state.value
        assertEquals(CardRegisterStep.Done, s.step)
        assertNull(s.lookup)
        assertNotNull(s.registered)
        assertEquals(1, onboardingCompleted)
        assertEquals(Triple("cvt_1", "생활비", "su_CARD_REGISTER"), link.lastRegister)
    }

    @Test fun `연타해도 등록은 한 번`() = runTest(dispatcher) {
        val vm = viewModel()
        toConfirm(vm)
        vm.register()
        vm.register()
        vm.register()
        assertTrue(vm.state.value.registering)
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        assertEquals(1, link.registers)
    }

    @Test fun `확인 토큰이 만료되면 입력 화면으로 · 문장과 함께`() = runTest(dispatcher) {
        link.registerResult = ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        val vm = viewModel()
        toConfirm(vm)
        vm.register()
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        val s = vm.state.value
        assertEquals(CardRegisterStep.Input, s.step)
        assertNull(s.lookup)
        assertFalse(s.registering)
        assertEquals("확인 시간이 지났어요 · 카드 정보를 다시 넣어 주세요", s.inputError)
        assertEquals(0, onboardingCompleted)
    }

    @Test fun `그 사이 정지된 카드면 입력 화면으로`() = runTest(dispatcher) {
        link.registerResult = ApiResult.Failure(ApiError.InvalidRequest("CARD_VERIFICATION_FAILED", buildJsonObject { put("reason", "ISSUER_SUSPENDED") }))
        val vm = viewModel()
        toConfirm(vm)
        vm.register()
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        assertEquals(CardRegisterStep.Input, vm.state.value.step)
        assertEquals("카드사에서 정지한 카드예요", vm.state.value.inputError)
    }

    @Test fun `본인 인증을 취소하면 다시 누를 수 있다`() = runTest(dispatcher) {
        val vm = viewModel()
        toConfirm(vm)
        vm.register()
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Cancelled(StepUpPurpose.CARD_REGISTER))
        advanceUntilIdle()
        vm.onStepUpIdle()
        assertFalse(vm.state.value.registering)
        assertEquals(CardRegisterStep.Confirm, vm.state.value.step)
        assertEquals(0, link.registers)
    }

    @Test fun `등록 응답 409 — 계좌를 연결하고 같은 토큰으로 이어서 등록한다`() = runTest(dispatcher) {
        link.registerResult = ApiResult.Failure(
            ApiError.Conflict("CARD_ACCOUNT_NOT_LINKED", buildJsonObject {
                put("bankCode", "088")
                put("accountLast4", "5678")
            }),
        )
        val vm = viewModel()
        toConfirm(vm)
        vm.register()
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        assertEquals(CardRegisterStep.Confirm, vm.state.value.step)
        assertTrue(vm.state.value.lookup!!.accountMissing)
        assertEquals("신한은행 ·5678", vm.state.value.lookup!!.issuerAccount!!.label)

        vm.register()                                // 계좌가 없으면 본인 인증도 열지 않는다
        advanceUntilIdle()
        assertEquals(1, link.registers)

        vm.openAccounts()
        advanceUntilIdle()
        assertEquals(CardRegisterStep.Accounts, vm.state.value.step)
        assertEquals(setOf("cand_sh"), vm.state.value.selected)   // 결제 계좌를 미리 고른다
        vm.linkAccounts()
        link.linkResult = ApiResult.Success(AccountLinkResult(listOf(AccountRef("088", "5678"))))
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertEquals(CardRegisterStep.Confirm, vm.state.value.step)
        assertTrue(vm.state.value.lookup!!.registrable)

        link.registerResult = ApiResult.Success(REGISTERED)
        vm.register()
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        assertEquals(CardRegisterStep.Done, vm.state.value.step)
        assertEquals("cvt_1", link.lastRegister?.first)
    }

    @Test fun `다른 계좌만 연결했으면 결제 계좌는 그대로 미연결`() = runTest(dispatcher) {
        link.lookupResult = ApiResult.Success(LOOKUP.copy(issuerAccount = IssuerAccount("088", "5678", linked = false)))
        val vm = viewModel()
        toConfirm(vm)
        vm.openAccounts()
        advanceUntilIdle()
        vm.toggleAccount("cand_sh")
        vm.toggleAccount("cand_kb")
        link.linkResult = ApiResult.Success(AccountLinkResult(listOf(AccountRef("004", "1234"))))
        vm.linkAccounts()
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertEquals(CardRegisterStep.Confirm, vm.state.value.step)
        assertTrue(vm.state.value.lookup!!.accountMissing)
    }

    @Test fun `계좌 연결 다시 시도는 같은 키 · 고른 계좌가 바뀌면 새 키`() = runTest(dispatcher) {
        link.lookupResult = ApiResult.Success(LOOKUP.copy(issuerAccount = IssuerAccount("088", "5678", linked = false)))
        link.linkResult = ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        toConfirm(vm)
        vm.openAccounts()
        advanceUntilIdle()
        vm.linkAccounts()
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertEquals(CardRegisterStep.Accounts, vm.state.value.step)
        vm.linkAccounts()
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertEquals(2, link.linkKeys.size)
        assertEquals(link.linkKeys[0], link.linkKeys[1])

        vm.toggleAccount("cand_kb")
        vm.linkAccounts()
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertNotEquals(link.linkKeys[1], link.linkKeys[2])
    }

    @Test fun `계좌 목록이 만료되면 다시 불러오고 키도 새로`() = runTest(dispatcher) {
        link.lookupResult = ApiResult.Success(LOOKUP.copy(issuerAccount = IssuerAccount("088", "5678", linked = false)))
        link.linkResult = ApiResult.Failure(ApiError.InvalidRequest("ACCOUNT_CANDIDATE_EXPIRED"))
        val vm = viewModel()
        toConfirm(vm)
        vm.openAccounts()
        advanceUntilIdle()
        val loads = link.candidateLoads
        vm.linkAccounts()
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertEquals(loads + 1, link.candidateLoads)
        assertEquals(CardRegisterStep.Accounts, vm.state.value.step)
        assertFalse(vm.state.value.linking)
        assertEquals(setOf("cand_sh"), vm.state.value.selected)
        assertEquals("계좌 목록이 오래됐어요 · 다시 불러왔어요", vm.state.value.message)

        vm.linkAccounts()
        passStepUp(vm, StepUpPurpose.ACCOUNT_LINK)
        assertNotEquals(link.linkKeys[0], link.linkKeys[1])
    }

    @Test fun `뒤로 — 단계 안에서 돌아가고 처음 입력에서만 경로를 떠난다`() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.back())                       // 입력 → 떠남
        toConfirm(vm)
        assertTrue(vm.back())                        // 확인 → 입력(토큰을 버림)
        assertEquals(CardRegisterStep.Input, vm.state.value.step)
        assertNull(vm.state.value.lookup)
    }

    @Test fun `다른 카드 등록 뒤 뒤로 가면 완료 화면`() = runTest(dispatcher) {
        val vm = viewModel()
        toConfirm(vm)
        vm.register()
        passStepUp(vm, StepUpPurpose.CARD_REGISTER)
        vm.anotherCard()
        assertEquals(CardRegisterStep.Input, vm.state.value.step)
        vm.onNumber("1234")
        assertTrue(vm.back())
        assertEquals(CardRegisterStep.Done, vm.state.value.step)
        assertEquals("", vm.state.value.number)
        assertFalse(vm.back())                       // 완료 → 떠남
    }

    private class StubLink : LinkRepository {
        var lookupResult: ApiResult<CardLookup> = ApiResult.Success(LOOKUP)
        var registerResult: ApiResult<RegisteredCard> = ApiResult.Success(REGISTERED)
        var linkResult: ApiResult<AccountLinkResult> = ApiResult.Success(AccountLinkResult(emptyList()))
        var lookups = 0
        var registers = 0
        var candidateLoads = 0
        var lastLookup: Triple<String, String, String>? = null
        var lastRegister: Triple<String, String?, String>? = null
        val linkKeys = mutableListOf<String>()

        override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup> {
            lookups++
            lastLookup = Triple(cardNumber, expiryMonth, expiryYear)
            return lookupResult
        }

        override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard> {
            registers++
            lastRegister = Triple(verificationToken, nickname, stepUpToken)
            return registerResult
        }

        override suspend fun accountCandidates(): ApiResult<List<AccountCandidate>> {
            candidateLoads++
            return ApiResult.Success(
                listOf(
                    AccountCandidate("cand_sh", brandOf("신한은행"), "주거래", "5678", alreadyLinked = false, bankCode = "088"),
                    AccountCandidate("cand_kb", brandOf("국민은행"), "입출금", "1234", alreadyLinked = false, bankCode = "004"),
                ),
            )
        }

        override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult> {
            linkKeys += idempotencyKey
            return linkResult
        }

        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> = ApiResult.Success(LinkedOverview(emptyList(), emptyList(), null))
    }

    private companion object {
        const val NUMBER = "9410123456785678"
        val LOOKUP = CardLookup(
            verificationToken = "cvt_1",
            brand = brandOf("신한카드"),
            productName = "Mr.Life",
            isCheck = false,
            maskedNumber = "9410-****-****-5678",
            last4 = "5678",
            paymentDay = 14,
            hasPriorHistory = true,
            holderMatched = true,
            alreadyRegistered = false,
            issuerAccount = IssuerAccount("088", "5678", linked = true),
        )
        val REGISTERED = RegisteredCard(21, brandOf("신한카드"), "Mr.Life", false, "5678", 14, null)

        fun unusedAuth(): AuthRepository = Proxy.newProxyInstance(
            AuthRepository::class.java.classLoader,
            arrayOf(AuthRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as AuthRepository
    }
}
