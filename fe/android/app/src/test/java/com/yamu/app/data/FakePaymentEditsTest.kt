package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentEdits
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** debug 가짜 결제 서버의 취소 · 메모가 BE와 같은 규칙인지 — 시연 중에 진짜와 다르게 굴지 않게 */
class FakePaymentEditsTest {
    private val edits = PaymentEdits()
    private val fake = FakePaymentRepository(FakePerformanceStore(), edits)

    @After fun tearDown() {
        DevSession.faultScenario = FaultScenario.NONE
    }

    @Test fun `같은 키로 다시 보내면 처음 결과, 다른 키로 동시에 두 번이면 하나만 취소`() = runTest {
        val first = async { fake.cancel(938, "key-a") }
        val second = async { fake.cancel(938, "key-b") }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is ApiResult.Success })
        assertEquals("ALREADY_CANCELED", (results.single { it is ApiResult.Failure } as ApiResult.Failure).error.code)
        val winnerKey = if (results[0] is ApiResult.Success) "key-a" else "key-b"
        assertEquals((results.first { it is ApiResult.Success } as ApiResult.Success).data, (fake.cancel(938, winnerKey) as ApiResult.Success).data)
        assertTrue(fake.isCanceled(938))
        assertTrue(edits.isCanceled(938))
        assertEquals(PayStatus.CANCELED, (fake.detail(938) as ApiResult.Success).data.status)
    }

    @Test fun `은행 오류면 502이고 결제는 승인 상태로 남아 다시 취소할 수 있다`() = runTest {
        DevSession.faultScenario = FaultScenario.BANK_ERROR
        val failed = fake.cancel(937, "key-a") as ApiResult.Failure
        assertTrue(failed.error is ApiError.BankUnavailable)
        assertEquals(PayStatus.APPROVED, FakePaymentHistory.receipt(937)!!.status)
        DevSession.faultScenario = FaultScenario.NONE
        assertTrue(fake.cancel(937, "key-a") is ApiResult.Success)
    }

    @Test fun `메모 — 정리해서 저장, 101자 400 MEMO_TOO_LONG, 빈 값은 지우기, 취소 뒤에도 남길 수 있다`() = runTest {
        assertEquals("팀 회식 1/N", (fake.saveMemo(936, " 팀 회식\n1/N ") as ApiResult.Success).data)
        assertEquals("팀 회식 1/N", (fake.detail(936) as ApiResult.Success).data.memo)
        assertEquals("MEMO_TOO_LONG", (fake.saveMemo(936, "가".repeat(101)) as ApiResult.Failure).error.code)
        assertNull((fake.saveMemo(936, "  ") as ApiResult.Success).data)
        assertNull((fake.detail(936) as ApiResult.Success).data.memo)
        fake.cancel(936, "k")
        assertEquals("환불 확인", (fake.saveMemo(936, "환불 확인") as ApiResult.Success).data)
    }

    @Test fun `취소 · 메모가 성공할 때만 결제 내역 버전이 오른다`() = runTest {
        val start = edits.version.value
        fake.saveMemo(935, "a")
        fake.saveMemo(935, "가".repeat(101))
        fake.cancel(935, "k")
        fake.cancel(935, "k2")   // 이미 취소
        assertEquals(start + 2, edits.version.value)
    }

    @Test fun `가짜 결제 내역 — 취소한 줄은 '취소'로 남고 합계에서 빠진다, CSV는 분당 3번`() = runTest {
        val report = FakeReportRepository(fake)
        val before = (report.payments(1) as ApiResult.Success).data
        fake.cancel(938, "k")
        val after = (report.payments(1) as ApiResult.Success).data
        assertTrue(after.items.first { it.paymentId == 938L }.canceled)
        assertNull(after.items.first { it.paymentId == 938L }.benefit)   // 받지 않은 혜택
        assertEquals(before.amount - 15_000, after.amount)
        val month = YearMonth.now(KST)
        repeat(3) { assertTrue(report.exportCsv(month) is ApiResult.Success) }
        val limited = report.exportCsv(month) as ApiResult.Failure
        assertEquals("RATE_LIMITED", limited.error.code)
        assertTrue((limited.error.detailInt("retryAfterSeconds") ?: 0) in 1..60)
    }
}
