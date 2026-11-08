package com.yamu.app.feature.payment

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentEdits
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.lang.reflect.Proxy

/** P-07 → P-08에서 취소하고 돌아오면 '결제를 마쳤어요'로 남지 않는다 */
class PayDoneViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val edits = PaymentEdits()
    private var detail = receipt()
    private var detailCalls = 0

    /** 방금 승인한 영수증(메모리) · 상세(#3)만 쓴다 */
    private val payments = Proxy.newProxyInstance(PaymentRepository::class.java.classLoader, arrayOf(PaymentRepository::class.java)) { _, method, _ ->
        when (method.name) {
            "recentReceipt" -> receipt()
            "detail" -> {
                detailCalls++
                ApiResult.Success(detail)
            }
            else -> error("안 쓰는 메서드: ${method.name}")
        }
    } as PaymentRepository

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `돌아왔을 때 그사이 취소가 있었으면 다시 받아 취소 상태를 보인다`() = runTest(dispatcher) {
        val vm = PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), payments, edits)
        advanceUntilIdle()
        vm.refreshIfChanged()
        advanceUntilIdle()
        assertEquals(0, detailCalls)                 // 바뀐 게 없으면 방금 승인한 영수증 그대로
        assertFalse(vm.state.value.receipt!!.canceled)
        detail = receipt().copy(status = PayStatus.CANCELED)
        edits.changed()                              // P-08에서 취소
        vm.refreshIfChanged()
        advanceUntilIdle()
        assertEquals(1, detailCalls)
        assertTrue(vm.state.value.receipt!!.canceled)
        vm.refreshIfChanged()
        advanceUntilIdle()
        assertEquals(1, detailCalls)                 // 한 번만
    }

    private fun receipt() = PaymentReceipt(
        paymentId = 77, merchantName = "스타벅스 역삼점", categoryName = "카페", amount = 15_000,
        paidAtIso = null, cardId = 1, cardBrand = brandOf("신한카드"), cardName = "카드", cardLast4 = "0001",
        isCheck = false, approvalNo = null, installmentMonths = null, benefits = emptyList(), chargedAmount = null,
        balanceAfter = null, billDateIso = null, billAmount = null, perfMonth = null, perfRecognized = null, perfCounted = true,
        perfTotalAfter = null, perfTarget = null,
    )
}
