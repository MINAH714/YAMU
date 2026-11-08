package com.yamu.app.feature.payment

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.PinPadResult
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpResults
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CodeState
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import com.yamu.app.feature.payment.data.Recommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 결제 승인 대기 판이 걸린 규칙 — 승인 뒤 화면이 바뀔 때까지 판을 붙여 두고, 판이 떠 있는 동안 결제 · 거절을 다시 부르지 않는다.
 * 뷰모델에 1초 시계 · 결제 코드 확인이 계속 돌아서 advanceUntilIdle 대신 시간을 정해 넘기고, 끝나면 viewModelScope를 닫는다.
 */
class PayCodeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = StepUpResults()
    private val payments = StubPayments()
    private val auth = object : AuthRepository by unusedAuth() {
        override suspend fun biometricReady() = false
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** 인증 → 코드 발급 → 가맹점이 찍어 결제 요청 화면(P-06c)까지 */
    private fun TestScope.toRequest(): PayCodeViewModel {
        val vm = PayCodeViewModel(payments, auth, StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results))
        runCurrent()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Verified(StepUpPurpose.BARCODE, StepUpToken("su", StepUpPurpose.BARCODE)))
        runCurrent()
        vm.setVisible(true)
        runCurrent()
        assertTrue(vm.state.value.phase is PayPhase.Request)
        return vm
    }

    private fun TestScope.finish(vm: PayCodeViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test fun `승인되면 화면이 바뀔 때까지 판을 붙여 두고 navigated 뒤에도 남는다`() = runTest(dispatcher) {
        val vm = toRequest()
        vm.approve()
        assertTrue(vm.state.value.approving)
        assertTrue(vm.state.value.waiting)
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        val s = vm.state.value
        assertFalse(s.approving)
        assertTrue(s.approvedLeaving)
        assertEquals(PAYMENT_ID, s.paidId)
        vm.navigated()
        assertNull(vm.state.value.paidId)
        assertTrue("판은 화면이 스택에서 빠질 때까지", vm.state.value.approvedLeaving)
        finish(vm)
    }

    @Test fun `같은 프레임에 두 번 눌러도 결제는 한 번`() = runTest(dispatcher) {
        val vm = toRequest()
        vm.approve()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(1, payments.approves)
        finish(vm)
    }

    @Test fun `승인 뒤 화면을 떠나는 중에는 다시 결제하지 않는다`() = runTest(dispatcher) {
        val vm = toRequest()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        vm.navigated()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(1, payments.approves)
        assertNull("다시 화면을 넘기지 않음", vm.state.value.paidId)
        finish(vm)
    }

    @Test fun `승인을 기다리는 동안 거절은 나가지 않는다`() = runTest(dispatcher) {
        val vm = toRequest()
        vm.approve()
        vm.reject()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(0, payments.rejects)
        assertNull(vm.state.value.closeMessage)
        vm.navigated()
        vm.reject()   // 승인 뒤 떠나는 중에도
        runCurrent()
        assertEquals(0, payments.rejects)
        finish(vm)
    }

    @Test fun `거절 응답이면 판이 걷히고 다른 카드로 다시 결제할 수 있다`() = runTest(dispatcher) {
        payments.approveResult = ApiResult.Success(ApproveOutcome.Declined("잔액이 부족해요", retryable = true, remainingAttempts = 2, suggestedCardId = 2))
        val vm = toRequest()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        val s = vm.state.value
        assertFalse(s.waiting)
        assertFalse(s.approvedLeaving)
        assertEquals(2L, s.selectedCardId)
        payments.approveResult = null
        vm.approve()
        assertTrue(vm.state.value.approving)
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(2, payments.approves)
        assertTrue(vm.state.value.approvedLeaving)
        finish(vm)
    }

    @Test fun `결과 불명 같은 실패면 판이 걷히고 다시 누를 수 있다`() = runTest(dispatcher) {
        payments.approveResult = ApiResult.Failure(ApiError.Network)
        val vm = toRequest()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        val s = vm.state.value
        assertFalse(s.waiting)
        assertTrue(s.declined!!.endsWith("다시 눌러 주세요"))
        payments.approveResult = null
        vm.approve()
        assertTrue(vm.state.value.approving)
        finish(vm)
    }

    @Test fun `승인 중 마감이 지나면 판이 걷히고 만료로`() = runTest(dispatcher) {
        payments.approveResult = ApiResult.Failure(ApiError.Unknown("PAYMENT_EXPIRED", 410))
        val vm = toRequest()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        val s = vm.state.value
        assertFalse(s.waiting)
        assertTrue(s.requestExpired)
        vm.approve()
        runCurrent()
        assertEquals("만료 뒤에는 다시 부르지 않음", 1, payments.approves)
        finish(vm)
    }

    @Test fun `1초 시계보다 먼저 마감이 지났으면 판을 띄우지 않고 만료로`() = runTest(dispatcher) {
        payments.request = request(OffsetDateTime.now().minusSeconds(1))
        val vm = toRequest()
        vm.approve()
        runCurrent()
        assertFalse(vm.state.value.waiting)
        assertTrue(vm.state.value.requestExpired)
        assertEquals(0, payments.approves)
        finish(vm)
    }

    @Test fun `거절 응답 뒤에도 걷히는 판의 카드는 맡긴 카드다`() = runTest(dispatcher) {
        payments.approveResult = ApiResult.Success(ApproveOutcome.Declined("잔액이 부족해요", retryable = true, remainingAttempts = 2, suggestedCardId = 2))
        val vm = toRequest()
        vm.approve()
        assertEquals(1L, vm.state.value.approvingCardId)
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(2L, vm.state.value.selectedCardId)
        assertEquals(1L, vm.state.value.approvingCardId)
        finish(vm)
    }

    private class StubPayments : PaymentRepository {
        var approves = 0
        var rejects = 0
        /** null이면 승인 */
        var approveResult: ApiResult<ApproveOutcome>? = null

        override suspend fun issueCode(stepUpToken: String) =
            ApiResult.Success(PayCode("tok", "barcode", "qr", OffsetDateTime.now().plusMinutes(3).toString()))

        /** 마감은 테스트마다 정한다. 기본은 한 시간 뒤라 느린 실행에도 만료되지 않음 */
        var request = request(OffsetDateTime.now().plusHours(1))

        override suspend fun codeStatus(token: String) = ApiResult.Success(PayCodeStatus(CodeState.SCANNED, request))

        override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> {
            approves++
            delay(APPROVE_MS)
            return approveResult ?: ApiResult.Success(ApproveOutcome.Approved(receipt(card)))
        }

        override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> {
            rejects++
            return ApiResult.Success(Unit)
        }

        override suspend fun merchants(): ApiResult<List<Merchant>> = error("안 씀")
        override suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation> = error("안 씀")
        override fun recentReceipt(paymentId: Long): PaymentReceipt? = null
        override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> = error("안 씀")
    }

    private companion object {
        const val PAYMENT_ID = 77L
        const val APPROVE_MS = 900L
        const val AMOUNT = 15_000L

        fun card(id: Long, check: Boolean) = PayCard(
            userCardId = id, brand = brandOf("신한카드"), name = "카드$id", last4 = "000$id", isCheck = check,
            netBenefit = BigDecimal.ZERO, benefitName = null, chargedAmount = null, installmentOptions = emptyList(),
            reason = null, performanceNote = null,
        )

        fun request(deadline: OffsetDateTime) = PaymentRequest(
            PAYMENT_ID, "스타벅스 역삼점", "카페", AMOUNT, deadline.toString(),
            listOf(card(1, check = true), card(2, check = false)),
        )

        fun receipt(card: PayCard) = PaymentReceipt(
            paymentId = PAYMENT_ID, merchantName = "스타벅스 역삼점", categoryName = "카페", amount = AMOUNT,
            paidAtIso = null, cardId = card.userCardId, cardBrand = card.brand, cardName = card.name, cardLast4 = card.last4,
            isCheck = card.isCheck, approvalNo = null, installmentMonths = null, benefits = emptyList(), chargedAmount = null,
            balanceAfter = null, billDateIso = null, billAmount = null, perfMonth = null, perfRecognized = null, perfCounted = true,
            perfTotalAfter = null, perfTarget = null,
        )

        fun unusedAuth(): AuthRepository = Proxy.newProxyInstance(
            AuthRepository::class.java.classLoader,
            arrayOf(AuthRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as AuthRepository
    }
}
