package com.yamu.app.feature.payment.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.ui.userMessage

// 결제 취소 · 메모의 앱 쪽 규칙. BE PaymentCancelService · PaymentMemoRequest와 같은 기준을 화면에서 미리 쓴다

/** 메모 — 한 줄, 앞뒤 공백을 뺀 코드포인트 100자. 줄바꿈 · 탭은 공백 하나로 바뀐다(BE PaymentMemoRequest) */
object MemoRules {
    const val MAX_LENGTH = 100

    private val LINE_BREAK_OR_TAB = Regex("\r\n|[\n\r\t\u000B\u000C\u0085  ]")

    /** 서버가 저장할 모양. 빈 문자열이면 지우는 것 */
    fun normalize(raw: String): String = LINE_BREAK_OR_TAB.replace(raw, " ").trim { Character.isWhitespace(it) }

    /** 한글 · 대부분의 이모지는 1자, 가족 · 국기처럼 여러 코드포인트로 된 이모지는 2자 넘게 센다(BE와 같다) */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** 입력 칸 아래 오류 한 줄. 저장할 수 있으면 null */
    fun problem(raw: String): String? {
        val text = normalize(raw)
        return when {
            text.any { Character.isISOControl(it) } -> "쓸 수 없는 문자가 있어요"
            length(text) > MAX_LENGTH -> "메모는 ${MAX_LENGTH}자까지 적을 수 있어요"
            else -> null
        }
    }
}

/** 취소 실패를 화면에 — [recheck]면 결제 상태를 다시 불러와 본다(취소됐을 수도 있다) */
data class CancelProblem(val message: String, val recheck: Boolean)

fun ApiError.toCancelProblem(): CancelProblem = when {
    // 응답이 끊겼거나 코드 없는 502 · 504 — 취소가 됐는지 모른다. 같은 키로 다시 보내면 BE가 처음 결과를 준다
    isOutcomeUnknown || code == "UPSTREAM_TIMEOUT" -> CancelProblem("취소됐는지 확인하지 못했어요. 결제 상태를 다시 불러올게요", recheck = true)
    code == "ALREADY_CANCELED" -> CancelProblem("이미 취소된 결제예요", recheck = true)
    code == "IDEMPOTENCY_IN_PROGRESS" -> CancelProblem("취소를 처리하고 있어요. 잠시 뒤 다시 확인해 주세요", recheck = true)
    code == "PAYMENT_NOT_CANCELABLE" -> when (detailString("reason")) {
        "NOT_APPROVED" -> CancelProblem("승인된 결제만 취소할 수 있어요", recheck = true)
        "SEED_TRANSACTION" -> CancelProblem("야무에 카드를 등록하기 전 거래는 취소할 수 없어요", recheck = false)
        "ACCOUNT_UNAVAILABLE" -> CancelProblem("돌려받을 계좌가 연결돼 있지 않아 취소할 수 없어요", recheck = false)
        "REFUND_NEEDS_REVIEW" -> CancelProblem("자동으로 돌려드릴 수 없는 결제라 지금은 취소할 수 없어요", recheck = false)
        else -> CancelProblem("취소할 수 없는 결제예요", recheck = true)
    }
    // 서버가 코드로 답한 금융망 실패 — 결제는 승인 상태로 남아 다시 취소할 수 있다
    this is ApiError.BankUnavailable -> CancelProblem("은행 연결이 늦어 취소하지 못했어요. 결제는 그대로예요", recheck = false)
    this is ApiError.NotFound -> CancelProblem("찾을 수 없는 결제예요", recheck = false)
    else -> CancelProblem(userMessage(), recheck = false)
}

/** 메모 저장 실패 — 입력 칸 아래에 */
fun ApiError.toMemoProblem(): String = when {
    code == "MEMO_TOO_LONG" -> "메모는 ${MemoRules.MAX_LENGTH}자까지 적을 수 있어요"
    this is ApiError.InvalidRequest -> "쓸 수 없는 문자가 있어요"   // 앱이 미리 걸러서 제어문자만 남는다
    this is ApiError.NotFound -> "찾을 수 없는 결제예요"
    else -> userMessage()
}
