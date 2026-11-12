package com.yamu.app.feature.payment

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.CardsOnlyLink
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.KnownMemo
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P-08이 깨지기 쉬운 곳 — 다른 곳(매장 POS)에서 한 취소를 돌아와서 알아채기, 잘못된 메모, 모르는 메모 덮어쓰기.
 */
class PaymentDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubRepo()
    private val link = CardsOnlyLink()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open(saved: SavedStateHandle = SavedStateHandle(mapOf("paymentId" to ID))): PaymentDetailViewModel =
        PaymentDetailViewModel(saved, repo, link).also { advanceUntilIdle() }

    // ── 다시 보일 때(결제 코드 화면에서 돌아옴) — 취소는 매장 POS가 하므로 상세가 서버에서 다시 받아 알아챈다 ──

    @Test fun `처음 보일 때는 다시 받지 않고 돌아오면 다시 받아 다른 곳에서 한 취소를 보인다`() = runTest(dispatcher) {
        val vm = open()
        vm.resumed()   // 첫 화면 — init에서 이미 받았다
        advanceUntilIdle()
        assertEquals(1, repo.detailCalls)
        repo.receipt = repo.receipt.copy(status = PayStatus.CANCELED, canceledAtIso = "2026-11-11T01:00:00Z")
        vm.resumed()
        advanceUntilIdle()
        assertEquals(2, repo.detailCalls)
        assertTrue(vm.state.value.receipt!!.canceled)
        assertFalse(vm.state.value.receipt!!.cancelable)   // '결제 취소' 버튼이 사라진다
    }

    @Test fun `받는 중에 다시 보이면 겹쳐 보내지 않는다`() = runTest(dispatcher) {
        val vm = PaymentDetailViewModel(SavedStateHandle(mapOf("paymentId" to ID)), repo, link)
        vm.resumed()
        vm.resumed()   // 아직 첫 상세를 받는 중
        advanceUntilIdle()
        assertEquals(1, repo.detailCalls)
    }

    @Test fun `돌아와 다시 받다 실패해도 보던 내용은 그대로`() = runTest(dispatcher) {
        val vm = open()
        vm.resumed()
        repo.detailResult = ApiResult.Failure(ApiError.Network)
        vm.resumed()
        advanceUntilIdle()
        assertEquals(ID, vm.state.value.receipt!!.paymentId)
        assertNotNull(vm.state.value.error)
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
        val vm = PaymentDetailViewModel(SavedStateHandle(mapOf("paymentId" to ID)), repo, link)
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
        val vm = PaymentDetailViewModel(SavedStateHandle(mapOf("paymentId" to ID)), repo, link)
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
        val memos = mutableListOf<String?>()
        var memoResult: (String?) -> ApiResult<String?> = { ApiResult.Success(it) }

        override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> {
            detailCalls++
            delay(100)
            return detailResult ?: ApiResult.Success(receipt)
        }

        override suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult> = error("상세는 직접 취소하지 않는다")

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
