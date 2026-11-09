package com.yamu.app.core.ui

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ErrorMessagesTest {
    @Test
    fun `마지막 값의 시각을 알면 언제 값인지 붙인다`() {
        val now = OffsetDateTime.now(KST).withHour(9).withMinute(5)
        val line = staleLine("은행 연결이 늦어요", now.toString())
        assertTrue(line, line.startsWith("은행 연결이 늦어요 · 마지막 "))
        assertTrue(line, line.endsWith("09:05 반영"))
    }

    @Test
    fun `시각을 모르면 마지막 값이라고만 한다`() {
        assertEquals("인터넷 연결을 확인해 주세요 · 마지막 값을 보여 드려요", staleLine("인터넷 연결을 확인해 주세요", null))
    }

    @Test
    fun `서버에 없는 기능은 준비 중이라고 하고 화면은 다시 시도를 숨긴다`() {
        assertEquals("아직 준비 중인 기능이에요", ApiError.from(404, "API_NOT_FOUND").userMessage())
        assertEquals("아직 준비 중인 기능이에요", ApiError.from(405, "METHOD_NOT_ALLOWED").userMessage())
        assertEquals("찾을 수 없어요", ApiError.from(404, "RESOURCE_NOT_FOUND").userMessage())

        assertTrue(isNotReadyMessage(staleLine(ApiError.from(404, "API_NOT_FOUND").userMessage(), null)))   // 화면이 뒤에 말을 붙여도
        assertFalse(isNotReadyMessage("찾을 수 없어요"))
        assertFalse(isNotReadyMessage(ApiError.Network.userMessage()))
    }

    @Test
    fun `403은 코드마다 다르게 말한다`() {
        assertEquals("본인 확인이 필요해요", ApiError.from(403, "STEP_UP_REQUIRED").userMessage())
        assertEquals("본인 확인에 실패했어요 · 다시 시도해 주세요", ApiError.from(403, "STEP_UP_FAILED").userMessage())
        assertEquals("여러 번 틀려서 잠겼어요 · 잠시 뒤 다시 시도해 주세요", ApiError.from(403, "DEVICE_LOCKED").userMessage())
        assertEquals("이 기능은 쓸 수 없어요", ApiError.from(403, "ADMIN_ONLY").userMessage())
    }

    @Test
    fun `409 중 같은 요청이라고 하는 건 멱등성 두 코드뿐이다`() {
        val same = "같은 요청이 이미 처리되고 있어요"
        assertEquals(same, ApiError.from(409, "IDEMPOTENCY_IN_PROGRESS").userMessage())
        assertEquals(same, ApiError.from(409, "IDEMPOTENCY_KEY_REUSED").userMessage())

        mapOf(
            "CONCURRENT_MODIFICATION" to "다른 요청과 겹쳤어요 · 다시 시도해 주세요",
            "PAYMENT_ALREADY_PROCESSED" to "이미 처리된 결제예요",
            "RETRY_LIMIT_EXCEEDED" to "다른 카드로 다시 시도할 수 있는 횟수를 넘었어요",
            "CARD_HAS_OPEN_BILL" to "출금 전인 청구서가 있어 카드를 해제할 수 없어요",
            "DEFAULT_CARD_REQUIRED" to "자동 선택을 끄려면 기본 카드를 먼저 정해 주세요",
            "ALREADY_REGISTERED" to "이미 가입했어요 · 이 폰을 다시 등록해 주세요",
        ).forEach { (code, message) -> assertEquals(code, message, ApiError.from(409, code).userMessage()) }

        // 표에 없는 409도 '같은 요청'이라고 하지 않는다
        val unknown = ApiError.from(409, "SOMETHING_NEW").userMessage()
        assertNotEquals(same, unknown)
        assertEquals("지금은 처리할 수 없는 상태예요", unknown)
    }

    @Test
    fun `해제된 기기는 다시 로그인 대신 처음부터라고 한다`() {
        assertEquals("이 폰 등록이 풀렸어요 · 처음부터 다시 시작해 주세요", ApiError.from(401, "DEVICE_NOT_REGISTERED").userMessage())
        assertEquals("다시 로그인해 주세요", ApiError.from(401, "UNAUTHORIZED").userMessage())
    }
}
