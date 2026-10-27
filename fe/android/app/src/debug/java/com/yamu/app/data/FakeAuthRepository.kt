package com.yamu.app.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.SessionInfo
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.StartState
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.LoginResult
import com.yamu.app.feature.auth.data.SessionStart
import com.yamu.app.feature.auth.data.StepUpChallenge
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * debug 전용 가짜 인증 — 서버 없이 가입 · 로그인 · 잠금 해제 흐름을 끝까지 볼 수 있게.
 * 서버 호출만 흉내 내고, 토큰 · 세션 · 기기 키는 **진짜 저장소**에 저장한다(앱을 다시 켜도 이어짐).
 *
 * 시험용 값:
 * - 가입 이메일 dup@ssafy.com → 이미 가입됨 · fin@ssafy.com → 금융망에서 쓰는 이메일
 * - 로그인 비밀번호 Wrong1234! → 불일치 · 이메일이 done으로 시작하면 초기 설정 끝난 사용자
 * - 간편 비밀번호: 가입 때 만든 것(없으면 135790) · 5번 틀리면 잠김
 */
@Singleton
class FakeAuthRepository @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionStore: SessionStore,
    private val deviceKeys: DeviceKeyManager,
    private val bank: FakeBank,
) : AuthRepository {

    private var pin = "135790"
    private var failures = 0
    private var lastPurpose = "APP_UNLOCK"

    override suspend fun restoreSession(): SessionStart {
        if (DevSession.applyOnNextStart) {
            DevSession.applyOnNextStart = false
            applyDebugStart(DevSession.startState)
        }
        delay(300)
        return if (tokenStore.refreshTokenBlocking() != null) SessionStart.Unlock else SessionStart.Welcome
    }

    /** 디버그 메뉴 '시작 상태'를 저장소에 반영 */
    private suspend fun applyDebugStart(state: StartState) {
        when (state) {
            StartState.NEED_SIGNUP -> {
                tokenStore.clear()
                sessionStore.clear()
                bank.reset()
            }
            StartState.NEED_LINK, StartState.READY -> {
                tokenStore.save(fakeTokens())
                sessionStore.saveUser(1, "김야무", "yamu@ssafy.com", onboardingCompleted = state == StartState.READY)
                if (state == StartState.READY) bank.linkAll() else bank.reset()
            }
        }
    }

    override suspend fun session(): SessionInfo = sessionStore.read()

    override suspend fun signup(name: String, email: String, password: String): ApiResult<LoginResult> {
        delay(600)
        when (email.trim()) {
            "dup@ssafy.com" -> return ApiResult.Failure(ApiError.Conflict("DUPLICATE_EMAIL"))
            "fin@ssafy.com" -> return ApiResult.Failure(ApiError.Conflict("FIN_ACCOUNT_EMAIL_TAKEN"))
        }
        tokenStore.save(fakeTokens())
        sessionStore.saveUser(1, name.trim(), email.trim(), onboardingCompleted = false)
        bank.reset()
        failures = 0
        return ApiResult.Success(LoginResult(onboardingCompleted = false))
    }

    override suspend fun login(email: String, password: String): ApiResult<LoginResult> {
        delay(500)
        if (password == "Wrong1234!") return ApiResult.Failure(ApiError.Unauthorized("INVALID_CREDENTIALS"))
        val done = email.trim().startsWith("done")
        tokenStore.save(fakeTokens())
        sessionStore.saveUser(1, "김야무", email.trim(), onboardingCompleted = done)
        if (done) bank.linkAll() else bank.reset()
        failures = 0
        return ApiResult.Success(LoginResult(onboardingCompleted = done))
    }

    override suspend fun logout() {
        tokenStore.clear()
        sessionStore.clear()
    }

    override suspend fun setPin(pin: String): ApiResult<Unit> {
        delay(300)
        this.pin = pin
        failures = 0
        return ApiResult.Success(Unit)
    }

    override fun canUseBiometric(): Boolean = deviceKeys.canUseBiometric()

    override suspend fun registerDevice(): ApiResult<Unit> {
        runCatching { deviceKeys.createKey() }.getOrElse { return ApiResult.Failure(ApiError.Unknown("KEY_CREATE_FAILED")) }
        sessionStore.setDeviceRegistered(true)
        return ApiResult.Success(Unit)
    }

    override suspend fun disableBiometric(userDeviceId: Long?): ApiResult<Unit> {
        delay(300)
        deviceKeys.deleteKey()
        sessionStore.setDeviceRegistered(false)
        return ApiResult.Success(Unit)
    }

    override suspend fun biometricReady(): Boolean =
        sessionStore.read().deviceRegistered && deviceKeys.hasKey() && deviceKeys.canUseBiometric()

    override suspend fun challenge(purpose: String): ApiResult<StepUpChallenge> {
        delay(200)
        if (failures >= 5) return locked()
        lastPurpose = purpose
        val pinOnly = purpose == StepUpPurpose.BARCODE && FakeSecurity.pinOnlyForCode   // M-08 '간편 비밀번호만' — 진짜 서버는 allowedMethods로
        return ApiResult.Success(StepUpChallenge("chl_${Random.nextInt(100_000)}", Random.nextBytes(32), allowsBiometric = biometricReady() && !pinOnly))
    }

    override suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken> {
        delay(300)
        return ApiResult.Success(StepUpToken("sut_fake", lastPurpose))   // 서명 검증은 서버 몫
    }

    override suspend fun verifyPin(challengeId: String, pin: String): ApiResult<StepUpToken> {
        delay(300)
        if (failures >= 5) return locked()
        if (pin == this.pin) {
            failures = 0
            return ApiResult.Success(StepUpToken("sut_fake", lastPurpose))
        }
        failures++
        if (failures >= 5) return locked()
        return ApiResult.Failure(
            ApiError.Unauthorized("STEP_UP_FAILED", buildJsonObject { put("remainingAttempts", 5 - failures) }),
        )
    }

    override suspend fun completeOnboarding() = sessionStore.setOnboardingCompleted(true)

    private fun locked(): ApiResult.Failure {
        val at = LocalTime.now().plusMinutes(30).format(DateTimeFormatter.ofPattern("HH:mm"))
        return ApiResult.Failure(ApiError.Locked("DEVICE_LOCKED", buildJsonObject { put("unlockAt", at) }))
    }

    private fun fakeTokens() = AuthTokens("fake-access-${Random.nextInt()}", "fake-refresh-${Random.nextInt()}")
}
