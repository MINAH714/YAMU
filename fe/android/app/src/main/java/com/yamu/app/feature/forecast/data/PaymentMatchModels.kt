package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiError
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// ── 예정 지출 · 실제 결제 연결 — BE PaymentMatchController(-319), docs/be/cashflow.md 「결제 매칭」 ──
// 카드를 지정한 예정 지출 회차 하나에 내 YAMU 승인 결제 하나. 연결하면 잔고예보에서 그 회차는 예정 차감에서 빠지고 실제 결제만 남는다

@Serializable data class MatchConfirmRequest(val cashflowItemId: Long, val occurrenceDate: String, val paymentId: Long)

@Serializable data class MatchConfirmedDto(
    val matchId: Long,
    val cashflowItemId: Long? = null,
    val occurrenceDate: String? = null,
    val paymentId: Long? = null,
    val matchStatus: String? = null,
    val matchedAt: String? = null,
    val warnings: List<String> = emptyList(),
)

/** 연결된 결제. amount는 결제 금액(즉시할인 전), approvedAmount는 실제로 나간 승인 금액 */
@Serializable data class MatchViewDto(
    val matchId: Long,
    val paymentId: Long,
    val merchantName: String? = null,
    val amount: Long? = null,
    val approvedAmount: Long? = null,
    val paymentStatus: String? = null,
    val matchStatus: String? = null,
    val matchedAt: String? = null,
)

/** 연결 상태 — 연결이 없으면 match가 null */
@Serializable data class MatchStatusDto(val cashflowItemId: Long? = null, val occurrenceDate: String? = null, val match: MatchViewDto? = null)

/** approvedAt은 UTC */
@Serializable data class MatchCandidateDto(
    val paymentId: Long,
    val merchantName: String? = null,
    val amount: Long? = null,
    val approvedAmount: Long? = null,
    val approvedAt: String? = null,
    val userCardId: Long? = null,
    val cardType: String? = null,
    val matchReasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** 후보 — 예정일 앞뒤 7일, 아직 연결되지 않은 승인 결제 최대 10건. match는 지금 연결된 결제(있으면 다른 후보로 확정 시 409) */
@Serializable data class MatchCandidatesDto(
    val cashflowItemId: Long? = null,
    val occurrenceDate: String? = null,
    val scheduledDate: String? = null,
    val scheduledAmount: Long? = null,
    val match: MatchViewDto? = null,
    val candidates: List<MatchCandidateDto> = emptyList(),
)

// ── 앱 안에서 쓰는 값 ──

/** 결제와 연결할 수 있는 회차 — 카드를 지정한 예정 지출만(계좌 이체 · 자동이체는 연결할 결제가 없어 BE가 400) */
val CashflowEntry.isMatchable: Boolean get() = !isIncome && userCardId != null

/** ACTIVE: 연결됨 · REVIEW_REQUIRED: 연결 뒤 결제가 취소돼 다시 확인 필요(회차는 다시 예정으로 세지만 연결 · 잠금은 남음) */
enum class MatchState { ACTIVE, REVIEW_REQUIRED, UNKNOWN }

data class PaymentMatch(
    val matchId: Long,
    val paymentId: Long,
    val merchantName: String,
    val amount: Long?,
    val approvedAmount: Long?,
    val state: MatchState,
) {
    /** 실제로 나간 돈 — 승인 금액, 없으면 결제 금액 */
    val paidAmount: Long? get() = approvedAmount ?: amount
}

data class MatchCandidate(
    val paymentId: Long,
    val merchantName: String,
    val amount: Long?,
    val approvedAmount: Long?,
    val approvedDate: LocalDate?,
    val isCredit: Boolean?,
    val reasons: List<String>,
    val warnings: List<String>,
) {
    val paidAmount: Long? get() = approvedAmount ?: amount
}

data class MatchCandidates(val scheduledDate: LocalDate?, val scheduledAmount: Long?, val match: PaymentMatch?, val candidates: List<MatchCandidate>)

/** 방금 연결한 결과 — 경고는 연결을 막지 않고 알리기만 한다 */
data class MatchConfirmed(val matchId: Long, val warnings: List<String>, val state: MatchState = MatchState.ACTIVE)

/** 연결해도 되지만 알릴 차이(BE Warning) — 모르는 값은 빼고 보인다 */
internal fun matchWarningText(code: String): String? = when (code.uppercase()) {
    "AMOUNT_MISMATCH" -> "금액이 달라요"
    "CARD_MISMATCH" -> "다른 카드로 결제했어요"
    "DATE_FAR" -> "예정일과 7일 넘게 떨어져 있어요"
    "MERCHANT_CATEGORY_MISMATCH" -> "업종이 달라요"
    else -> null
}

/** 후보로 고른 근거(BE Reason) */
internal fun matchReasonText(code: String): String? = when (code.uppercase()) {
    "AMOUNT_MATCH" -> "금액 같음"
    "CARD_MATCH" -> "같은 카드"
    "MERCHANT_CATEGORY_MATCH" -> "같은 업종"
    "DATE_NEAR" -> "예정일 근처"
    else -> null
}

/**
 * UTC 시각 → 한국 날짜(BE도 매칭 날짜를 승인 시각의 한국 날짜로 센다).
 * BE UtcLocalDateTimeSerializer는 끝에 Z를 붙여 보낸다("2026-11-05T03:30:00Z"). Z가 없는 값도 UTC로 읽는다
 */
internal fun utcToKstDate(value: String?): LocalDate? = value?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(it).atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate() }.getOrNull()
}

fun MatchViewDto.toMatch() = PaymentMatch(
    matchId = matchId,
    paymentId = paymentId,
    merchantName = merchantName?.takeIf { it.isNotBlank() } ?: "결제",
    amount = amount,
    approvedAmount = approvedAmount,
    state = enumOf(matchStatus, MatchState.UNKNOWN),
)

fun MatchCandidatesDto.toCandidates() = MatchCandidates(
    scheduledDate = parseDate(scheduledDate),
    scheduledAmount = scheduledAmount,
    match = match?.toMatch(),
    candidates = candidates.map { c ->
        MatchCandidate(
            paymentId = c.paymentId,
            merchantName = c.merchantName?.takeIf { it.isNotBlank() } ?: "결제",
            amount = c.amount,
            approvedAmount = c.approvedAmount,
            approvedDate = utcToKstDate(c.approvedAt),
            isCredit = c.cardType?.let { it.equals("CREDIT", ignoreCase = true) },
            reasons = c.matchReasons.mapNotNull(::matchReasonText).distinct(),
            warnings = c.warnings.mapNotNull(::matchWarningText).distinct(),
        )
    },
)

/** 승인된 결제만 연결할 수 있어 matchStatus가 없으면 ACTIVE로 본다 */
fun MatchConfirmedDto.toConfirmed() = MatchConfirmed(matchId, warnings.mapNotNull(::matchWarningText).distinct(), enumOf(matchStatus, MatchState.ACTIVE))

/** 확정 응답에는 가맹점 · 금액이 없어 고른 후보에서 채운다. 후보를 못 찾으면 이름만 '결제'로 두고 상태 조회가 채운다 */
fun MatchConfirmed.toMatch(paymentId: Long, picked: MatchCandidate?) = PaymentMatch(
    matchId = matchId,
    paymentId = paymentId,
    merchantName = picked?.merchantName ?: "결제",
    amount = picked?.amount,
    approvedAmount = picked?.approvedAmount,
    state = state,
)

/** 연결 · 해제 실패 → 문장. 409는 회차나 결제가 이미 다른 상대와 연결됨(먼저 해제해야 함) */
fun ApiError.toMatchMessage(): String = when {
    code == "CASHFLOW_MATCH_CONFLICT" -> "이 회차나 결제가 이미 다른 것과 연결돼 있어요 · 목록을 새로 불러왔어요"
    this is ApiError.NotFound -> "예정 지출이나 결제를 찾을 수 없어요 · 목록을 새로 불러왔어요"
    this is ApiError.InvalidRequest -> fieldErrors().values.firstOrNull { it.isNotBlank() } ?: "연결할 수 없는 결제예요 · 취소됐거나 카드를 지정하지 않은 예정 지출일 수 있어요"
    this == ApiError.Network -> "인터넷 연결을 확인해 주세요"
    else -> "잠시 뒤 다시 시도해 주세요"
}
