package com.yamu.app.core.ui

import com.yamu.app.core.format.formatSyncedAt
import com.yamu.app.core.network.ApiError

/** 서버에 아직 없는 기능일 때 문장 — 다시 해도 같아서 ErrorState가 '다시 시도'를 숨긴다 */
const val NOT_READY_MESSAGE = "아직 준비 중인 기능이에요"

/** 화면이 넘긴 오류 문장이 '준비 중'인가(화면은 ApiError 대신 문장만 넘기고, 앞뒤에 말을 붙이기도 한다) */
fun isNotReadyMessage(message: String): Boolean = NOT_READY_MESSAGE in message

/**
 * 오류 → 사용자 문장(02 문서 6장). 해요체 · 무엇을 하면 되는지.
 * 같은 HTTP 상태라도 뜻이 다른 코드(403 · 409 등)는 [codeMessages]가 먼저, 없으면 종류별 문장.
 * 가입 · 결제 승인처럼 화면이 자기 표를 가진 곳은 그 표를 먼저 보고 여기로 온다
 */
fun ApiError.userMessage(): String = codeMessages[code] ?: when (this) {
    is ApiError.BankUnavailable -> "은행 연결이 늦어요"
    ApiError.Network -> "인터넷 연결을 확인해 주세요"
    is ApiError.DataInsufficient -> "데이터가 더 모이면 알려 드려요"
    is ApiError.NotFound -> "찾을 수 없어요"
    is ApiError.NotSupported -> NOT_READY_MESSAGE
    is ApiError.Unauthorized -> "다시 로그인해 주세요"
    is ApiError.StepUpRequired -> "본인 확인이 필요해요"
    is ApiError.Conflict -> "지금은 처리할 수 없는 상태예요"
    is ApiError.InvalidRequest -> "입력한 내용을 다시 확인해 주세요"
    is ApiError.Locked -> "잠시 잠겼어요 · 나중에 다시 시도해 주세요"
    is ApiError.Server, is ApiError.Unknown -> "잠시 뒤 다시 시도해 주세요"
}

/** BE ErrorCode.java 기준. 문장은 BE 메시지를 해요체로 옮겼다 */
private val codeMessages: Map<String, String> = mapOf(
    // 401 — 다른 폰에서 재등록해 이 기기가 해제됨(앱은 첫 화면으로 간다)
    "DEVICE_NOT_REGISTERED" to "이 폰 등록이 풀렸어요 · 처음부터 다시 시작해 주세요",
    // 403
    "DEVICE_LOCKED" to "여러 번 틀려서 잠겼어요 · 잠시 뒤 다시 시도해 주세요",
    "STEP_UP_FAILED" to "본인 확인에 실패했어요 · 다시 시도해 주세요",
    "ADMIN_ONLY" to "이 기능은 쓸 수 없어요",
    // 409 — '같은 요청'은 멱등성 두 코드뿐이다
    "IDEMPOTENCY_IN_PROGRESS" to "같은 요청이 이미 처리되고 있어요",
    "IDEMPOTENCY_KEY_REUSED" to "같은 요청이 이미 처리되고 있어요",
    "CONCURRENT_MODIFICATION" to "다른 요청과 겹쳤어요 · 다시 시도해 주세요",
    "ALREADY_REGISTERED" to "이미 가입했어요 · 이 폰을 다시 등록해 주세요",
    "SIGNUP_STEP_INCOMPLETE" to "앞 단계를 먼저 마쳐 주세요",
    "DEVICE_LIMIT_EXCEEDED" to "기기는 한 대만 등록할 수 있어요",
    "ACCOUNT_ALREADY_LINKED" to "이미 연결한 계좌예요",
    "CARD_ALREADY_REGISTERED" to "이미 등록한 카드예요",
    "CARD_ACCOUNT_NOT_SET" to "카드사에 결제 정보가 없어 이 카드는 등록할 수 없어요",
    "CARD_ACCOUNT_NOT_LINKED" to "이 카드의 결제 계좌를 먼저 연결해 주세요",
    "PAYMENT_DAY_CHANGE_LOCKED" to "결제일은 바꾸고 3개월이 지나야 다시 바꿀 수 있어요",
    "CARD_UNREGISTERED" to "등록을 해제한 카드예요",
    "CARD_HAS_OPEN_BILL" to "출금 전인 청구서가 있어 카드를 해제할 수 없어요",
    "DEFAULT_CARD_REQUIRED" to "자동 선택을 끄려면 기본 카드를 먼저 정해 주세요",
    "BILL_OUTSTANDING" to "출금 전인 청구서가 있어 탈퇴할 수 없어요",
    "REFUND_PENDING" to "입금 전인 환급이 있어 탈퇴할 수 없어요",
    "INVALID_STATUS_CHANGE" to "지금 상태에서는 바꿀 수 없어요",
    "PAID_OCCURRENCE_LOCKED" to "실제 결제와 연결된 회차가 있어 바꿀 수 없어요",
    "CASHFLOW_MATCH_CONFLICT" to "이미 다른 결제나 예정 지출과 연결돼 있어요",
    "PAYMENT_ALREADY_PROCESSED" to "이미 처리된 결제예요",
    "RETRY_LIMIT_EXCEEDED" to "다른 카드로 다시 시도할 수 있는 횟수를 넘었어요",
    "PAYMENT_NOT_CANCELABLE" to "취소할 수 없는 결제예요",
    "ALREADY_CANCELED" to "이미 취소한 결제예요",
    "NO_ACTIVE_CARD" to "결제에 쓸 카드가 없어요 · 카드를 먼저 등록해 주세요",
    "BARCODE_ALREADY_USED" to "이미 결제에 쓴 바코드예요",
)

/** 새로고침이 실패해 마지막 값을 보여 줄 때 한 줄 — '은행 연결이 늦어요 · 마지막 오늘 15:44 반영'(언제 값인지 모르면 '마지막 값을 보여 드려요') */
fun staleLine(message: String, syncedAtIso: String?): String =
    "$message · " + (formatSyncedAt(syncedAtIso)?.let { "마지막 $it" } ?: "마지막 값을 보여 드려요")
