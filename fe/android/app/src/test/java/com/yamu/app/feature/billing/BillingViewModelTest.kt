package com.yamu.app.feature.billing

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.billing.data.BillDetail
import com.yamu.app.feature.billing.data.BillDetailDto
import com.yamu.app.feature.billing.data.BillDto
import com.yamu.app.feature.billing.data.BillList
import com.yamu.app.feature.billing.data.BillingRepository
import com.yamu.app.feature.billing.data.RefundDto
import com.yamu.app.feature.billing.data.RefundPage
import com.yamu.app.feature.billing.data.RefundSummary
import com.yamu.app.feature.billing.data.toBill
import com.yamu.app.feature.billing.data.toBillDetail
import com.yamu.app.feature.billing.data.toRefund
import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.feature.link.data.CardsOnlyLink
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.brandOf
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 청구 · 환급 화면 — 첫 실패와 새로 고침 실패 · 남의 카드 · 더 보기 연타 · 밀린 페이지 · 남의 청구서 */
class BillingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubBilling()
    private val link = CardsOnlyLink()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.billing(userCardId: Long? = null): BillingViewModel {
        val vm = BillingViewModel(SavedStateHandle(if (userCardId != null) mapOf("userCardId" to userCardId) else emptyMap()), repo, link)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    // ── 청구서 ──

    @Test fun `전체로 열면 청구서와 환급을 같이 읽는다`() = runTest(dispatcher) {
        val vm = billing()
        assertEquals(listOf<Long?>(null), repo.billCalls)
        assertEquals(listOf(1), repo.refundCalls)
        assertEquals(1, vm.state.value.bills?.bills?.size)
        assertFalse(vm.state.value.cardFiltered)
    }

    @Test fun `별명 카드 청구서도 카드 목록의 상품명으로 그림을 찾고, 목록은 한 번만 받는다`() = runTest(dispatcher) {
        repo.bills = ApiResult.Success(
            BillList(listOfNotNull(BillDto(billId = 202, userCardId = 15, nickname = "생활비 카드", status = "CONFIRMED", finalAmount = 50_000).toBill()), next = null, unpaidCount = 0),
        )
        link.result = ApiResult.Success(
            listOf(LinkedCard(15, brandOf("신한카드"), "신한카드 Mr.Life", isCheck = false, last4 = "0015", paymentDay = 25, recognizedAmount = null, yearMonth = null, nickname = "생활비 카드")),
        )
        val vm = billing()
        assertEquals("생활비 카드", vm.state.value.bills?.bills?.single()?.cardName)
        assertEquals("신한카드 Mr.Life", vm.arts.value.getValue(15).productName)
        assertNotNull(CardImages.forProduct(vm.arts.value.getValue(15).productName))

        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, link.calls)
    }

    @Test fun `카드 상세에서 오면 그 카드만 · 환급은 부르지 않는다`() = runTest(dispatcher) {
        val vm = billing(userCardId = 13)
        assertEquals(listOf<Long?>(13), repo.billCalls)
        assertTrue(repo.refundCalls.isEmpty())
        assertTrue(vm.state.value.cardFiltered)
    }

    @Test fun `남의 카드 · 없는 카드(404)면 '찾을 수 없는 카드'`() = runTest(dispatcher) {
        repo.bills = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val s = billing(userCardId = 77).state.value
        assertTrue(s.cardGone)
        assertNull(s.bills)
    }

    @Test fun `처음부터 못 읽으면 오류 문장 · 다시 시도`() = runTest(dispatcher) {
        repo.bills = ApiResult.Failure(ApiError.Network)
        val vm = billing()
        assertEquals("인터넷 연결을 확인해 주세요", vm.state.value.billsError)
        repo.bills = ApiResult.Success(sampleBills())
        vm.loadBills()
        advanceUntilIdle()
        assertNull(vm.state.value.billsError)
        assertNotNull(vm.state.value.bills)
    }

    @Test fun `새로 고침이 실패하면 보던 목록을 두고 스낵바만`() = runTest(dispatcher) {
        val vm = billing()
        repo.bills = ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))
        vm.loadBills()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.bills?.bills?.size)
        assertNull(s.billsError)
        assertEquals(listOf("청구서를 새로 불러오지 못했어요 · 잠시 뒤 다시 시도해 주세요"), s.messages)
    }

    @Test fun `청구서 · 환급 새로 고침이 둘 다 실패해도 스낵바 둘이 차례로 남는다`() = runTest(dispatcher) {
        val vm = billing()
        repo.bills = ApiResult.Failure(ApiError.Network)
        repo.pages[1] = ApiResult.Failure(ApiError.Network)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.messages.size)
        assertTrue(vm.state.value.messages[0].startsWith("청구서를"))
        vm.messageShown()
        assertEquals(1, vm.state.value.messages.size)
        assertTrue(vm.state.value.messages[0].startsWith("환급을"))
    }

    // ── 환급 ──

    @Test fun `더 보기 연타는 한 번만 부른다`() = runTest(dispatcher) {
        val vm = billing()
        repo.gate = CompletableDeferred()
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.state.value.loadingMore)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), repo.refundCalls)
        assertEquals(listOf("r1", "r2", "r3"), vm.state.value.refunds.map { it.key })
        assertFalse(vm.state.value.refundsHasNext)
    }

    @Test fun `새 환급이 생겨 페이지가 밀려도 같은 줄은 한 번만`() = runTest(dispatcher) {
        repo.pages[2] = ApiResult.Success(page(listOf(refund(2), refund(3)), hasNext = false))   // r2가 1페이지 끝에서 2페이지 앞으로 밀림
        val vm = billing()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("r1", "r2", "r3"), vm.state.value.refunds.map { it.key })
    }

    @Test fun `더 보기가 실패하면 목록은 그대로 · 버튼 아래 한 줄`() = runTest(dispatcher) {
        repo.pages[2] = ApiResult.Failure(ApiError.Network)
        val vm = billing()
        vm.loadMore()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("r1", "r2"), s.refunds.map { it.key })
        assertEquals("인터넷 연결을 확인해 주세요", s.moreError)
        assertTrue(s.refundsHasNext)
    }

    @Test fun `더 보기 중에 화면이 새로 고치면 옛 2페이지를 섞지 않는다`() = runTest(dispatcher) {
        val vm = billing()
        repo.gate = CompletableDeferred()
        vm.loadMore()
        advanceUntilIdle()
        repo.gate = null
        repo.pages[1] = ApiResult.Success(page(listOf(refund(9), refund(1)), hasNext = true))
        vm.loadRefunds()
        advanceUntilIdle()
        assertEquals(listOf("r9", "r1"), vm.state.value.refunds.map { it.key })
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `환급을 처음부터 못 읽으면 오류 · 읽은 뒤 실패면 스낵바`() = runTest(dispatcher) {
        repo.pages[1] = ApiResult.Failure(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
        val vm = billing()
        assertEquals("은행 연결이 늦어요", vm.state.value.refundsError)
        assertFalse(vm.state.value.refundsLoaded)

        repo.pages[1] = ApiResult.Success(page(listOf(refund(1)), hasNext = false))
        vm.loadRefunds()
        advanceUntilIdle()
        repo.pages[1] = ApiResult.Failure(ApiError.Network)
        vm.loadRefunds()
        advanceUntilIdle()
        assertEquals(listOf("r1"), vm.state.value.refunds.map { it.key })
        assertTrue(vm.state.value.messages.single().startsWith("환급을 새로 불러오지 못했어요"))
    }

    @Test fun `상세에 다녀와 새로 고쳐도 더 보기로 읽은 페이지까지 다시 읽는다`() = runTest(dispatcher) {
        repo.pages[2] = ApiResult.Success(page(listOf(refund(3), refund(4)), hasNext = true))
        repo.pages[3] = ApiResult.Success(page(listOf(refund(5)), hasNext = false))
        val vm = billing()
        vm.loadMore()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        repo.refundCalls.clear()

        vm.refresh()   // 화면에 돌아옴
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), repo.refundCalls)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), vm.state.value.refunds.map { it.key })
        assertEquals(3, vm.state.value.refundPage)
        assertFalse(vm.state.value.refundsHasNext)
    }

    @Test fun `다시 읽다가 뒤 페이지가 실패하면 보던 목록을 그대로 둔다`() = runTest(dispatcher) {
        val vm = billing()
        vm.loadMore()
        advanceUntilIdle()
        repo.pages[1] = ApiResult.Success(page(listOf(refund(9), refund(1)), hasNext = true))
        repo.pages[2] = ApiResult.Failure(ApiError.Network)
        vm.loadRefunds()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("r1", "r2", "r3"), s.refunds.map { it.key })   // 1페이지만 바꿔 끼우지 않는다
        assertEquals(2, s.refundPage)
        assertFalse(s.refundsLoading)
        assertEquals(1, s.messages.size)
    }

    @Test fun `환급 목록이 비어도 입금 전 합계는 남는다`() = runTest(dispatcher) {
        // BE 합계는 기간과 상관없다 — 3개월보다 먼저 생겨 아직 입금 전인 환급은 합계에만 들어 있다
        repo.pages[1] = ApiResult.Success(RefundPage(emptyList(), page = 1, hasNext = false, summary = RefundSummary(12_000, 1, 0)))
        val s = billing().state.value
        assertTrue(s.refundsLoaded)
        assertTrue(s.refunds.isEmpty())
        assertEquals(RefundSummary(12_000, 1, 0), s.pendingSummary)
    }

    @Test fun `입금 전 환급이 없으면 합계 카드를 띄우지 않는다`() = runTest(dispatcher) {
        assertNull(billing().state.value.pendingSummary)
        repo.pages[1] = ApiResult.Success(RefundPage(emptyList(), page = 1, hasNext = false, summary = RefundSummary(0, 0, 0)))
        val vm = billing()
        assertTrue(vm.state.value.refunds.isEmpty())
        assertNull(vm.state.value.pendingSummary)
    }

    @Test fun `목록이 줄어 앞 페이지에서 끝나면 거기서 멈춘다`() = runTest(dispatcher) {
        val vm = billing()
        vm.loadMore()
        advanceUntilIdle()
        repo.refundCalls.clear()
        repo.pages[1] = ApiResult.Success(page(listOf(refund(1)), hasNext = false))
        vm.loadRefunds()
        advanceUntilIdle()
        assertEquals(listOf(1), repo.refundCalls)
        assertEquals(1, vm.state.value.refundPage)
        assertEquals(listOf("r1"), vm.state.value.refunds.map { it.key })
    }

    // ── 청구서 상세 ──

    private fun TestScope.detail(): BillDetailViewModel {
        val vm = BillDetailViewModel(SavedStateHandle(mapOf("billId" to 201L)), repo, link)
        vm.load()
        advanceUntilIdle()
        return vm
    }

    @Test fun `남의 청구서(404)면 '찾을 수 없는 청구서' · 앞서 본 값도 지운다`() = runTest(dispatcher) {
        val vm = detail()
        assertNotNull(vm.state.value.detail)
        repo.detail = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value.gone)
        assertNull(vm.state.value.detail)
    }

    @Test fun `상세를 처음부터 못 읽으면 오류 · 본 뒤 실패면 값을 두고 스낵바`() = runTest(dispatcher) {
        repo.detail = ApiResult.Failure(ApiError.Network)
        val vm = detail()
        assertEquals("인터넷 연결을 확인해 주세요", vm.state.value.error)

        repo.detail = ApiResult.Success(BillDetailDto(billId = 201, status = "PAID").toBillDetail(201))
        vm.load()
        advanceUntilIdle()
        repo.detail = ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))
        vm.load()
        advanceUntilIdle()
        assertNotNull(vm.state.value.detail)
        assertFalse(vm.state.value.gone)
        assertEquals("새로 불러오지 못했어요 · 잠시 뒤 다시 시도해 주세요", vm.state.value.message)
        assertEquals(listOf(201L, 201L, 201L), repo.detailCalls)
    }
}

private fun sampleBills() = BillList(listOfNotNull(BillDto(billId = 201, status = "CONFIRMED", finalAmount = 320_000).toBill()), next = null, unpaidCount = 0)

private fun refund(id: Long) = RefundDto(refundId = id, paymentId = 100 + id, status = "COMPLETED", amount = 1_000).toRefund()!!

private fun page(items: List<com.yamu.app.feature.billing.data.Refund>, hasNext: Boolean) =
    RefundPage(items, page = 1, hasNext = hasNext, summary = RefundSummary(0, 0, 0))

private class StubBilling : BillingRepository {
    var bills: ApiResult<BillList> = ApiResult.Success(sampleBills())
    var detail: ApiResult<BillDetail> = ApiResult.Success(BillDetailDto(billId = 201, status = "CONFIRMED").toBillDetail(201))
    val pages = mutableMapOf<Int, ApiResult<RefundPage>>(
        1 to ApiResult.Success(page(listOf(refund(1), refund(2)), hasNext = true)),
        2 to ApiResult.Success(page(listOf(refund(3)), hasNext = false)),
    )
    var gate: CompletableDeferred<Unit>? = null
    val billCalls = mutableListOf<Long?>()
    val refundCalls = mutableListOf<Int>()
    val detailCalls = mutableListOf<Long>()

    override suspend fun bills(userCardId: Long?): ApiResult<BillList> {
        billCalls += userCardId
        return bills
    }

    override suspend fun bill(billId: Long): ApiResult<BillDetail> {
        detailCalls += billId
        return detail
    }

    override suspend fun refunds(page: Int): ApiResult<RefundPage> {
        refundCalls += page
        gate?.await()
        return pages.getValue(page)
    }
}
