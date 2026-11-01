package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
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
    @POST("auth/signup") suspend fun signup(@Body body: SignupRequest): Response<ApiEnvelope<SignupResponse>>
    @POST("auth/logout") suspend fun logout(@Body body: LogoutRequest): Response<ApiEnvelope<JsonElement>>
    @POST("auth/pin") suspend fun setPin(@Body body: PinRequest): Response<ApiEnvelope<JsonElement>>
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

    /** 이메일 가입(가짜 가입 화면) — Task 8.2.9에서 휴대폰 본인인증으로 바뀐다 */
    suspend fun signup(name: String, email: String, password: String): ApiResult<LoginResult>

    /** 로그인 challenge(60초 · 한 번만) — 시도할 때마다 새로 받는다 */
    suspend fun loginChallenge(): ApiResult<StepUpChallenge>
    suspend fun loginWithPin(challenge: StepUpChallenge, pin: String): ApiResult<LoginResult>
    suspend fun loginWithBiometric(challenge: StepUpChallenge, signatureBase64: String): ApiResult<LoginResult>

    /** 토큰만 지운다 — 기기 번호 · 키는 남아 같은 폰이면 PIN으로 다시 로그인 */
    suspend fun logout()

    suspend fun setPin(pin: String): ApiResult<Unit>

    /** 지문을 쓸 수 있는 기기인가(지문 등록 + 강한 생체) */
    fun canUseBiometric(): Boolean

    /** 기기 등록(가입 마지막 단계) — 기기 키는 늘, 지문 키는 [biometric]일 때만 보낸다 */
    suspend fun registerDevice(biometric: Boolean): ApiResult<Unit>

    /** 지문 켜기 · 끄기(M-08) — SECURITY_SETTINGS 본인 확인 뒤 이 기기를 다시 등록(끌 때는 지문 키를 빼고) */
    suspend fun setBiometric(enabled: Boolean, stepUpToken: String): ApiResult<Unit>

    /** 지문으로 서명할 수 있는 상태인가(지문 키 등록 + 키 살아 있음 + 지문 가능) */
    suspend fun biometricReady(): Boolean

    suspend fun challenge(purpose: String): ApiResult<StepUpChallenge>
    suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken>
    suspend fun verifyPin(challenge: StepUpChallenge, pin: String): ApiResult<StepUpToken>

    /** 초기 설정(계좌 · 카드 연결 · 첫 분석)이 끝났을 때 */
    suspend fun completeOnboarding()
}

/** 서버에서 이 기기가 없어졌다(해제 · 사용자 상태 변경) — 첫 화면에서 다시 시작 */
const val DEVICE_NOT_REGISTERED = "DEVICE_NOT_REGISTERED"

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val loginApi: LoginApi,
    private val api: AccountApi,
    private val tokenStore: TokenStore,
    private val sessionStore: SessionStore,
    private val deviceKeys: DeviceKeyManager,
) : AuthRepository {

    override suspend fun sessionStart(): SessionStart {
        val session = sessionStore.read()
        if (session.userDeviceId != null && deviceKeys.hasDeviceKey()) return SessionStart.Login
        if (session.userDeviceId != null) forgetDevice()          // 기기 키가 없으면 서명할 수 없다(앱 데이터 지움 등)
        return SessionStart.Welcome
    }

    override suspend fun session(): SessionInfo = sessionStore.read()

    override suspend fun signup(name: String, email: String, password: String): ApiResult<LoginResult> =
        apiCall { api.signup(SignupRequest(email.trim(), password, name.trim())) }.map {
            sessionStore.saveUser(it.userId, name.trim(), email.trim(), onboardingCompleted = false)
            LoginResult(onboardingCompleted = false)
        }

    override suspend fun loginChallenge(): ApiResult<StepUpChallenge> {
        val userDeviceId = sessionStore.read().userDeviceId
            ?: return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        val r = apiCall { loginApi.challenge(LoginChallengeRequest(userDeviceId)) }
        if (r is ApiResult.Failure && r.error.code == DEVICE_NOT_REGISTERED) forgetDevice()
        val ready = biometricReady()
        return r.map { StepUpChallenge(it.challengeId, it.challenge, allowsBiometric = it.biometricEnabled && ready) }
    }

    override suspend fun loginWithPin(challenge: StepUpChallenge, pin: String): ApiResult<LoginResult> {
        val signature = deviceKeys.signWithDeviceKey(challenge.challenge)
        if (signature == null) {
            forgetDevice()
            return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        }
        return login(challenge, AuthMethod.PIN, signature, pin)
    }

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
    }

    override suspend fun setPin(pin: String): ApiResult<Unit> = apiCallNoData { api.setPin(PinRequest(pin)) }

    override fun canUseBiometric(): Boolean = deviceKeys.canUseBiometric()

    override suspend fun registerDevice(biometric: Boolean): ApiResult<Unit> =
        sendDevice(biometric, stepUpToken = null)

    override suspend fun setBiometric(enabled: Boolean, stepUpToken: String): ApiResult<Unit> =
        sendDevice(enabled, stepUpToken)

    /** 기기 키는 있으면 그대로(없으면 만든다), 지문 키는 켤 때마다 새로 만든다 */
    private suspend fun sendDevice(biometric: Boolean, stepUpToken: String?): ApiResult<Unit> {
        val keys = runCatching {
            deviceKeys.devicePublicKey() to (if (biometric) deviceKeys.createBiometricKey() else null)
        }.getOrElse { return ApiResult.Failure(ApiError.Unknown("KEY_CREATE_FAILED")) }
        val body = DeviceRequest(
            deviceId = sessionStore.deviceId(),
            deviceName = deviceKeys.deviceName(),
            platform = "ANDROID",
            devicePublicKey = keys.first,
            biometricPublicKey = keys.second,
            biometricTypes = if (biometric) deviceKeys.biometricTypes() else null,
        )
        val r = apiCall { api.registerDevice(stepUpToken, body) }
        when (r) {
            is ApiResult.Success -> {
                sessionStore.saveDevice(r.data.userDeviceId, r.data.biometricEnabled)
                if (!r.data.biometricEnabled) deviceKeys.deleteBiometricKey()
                val access = r.data.accessToken
                val refresh = r.data.refreshToken
                if (access != null && refresh != null) tokenStore.save(AuthTokens(access, refresh))
            }
            is ApiResult.Failure -> if (biometric) deviceKeys.deleteBiometricKey()   // 서버에 안 올라간 지문 키는 버린다
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
        val signature = deviceKeys.signWithDeviceKey(challenge.challenge)
            ?: return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        return apiCall { api.verify(VerifyRequest(challenge.challengeId, AuthMethod.PIN, signature, pin)) }
            .map { StepUpToken(it.stepUpToken, it.purpose) }
    }

    override suspend fun completeOnboarding() = sessionStore.setOnboardingCompleted(true)

    /** 이 폰을 처음 상태로 — 토큰 · 세션 · 기기 키 */
    private suspend fun forgetDevice() {
        tokenStore.clear()
        sessionStore.clear()
        deviceKeys.deleteAll()
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
}
