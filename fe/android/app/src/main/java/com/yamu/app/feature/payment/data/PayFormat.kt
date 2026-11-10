package com.yamu.app.feature.payment.data

import com.yamu.app.core.format.toWon
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
 * 실적 줄의 '+'와 헷갈리지 않게 마이너스는 쓰지 않는다(11/10 QA)
 */
fun ReceiptBenefit.amountText(): String {
    val won = "${amount.toWon()}원"
    return when (kind) {
        BenefitKind.INSTANT, BenefitKind.UNKNOWN -> "$won 할인"
        BenefitKind.BILLING -> "$won 청구할인"
        BenefitKind.CASHBACK -> "$won 환급"
        BenefitKind.POINT -> "$won 적립"
    }
}

/** 즉시할인이 있을 때 큰 금액 아래 한 줄 — '6,600원에서 660원 바로 할인'. 없으면 null */
fun PaymentReceipt.instantDiscountText(): String? =
    instantDiscount.takeIf { it > 0 }?.let { "${amount.toWon()}원에서 ${it.toWon()}원 바로 할인" }

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
