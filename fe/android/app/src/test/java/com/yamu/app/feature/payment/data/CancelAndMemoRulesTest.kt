package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.ApiError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 메모 규칙은 BE PaymentMemoRequest와 같아야 하고, 취소 실패는 '결과를 모름'과 '실패'를 갈라야 한다 */
class CancelAndMemoRulesTest {

    // ── 메모 ──

    @Test fun `줄바꿈 · 탭은 공백 하나로, 앞뒤 공백은 뺀다`() {
        assertEquals("팀 회식 1/N 정산", MemoRules.normalize("  팀 회식\r\n1/N\t정산 \n"))
        assertEquals("a b", MemoRules.normalize("a b"))
    }

    @Test fun `공백뿐이면 빈 문자열 — 지우기`() {
        assertEquals("", MemoRules.normalize(" \n\t "))
        assertNull(MemoRules.problem("   "))
    }

    @Test fun `100자는 되고 101자는 막는다 — 정리한 뒤 길이로`() {
        assertNull(MemoRules.problem("가".repeat(100)))
        assertNull(MemoRules.problem("  " + "가".repeat(100) + "\n"))
        assertEquals("메모는 100자까지 적을 수 있어요", MemoRules.problem("가".repeat(101)))
    }

    @Test fun `이모지는 코드포인트로 센다 — 대부분 1자, 가족 이모지는 여러 자`() {
        assertEquals(1, MemoRules.length("😀"))                 // UTF-16으로는 2칸
        assertEquals(5, MemoRules.length("👨‍👩‍👧"))  // 사람 셋 + 잇는 문자 둘
        assertNull(MemoRules.problem("😀".repeat(100)))
    }

    @Test fun `줄바꿈 말고 다른 제어문자는 막는다`() {
        assertEquals("쓸 수 없는 문자가 있어요", MemoRules.problem("메모\u0007"))
        assertNull(MemoRules.problem("메모\u000B끝"))   // 세로 탭은 줄바꿈으로 본다
    }

    // ── 취소 실패 ──

    @Test fun `응답이 끊기면 결과를 모른다 — 상태를 다시 본다`() {
        assertTrue(ApiError.Network.toCancelProblem().recheck)
        assertTrue(ApiError.BankUnavailable("").toCancelProblem().recheck)   // 코드 없는 502 · 504(프록시)
        assertTrue(ApiError.Unknown("PARSE_ERROR").toCancelProblem().recheck)
        assertTrue(ApiError.BankUnavailable("UPSTREAM_TIMEOUT").toCancelProblem().recheck)
    }

    @Test fun `서버가 코드로 답한 금융망 실패는 실패 — 결제는 그대로라 다시 누르면 된다`() {
        val p = ApiError.BankUnavailable("FIN_API_UNAVAILABLE").toCancelProblem()
        assertFalse(p.recheck)
        assertEquals("은행 연결이 늦어 취소하지 못했어요. 결제는 그대로예요", p.message)
        assertFalse(ApiError.BankUnavailable("FIN_API_ERROR").toCancelProblem().recheck)
    }

    @Test fun `이미 취소 · 처리 중은 상태를 다시 불러 본다`() {
        assertEquals(CancelProblem("이미 취소된 결제예요", recheck = true), ApiError.Conflict("ALREADY_CANCELED").toCancelProblem())
        assertTrue(ApiError.Conflict("IDEMPOTENCY_IN_PROGRESS").toCancelProblem().recheck)
    }

    @Test fun `취소 불가 사유별 문장`() {
        fun reason(r: String) = ApiError.Conflict("PAYMENT_NOT_CANCELABLE", buildJsonObject { put("reason", r) }).toCancelProblem()
        assertEquals("승인된 결제만 취소할 수 있어요", reason("NOT_APPROVED").message)
        assertEquals("야무에 카드를 등록하기 전 거래는 취소할 수 없어요", reason("SEED_TRANSACTION").message)
        assertEquals("자동으로 돌려드릴 수 없는 결제라 지금은 취소할 수 없어요", reason("REFUND_NEEDS_REVIEW").message)
        assertEquals("취소할 수 없는 결제예요", reason("SOMETHING_NEW").message)
    }

    @Test fun `없는 결제 · 다른 사용자의 결제는 404 하나로`() {
        assertEquals(CancelProblem("찾을 수 없는 결제예요", recheck = false), ApiError.NotFound("RESOURCE_NOT_FOUND").toCancelProblem())
    }
}
