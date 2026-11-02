package com.yamu.app.feature.auth.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서버와 같은 입력 규칙을 앱에서 먼저(08 문서 4-2). 틀리면 안내 문장, 맞으면 null. 본인인증 칸은 SignupValidation */
object AuthValidation {

    /** 숫자 6자리 · 같은 숫자 반복 · 연속 숫자(오름 · 내림) 금지 — API #14 */
    fun pin(value: String): String? {
        if (value.length != 6 || !value.all { it.isDigit() }) return "숫자 6자리를 넣어 주세요"
        val digits = value.map { it - '0' }
        val allSame = digits.all { it == digits[0] }
        val ascending = digits.zipWithNext().all { (a, b) -> b == a + 1 }
        val descending = digits.zipWithNext().all { (a, b) -> b == a - 1 }
        return if (allSame || ascending || descending) "쉬운 번호는 쓸 수 없어요" else null
    }
}

/**
 * 인증 관련 서버 오류 코드 → 사용자 문장(08 문서 4-3). 모르는 코드면 null(공통 문장 사용).
 * [unlockAt]은 잠금이 풀리는 시각(HH:mm, [lockedUntilClock]으로 바꾼 값)
 */
fun authErrorMessage(code: String, remainingAttempts: Int? = null, unlockAt: String? = null): String? = when (code) {
    "INVALID_PIN_FORMAT" -> "쉬운 번호는 쓸 수 없어요"
    // 로그인은 PIN_MISMATCH, 본인 확인은 STEP_UP_FAILED. 남은 횟수가 없으면 서명 실패(지문 등)
    "PIN_MISMATCH", "STEP_UP_FAILED" ->
        if (remainingAttempts != null) "간편 비밀번호가 맞지 않아요 · ${remainingAttempts}번 남았어요" else "본인 확인에 실패했어요. 다시 시도해 주세요"
    "SIGNATURE_INVALID" -> "본인 확인에 실패했어요. 다시 시도해 주세요"
    "DEVICE_LOCKED" -> "5번 틀려서 잠겼어요" + (unlockAt?.let { " · ${it}에 풀려요" } ?: " · 30분 뒤에 다시 시도해 주세요")
    "CHALLENGE_EXPIRED" -> "시간이 지났어요. 다시 시도해 주세요"
    "BIOMETRIC_NOT_ENABLED" -> "이 폰은 지문이 꺼져 있어요 · 간편 비밀번호로 해 주세요"
    "DEVICE_NOT_REGISTERED" -> "이 폰은 등록돼 있지 않아요 · 처음부터 다시 시작해 주세요"
    "INVALID_PUBLIC_KEY", "KEY_CREATE_FAILED" -> "이 폰의 보안 키를 만들지 못했어요. 다시 시도해 주세요"
    "KEY_SIGN_FAILED" -> "이 폰의 보안 키를 쓰지 못했어요. 다시 시도해 주세요"
    "TOO_MANY_REQUESTS" -> "요청이 너무 잦아요 · 잠시 뒤 다시 시도해 주세요"
    "DEVICE_LIMIT_EXCEEDED" -> "기기는 1대만 쓸 수 있어요 · 마이에서 예전 기기를 해제해 주세요"
    "FIN_API_UNAVAILABLE" -> "은행 연결이 늦어요 · 잠시 뒤 다시 시도해 주세요"
    else -> null
}

/** 서버 lockedUntil(UTC, 2026-11-01T12:30:00Z) → 이 폰 시각 HH:mm. 못 읽으면 null */
fun lockedUntilClock(lockedUntil: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    lockedUntil?.let { runCatching { Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull() }

/** 서버 lockedUntil까지 남은 시간(ms). 못 읽거나 이미 지났으면 null — 입력을 막지 않고 다음 응답에 맡긴다 */
fun lockRemainingMillis(lockedUntil: String?, now: Instant = Instant.now()): Long? =
    lockedUntil?.let { runCatching { Instant.parse(it).toEpochMilli() - now.toEpochMilli() }.getOrNull() }?.takeIf { it > 0 }
