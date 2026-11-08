package com.yamu.app.feature.payment

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.KnownMemo
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import com.yamu.app.feature.payment.data.Recommendation
import com.yamu.app.feature.payment.data.RefundLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P-08 취소 · 메모가 깨지기 쉬운 곳 — 연타, 결과를 모르는 실패 뒤 다시 시도(같은 키), 금융망 실패, 이미 취소, 잘못된 메모.
 */
class PaymentDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubRepo()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open(saved: SavedStateHandle = SavedStateHandle(mapOf("paymentId" to ID))): PaymentDetailViewModel =
        PaymentDetailViewModel(saved, repo).also { advanceUntilIdle() }

    // ── 취소 ──

    @Test fun `연타해도 취소 요청은 한 번 — 처리 중이면 무시`() = runTest(dispatcher) {
        val vm = open()
        vm.cancel()
        vm.cancel()
        runCurrent()
        vm.cancel()
        assertTrue(vm.state.value.canceling)
        advanceUntilIdle()
        assertEquals(1, repo.cancelKeys.size)
        assertTrue(vm.state.value.receipt!!.canceled)
        assertFalse(vm.state.value.canceling)
        vm.cancel()   // 이미 취소됨 — 버튼이 없어도 다시 부르지 않는다
        advanceUntilIdle()
        assertEquals(1, repo.cancelKeys.size)
    }

    @Test fun `성공하면 취소 상태 · 잔액 · 스낵바, 그리고 서버 값으로 다시 불러온다`() = runTest(dispatcher) {
        repo.receipt = receipt(check = true)
        repo.cancelResult = ApiResult.Success(CancelResult(ID, "2026-11-08T07:00:00Z", 0, emptyList(), balanceAfter = 965_000))
        val vm = open()
        val loadsBefore = repo.detailCalls
        vm.cancel()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(965_000L, s.balanceAfter)
        assertEquals("결제를 취소했어요 · 계좌 잔액 965,000원", s.message)
        assertEquals(loadsBefore + 1, repo.detailCalls)
        assertEquals(PayStatus.CANCELED, s.receipt!!.status)
    }

    @Test fun `응답이 끊기면 상태를 다시 보고, 다시 누르면 같은 키로 보낸다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Network)
        val vm = open()
        val loadsBefore = repo.detailCalls
        vm.cancel()
        advanceUntilIdle()
        assertEquals("취소됐는지 확인하지 못했어요. 결제 상태를 다시 불러올게요", vm.state.value.message)
        assertEquals(loadsBefore + 1, repo.detailCalls)   // 취소됐을 수 있다 — 상태로 판단
        repo.cancelResult = ApiResult.Success(CancelResult(ID, null, 1, emptyList(), null))
        vm.cancel()
        advanceUntilIdle()
        assertEquals(2, repo.cancelKeys.size)
        assertEquals(repo.cancelKeys[0], repo.cancelKeys[1])
        assertEquals("결제를 취소했어요 · 청구에서 빠져요", vm.state.value.message)
    }

    @Test fun `화면이 다시 만들어져도 같은 키 — SavedStateHandle`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Network)
        val saved = SavedStateHandle(mapOf("paymentId" to ID))
        open(saved).cancel()
        advanceUntilIdle()
        val restored = SavedStateHandle(mapOf("paymentId" to ID, PaymentDetailViewModel.CANCEL_KEY to saved.get<String>(PaymentDetailViewModel.CANCEL_KEY)))
        open(restored).cancel()
        advanceUntilIdle()
        assertEquals(2, repo.cancelKeys.size)
        assertEquals(repo.cancelKeys[0], repo.cancelKeys[1])
    }

    @Test fun `은행이 거절(FIN_API_ERROR) — 결제는 그대로, 다시 불러오지 않는다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.BankUnavailable("FIN_API_ERROR"))
        val vm = open()
        val loadsBefore = repo.detailCalls
        vm.cancel()
        advanceUntilIdle()
        assertEquals("은행이 처리하지 못해 취소하지 못했어요. 결제는 그대로예요", vm.state.value.message)
        assertEquals(loadsBefore, repo.detailCalls)
        assertTrue(vm.state.value.receipt!!.cancelable)   // 다시 누를 수 있다
    }

    @Test fun `체크카드 입금 결과 불명(FIN_API_UNAVAILABLE) — 단정하지 않고 상태를 다시 읽고, 다시 누르면 같은 키`() = runTest(dispatcher) {
        repo.receipt = receipt(check = true)
        repo.cancelResult = ApiResult.Failure(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
        val vm = open()
        val loadsBefore = repo.detailCalls
        vm.cancel()
        advanceUntilIdle()
        assertEquals("은행 응답을 확인하지 못했어요. 잠시 뒤 다시 취소해 주세요", vm.state.value.message)
        assertEquals(loadsBefore + 1, repo.detailCalls)
        repo.cancelResult = ApiResult.Success(CancelResult(ID, null, 0, emptyList(), balanceAfter = 965_000))
        vm.cancel()
        advanceUntilIdle()
        assertEquals(repo.cancelKeys[0], repo.cancelKeys[1])   // BE가 거래내역 태그로 찾아 두 번 입금하지 않는다
    }

    @Test fun `다른 기기에서 먼저 취소 — 409 ALREADY_CANCELED면 다시 불러 취소 상태를 보인다`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Conflict("ALREADY_CANCELED"))
        val vm = open()
        repo.receipt = repo.receipt.copy(status = PayStatus.CANCELED)
        vm.cancel()
        advanceUntilIdle()
        assertEquals("이미 취소된 결제예요", vm.state.value.message)
        assertTrue(vm.state.value.receipt!!.canceled)
    }

    @Test fun `입금 계좌가 없어 취소 불가 — 사유 문장`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Failure(ApiError.Conflict("PAYMENT_NOT_CANCELABLE", buildJsonObject { put("reason", "ACCOUNT_UNAVAILABLE") }))
        val vm = open()
        vm.cancel()
        advanceUntilIdle()
        assertEquals("돌려받을 계좌가 연결돼 있지 않아 취소할 수 없어요", vm.state.value.message)
    }

    @Test fun `가입 전 거래 · 거절된 결제는 보내지 않는다`() = runTest(dispatcher) {
        repo.receipt = receipt().copy(isSeed = true)
        open().cancel()
        repo.receipt = receipt().copy(status = PayStatus.DECLINED)
        open().cancel()
        advanceUntilIdle()
        assertTrue(repo.cancelKeys.isEmpty())
    }

    @Test fun `상세를 못 불러왔으면 취소를 보내지 않는다`() = runTest(dispatcher) {
        repo.detailResult = ApiResult.Failure(ApiError.Network)
        val vm = open()
        vm.cancel()
        advanceUntilIdle()
        assertNull(vm.state.value.receipt)
        assertTrue(repo.cancelKeys.isEmpty())
    }

    @Test fun `신용카드 출금 뒤 취소 — 돌려받을 금액을 스낵바로`() = runTest(dispatcher) {
        repo.cancelResult = ApiResult.Success(CancelResult(ID, null, 0, listOf(RefundLine(30_000, "2026-12-05", false), RefundLine(30_000, null, false)), null))
        val vm = open()
        vm.cancel()
        advanceUntilIdle()
        assertEquals("결제를 취소했어요 · 60,000원을 돌려드려요", vm.state.value.message)
    }

    // ── 메모 ──

    @Test fun `메모 저장 — 정리한 한 줄을 보내고 상세에 바로 보인다`() = runTest(dispatcher) {
        val vm = open()
        vm.openMemo()
        vm.saveMemo("  팀 회식\n1/N ")
        advanceUntilIdle()
        assertEquals(listOf<String?>("팀 회식 1/N"), repo.memos)
        assertEquals("팀 회식 1/N", vm.state.value.receipt!!.memo)
        assertFalse(vm.state.value.memoOpen)
        assertEquals("메모를 남겼어요", vm.state.value.message)
    }

    @Test fun `공백뿐이면 지우기(null)로 보낸다`() = runTest(dispatcher) {
        repo.receipt = receipt().copy(memo = "옛 메모")
        val vm = open()
        vm.saveMemo("   ")
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), repo.memos)
        assertNull(vm.state.value.receipt!!.memo)
        assertEquals("메모를 지웠어요", vm.state.value.message)
    }

    @Test fun `101자 · 제어문자는 서버에 보내지 않고 칸 아래 오류`() = runTest(dispatcher) {
        val vm = open()
        vm.openMemo()
        vm.saveMemo("가".repeat(101))
        assertEquals("메모는 100자까지 적을 수 있어요", vm.state.value.memoError)
        vm.saveMemo("메모\u0000")
        assertEquals("쓸 수 없는 문자가 있어요", vm.state.value.memoError)
        advanceUntilIdle()
        assertTrue(repo.memos.isEmpty())
        assertTrue(vm.state.value.memoOpen)
        vm.memoEdited()
        assertNull(vm.state.value.memoError)
    }

    @Test fun `저장 버튼 연타 — 한 번만 보낸다`() = runTest(dispatcher) {
        val vm = open()
        vm.saveMemo("a")
        vm.saveMemo("a")
        advanceUntilIdle()
        assertEquals(1, repo.memos.size)
    }

    @Test fun `저장 실패면 시트를 열어 둔 채 이유를 보인다`() = runTest(dispatcher) {
        repo.memoResult = { ApiResult.Failure(ApiError.Network) }
        val vm = open()
        vm.openMemo()
        vm.saveMemo("a")
        advanceUntilIdle()
        assertTrue(vm.state.value.memoOpen)
        assertEquals("인터넷 연결을 확인해 주세요", vm.state.value.memoError)
        assertFalse(vm.state.value.savingMemo)
    }

    @Test fun `취소된 결제에도 메모를 남길 수 있다`() = runTest(dispatcher) {
        repo.receipt = receipt().copy(status = PayStatus.CANCELED)
        val vm = open()
        vm.saveMemo("환불 확인")
        advanceUntilIdle()
        assertEquals("환불 확인", vm.state.value.receipt!!.memo)
        assertNotNull(vm.state.value.message)
    }

    // ── 메모를 모를 때(홈 · 알림 · 결제 완료에서 바로 들어옴, 앱 다시 켬) ──

    @Test fun `모르면 결제 내역에서 한 번 찾아 채운다 — 찾는 동안은 시트를 열지 않는다`() = runTest(dispatcher) {
        repo.receipt = receipt().copy(memoKnown = false)
        repo.found = KnownMemo("팀 회식")
        val vm = PaymentDetailViewModel(SavedStateHandle(mapOf("paymentId" to ID)), repo)
        advanceTimeBy(150)   // 상세는 받았고 찾는 중
        assertTrue(vm.state.value.memoChecking)
        vm.openMemo()
        assertFalse(vm.state.value.memoOpen)
        advanceUntilIdle()
        assertEquals("팀 회식", vm.state.value.receipt!!.memo)
        assertTrue(vm.state.value.receipt!!.memoKnown)
        assertFalse(vm.state.value.memoChecking)
        vm.load()
        advanceUntilIdle()
        assertEquals(1, repo.findCalls)                       // 화면마다 한 번
        assertEquals("팀 회식", vm.state.value.receipt!!.memo)   // 다시 불러와도 '모름'으로 되돌리지 않는다
    }

    @Test fun `못 찾으면 '모름'으로 두고 지우기는 막는다 — 새로 적으면 저장되고 그때부터 안다`() = runTest(dispatcher) {
        repo.receipt = receipt().copy(memoKnown = false)
        val vm = open()
        assertFalse(vm.state.value.receipt!!.memoKnown)
        vm.openMemo()
        vm.saveMemo("  ")
        assertEquals("전에 남긴 메모를 불러오지 못해 지울 수 없어요", vm.state.value.memoError)
        advanceUntilIdle()
        assertTrue(repo.memos.isEmpty())
        vm.saveMemo("새 메모")
        advanceUntilIdle()
        assertEquals(listOf<String?>("새 메모"), repo.memos)
        assertTrue(vm.state.value.receipt!!.memoKnown)
    }

    @Test fun `찾는 사이 저장했으면 찾은 값으로 덮지 않는다`() = runTest(dispatcher) {
        repo.receipt = receipt().copy(memoKnown = false)
        repo.found = KnownMemo("옛 메모")
        repo.findDelay = 2_000
        val vm = PaymentDetailViewModel(SavedStateHandle(mapOf("paymentId" to ID)), repo)
        advanceTimeBy(150)
        vm.saveMemo("새 메모")
        advanceUntilIdle()
        assertEquals("새 메모", vm.state.value.receipt!!.memo)
    }

    private class StubRepo : PaymentRepository {
        var found: KnownMemo? = null
        var findCalls = 0
        var findDelay = 500L
        var receipt = receipt()
        var detailResult: ApiResult<PaymentReceipt>? = null
        var detailCalls = 0
        val cancelKeys = mutableListOf<String>()
        var cancelResult: ApiResult<CancelResult> = ApiResult.Success(CancelResult(ID, "2026-11-08T07:00:00Z", 1, emptyList(), null))
        val memos = mutableListOf<String?>()
        var memoResult: (String?) -> ApiResult<String?> = { ApiResult.Success(it) }

        override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> {
            detailCalls++
            delay(100)
            return detailResult ?: ApiResult.Success(receipt)
        }

        override suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult> {
            cancelKeys += idempotencyKey
            delay(900)
            return cancelResult.also { if (it is ApiResult.Success) receipt = receipt.copy(status = PayStatus.CANCELED) }
        }

        override suspend fun saveMemo(paymentId: Long, memo: String?): ApiResult<String?> {
            memos += memo
            delay(300)
            return memoResult(memo).also { if (it is ApiResult.Success) receipt = receipt.copy(memo = it.data, memoKnown = true) }
        }

        override suspend fun findMemo(paymentId: Long, paidAtIso: String?): KnownMemo? {
            findCalls++
            delay(findDelay)
            return found
        }

        override suspend fun merchants(): ApiResult<List<Merchant>> = error("안 씀")
        override suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation> = error("안 씀")
        override suspend fun issueCode(stepUpToken: String): ApiResult<PayCode> = error("안 씀")
        override suspend fun codeStatus(token: String): ApiResult<PayCodeStatus> = error("안 씀")
        override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> = error("안 씀")
        override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> = error("안 씀")
        override fun recentReceipt(paymentId: Long): PaymentReceipt? = null
    }

    private companion object {
        const val ID = 31L

        fun receipt(check: Boolean = false) = PaymentReceipt(
            paymentId = ID, merchantName = "야무마트 강남점", categoryName = "마트", amount = 90_000,
            paidAtIso = "2026-11-01T03:00:00Z", cardId = 15, cardBrand = brandOf("신한카드"), cardName = "Mr.Life", cardLast4 = "5096",
            isCheck = check, approvalNo = "Y1", installmentMonths = null, benefits = emptyList(), chargedAmount = 90_000,
            balanceAfter = null, billDateIso = null, billAmount = 90_000, perfMonth = 11, perfRecognized = 90_000, perfCounted = true,
            perfTotalAfter = null, perfTarget = null,
        )
    }
}
