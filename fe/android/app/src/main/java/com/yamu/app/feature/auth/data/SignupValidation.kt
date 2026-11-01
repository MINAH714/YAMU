package com.yamu.app.feature.auth.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 본인인증 입력 규칙 — 서버(PhoneVerificationRequest · PersonalDataNormalizer)와 같게 앱에서 먼저. 틀리면 안내 문장, 맞으면 null */
object SignupValidation {
    private val PHONE = Regex("010\\d{8}|01[16789]\\d{7,8}")

    fun name(value: String): String? =
        if (value.trim().length in 2..20) null else "이름은 2~20자로 넣어 주세요"

    /** 생년월일 8자리(YYYYMMDD) → 날짜. 없는 날짜 · 오늘 이후면 null */
    fun birthDate(value: String, today: LocalDate = LocalDate.now()): LocalDate? {
        if (value.length != 8 || !value.all(Char::isDigit)) return null
        val date = runCatching { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return null
        return date.takeIf { it.isBefore(today) && it.year >= 1900 }
    }

    fun birthDateError(value: String, today: LocalDate = LocalDate.now()): String? =
        if (birthDate(value, today) == null) "생년월일 8자리를 확인해 주세요(예: 19980101)" else null

    fun phone(value: String): String? =
        if (PHONE.matches(value)) null else "휴대폰 번호를 확인해 주세요(숫자만)"
}

/**
 * 가입 · 기기 재등록 서버 오류 코드 → 사용자 문장. 모르는 코드면 null(인증 · 공통 문장 사용).
 * [remainingAttempts] · [retryAfterSeconds]는 서버 error.details
 */
fun signupErrorMessage(code: String, remainingAttempts: Int? = null, retryAfterSeconds: Int? = null): String? {
    val left = remainingAttempts?.let { " · ${it}번 남았어요" }.orEmpty()
    val wait = retryAfterSeconds?.let { "${it}초 뒤" } ?: "잠시 뒤"
    return when (code) {
        "DEMO_USER_NOT_FOUND" -> "시연에 등록된 사용자가 아니에요 · 이름 · 생년월일 · 번호를 확인해 주세요"
        "REJOIN_NOT_AVAILABLE" -> "탈퇴한 사용자는 다시 가입할 수 없어요"
        "VERIFICATION_TOO_FREQUENT", "AUTH_REQUEST_TOO_FREQUENT" -> "방금 보냈어요 · $wait 다시 받아 주세요"
        "VERIFICATION_CODE_MISMATCH" -> "인증번호가 맞지 않아요$left"
        "VERIFICATION_EXPIRED" -> "인증번호 시간이 지났어요 · 다시 받아 주세요"
        SIGNUP_TOKEN_INVALID -> "시간이 지났어요 · 본인인증부터 다시 해 주세요"
        "SIGNUP_STEP_INCOMPLETE" -> "앞 단계를 먼저 마쳐 주세요"
        "ACCOUNT_CANDIDATE_EXPIRED" -> "계좌 목록이 오래됐어요 · 다시 불러왔어요"
        "ONE_WON_DAILY_LIMIT_EXCEEDED" -> "오늘은 1원을 더 보낼 수 없어요 · 내일 다시 해 주세요"
        "AUTH_CODE_MISMATCH" -> "숫자가 맞지 않아요$left"
        "AUTH_CODE_EXPIRED", "VERIFICATION_TOKEN_EXPIRED" -> "시간이 지났어요 · 1원을 다시 받아 주세요"
        "AUTH_CODE_LOCKED" -> "오늘은 숫자를 너무 많이 틀렸어요 · 내일 다시 해 주세요"
        "IDEMPOTENCY_IN_PROGRESS" -> "처리하고 있어요 · 잠시 뒤 다시 눌러 주세요"
        "ACCOUNT_ALREADY_LINKED" -> "이미 연결된 계좌예요"
        "TOO_MANY_REQUESTS" -> "요청이 너무 잦아요 · $wait 다시 시도해 주세요"
        else -> null
    }
}
