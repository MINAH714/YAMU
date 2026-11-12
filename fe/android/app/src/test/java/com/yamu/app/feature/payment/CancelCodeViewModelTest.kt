package com.yamu.app.feature.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import com.yamu.app.feature.payment.data.RefundLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * 결제 상세 '결제 취소' → 결제 코드 화면이 매장 POS의 취소를 알아채는 규칙 — 보이는 동안만 몇 초마다 상세를 보고,
 * 취소되면 더 보지 않고, 실패하면 다음 차례에 다시 본다. 지켜보기 루프가 계속 돌아서 시간을 정해 넘기고 끝나면 viewModelScope를 닫는다.
 * 임시 앱 내 취소(-424 · -425 머지 전까지)는 예전 결제 상세 취소와 같은 곳을 본다 — 연타, 결과를 모르는 실패 뒤 같은 키, 은행 거절, 이미 취소
 */
class CancelCodeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubRepo()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open(saved: SavedStateHandle = SavedStateHandle(mapOf("paymentId" to ID))): CancelCodeViewModel =
        CancelCodeViewModel(saved, repo).also { advanceTimeBy(DETAIL_MS + 1) }

    private fun TestScope.finish(vm: CancelCodeViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test fun `안내에는 가맹점과 실제 결제 금액(즉시할인 뒤)`() = runTest(dispatcher) {
        val vm = open()
        assertEquals(CancelTarget("GS25 역삼점", 5_940), vm.target.value)
        assertEquals("GS25 역삼점 5,940원 결제를 취소하려면 매장에 이 코드를 보여 주세요", cancelHint(vm.target.value))
        finish(vm)
    }

    @Test fun `결제를 아직 못 받았으면 가맹점 · 금액 없이`() {
        assertEquals("결제를 취소하려면 매장에 이 코드를 보여 주세요", cancelHint(null))
        assertEquals("5,940원 결제를 취소하려면 매장에 이 코드를 보여 주세요", cancelHint(CancelTarget(null, 5_940)))
    }

    @Test fun `보이는 동안 몇 초마다 보고 취소되면 알린 뒤 더 받지 않는다`() = runTest(dispatcher) {
        val vm = open()
        vm.setVisible(true)
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS + DETAIL_MS + 1)
        assertEquals(2, repo.detailCalls)
        assertFalse(vm.canceled.value)
        repo.receipt = repo.receipt.copy(status = PayStatus.CANCELED)   // 매장 POS가 취소함
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS + DETAIL_MS + 1)
        assertTrue(vm.canceled.value)
        val calls = repo.detailCalls
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS * 5)
        vm.checkNow()
        runCurrent()
        assertEquals(calls, repo.detailCalls)
        finish(vm)
    }

    @Test fun `안 보이면(인증 화면 · 앱 내림) 받지 않는다`() = runTest(dispatcher) {
        val vm = open()
        vm.setVisible(true)
        vm.setVisible(false)
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS * 5)
        assertEquals(1, repo.detailCalls)   // 처음 안내용 한 번뿐
        finish(vm)
    }

    @Test fun `코드가 쓰이면 바로 보고, 지켜보기와 겹치면 한 번만 보낸다`() = runTest(dispatcher) {
        val vm = open()
        vm.setVisible(true)
        repo.receipt = repo.receipt.copy(status = PayStatus.CANCELED)
        vm.checkNow()
        vm.checkNow()
        advanceTimeBy(DETAIL_MS + 1)
        assertTrue(vm.canceled.value)
        assertEquals(2, repo.detailCalls)
        finish(vm)
    }

    @Test fun `받기에 실패하면 다음 차례에 다시 본다`() = runTest(dispatcher) {
        repo.result = ApiResult.Failure(ApiError.Network)
        val vm = open()
        assertNull(vm.target.value)
        vm.setVisible(true)
        repo.result = null
        repo.receipt = repo.receipt.copy(status = PayStatus.CANCELED)
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS + DETAIL_MS + 1)
        assertTrue(vm.canceled.value)
        finish(vm)
    }

    // ── 임시 앱 내 취소 ──

    @Test fun `앱에서 바로 취소 — 연타해도 한 번, 성공하면 돌려받을 금액 문장으로 닫는다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Success(CancelResult(ID, "2026-11-11T05:00:00Z", 0, listOf(RefundLine(5_940, "2026-12-05", false)), null))
        val vm = open()
        vm.cancelInApp()
        vm.cancelInApp()
        runCurrent()
        vm.cancelInApp()
        assertTrue(vm.canceling.value)
        advanceUntilIdle()
        assertEquals(1, repo.cancelKeys.size)
        assertTrue(vm.canceled.value)
        assertFalse(vm.canceling.value)
        assertEquals("결제를 취소했어요 · 5,940원을 돌려드려요", vm.closeMessage.value)
        vm.cancelInApp()   // 이미 취소됨 — 버튼이 없어도 다시 보내지 않는다
        advanceUntilIdle()
        assertEquals(1, repo.cancelKeys.size)
    }

    @Test fun `체크카드는 입금 뒤 잔액으로 닫고, 매장이 취소했으면 기본 문장`() = runTest(dispatcher) {
        repo.receipt = repo.receipt.copy(isCheck = true)
        repo.cancelResult = ApiResult.Success(CancelResult(ID, null, 0, emptyList(), balanceAfter = 965_000))
        val vm = open()
        assertEquals(CancelCodeViewModel.POS_CANCELED_MESSAGE, vm.closeMessage.value)
        vm.cancelInApp()
        advanceUntilIdle()
        assertEquals("결제를 취소했어요 · 계좌 잔액 965,000원", vm.closeMessage.value)
    }

    @Test fun `응답이 끊기면 상태를 다시 보고, 다시 누르면 같은 키로 보낸다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Network)
        val vm = open()
        val loadsBefore = repo.detailCalls
        vm.cancelInApp()
        advanceUntilIdle()
        assertEquals("취소됐는지 확인하지 못했어요. 결제 상태를 다시 불러올게요", vm.message.value)
        assertEquals(loadsBefore + 1, repo.detailCalls)   // 취소됐을 수 있다 — 상태로 판단
        assertFalse(vm.canceled.value)
        repo.cancelResult = ApiResult.Success(CancelResult(ID, null, 1, emptyList(), null))
        vm.cancelInApp()
        advanceUntilIdle()
        assertEquals(2, repo.cancelKeys.size)
        assertEquals(repo.cancelKeys[0], repo.cancelKeys[1])
        assertEquals("결제를 취소했어요 · 청구에서 빠져요", vm.closeMessage.value)
    }

    @Test fun `화면이 다시 만들어져도 같은 키 — SavedStateHandle`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Network)
        val saved = SavedStateHandle(mapOf("paymentId" to ID))
        open(saved).cancelInApp()
        advanceUntilIdle()
        val restored = SavedStateHandle(mapOf("paymentId" to ID, CancelCodeViewModel.CANCEL_KEY to saved.get<String>(CancelCodeViewModel.CANCEL_KEY)))
        open(restored).cancelInApp()
        advanceUntilIdle()
        assertEquals(2, repo.cancelKeys.size)
        assertEquals(repo.cancelKeys[0], repo.cancelKeys[1])
    }

    @Test fun `은행이 거절(FIN_API_ERROR) — 결제는 그대로, 다시 받지 않고 다시 누를 수 있다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.BankUnavailable("FIN_API_ERROR"))
        val vm = open()
        val loadsBefore = repo.detailCalls
        vm.cancelInApp()
        advanceUntilIdle()
        assertEquals("은행이 처리하지 못해 취소하지 못했어요. 결제는 그대로예요", vm.message.value)
        assertEquals(loadsBefore, repo.detailCalls)
        assertFalse(vm.canceled.value)
        assertFalse(vm.canceling.value)
        vm.messageShown()
        assertNull(vm.message.value)
        vm.cancelInApp()
        advanceUntilIdle()
        assertEquals(2, repo.cancelKeys.size)
    }

    @Test fun `다른 곳에서 먼저 취소 — ALREADY_CANCELED면 다시 받아 취소로 닫는다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Conflict("ALREADY_CANCELED"))
        val vm = open()
        repo.receipt = repo.receipt.copy(status = PayStatus.CANCELED)
        vm.cancelInApp()
        advanceUntilIdle()
        assertEquals("이미 취소된 결제예요", vm.message.value)
        assertTrue(vm.canceled.value)
        assertEquals(CancelCodeViewModel.POS_CANCELED_MESSAGE, vm.closeMessage.value)
    }

    @Test fun `상세를 못 받았거나 가입 전 거래면 보내지 않는다`() = runTest(dispatcher) {
        repo.result = ApiResult.Failure(ApiError.Network)
        open().cancelInApp()
        repo.result = null
        repo.receipt = repo.receipt.copy(isSeed = true)
        val seed = open()
        assertFalse(seed.receipt.value!!.cancelable)
        seed.cancelInApp()
        advanceUntilIdle()
        assertTrue(repo.cancelKeys.isEmpty())
    }

    @Test fun `앱 내 취소를 보내는 동안은 지켜보기가 상세를 받지 않는다`() = runTest(dispatcher) {
        repo.cancelDelayMs = CancelCodeViewModel.WATCH_INTERVAL_MS * 2 - 500
        repo.cancelResult = ApiResult.Success(CancelResult(ID, null, 1, emptyList(), null))
        val vm = open()
        vm.setVisible(true)
        vm.cancelInApp()
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS + DETAIL_MS + 1)
        assertEquals(1, repo.detailCalls)   // 처음 안내용 한 번뿐
        advanceTimeBy(CancelCodeViewModel.WATCH_INTERVAL_MS * 2)
        assertTrue(vm.canceled.value)
        assertEquals(1, repo.detailCalls)
        assertEquals("결제를 취소했어요 · 청구에서 빠져요", vm.closeMessage.value)
        finish(vm)
    }

    private class StubRepo : PaymentRepository {
        var receipt = PaymentReceipt(
            paymentId = ID, merchantName = "GS25 역삼점", categoryName = "편의점", amount = 6_600,
            paidAtIso = "2026-11-10T07:50:00Z", cardId = 15, cardBrand = null, cardName = "Mr.Life", cardLast4 = "5096",
            isCheck = false, approvalNo = "Y1", installmentMonths = null, benefits = emptyList(), chargedAmount = 5_940,
            balanceAfter = null, billDateIso = null, billAmount = 5_940, perfMonth = 11, perfRecognized = 5_940, perfCounted = true,
            perfTotalAfter = null, perfTarget = null,
        )
        var result: ApiResult<PaymentReceipt>? = null
        var detailCalls = 0
        val cancelKeys = mutableListOf<String>()
        var cancelResult: ApiResult<CancelResult> = ApiResult.Success(CancelResult(ID, "2026-11-11T05:00:00Z", 1, emptyList(), null))
        var cancelDelayMs = 900L

        override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> {
            detailCalls++
            delay(DETAIL_MS)
            return result ?: ApiResult.Success(receipt)
        }

        override suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult> {
            cancelKeys += idempotencyKey
            delay(cancelDelayMs)
            return cancelResult.also { if (it is ApiResult.Success) receipt = receipt.copy(status = PayStatus.CANCELED) }
        }
        override suspend fun saveMemo(paymentId: Long, memo: String?): ApiResult<String?> = error("안 씀")
        override suspend fun issueCode(stepUpToken: String): ApiResult<PayCode> = error("안 씀")
        override suspend fun codeStatus(token: String): ApiResult<PayCodeStatus> = error("안 씀")
        override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> = error("안 씀")
        override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> = error("안 씀")
        override fun recentReceipt(paymentId: Long): PaymentReceipt? = null
    }

    private companion object {
        const val ID = 77L
        const val DETAIL_MS = 100L
    }
}
