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
 * 본인인증 입력 화면 안내 — 가입은 미리 등록된 사람만 된다는 것을 인증번호를 받기 전에 알린다(B-03). 누가 등록됐는지는 드러나지 않는다.
 * 첫 화면 '로그인'으로 온 경우는 가입한 사람이 대부분이지만 서버가 가입으로 이어 줄 수도 있어 두 경우 모두 맞는 문장으로
 */
fun phoneVerifyGuide(signup: Boolean): String =
    if (signup) "야무는 시연용으로 미리 등록된 분만 가입할 수 있어요.\n등록된 이름 · 생년월일 · 휴대폰 번호를 넣어 주세요"
    else "시연에 등록된 이름 · 생년월일 · 휴대폰 번호를 넣어 주세요"

/** 404 DEMO_USER_NOT_FOUND — 어느 경우인지 말하지 않고 칸은 그대로 두어 오타만 고쳐 다시 받게 한다(B-03) */
fun demoUserNotFoundMessage(signup: Boolean): String =
    if (signup) "입력한 정보로는 가입할 수 없어요 · 등록된 이름 · 생년월일 · 번호인지 확인해 주세요"
    else "입력한 정보로는 본인 확인을 할 수 없어요 · 등록된 이름 · 생년월일 · 번호인지 확인해 주세요"

/**
 * 가입 · 기기 재등록 서버 오류 코드 → 사용자 문장. 모르는 코드면 null(인증 · 공통 문장 사용).
 * INVALID_PIN_FORMAT · INVALID_PUBLIC_KEY · FIN_API_UNAVAILABLE은 인증 문장([authErrorMessage])이 받는다.
 * 409 · 404처럼 공통 문장으로 떨어지면 엉뚱해지는 코드와 가입 중엔 따를 수 없는 인증 문장(DEVICE_LIMIT_EXCEEDED)은 여기서 잡는다.
 * [remainingAttempts] · [retryAfterSeconds]는 서버 error.details
 */
fun signupErrorMessage(code: String, remainingAttempts: Int? = null, retryAfterSeconds: Int? = null): String? {
    val left = remainingAttempts?.let { " · ${it}번 남았어요" }.orEmpty()
    val wait = retryAfterSeconds?.let { "${it}초 뒤" } ?: "잠시 뒤"
    return when (code) {
        // 명단에 없음 · 하나라도 다름 · 시험망 준비 전 · 탈퇴 후 이름 다름이 모두 같은 404다. 번호만으로 가입 여부를 알 수 없게
        // 서버가 일부러 나누지 않으므로 문구도 어느 경우인지 말하지 않는다(B-03). '이 폰 등록'은 [demoUserNotFoundMessage]
        "DEMO_USER_NOT_FOUND" -> demoUserNotFoundMessage(signup = true)
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
        // 1원을 보낼 때 은행이 거절(BE SsafyAccountApi)
        "ACCOUNT_NOT_FOUND_AT_BANK" -> "은행에서 이 계좌를 찾지 못했어요 · 다른 계좌를 골라 주세요"
        "ACCOUNT_NOT_SUPPORTED" -> "입출금 계좌만 연결할 수 있어요 · 다른 계좌를 골라 주세요"
        // 같은 키로 다른 숫자를 보냈다(계좌 연결은 성공한 결과만 키에 남아 거의 없다)
        "IDEMPOTENCY_KEY_REUSED" -> "요청이 겹쳤어요 · 1원을 다시 받아 주세요"
        // 가입 모드로 PIN · 기기를 등록하는 사이 가입이 끝났다 — 첫 화면 '로그인'으로 본인인증하면 재등록으로 이어진다
        "ALREADY_REGISTERED" -> "이미 가입을 마쳤어요 · 처음 화면의 로그인으로 이 폰을 등록해 주세요"
        // 재등록은 다른 기기를 먼저 해제해서 가입 중에만 나는 안전장치다. 가입 중엔 마이 화면에 갈 수 없다
        "DEVICE_LIMIT_EXCEEDED" -> "이미 등록된 폰이 있어요 · 처음 화면의 로그인으로 이 폰을 다시 등록해 주세요"
        "TOO_MANY_REQUESTS" -> "요청이 너무 잦아요 · $wait 다시 시도해 주세요"
        else -> null
    }
}
