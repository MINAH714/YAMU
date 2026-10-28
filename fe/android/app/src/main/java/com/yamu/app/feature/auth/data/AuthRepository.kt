package com.yamu.app.feature.auth.data

import android.util.Base64
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.AuthApi
import com.yamu.app.core.network.RefreshRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.awaitResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

/** 가입 · 로그인 · 본인 인증 API(#67 · #68 · #22 · #14 · #15 · #46 · #47) — 재발급(#21)은 core/network의 AuthApi */
interface AccountApi {
    @POST("auth/signup") suspend fun signup(@Body body: SignupRequest): Response<ApiEnvelope<SignupResponse>>
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): Response<ApiEnvelope<LoginResponse>>
    @POST("auth/logout") suspend fun logout(@Body body: LogoutRequest): Response<ApiEnvelope<JsonElement>>
    @POST("auth/pin") suspend fun setPin(@Body body: PinRequest): Response<ApiEnvelope<JsonElement>>
    @POST("auth/devices") suspend fun registerDevice(@Body body: DeviceRequest): Response<ApiEnvelope<DeviceResponse>>
    @POST("auth/step-up/challenge") suspend fun challenge(@Body body: ChallengeRequest): Response<ApiEnvelope<ChallengeResponse>>
    @POST("auth/step-up/verify") suspend fun verify(@Body body: VerifyRequest): Response<ApiEnvelope<VerifyResponse>>
    @DELETE("auth/devices/{id}") suspend fun revokeDevice(@Path("id") userDeviceId: Long): Response<ApiEnvelope<JsonElement>>
}

interface AuthRepository {
    /** 앱 시작: Refresh 토큰이 있으면 #21로 세션을 살리고 잠금 해제로, 거절되면 로그인으로 */
    suspend fun restoreSession(): SessionStart

    suspend fun session(): SessionInfo

    /** 가입(#67) 뒤 같은 이메일 · 비밀번호로 바로 로그인(#68) — 가입 응답엔 토큰이 없다 */
    suspend fun signup(name: String, email: String, password: String): ApiResult<LoginResult>
    suspend fun login(email: String, password: String): ApiResult<LoginResult>
    suspend fun logout()

    suspend fun setPin(pin: String): ApiResult<Unit>

    /** 지문을 쓸 수 있는 기기인가(지문 등록 + 강한 생체) */
    fun canUseBiometric(): Boolean

    /** 기기 키를 만들고 공개키를 등록(#15) */
    suspend fun registerDevice(): ApiResult<Unit>

    /** 지문 끄기(M-08) — 이 기기 등록 해제(#16) + 기기 키 삭제. 간편 비밀번호만 남는다 */
    suspend fun disableBiometric(userDeviceId: Long?): ApiResult<Unit>

    /** 지문 서명이 가능한 상태인가(기기 등록 + 키 살아 있음 + 지문 가능) */
    suspend fun biometricReady(): Boolean

    suspend fun challenge(purpose: String): ApiResult<StepUpChallenge>
    suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken>
    suspend fun verifyPin(challengeId: String, pin: String): ApiResult<StepUpToken>

    /** 초기 설정(계좌 · 카드 연결 · 첫 분석)이 끝났을 때 */
    suspend fun completeOnboarding()
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: AccountApi,
    private val refreshApi: AuthApi,
    private val tokenStore: TokenStore,
    private val sessionStore: SessionStore,
    private val deviceKeys: DeviceKeyManager,
) : AuthRepository {

    override suspend fun restoreSession(): SessionStart = withContext(Dispatchers.IO) {
        val refresh = tokenStore.refreshTokenBlocking() ?: return@withContext SessionStart.Welcome
        val response = runCatching { refreshApi.refresh(RefreshRequest(refresh)).awaitResponse() }.getOrNull()
            ?: return@withContext SessionStart.Unlock          // 네트워크 문제 — 잠금 해제 화면에서 다시 시도
        val tokens = response.body()?.takeIf { response.isSuccessful && it.success }?.data
        if (tokens != null) {
            tokenStore.save(AuthTokens(tokens.accessToken, tokens.refreshToken))
            SessionStart.Unlock
        } else {
            tokenStore.clear()
            sessionStore.clear()
            SessionStart.Login
        }
    }

    override suspend fun session(): SessionInfo = sessionStore.read()

    override suspend fun signup(name: String, email: String, password: String): ApiResult<LoginResult> {
        val created = apiCall { api.signup(SignupRequest(email.trim(), password, name.trim())) }
        if (created is ApiResult.Failure) return created
        return login(email, password).also {
            if (it is ApiResult.Success) sessionStore.saveUser(null, name.trim(), email.trim(), onboardingCompleted = false)
        }
    }

    override suspend fun login(email: String, password: String): ApiResult<LoginResult> =
        apiCall { api.login(LoginRequest(email.trim(), password)) }.map { res ->
            tokenStore.save(AuthTokens(res.accessToken, res.refreshToken))
            val done = res.user?.onboardingCompleted ?: false
            sessionStore.saveUser(res.user?.userId, res.user?.name, email.trim(), done)
            LoginResult(onboardingCompleted = done)
        }

    override suspend fun logout() {
        tokenStore.refreshTokenBlocking()?.let { refresh -> apiCallNoData { api.logout(LogoutRequest(refresh)) } }
        tokenStore.clear()
        sessionStore.clear()
    }

    override suspend fun setPin(pin: String): ApiResult<Unit> = apiCallNoData { api.setPin(PinRequest(pin)) }

    override fun canUseBiometric(): Boolean = deviceKeys.canUseBiometric()

    override suspend fun registerDevice(): ApiResult<Unit> {
        val publicKey = runCatching { deviceKeys.createKey() }.getOrNull()
            ?: return ApiResult.Failure(com.yamu.app.core.network.ApiError.Unknown("KEY_CREATE_FAILED"))
        val result = apiCall {
            api.registerDevice(DeviceRequest(sessionStore.deviceId(), deviceKeys.deviceName(), publicKey, deviceKeys.biometricTypes()))
        }.map { }
        if (result is ApiResult.Success) sessionStore.setDeviceRegistered(true) else deviceKeys.deleteKey()
        return result
    }

    override suspend fun disableBiometric(userDeviceId: Long?): ApiResult<Unit> {
        val r = userDeviceId?.let { id -> apiCallNoData { api.revokeDevice(id) } } ?: ApiResult.Success(Unit)
        if (r is ApiResult.Success) {
            deviceKeys.deleteKey()
            sessionStore.setDeviceRegistered(false)
        }
        return r
    }

    override suspend fun biometricReady(): Boolean =
        sessionStore.read().deviceRegistered && deviceKeys.hasKey() && deviceKeys.canUseBiometric()

    override suspend fun challenge(purpose: String): ApiResult<StepUpChallenge> {
        val deviceId = if (sessionStore.read().deviceRegistered) sessionStore.deviceId() else null
        return apiCall { api.challenge(ChallengeRequest(purpose, deviceId)) }.map {
            // TODO(백엔드 확인): 서명 대상 = challenge를 Base64로 푼 바이트로 가정
            StepUpChallenge(it.challengeId, Base64.decode(it.challenge, Base64.DEFAULT), "BIOMETRIC" in it.allowedMethods)
        }
    }

    override suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken> =
        apiCall {
            api.verify(VerifyRequest(challengeId, "BIOMETRIC", deviceId = sessionStore.deviceId(), signature = signatureBase64))
        }.map { StepUpToken(it.stepUpToken, it.purpose) }

    override suspend fun verifyPin(challengeId: String, pin: String): ApiResult<StepUpToken> =
        apiCall { api.verify(VerifyRequest(challengeId, "PIN", pin = pin)) }.map { StepUpToken(it.stepUpToken, it.purpose) }

    override suspend fun completeOnboarding() = sessionStore.setOnboardingCompleted(true)
}

@Module
@InstallIn(SingletonComponent::class)
object AccountApiModule {
    @Provides
    @Singleton
    fun accountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)
}
