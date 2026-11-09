package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.feature.payment.data.PaymentEdits
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.SessionInfo
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 로그인 API — 인증 헤더 없는 클라이언트로 부른다(지난 Access 토큰이 붙지 않게) */
interface LoginApi {
    @POST("auth/login/challenge") suspend fun challenge(@Body body: LoginChallengeRequest): Response<ApiEnvelope<LoginChallengeResponse>>
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): Response<ApiEnvelope<LoginResponse>>
}

/** 로그인한 뒤 쓰는 인증 API — 로그아웃 · PIN · 기기 등록 · 본인 확인. 재발급은 core/network의 AuthApi */
interface AccountApi {
    @POST("auth/logout") suspend fun logout(@Body body: LogoutRequest): Response<ApiEnvelope<JsonElement>>
    @POST("auth/devices") suspend fun registerDevice(
        @Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String?,
        @Body body: DeviceRequest,
    ): Response<ApiEnvelope<DeviceResponse>>
    @POST("auth/step-up/challenge") suspend fun challenge(@Body body: ChallengeRequest): Response<ApiEnvelope<ChallengeResponse>>
    @POST("auth/step-up/verify") suspend fun verify(@Body body: VerifyRequest): Response<ApiEnvelope<VerifyResponse>>
}

interface AuthRepository {
    /** 앱 시작: 이 폰에 기기 번호와 기기 키가 있으면 PIN · 지문 로그인, 없으면 첫 화면 */
    suspend fun sessionStart(): SessionStart

    suspend fun session(): SessionInfo

    /** 로그인 challenge(60초 · 한 번만) — 시도할 때마다 새로 받는다 */
    suspend fun loginChallenge(): ApiResult<StepUpChallenge>
    suspend fun loginWithPin(challenge: StepUpChallenge, pin: String): ApiResult<LoginResult>
    suspend fun loginWithBiometric(challenge: StepUpChallenge, signatureBase64: String): ApiResult<LoginResult>

    /** 토큰만 지운다 — 기기 번호 · 키는 남아 같은 폰이면 PIN으로 다시 로그인 */
    suspend fun logout()

    /** 지문을 쓸 수 있는 기기인가(지문 등록 + 강한 생체) */
    fun canUseBiometric(): Boolean

    /** 지문 켜기 · 끄기(M-08) — SECURITY_SETTINGS 본인 확인 뒤 이 기기를 다시 등록(끌 때는 지문 키를 빼고) */
    suspend fun setBiometric(enabled: Boolean, stepUpToken: String): ApiResult<Unit>

    /** 지문으로 서명할 수 있는 상태인가(지문 키 등록 + 키 살아 있음 + 지문 가능) */
    suspend fun biometricReady(): Boolean

    suspend fun challenge(purpose: String): ApiResult<StepUpChallenge>
    suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken>
    suspend fun verifyPin(challenge: StepUpChallenge, pin: String): ApiResult<StepUpToken>

    /** 초기 설정(계좌 · 카드 연결 · 첫 분석)이 끝났을 때 */
    suspend fun completeOnboarding()

    /** 회원 탈퇴가 끝났을 때 — 이 폰을 처음 상태로. 실패했을 때 부르면 계정은 남았는데 기기만 비게 된다 */
    suspend fun forgetAfterWithdrawal()
}

/** 서버에서 이 기기가 없어졌다(해제 · 사용자 상태 변경) — 첫 화면에서 다시 시작 */
const val DEVICE_NOT_REGISTERED = "DEVICE_NOT_REGISTERED"

private const val KEY_SIGN_FAILED = "KEY_SIGN_FAILED"

/** 기기 등록이 서버에서 거절됐다고 확정할 수 있나. 시간 초과 · 연결 끊김은 서버가 끝냈을 수 있어 아니다(다음 로그인 응답으로 맞춘다) */
internal fun ApiError.keyNotOnServer(): Boolean = this !is ApiError.Network

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val loginApi: LoginApi,
    private val api: AccountApi,
    private val tokenStore: TokenStore,
    private val sessionStore: SessionStore,
    private val deviceKeys: DeviceKeyManager,
    private val devices: DeviceRegistration,
    private val paymentEdits: PaymentEdits,
) : AuthRepository {

    override suspend fun sessionStart(): SessionStart {
        val session = sessionStore.read()
        if (session.userDeviceId != null && deviceKeys.hasDeviceKey()) return SessionStart.Login
        if (session.userDeviceId != null) forgetDevice()          // 기기 키가 없으면 서명할 수 없다(앱 데이터 지움 등)
        return SessionStart.Welcome
    }

    override suspend fun session(): SessionInfo = sessionStore.read()

    override suspend fun loginChallenge(): ApiResult<StepUpChallenge> {
        val userDeviceId = sessionStore.read().userDeviceId
            ?: return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        val r = apiCall { loginApi.challenge(LoginChallengeRequest(userDeviceId)) }
        when {
            r is ApiResult.Failure && r.error.code == DEVICE_NOT_REGISTERED -> forgetDevice()
            r is ApiResult.Success -> {   // 기기 등록 응답을 못 받았을 수 있어 지문 상태는 서버 값으로 맞춘다
                sessionStore.saveDevice(userDeviceId, r.data.biometricEnabled)
                if (!r.data.biometricEnabled) deviceKeys.deleteBiometricKey()
            }
            else -> Unit
        }
        val ready = biometricReady()
        return r.map { StepUpChallenge(it.challengeId, it.challenge, allowsBiometric = it.biometricEnabled && ready) }
    }

    override suspend fun loginWithPin(challenge: StepUpChallenge, pin: String): ApiResult<LoginResult> {
        val signature = signWithDeviceKey(challenge.challenge)
            .getOrElse { return ApiResult.Failure(ApiError.Unknown(KEY_SIGN_FAILED)) }
        if (signature == null) {
            forgetDevice()
            return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        }
        return login(challenge, AuthMethod.PIN, signature, pin)
    }

    /** Keystore 서명은 메인 스레드 밖에서. 잠깐의 Keystore 오류로 기기 등록을 지우지 않게 '키 없음(null)'과 구분한다 */
    private suspend fun signWithDeviceKey(challenge: String): Result<String?> =
        withContext(Dispatchers.Default) { runCatching { deviceKeys.signWithDeviceKey(challenge) } }

    override suspend fun loginWithBiometric(challenge: StepUpChallenge, signatureBase64: String): ApiResult<LoginResult> =
        login(challenge, AuthMethod.BIOMETRIC, signatureBase64, pin = null)

    private suspend fun login(challenge: StepUpChallenge, method: String, signature: String, pin: String?): ApiResult<LoginResult> {
        val userDeviceId = sessionStore.read().userDeviceId
            ?: return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        val r = apiCall { loginApi.login(LoginRequest(userDeviceId, challenge.challengeId, method, signature, pin)) }
        if (r is ApiResult.Failure && r.error.code == DEVICE_NOT_REGISTERED) forgetDevice()
        return r.map { res ->
            tokenStore.save(AuthTokens(res.accessToken, res.refreshToken))
            val done = res.user?.onboardingCompleted ?: false
            sessionStore.saveUser(res.user?.userId, res.user?.name, null, done)
            LoginResult(onboardingCompleted = done)
        }
    }

    override suspend fun logout() {
        tokenStore.refreshTokenBlocking()?.let { refresh -> apiCallNoData { api.logout(LogoutRequest(refresh)) } }
        tokenStore.clear()
        paymentEdits.clear()   // 받아 둔 결제 메모 — 다음에 로그인하는 사람에게 보이지 않게
    }

    override fun canUseBiometric(): Boolean = deviceKeys.canUseBiometric()

    /** 로그인한 회원이 이 기기를 다시 등록 — 기기 키는 그대로, 지문 키는 켤 때 새로 */
    override suspend fun setBiometric(enabled: Boolean, stepUpToken: String): ApiResult<Unit> {
        val body = devices.request(enabled) ?: return ApiResult.Failure(ApiError.Unknown("KEY_CREATE_FAILED"))
        val r = apiCall { api.registerDevice(stepUpToken, body) }
        when (r) {
            is ApiResult.Success -> devices.saved(r.data)
            is ApiResult.Failure -> devices.failed(enabled, r.error)
        }
        return r.map { }
    }

    override suspend fun biometricReady(): Boolean =
        sessionStore.read().biometricEnabled && deviceKeys.hasBiometricKey() && deviceKeys.canUseBiometric()

    override suspend fun challenge(purpose: String): ApiResult<StepUpChallenge> {
        val ready = biometricReady()
        return apiCall { api.challenge(ChallengeRequest(purpose)) }.map {
            StepUpChallenge(it.challengeId, it.challenge, allowsBiometric = ready && AuthMethod.BIOMETRIC in it.allowedMethods)
        }
    }

    override suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken> =
        apiCall { api.verify(VerifyRequest(challengeId, AuthMethod.BIOMETRIC, signatureBase64)) }
            .map { StepUpToken(it.stepUpToken, it.purpose) }

    override suspend fun verifyPin(challenge: StepUpChallenge, pin: String): ApiResult<StepUpToken> {
        val signature = signWithDeviceKey(challenge.challenge)
            .getOrElse { return ApiResult.Failure(ApiError.Unknown(KEY_SIGN_FAILED)) }
        if (signature == null) {
            forgetDeviceInSession()
            return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        }
        val r = apiCall { api.verify(VerifyRequest(challenge.challengeId, AuthMethod.PIN, signature, pin)) }
        if (r is ApiResult.Failure && r.error.code == DEVICE_NOT_REGISTERED) forgetDeviceInSession()
        return r.map { StepUpToken(it.stepUpToken, it.purpose) }
    }

    override suspend fun completeOnboarding() = sessionStore.setOnboardingCompleted(true)

    override suspend fun forgetAfterWithdrawal() = forgetDevice()

    /** 이 폰을 처음 상태로 — 토큰 · 세션 · 기기 키 */
    private suspend fun forgetDevice() {
        tokenStore.clear()
        sessionStore.clear()
        deviceKeys.deleteAll()
        paymentEdits.clear()
    }

    /** 로그인한 채로 기기가 없어졌을 때 — 지운 뒤 로그인 화면을 거쳐 첫 화면으로 */
    private suspend fun forgetDeviceInSession() {
        forgetDevice()
        tokenStore.notifySessionExpired()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AccountApiModule {
    @Provides
    @Singleton
    fun accountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)

    @Provides
    @Singleton
    fun loginApi(@Named("auth") retrofit: Retrofit): LoginApi = retrofit.create(LoginApi::class.java)

    /** 가입 진행 토큰을 직접 붙인다 — Access 토큰이 자동으로 붙는 클라이언트를 쓰면 안 된다 */
    @Provides
    @Singleton
    fun signupApi(@Named("auth") retrofit: Retrofit): SignupApi = retrofit.create(SignupApi::class.java)
}
