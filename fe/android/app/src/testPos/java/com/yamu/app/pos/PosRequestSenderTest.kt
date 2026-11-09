package com.yamu.app.pos

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * 결제 요청 다시 보내기(R-2). BE는 posCode · merchantId · barcodeToken · amount · plan · installmentMonths · orderNo로 지문을 만든다.
 * 그래서 다시 보낼 때는 키와 본문을 그대로 두고, 서버가 거절한 뒤에는 새 키 · 새 주문번호로 간다
 */
class PosRequestSenderTest {
    private val merchant = PosMerchant(12, "POS-YAMU-CAFE", "야무 테스트 카페")

    private fun sender(repo: PosRepository): PosRequestSender {
        var keys = 0
        var orders = 0
        return PosRequestSender(repo, merchant, 15_000, newKey = { "key-${++keys}" }, newOrderNo = { "order-${++orders}" })
    }

    @Test fun `본문에 고른 가맹점 · 단말 · 금액 · 주문번호가 들어간다`() = runTest {
        val repo = ScriptedPosRepository(requests = listOf(ApiResult.Success(41)))
        assertEquals(PosSendOutcome.Requested(41), sender(repo).send("YAMU8F2A"))
        assertEquals(PosAttempt("key-1", PosPaymentRequest("YAMU8F2A", 12, "POS-YAMU-CAFE", 15_000, "order-1")), repo.sent.single())
    }

    @Test fun `응답이 끊긴 뒤 다시 보내면 같은 키 · 같은 본문(주문번호까지)`() = runTest {
        val repo = ScriptedPosRepository(requests = listOf(ApiResult.Failure(ApiError.Network), ApiResult.Success(41)))
        val s = sender(repo)
        assertEquals(PosSendOutcome.Unknown("NETWORK"), s.send("YAMU8F2A"))
        assertNotNull(s.pending)
        assertEquals(PosSendOutcome.Requested(41), s.resend())
        assertEquals(2, repo.sent.size)
        assertEquals(repo.sent[0], repo.sent[1])
        assertEquals("order-1", repo.sent[1].body.orderNo)
        assertNull(s.pending)
    }

    @Test fun `같은 코드를 다시 찍어도 남은 시도를 그대로 보낸다`() = runTest {
        val repo = ScriptedPosRepository(
            requests = listOf(ApiResult.Failure(ApiError.from(500, "INTERNAL_SERVER_ERROR")), ApiResult.Success(41)),
        )
        val s = sender(repo)
        assertTrue(s.send("YAMU8F2A") is PosSendOutcome.Unknown)
        assertEquals(PosSendOutcome.Requested(41), s.send("YAMU8F2A"))
        assertEquals(repo.sent[0], repo.sent[1])
    }

    @Test fun `서버가 같은 키를 처리 중이면 같은 시도로 다시 보낸다`() = runTest {
        val repo = ScriptedPosRepository(
            requests = listOf(ApiResult.Failure(ApiError.from(409, "IDEMPOTENCY_IN_PROGRESS")), ApiResult.Success(41)),
        )
        val s = sender(repo)
        assertEquals(PosSendOutcome.Unknown("IDEMPOTENCY_IN_PROGRESS"), s.send("YAMU8F2A"))
        assertEquals(PosSendOutcome.Requested(41), s.resend())
        assertEquals(repo.sent[0], repo.sent[1])
    }

    @Test fun `서버가 거절하면 시도를 버리고 다음 코드는 새 키 · 새 주문번호`() = runTest {
        val repo = ScriptedPosRepository(
            requests = listOf(ApiResult.Failure(ApiError.from(409, "BARCODE_ALREADY_USED")), ApiResult.Success(42)),
        )
        val s = sender(repo)
        assertEquals(PosSendOutcome.Rejected("BARCODE_ALREADY_USED"), s.send("YAMU8F2A"))
        assertNull(s.pending)
        assertEquals(PosSendOutcome.Requested(42), s.send("YAMU7C1D"))
        assertEquals("key-2", repo.sent[1].idempotencyKey)
        assertEquals("order-2", repo.sent[1].body.orderNo)
        assertEquals("YAMU7C1D", repo.sent[1].body.barcodeToken)
    }

    @Test fun `접수됐는지 모르는 시도가 있으면 다른 코드는 보내지 않는다`() = runTest {
        val repo = ScriptedPosRepository(requests = listOf(ApiResult.Failure(ApiError.Network)))
        val s = sender(repo)
        s.send("YAMU8F2A")
        assertEquals(PosSendOutcome.Blocked, s.send("YAMU7C1D"))
        assertEquals(1, repo.sent.size)
    }

    @Test fun `버리면 같은 코드도 새 시도로 간다`() = runTest {
        val repo = ScriptedPosRepository(
            requests = listOf(ApiResult.Failure(ApiError.Network), ApiResult.Failure(ApiError.from(409, "BARCODE_ALREADY_USED"))),
        )
        val s = sender(repo)
        s.send("YAMU8F2A")
        s.discard()
        assertNull(s.resend())
        assertEquals(PosSendOutcome.Rejected("BARCODE_ALREADY_USED"), s.send("YAMU8F2A"))
        assertEquals("key-2", repo.sent[1].idempotencyKey)
    }

    @Test fun `접수 여부를 모르는 실패와 서버가 거절한 실패를 가른다`() {
        assertTrue(ApiError.Network.isPosOutcomeUnknown)
        assertTrue(ApiError.from(500, "INTERNAL_SERVER_ERROR").isPosOutcomeUnknown)
        assertTrue(ApiError.from(504, null).isPosOutcomeUnknown)
        assertTrue(ApiError.from(409, "IDEMPOTENCY_IN_PROGRESS").isPosOutcomeUnknown)
        assertFalse(ApiError.from(409, "IDEMPOTENCY_KEY_REUSED").isPosOutcomeUnknown)
        assertFalse(ApiError.from(409, "BARCODE_ALREADY_USED").isPosOutcomeUnknown)
        assertFalse(ApiError.from(410, "BARCODE_EXPIRED").isPosOutcomeUnknown)
        assertFalse(ApiError.from(401, "INVALID_POS_KEY").isPosOutcomeUnknown)
        assertFalse(ApiError.from(404, "MERCHANT_NOT_FOUND").isPosOutcomeUnknown)
    }

    @Test fun `주문번호는 서울 시각에 난수 세 자리`() {
        val utc = ZonedDateTime.of(2026, 11, 9, 1, 15, 0, 0, ZoneOffset.UTC)
        val orderNo = newPosOrderNo(utc, Random(7))
        assertTrue(orderNo, Regex("POS-20261109-101500-\\d{3}").matches(orderNo))
        assertTrue(orderNo.length <= 64)
    }
}
