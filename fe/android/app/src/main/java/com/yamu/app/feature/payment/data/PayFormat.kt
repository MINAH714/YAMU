package com.yamu.app.feature.payment.data

import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.toWon
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

// 결제 화면 · 모의 POS 단말이 같이 쓰는 표기(단위 테스트 PayFormatTest)

/** 결제번호를 4자리씩 — 'YAMU8F2A9C1D7E' → 'YAMU 8F2A 9C1D 7E' */
fun groupCode(value: String, size: Int = 4): String = value.chunked(size).joinToString(" ")

/** 남은 시간 'mm:ss' — 0보다 작으면 00:00 */
fun formatRemaining(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** 서버 시각(ISO-8601) → epoch 밀리초. 못 읽으면 null */
fun epochMillisOrNull(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

/** 서버 시각(ISO-8601)까지 남은 초. 못 읽으면 null */
fun secondsUntil(iso: String?, now: Instant = Instant.now()): Long? {
    val at = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
    return (at.toEpochMilli() - now.toEpochMilli() + 999) / 1000   // 올림 — 0.4초 남으면 1초
}

private const val QR_PREFIX = "yamu://pay/"

/**
 * 모의 POS가 찍거나 손으로 넣은 값 → 결제 토큰.
 * QR은 'yamu://pay/{토큰}', 바코드 · 손 입력은 토큰 그대로(띄어쓰기 · 하이픈은 지움). 비면 null
 */
fun parseScannedCode(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    val body = if (text.startsWith(QR_PREFIX, ignoreCase = true)) text.substring(QR_PREFIX.length) else text
    return body.filter { it.isLetterOrDigit() }.uppercase().ifEmpty { null }
}

/**
 * 결제 완료 · 상세 혜택 줄의 금액 — 부호 없이 반영되는 때를 붙인다('660원 할인' · '3,180원 청구할인' · '3,180원 환급' · '1,500원 적립').
 * 원화로 못 바꾼 적립은 포인트 수에 P를 붙인다('1,500P 적립', S15P31E103-580).
 * 실적 줄의 '+'와 헷갈리지 않게 마이너스는 쓰지 않는다(11/10 QA)
 */
fun ReceiptBenefit.amountText(): String {
    points?.let { return "${it.toWon()}P 적립" }
    val won = "${amount.toWon()}원"
    return when (kind) {
        BenefitKind.INSTANT, BenefitKind.UNKNOWN -> "$won 할인"
        BenefitKind.BILLING -> "$won 청구할인"
        BenefitKind.CASHBACK -> "$won 환급"
        BenefitKind.POINT -> "$won 적립"
    }
}

// ── 결제 확인(P-06c) 카드 칸 — S15P31E103-555 ──

/** 카드 칸의 혜택 방식 태그. 반영 시점을 모르면 null(태그 없이 금액만) */
fun BenefitKind.tagText(): String? = when (this) {
    BenefitKind.INSTANT -> "즉시할인"
    BenefitKind.BILLING -> "청구할인"
    BenefitKind.CASHBACK -> "환급할인"
    BenefitKind.POINT -> "적립"
    BenefitKind.UNKNOWN -> null
}

/**
 * 카드 칸의 혜택 금액 — 순혜택(원)에 방식별 말을 붙인다('1,980원 할인' · '660원 돌려받아요' · '330원 적립').
 * 비교 못 한 카드 · 순혜택 0원 이하는 null.
 * 임시: 적립은 결제 완료 화면('1,500원 적립')처럼 원으로 쓴다 — 결제 응답에 포인트의 원화 가치가 없어 순혜택 값을 쓴다
 */
fun PayCard.benefitHeadline(): String? {
    if (benefitUnknown || netBenefit.signum() <= 0) return null
    val won = "${netBenefit.toWon()}원"
    if (hasMixedBenefits) return "$won 예상 혜택"
    return when (benefitKind) {
        BenefitKind.CASHBACK -> "$won 돌려받아요"
        BenefitKind.POINT -> "$won 적립"
        BenefitKind.INSTANT, BenefitKind.BILLING, BenefitKind.UNKNOWN -> "$won 할인"
    }
}

/**
 * 결제 버튼 — '삼성카드로 6,600원 결제'. 즉시할인 카드는 실제로 빠지는 금액만 '5,940원 결제'(서버 chargedAmount = 결제 금액 − 즉시할인).
 * 임시: 즉시할인 버튼에 카드 이름을 붙일지는 미정(S15P31E103-555)
 */
fun PayCard.payButtonLabel(amount: Long): String {
    val charged = chargedAmount
    if ((benefitKind == BenefitKind.INSTANT || hasMixedBenefits) && charged != null && charged in 0 until amount) return "${charged.toWon()}원 결제"
    return "${brand.name}${brand.name.directionParticle()} ${amount.toWon()}원 결제"
}

/** 목록 아래 비교 문구의 값 — [pickSelected]면 '야무픽이 N원 더 아껴요', 아니면 '야무픽으로 내면 N원 더 아낄 수 있어요' */
data class PickGap(val won: BigDecimal, val pickSelected: Boolean)

/**
 * 야무픽과 고른 카드의 순혜택 차이. 아래면 null(문구를 숨긴다):
 * 야무픽이 없다 · 야무픽이 이 결제에서 거절됐다 · 고른 카드가 없거나 비교 못 한 카드다 · 차이가 0원 이하다.
 * 야무픽을 골랐으면 비교한 다른 카드 중 순혜택이 가장 큰 카드와 견준다(그런 카드가 없으면 null)
 */
fun pickGap(cards: List<PayCard>, selected: PayCard?, declinedIds: Set<Long> = emptySet()): PickGap? {
    val pick = cards.firstOrNull { it.isYamuPick } ?: return null
    if (pick.userCardId in declinedIds || selected == null || selected.benefitUnknown) return null
    val pickSelected = selected.userCardId == pick.userCardId
    val other = if (pickSelected) {
        cards.filter { !it.benefitUnknown && it.userCardId != pick.userCardId }.maxByOrNull { it.netBenefit } ?: return null
    } else selected
    val gap = pick.netBenefit - other.netBenefit
    return if (gap.signum() > 0) PickGap(gap, pickSelected) else null
}

/**
 * 즉시할인이 있을 때 큰 금액 아래 한 줄 — '6,600원에서 660원 바로 할인'. 없으면 null.
 * 취소된 결제는 할인도 함께 없어져 null(서버 chargedAmount는 취소 뒤에도 승인 당시 값이라 금액만으로는 못 가린다, S15P31E103-582)
 */
fun PaymentReceipt.instantDiscountText(): String? =
    instantDiscount.takeIf { it > 0 && !canceled }?.let { "${amount.toWon()}원에서 ${it.toWon()}원 바로 할인" }

/** 승인 거절 코드(#1 failure.code) → 사용자 문장 */
fun declineMessage(code: String?): String = when (code) {
    "INSUFFICIENT_BALANCE" -> "연결 계좌 잔액이 부족해요"
    "CREDIT_LIMIT_EXCEEDED" -> "카드 한도를 넘었어요"
    "CARD_SUSPENDED" -> "정지된 카드예요"
    "UNPAID_BILL" -> "미납된 청구가 있는 카드예요"
    "ISSUER_DECLINED" -> "카드사가 결제를 거절했어요"
    "ACCOUNT_UNAVAILABLE" -> "출금 계좌를 쓸 수 없어요 · 계좌를 다시 연결해 주세요"    // BE 임시 코드(명세 8장 Q9)
    // BE 임시 코드 — 출금이 늦게 처리되면 나중에 승인될 수 있어 다시 결제하라고 하지 않는다(BE PosFailureMessages와 같은 안내)
    "WITHDRAWAL_NOT_CONFIRMED" -> "결제 결과를 확인하지 못했어요 · 결제 내역에서 결과를 확인해 주세요"
    else -> "결제가 승인되지 않았어요"
}

/** 모의 POS 결제 요청(#27) · 결과 조회(#63) 오류 코드 → 가맹점 직원이 볼 문장 */
fun posErrorMessage(code: String?): String = when (code) {
    "BARCODE_EXPIRED" -> "고객 코드 시간이 지났어요 · 새 코드를 받아 달라고 해 주세요"
    "BARCODE_ALREADY_USED" -> "이미 결제에 쓴 코드예요"
    "BARCODE_NOT_FOUND" -> "코드를 찾을 수 없어요 · 다시 찍어 주세요"
    "INVALID_POS_KEY" -> "단말 키가 맞지 않아요 · 설정을 확인해 주세요"
    "INVALID_AMOUNT" -> "금액을 다시 넣어 주세요"
    "MERCHANT_NOT_FOUND" -> "가맹점을 다시 골라 주세요"
    "IDEMPOTENCY_IN_PROGRESS" -> "서버가 같은 요청을 아직 처리하고 있어요 · 잠시 뒤 다시 보내 주세요"
    "IDEMPOTENCY_KEY_REUSED" -> "앞 요청과 내용이 달라 보낼 수 없어요 · 코드를 다시 찍어 주세요"
    "RESOURCE_NOT_FOUND" -> "결제를 찾을 수 없어요 · 이 단말에서 만든 결제인지 확인해 주세요"
    else -> "결제 요청을 보내지 못했어요 · 다시 시도해 주세요"
}
