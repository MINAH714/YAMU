package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.security.SessionStore
import com.yamu.app.feature.link.data.BankDto
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.brandOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

// ── 서버 요청 · 응답(API 명세 V2 「휴대폰 본인인증」 「계좌 연결」 「기기 등록」 — BE auth/dto · account/dto와 같은 이름) ──

/** carrier: SKT · KT · LGU · MVNO, birthDate: YYYY-MM-DD, phoneNumber: 숫자만 */
@Serializable data class PhoneVerificationRequest(val name: String, val birthDate: String, val carrier: String, val phoneNumber: String)

/** demoCode — 시연용 인증번호(실제 문자는 보내지 않는다) */
@Serializable data class PhoneVerificationResponse(val verificationId: String, val expiresAt: String? = null, val demoCode: String? = null)

@Serializable data class PhoneConfirmRequest(val code: String)

/** mode: SIGNUP(가입 전 시드 사용자) · DEVICE_RECOVERY(이미 가입 — 이 폰 다시 등록) */
@Serializable data class PhoneConfirmResponse(val signupToken: String, val expiresIn: Long = 1800, val mode: String, val nextStep: String? = null)

@Serializable data class AccountCandidatesResponse(val accounts: List<AccountCandidateDto> = emptyList(), val expiresAt: String? = null)

@Serializable data class AccountCandidateDto(
    val candidateId: String,
    val bank: BankDto,
    val maskedAccountNo: String,
    val accountName: String? = null,
    val alreadyLinked: Boolean = false,
)

/** 재등록은 연결된 계좌 하나만 */
@Serializable data class OneWonAuthRequest(val candidateIds: List<String>, val primaryCandidateId: String)

@Serializable data class OneWonAuthResponse(
    val verificationToken: String,
    val bank: BankDto? = null,
    val maskedAccountNo: String? = null,
    /** UTC(끝에 Z). authExpiresAt = 요청 시각 + 5분 — 둘의 차로 남은 시간을 센다 */
    val authRequestedAt: String? = null,
    val authExpiresAt: String? = null,
    val depositorNamePrefix: String = "YAMU",
    val codeLength: Int = 4,
    val hint: String? = null,
    /** 금융망 응답이 늦었음 — 1원이 안 보이면 1분 뒤 다시 받기 */
    val deliveryUncertain: Boolean = false,
    /** 시연용 1원 숫자(BE S15P31E103-350, 시연 서버만 · 없으면 필드가 빠진다). 오면 문자 알림으로 띄운다 */
    val demoCode: String? = null,
)

@Serializable data class SignupAccountLinkRequest(val verificationToken: String, val authCode: String)

/** 가입 · 기기 재등록 API — 가입 진행 토큰(sgt_…)을 [bearer]로 직접 붙인다(인증 헤더 없는 클라이언트) */
interface SignupApi {
    @POST("auth/phone-verifications")
    suspend fun requestCode(@Body body: PhoneVerificationRequest): Response<ApiEnvelope<PhoneVerificationResponse>>

    @POST("auth/phone-verifications/{id}/confirm")
    suspend fun confirm(@Path("id") verificationId: String, @Body body: PhoneConfirmRequest): Response<ApiEnvelope<PhoneConfirmResponse>>

    @GET("accounts/candidates")
    suspend fun candidates(@Header(YamuHeaders.AUTHORIZATION) bearer: String): Response<ApiEnvelope<AccountCandidatesResponse>>

    @POST("accounts/one-won-auth")
    suspend fun oneWon(@Header(YamuHeaders.AUTHORIZATION) bearer: String, @Body body: OneWonAuthRequest): Response<ApiEnvelope<OneWonAuthResponse>>

    @POST("accounts")
    suspend fun linkAccounts(
        @Header(YamuHeaders.AUTHORIZATION) bearer: String,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: SignupAccountLinkRequest,
    ): Response<ApiEnvelope<JsonElement>>

    @POST("auth/pin")
    suspend fun setPin(@Header(YamuHeaders.AUTHORIZATION) bearer: String, @Body body: PinRequest): Response<ApiEnvelope<JsonElement>>

    @POST("auth/devices")
    suspend fun registerDevice(@Header(YamuHeaders.AUTHORIZATION) bearer: String, @Body body: DeviceRequest): Response<ApiEnvelope<DeviceResponse>>
}

// ── 앱 안에서 쓰는 값 ──

enum class SignupMode { SIGNUP, DEVICE_RECOVERY }

enum class Carrier(val code: String, val label: String) {
    SKT("SKT", "SKT"),
    KT("KT", "KT"),
    LGU("LGU", "LG U+"),
    MVNO("MVNO", "알뜰폰"),
}

data class CodeSent(val verificationId: String, val demoCode: String?)

/** 본인인증 칸에 넣은 값(고치는 대로) — 본인인증 화면을 다시 열거나 본인인증부터 다시 할 때 채운다. [birth]는 8자리 그대로 */
data class IdentityDraft(val name: String = "", val birth: String = "", val carrier: Carrier? = null, val phone: String = "") {
    val isEmpty: Boolean get() = name.isEmpty() && birth.isEmpty() && carrier == null && phone.isEmpty()
}

/** 본인인증부터 다시 하게 된 까닭 — EXPIRED: 서버가 가입 진행 토큰을 거절(30분 지남 등) · LOST: 앱이 다시 켜져 진행 상태가 없음 */
enum class SignupRestart { EXPIRED, LOST }

data class AccountCandidate(val id: String, val brand: Brand, val name: String, val last4: String, val alreadyLinked: Boolean, val bankCode: String? = null)

data class SignupOneWon(
    val bankName: String,
    val last4: String,
    val prefix: String,
    val codeLength: Int,
    val hint: String?,
    val deliveryUncertain: Boolean,
    val demoCode: String?,
    /**
     * 1원 숫자를 넣을 수 있는 시간(초)과 1원 요청을 보낸 이 폰 시각 — 남은 시간 표시용(최종 만료는 SSAFY가 판단).
     * BE는 은행 송금이 끝난 뒤 authRequestedAt을 찍으니, 요청을 보낸 때부터 세면 응답이 늦어도 남은 시간이 실제보다 길게 보이지 않는다
     */
    val validSeconds: Int = ONE_WON_VALID_SECONDS,
    val sentAtMillis: Long = System.currentTimeMillis(),
)

/** 지금 남은 시간(초). 0 이하면 지났다 */
fun SignupOneWon.secondsLeft(nowMillis: Long): Int = validSeconds - ((nowMillis - sentAtMillis).coerceAtLeast(0) / 1_000).toInt()

/** 1원 응답 → 화면 값. [sentAtMillis]는 요청을 보낸 이 폰 시각(응답을 받은 시각이 아니다) */
fun OneWonAuthResponse.toOneWon(sentAtMillis: Long) = SignupOneWon(
    bankName = bank?.name.orEmpty(),
    last4 = maskedAccountNo.orEmpty().filter(Char::isDigit).takeLast(4),
    prefix = depositorNamePrefix,
    codeLength = codeLength,
    hint = hint,
    deliveryUncertain = deliveryUncertain,
    demoCode = demoCode,
    validSeconds = oneWonValidSeconds(authRequestedAt, authExpiresAt),
    sentAtMillis = sentAtMillis,
)

/** 1원 인증 유효 시간 — BE 「1원 인증 요청」 authExpiresAt = 요청 시각 + 5분 */
const val ONE_WON_VALID_SECONDS = 300

/**
 * 1원 숫자를 넣을 수 있는 시간(초). 서버가 준 두 시각의 차라 폰 시계가 틀려도 맞다.
 * 하나라도 없거나 못 읽으면 5분
 */
fun oneWonValidSeconds(authRequestedAt: String?, authExpiresAt: String?): Int {
    val from = authRequestedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val to = authExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val seconds = if (from != null && to != null) Duration.between(from, to).seconds else null
    return seconds?.takeIf { it in 1..3_600 }?.toInt() ?: ONE_WON_VALID_SECONDS
}

fun AccountCandidateDto.toCandidate() = AccountCandidate(
    id = candidateId,
    brand = brandOf(bank.name),
    name = accountName ?: bank.name,
    last4 = maskedAccountNo.filter(Char::isDigit).takeLast(4),
    alreadyLinked = alreadyLinked,
    bankCode = bank.code,
)

/** 가입 진행 토큰이 없거나 만료 — 본인인증부터 다시 */
const val SIGNUP_TOKEN_INVALID = "SIGNUP_TOKEN_INVALID"

/**
 * 가입 · 기기 재등록 한 번의 진행 상태(메모리에만 — 앱이 꺼지면 본인인증부터 다시).
 * 가입: 본인인증 → 계좌 고르기 → 1원(계좌 연결) → PIN → 기기 등록(토큰 발급).
 * 재등록: 본인인증 → PIN 그대로/새로 · 지문 고르기 → 연결된 계좌 하나로 1원 → 1원 숫자로 기기 등록(토큰 발급).
 */
interface SignupRepository {
    /** 본인인증을 마쳤으면 가입 · 재등록, 아니면 null */
    val mode: SignupMode?
    val name: String?

    /** 약관(A-04)에 동의했나 — 첫 화면 '로그인'으로 들어와 가입이면 약관부터. 서버에 저장하지 않아 본인인증부터 다시 해도 남긴다 */
    var termsAgreed: Boolean

    /**
     * 본인인증 칸에 넣은 값 — 화면이 칸을 고칠 때마다 적는다. 약관을 거쳐 본인인증 화면을 새로 열거나
     * 본인인증부터 다시 할 때 이 값으로 칸을 채운다(인증번호를 받을 때의 값이 아니라 마지막으로 고친 값). 첫 화면으로 가면 지운다
     */
    var draft: IdentityDraft

    /** 재등록 때 지문 키를 같이 등록할지(A-08에서 고름) */
    var recoveryBiometric: Boolean

    /** 재등록에서 새 PIN을 서버에 맡겼나 — 이 본인인증 동안은 기기 등록 때 그 PIN이 적용된다 */
    val pinStaged: Boolean

    /**
     * 가입: 이 본인인증으로 1원 계좌 연결을 마쳤나. PIN 만들기에서 본인인증으로 돌아갔다 와도 1원을 다시 받지 않고
     * PIN 만들기로 바로 잇는다(QA 07 · P1-17). 본인인증을 버리면 같이 지운다
     */
    val accountLinked: Boolean get() = false

    suspend fun requestCode(name: String, birthDate: LocalDate, carrier: Carrier, phoneNumber: String): ApiResult<CodeSent>
    suspend fun confirmCode(verificationId: String, code: String): ApiResult<SignupMode>
    suspend fun candidates(): ApiResult<List<AccountCandidate>>
    suspend fun sendOneWon(candidateIds: List<String>, primaryId: String): ApiResult<SignupOneWon>

    /** 마지막으로 보낸 1원(A-05c 화면이 읽는다) */
    val oneWon: SignupOneWon?

    /** 같은 계좌로 1원 다시 — 1분 뒤부터(서버가 막는다) */
    suspend fun resendOneWon(): ApiResult<SignupOneWon>

    /** 가입: 1원 숫자로 계좌 연결(POST /accounts). 재등록: 1원 숫자로 이 폰 등록(POST /auth/devices) — 토큰까지 받는다 */
    suspend fun confirmOneWon(code: String): ApiResult<Unit>

    /** 가입: 바로 저장 · 재등록: 등록이 끝날 때 바뀐다(서버가 맡아 둠) */
    suspend fun setPin(pin: String): ApiResult<Unit>

    /** 가입 마지막 단계 — 토큰을 받아 저장하고 진행 상태를 지운다 */
    suspend fun registerDevice(biometric: Boolean): ApiResult<Unit>

    /** 받아 둔 본인인증을 버린다(칸을 고쳤다) — 넣은 값 · 약관 동의는 남긴다 */
    fun dropVerification()

    /**
     * 진행을 버리고 본인인증부터 다시 — 넣은 값 · 약관 동의는 남기고 까닭을 적어 둔다.
     * 가입 진행 토큰이 없거나 서버가 거절하면 저장소가 스스로 부른다
     */
    fun restart(reason: SignupRestart)

    /** 본인인증부터 다시 하게 된 까닭 — 본인인증 화면이 한 번 읽고 지운다 */
    fun takeRestartReason(): SignupRestart?

    /** 처음 화면으로 — 넣은 값 · 약관 동의까지 모두 지운다 */
    fun clear()
}

@Singleton
class SignupRepositoryImpl @Inject constructor(
    private val api: SignupApi,
    private val devices: DeviceRegistration,
    private val sessionStore: SessionStore,
) : SignupRepository {
    private var token: String? = null
    private var pendingName: String? = null
    private var verificationToken: String? = null
    private var linkKey: String? = null          // 계좌 연결 멱등 키 — 1원 한 번에 하나, 재시도엔 같은 키
    private var lastRequest: OneWonAuthRequest? = null

    override var oneWon: SignupOneWon? = null
        private set
    override var mode: SignupMode? = null
        private set
    override var name: String? = null
        private set
    override var termsAgreed: Boolean = false
    override var draft: IdentityDraft = IdentityDraft()
    private var restartReason: SignupRestart? = null
    override var recoveryBiometric: Boolean = false
    override var pinStaged: Boolean = false
        private set
    override var accountLinked: Boolean = false
        private set

    override suspend fun requestCode(name: String, birthDate: LocalDate, carrier: Carrier, phoneNumber: String): ApiResult<CodeSent> {
        pendingName = name.trim()
        return apiCall { api.requestCode(PhoneVerificationRequest(name.trim(), birthDate.toString(), carrier.code, phoneNumber)) }
            .map { CodeSent(it.verificationId, it.demoCode) }
    }

    override suspend fun confirmCode(verificationId: String, code: String): ApiResult<SignupMode> =
        apiCall { api.confirm(verificationId, PhoneConfirmRequest(code)) }.map { res ->
            token = res.signupToken
            mode = if (res.mode == "DEVICE_RECOVERY") SignupMode.DEVICE_RECOVERY else SignupMode.SIGNUP
            name = pendingName
            verificationToken = null
            linkKey = null
            lastRequest = null
            oneWon = null
            pinStaged = false
            mode!!
        }

    override suspend fun candidates(): ApiResult<List<AccountCandidate>> =
        withToken { bearer -> apiCall { api.candidates(bearer) }.map { r -> r.accounts.map { it.toCandidate() } } }

    override suspend fun sendOneWon(candidateIds: List<String>, primaryId: String): ApiResult<SignupOneWon> =
        send(OneWonAuthRequest(candidateIds, primaryId))

    override suspend fun resendOneWon(): ApiResult<SignupOneWon> {
        val last = lastRequest ?: return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        return send(last)
    }

    private suspend fun send(request: OneWonAuthRequest): ApiResult<SignupOneWon> =
        withToken { bearer ->
            val sentAt = System.currentTimeMillis()   // 남은 시간은 요청을 보낸 때부터 — 응답이 늦어도 길게 보이지 않게
            apiCall { api.oneWon(bearer, request) }.map { r ->
                lastRequest = request
                verificationToken = r.verificationToken
                linkKey = newIdempotencyKey()
                r.toOneWon(sentAt).also { oneWon = it }
            }
        }

    override suspend fun confirmOneWon(code: String): ApiResult<Unit> {
        val sent = verificationToken ?: return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        if (mode == SignupMode.DEVICE_RECOVERY) return register(recoveryBiometric, sent, code)
        val key = linkKey ?: newIdempotencyKey().also { linkKey = it }
        return withToken { bearer -> apiCallNoData { api.linkAccounts(bearer, key, SignupAccountLinkRequest(sent, code)) } }
            .also { if (it is ApiResult.Success) accountLinked = true }
    }

    override suspend fun setPin(pin: String): ApiResult<Unit> =
        withToken { bearer -> apiCallNoData { api.setPin(bearer, PinRequest(pin)) } }.also {
            if (it is ApiResult.Success && mode == SignupMode.DEVICE_RECOVERY) pinStaged = true
        }

    override suspend fun registerDevice(biometric: Boolean): ApiResult<Unit> = register(biometric, null, null)

    private suspend fun register(biometric: Boolean, verificationToken: String?, authCode: String?): ApiResult<Unit> =
        withToken { bearer ->
            val body = devices.request(biometric, verificationToken, authCode)
                ?: return@withToken ApiResult.Failure(ApiError.Unknown("KEY_CREATE_FAILED"))
            val r = apiCall { api.registerDevice(bearer, body) }
            when (r) {
                is ApiResult.Success -> {
                    devices.saved(r.data)
                    sessionStore.saveUser(null, name, null, onboardingCompleted = false)   // 재등록은 다음 로그인 응답이 바로잡는다
                    clear()
                }
                is ApiResult.Failure -> devices.failed(biometric, r.error)
            }
            r.map { }
        }

    override fun dropVerification() = forgetProgress()

    override fun restart(reason: SignupRestart) {
        forgetProgress()
        restartReason = reason
    }

    override fun takeRestartReason(): SignupRestart? = restartReason.also { restartReason = null }

    override fun clear() {
        forgetProgress()
        pendingName = null
        draft = IdentityDraft()
        termsAgreed = false
        restartReason = null
    }

    /** 본인인증으로 받은 것(토큰 · 1원 · 맡긴 PIN)만 지운다 */
    private fun forgetProgress() {
        token = null
        mode = null
        name = null
        verificationToken = null
        linkKey = null
        lastRequest = null
        oneWon = null
        recoveryBiometric = false
        pinStaged = false
        accountLinked = false
    }

    /** 가입 진행 토큰을 붙여 부른다. 토큰이 없거나 서버가 거절하면 진행을 버리고 본인인증부터(넣은 값 · 약관 동의는 남김) */
    private suspend fun <T> withToken(block: suspend (bearer: String) -> ApiResult<T>): ApiResult<T> {
        val t = token ?: return ApiResult.Failure(ApiError.Unauthorized(SIGNUP_TOKEN_INVALID)).also { restart(SignupRestart.LOST) }
        val r = block("Bearer $t")
        if (r is ApiResult.Failure && r.error.code == SIGNUP_TOKEN_INVALID) restart(SignupRestart.EXPIRED)
        return r
    }
}
