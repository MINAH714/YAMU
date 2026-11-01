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
    val authExpiresAt: String? = null,
    val depositorNamePrefix: String = "YAMU",
    val codeLength: Int = 4,
    val hint: String? = null,
    /** 금융망 응답이 늦었음 — 1원이 안 보이면 1분 뒤 다시 받기 */
    val deliveryUncertain: Boolean = false,
    /** 시연용 1원 숫자 — BE에 요청해 둔 값(아직 없음). 오면 문자 알림으로 띄운다 */
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

data class AccountCandidate(val id: String, val brand: Brand, val name: String, val last4: String, val alreadyLinked: Boolean)

data class SignupOneWon(
    val bankName: String,
    val last4: String,
    val prefix: String,
    val codeLength: Int,
    val hint: String?,
    val deliveryUncertain: Boolean,
    val demoCode: String?,
)

fun AccountCandidateDto.toCandidate() = AccountCandidate(
    id = candidateId,
    brand = brandOf(bank.name),
    name = accountName ?: bank.name,
    last4 = maskedAccountNo.filter(Char::isDigit).takeLast(4),
    alreadyLinked = alreadyLinked,
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

    /** 약관(A-04)에 동의했나 — 첫 화면 '로그인'으로 들어와 가입이면 약관부터 */
    var termsAgreed: Boolean

    /** 재등록 때 지문 키를 같이 등록할지(A-08에서 고름) */
    var recoveryBiometric: Boolean

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
    override var recoveryBiometric: Boolean = false

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
            apiCall { api.oneWon(bearer, request) }.map { r ->
                lastRequest = request
                verificationToken = r.verificationToken
                linkKey = newIdempotencyKey()
                SignupOneWon(
                    bankName = r.bank?.name.orEmpty(),
                    last4 = r.maskedAccountNo.orEmpty().filter(Char::isDigit).takeLast(4),
                    prefix = r.depositorNamePrefix,
                    codeLength = r.codeLength,
                    hint = r.hint,
                    deliveryUncertain = r.deliveryUncertain,
                    demoCode = r.demoCode,
                ).also { oneWon = it }
            }
        }

    override suspend fun confirmOneWon(code: String): ApiResult<Unit> {
        val sent = verificationToken ?: return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        if (mode == SignupMode.DEVICE_RECOVERY) return register(recoveryBiometric, sent, code)
        val key = linkKey ?: newIdempotencyKey().also { linkKey = it }
        return withToken { bearer -> apiCallNoData { api.linkAccounts(bearer, key, SignupAccountLinkRequest(sent, code)) } }
    }

    override suspend fun setPin(pin: String): ApiResult<Unit> =
        withToken { bearer -> apiCallNoData { api.setPin(bearer, PinRequest(pin)) } }

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
                is ApiResult.Failure -> devices.failed(biometric)
            }
            r.map { }
        }

    override fun clear() {
        token = null
        mode = null
        name = null
        pendingName = null
        verificationToken = null
        linkKey = null
        lastRequest = null
        oneWon = null
        termsAgreed = false
        recoveryBiometric = false
    }

    /** 가입 진행 토큰을 붙여 부른다. 토큰이 없거나 서버가 거절하면 진행 상태를 지운다 */
    private suspend fun <T> withToken(block: suspend (bearer: String) -> ApiResult<T>): ApiResult<T> {
        val t = token ?: return ApiResult.Failure(ApiError.Unauthorized(SIGNUP_TOKEN_INVALID))
        val r = block("Bearer $t")
        if (r is ApiResult.Failure && r.error.code == SIGNUP_TOKEN_INVALID) clear()
        return r
    }
}
