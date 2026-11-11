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
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.CodeState
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
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
    private val link = StubLink()
    private val auth = object : AuthRepository by unusedAuth() {
        override suspend fun biometricReady() = false
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** 인증 → 코드 발급 → 가맹점이 찍어 결제 요청 화면(P-06c)까지 */
    private fun TestScope.toRequest(): PayCodeViewModel {
        val vm = PayCodeViewModel(payments, auth, StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results), link)
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

    // ── 혜택을 비교하지 못한 카드(추천 uncomparedCards) ──

    @Test fun `데이터 부족이면 미리 고르지 않고 결제 가능 여부를 모르는 카드는 고를 수 없다`() = runTest(dispatcher) {
        payments.request = request(OffsetDateTime.now().plusHours(1), listOf(uncompared(3, direct = true), uncompared(4, direct = false)))
        val vm = toRequest()
        assertNull(vm.state.value.selectedCardId)
        vm.approve()
        runCurrent()
        assertFalse(vm.state.value.waiting)
        vm.selectCard(4)
        assertNull(vm.state.value.selectedCardId)
        vm.selectCard(3)
        assertEquals(3L, vm.state.value.selectedCardId)
        assertEquals(0, payments.approves)
        finish(vm)
    }

    @Test fun `비교 못 한 카드는 혜택 없음을 확인한 뒤에만 결제한다`() = runTest(dispatcher) {
        payments.request = request(OffsetDateTime.now().plusHours(1), listOf(uncompared(3, direct = true)))
        val vm = toRequest()
        vm.selectCard(3)
        vm.approve()
        runCurrent()
        assertTrue(vm.state.value.askNoBenefit)
        assertFalse(vm.state.value.waiting)
        vm.dismissNoBenefit()
        assertFalse(vm.state.value.askNoBenefit)
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals("닫으면 결제하지 않는다", 0, payments.approves)
        vm.approve()
        assertTrue("다시 누르면 또 묻는다", vm.state.value.askNoBenefit)
        vm.confirmNoBenefit()
        assertFalse(vm.state.value.askNoBenefit)
        assertTrue(vm.state.value.approving)
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(1, payments.approves)
        assertEquals(3L, payments.approvedCardId)
        finish(vm)
    }

    /** 정민아 님 리뷰 P2 — 추천 A 거절 → 비교 못 한 B로 다시 결제 → B도 거절이면 A로 되돌아가지 않는다(BE는 ALREADY_DECLINED) */
    @Test fun `거절된 카드는 모두 기억해 저절로 고르지도 다시 고르지도 않는다`() = runTest(dispatcher) {
        payments.request = request(OffsetDateTime.now().plusHours(1), listOf(card(1, check = false), uncompared(3, direct = true)))
        payments.approveResult = ApiResult.Success(ApproveOutcome.Declined("잔액이 부족해요", retryable = true, remainingAttempts = 2, suggestedCardId = 3))
        val vm = toRequest()
        assertEquals("1순위만 미리 고른다", 1L, vm.state.value.selectedCardId)
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertNull("서버가 권해도 비교 못 한 카드로 옮기지 않고, 거절된 1순위에 머물지도 않는다", vm.state.value.selectedCardId)
        vm.selectCard(1)
        assertNull("거절된 카드는 고를 수 없다", vm.state.value.selectedCardId)
        vm.selectCard(3)
        vm.approve()
        vm.confirmNoBenefit()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(2, payments.approves)
        assertEquals(setOf(1L, 3L), vm.state.value.declinedCardIds)
        assertNull("앞서 거절된 1로 되돌아가지 않는다", vm.state.value.selectedCardId)
        vm.approve()
        runCurrent()
        assertEquals(2, payments.approves)
        finish(vm)
    }

    @Test fun `후보가 연달아 거절되면 아직 시도하지 않은 후보로 옮긴다`() = runTest(dispatcher) {
        payments.request = request(OffsetDateTime.now().plusHours(1), listOf(card(1, check = false), card(2, check = false), card(4, check = false)))
        payments.approveResult = ApiResult.Success(ApproveOutcome.Declined("잔액이 부족해요", retryable = true, remainingAttempts = 2, suggestedCardId = null))
        val vm = toRequest()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(2L, vm.state.value.selectedCardId)
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals("1로 되돌아가지 않는다", 4L, vm.state.value.selectedCardId)
        finish(vm)
    }

    @Test fun `결과를 모르는 실패 뒤 같은 카드로 다시 누르면 혜택 없음을 다시 묻지 않는다`() = runTest(dispatcher) {
        payments.request = request(OffsetDateTime.now().plusHours(1), listOf(uncompared(3, direct = true)))
        payments.approveResult = ApiResult.Failure(ApiError.Network)
        val vm = toRequest()
        vm.selectCard(3)
        vm.approve()
        vm.confirmNoBenefit()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        payments.approveResult = null
        vm.approve()
        assertFalse("같은 결제에서 확인한 카드", vm.state.value.askNoBenefit)
        assertTrue(vm.state.value.approving)
        finish(vm)
    }

    /** 정민아 님 리뷰 P1 — 저장소가 출금 미확인을 retryable false로 넘기면 다른 카드로 옮기지 않고 요청을 닫는다 */
    @Test fun `다시 결제할 수 없는 거절이면 카드를 옮기지 않고 닫기만 남긴다`() = runTest(dispatcher) {
        payments.approveResult = ApiResult.Success(ApproveOutcome.Declined("결제 결과를 확인하지 못했어요", retryable = false, remainingAttempts = 2, suggestedCardId = null))
        val vm = toRequest()
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        val s = vm.state.value
        assertEquals(1L, s.selectedCardId)
        assertFalse(s.canRetry)
        assertTrue(s.requestDone)
        assertEquals("남은 횟수를 붙이지 않는다", "결제 결과를 확인하지 못했어요", s.declined)
        vm.selectCard(2)
        vm.approve()
        runCurrent()
        assertEquals(1, payments.approves)
        finish(vm)
    }

    /** Q-19 — 별명 카드는 응답만으로는 카드사 · 상품명을 몰라 카드 목록에서 붙인다 */
    private fun aliasRequest() = request(
        OffsetDateTime.now().plusHours(1),
        cards = listOf(card(1, check = false).copy(name = "점심 카드", brand = brandOf("점심 카드"), issuerUnknown = true), card(2, check = true)),
    )

    @Test fun `코드를 띄운 동안 받은 카드 목록으로 별명 카드에 카드사 · 상품명을 붙인다`() = runTest(dispatcher) {
        payments.request = aliasRequest()
        link.cardsResult = ApiResult.Success(listOf(listedToktok))
        val vm = toRequest()
        val card = vm.state.value.selectedCard!!
        assertEquals("점심 카드", card.name)
        assertEquals("KB국민카드", card.brand.name)
        assertEquals("KB국민 청춘대로 톡톡카드", card.imageName)
        assertEquals("목록에 없는 카드는 그대로", "신한카드", vm.state.value.request!!.cards[1].brand.name)
        assertEquals(1, link.cardsCalls)
        finish(vm)
    }

    @Test fun `카드 목록이 찍힌 뒤에 오면 그때 붙이고 고른 카드는 그대로`() = runTest(dispatcher) {
        payments.request = aliasRequest()
        link.cardsResult = ApiResult.Success(listOf(listedToktok))
        link.cardsDelayMs = 5_000
        val vm = toRequest()
        assertEquals("아직 응답 값", "점심 카드", vm.state.value.selectedCard!!.brand.name)
        vm.selectCard(2)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("KB국민카드", vm.state.value.request!!.cards[0].brand.name)
        assertEquals(2L, vm.state.value.selectedCardId)
        finish(vm)
    }

    @Test fun `카드 목록을 못 받아도 요청 화면은 응답 값으로 뜨고 결제할 수 있다`() = runTest(dispatcher) {
        payments.request = aliasRequest()
        link.cardsResult = ApiResult.Failure(ApiError.Network)
        val vm = toRequest()
        assertEquals("점심 카드", vm.state.value.selectedCard!!.brand.name)
        assertEquals("점심 카드", vm.state.value.selectedCard!!.imageName)
        vm.approve()
        advanceTimeBy(APPROVE_MS + 1)
        runCurrent()
        assertEquals(1, payments.approves)
        finish(vm)
    }

    private class StubLink : LinkRepository by unusedLink() {
        var cardsResult: ApiResult<List<LinkedCard>> = ApiResult.Success(emptyList())
        var cardsDelayMs = 0L
        var cardsCalls = 0

        override suspend fun cards(): ApiResult<List<LinkedCard>> {
            cardsCalls++
            delay(cardsDelayMs)
            return cardsResult
        }
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

        var approvedCardId: Long? = null

        override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> {
            approves++
            approvedCardId = card.userCardId
            delay(APPROVE_MS)
            return approveResult ?: ApiResult.Success(ApproveOutcome.Approved(receipt(card)))
        }

        override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> {
            rejects++
            return ApiResult.Success(Unit)
        }

        override fun recentReceipt(paymentId: Long): PaymentReceipt? = null
        override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> = error("안 씀")
        override suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult> = error("안 씀")
        override suspend fun saveMemo(paymentId: Long, memo: String?): ApiResult<String?> = error("안 씀")
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

        /** 비교 못 한 카드 — [direct]면 혜택 없이 바로 결제할 수 있고, 아니면 결제 가능 여부도 모른다 */
        fun uncompared(id: Long, direct: Boolean) = card(id, check = false).copy(benefitUnknown = true, selectable = direct)

        fun request(deadline: OffsetDateTime, cards: List<PayCard> = listOf(card(1, check = true), card(2, check = false))) = PaymentRequest(
            PAYMENT_ID, "스타벅스 역삼점", "카페", AMOUNT, deadline.toString(), cards,
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

        fun unusedLink(): LinkRepository = Proxy.newProxyInstance(
            LinkRepository::class.java.classLoader,
            arrayOf(LinkRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as LinkRepository

        /** 카드 목록의 1번 카드 — 별명 '점심 카드'를 지은 톡톡 */
        val listedToktok = LinkedCard(
            userCardId = 1, brand = brandOf("KB국민카드", color = "#FFBC00"), productName = "KB국민 청춘대로 톡톡카드", isCheck = false,
            last4 = "0001", paymentDay = 14, recognizedAmount = null, yearMonth = null, nickname = "점심 카드",
        )
    }
}
