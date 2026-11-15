package com.yamu.app.feature.payment

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.PaymentApi
import com.yamu.app.feature.payment.data.PaymentEdits
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** P-07 → P-08에서 취소했거나 P-08이 다시 읽어 취소를 알게 됐으면 돌아왔을 때 '결제를 마쳤어요'로 남지 않는다 */
class PayDoneViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val edits = PaymentEdits()
    private val detail = receipt()
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

    @Test fun `거래 상세에서 취소하고 돌아오면 보던 영수증에 취소만 반영한다 — 체크카드 남은 잔액은 그대로`() = runTest(dispatcher) {
        val vm = PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), payments, edits)
        advanceUntilIdle()
        vm.refreshIfChanged()
        assertFalse(vm.state.value.receipt!!.canceled)   // 바뀐 게 없으면 그대로
        edits.markCanceled(77, "2026-11-08T07:00:00Z")   // P-08에서 취소
        edits.changed()
        vm.refreshIfChanged()
        advanceUntilIdle()
        val r = vm.state.value.receipt!!
        assertTrue(r.canceled)
        assertEquals("2026-11-08T07:00:00Z", r.canceledAtIso)
        assertEquals(985_000L, r.balanceAfter)            // 상세(#3)에는 없는 값 — 영수증을 갈아 끼우지 않는다
        assertEquals(0, detailCalls)                      // 다시 받지 않는다(실패해서 '결제를 마쳤어요'로 남을 일도 없다)
    }

    @Test fun `메모만 저장하고 돌아오면 아무것도 바꾸지 않는다`() = runTest(dispatcher) {
        val vm = PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), payments, edits)
        advanceUntilIdle()
        edits.rememberMemo(77, "정산")
        edits.changed()
        vm.refreshIfChanged()
        advanceUntilIdle()
        assertEquals(0, detailCalls)
        assertFalse(vm.state.value.receipt!!.canceled)
        assertEquals(985_000L, vm.state.value.receipt!!.balanceAfter)
    }

    @Test fun `상세를 다시 읽어 취소를 알게 돼도 반영한다 — 취소 응답이 끊겼거나 다른 기기에서 취소`() = runTest(dispatcher) {
        val vm = PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), payments, edits)
        advanceUntilIdle()
        MockWebServer().use { server ->
            server.start()
            val real = PaymentRepositoryImpl(server.retrofit().create(PaymentApi::class.java), edits)   // 같은 PaymentEdits를 본다
            server.enqueue(json(200, """{"success":true,"data":{"paymentId":77,"source":"YAMU","status":"CANCELED","amount":15000,"canceledAt":"2026-11-08T07:00:00Z"}}"""))
            assertTrue((real.detail(77) as ApiResult.Success).data.canceled)   // P-08이 다시 읽은 상세
        }
        vm.refreshIfChanged()
        val r = vm.state.value.receipt!!
        assertTrue(r.canceled)                            // '결제를 취소했어요'
        assertEquals("2026-11-08T07:00:00Z", r.canceledAtIso)
        assertEquals(985_000L, r.balanceAfter)
        assertEquals(0, detailCalls)
    }

    @Test fun `다른 결제를 취소한 것은 반영하지 않는다`() = runTest(dispatcher) {
        val vm = PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), payments, edits)
        advanceUntilIdle()
        edits.markCanceled(78, null)
        edits.changed()
        vm.refreshIfChanged()
        assertFalse(vm.state.value.receipt!!.canceled)
    }

    // ── 결제 완료 실적 칩 · 굴림(M-DONE-pay, S15P31E103-502) ──

    @Test fun `방금 승인한 영수증이면 연출하고 상세로 다시 받았으면 하지 않는다`() = runTest(dispatcher) {
        assertTrue(PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), payments, edits).state.value.justPaid)
        val noRecent = Proxy.newProxyInstance(PaymentRepository::class.java.classLoader, arrayOf(PaymentRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "recentReceipt" -> null
                "detail" -> ApiResult.Success(detail)
                else -> error("안 쓰는 메서드: ${method.name}")
            }
        } as PaymentRepository
        val vm = PayDoneViewModel(SavedStateHandle(mapOf("paymentId" to 77L)), noRecent, edits)
        advanceUntilIdle()
        assertEquals(77L, vm.state.value.receipt?.paymentId)
        assertFalse(vm.state.value.justPaid)
    }

    @Test fun `실적 칩은 인정액 · 굴림은 결제 뒤 누적에서 인정액을 뺀 값부터`() {
        val roll = credit(recognized = 15_000, total = 300_000).perfRoll()!!
        assertEquals("+15,000원", roll.chip)
        assertEquals(285_000L, roll.before)
        assertEquals(300_000L, roll.after)
    }

    @Test fun `누적이 없거나 실적에서 빠지거나 취소 · 0원이면 칩 · 굴림 없이 지금처럼`() {
        assertNull("BE -390 전 — 누적 없음", credit(recognized = 15_000, total = null).perfRoll())
        assertNull("실적에서 빠지는 결제", credit(recognized = 15_000, total = 300_000).copy(perfCounted = false).perfRoll())
        assertNull("인정액 0원", credit(recognized = 0, total = 300_000).perfRoll())
        assertNull("인정액 없음", credit(recognized = null, total = 300_000).perfRoll())
        assertNull("누적이 인정액보다 작으면 앞 값이 0 아래", credit(recognized = 15_000, total = 10_000).perfRoll())
        assertNull("취소된 결제", credit(recognized = 15_000, total = 300_000).copy(status = PayStatus.CANCELED).perfRoll())
        assertEquals("이번 결제가 첫 실적이면 0원부터", 0L, credit(recognized = 15_000, total = 15_000).perfRoll()?.before)
    }

    private fun credit(recognized: Long?, total: Long?) =
        receipt().copy(isCheck = false, balanceAfter = null, perfMonth = 11, perfRecognized = recognized, perfTotalAfter = total, perfTarget = 300_000)

    private fun receipt() = PaymentReceipt(
        paymentId = 77, merchantName = "스타벅스 역삼점", categoryName = "카페", amount = 15_000,
        paidAtIso = null, cardId = 1, cardBrand = brandOf("신한카드"), cardName = "카드", cardLast4 = "0001",
        isCheck = true, approvalNo = null, installmentMonths = null, benefits = emptyList(), chargedAmount = null,
        balanceAfter = 985_000, billDateIso = null, billAmount = null, perfMonth = null, perfRecognized = null, perfCounted = true,
        perfTotalAfter = null, perfTarget = null,
    )
}
