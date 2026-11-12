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
import com.yamu.app.feature.auth.data.DEVICE_NOT_REGISTERED
import com.yamu.app.feature.auth.data.LoginResult
import com.yamu.app.feature.auth.data.SessionStart
import com.yamu.app.feature.auth.data.StepUpChallenge
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * debug 전용 가짜 인증 — 서버 없이 가입 · 로그인 · 본인 확인 흐름을 끝까지 볼 수 있게.
 * 서버 호출만 흉내 내고, 토큰 · 세션 · 기기 키는 **진짜 저장소**에 저장한다(앱을 다시 켜도 이어짐).
 * 기기 키 서명도 진짜 Keystore로 해 본다(서명 검증은 서버 몫이라 여기선 하지 않음).
 *
 * 시험용 값:
 * - 간편 비밀번호: 가입 · 재등록 때 만든 것(없으면 135790, [FakePin]) · 5번 틀리면 30분 잠김
 * - 로그인 간편 비밀번호 000000 → 서버에서 이 기기가 없어짐(DEVICE_NOT_REGISTERED) → 첫 화면
 * 가입 · 기기 재등록은 [FakeSignupRepository]
 */
@Singleton
class FakeAuthRepository @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionStore: SessionStore,
    private val deviceKeys: DeviceKeyManager,
    private val bank: FakeBank,
) : AuthRepository {
    private var failures = 0
    private var lastPurpose = "APP_UNLOCK"

    override suspend fun sessionStart(): SessionStart {
        if (DevSession.applyOnNextStart) {
            DevSession.applyOnNextStart = false
            applyDebugStart(DevSession.startState)
        }
        delay(300)
        val s = sessionStore.read()
        return if (s.userDeviceId != null && deviceKeys.hasDeviceKey()) SessionStart.Login else SessionStart.Welcome
    }

    /** 디버그 메뉴 '시작 상태'를 저장소에 반영 — 로그인 전 · 끝은 이 폰을 기기 7번으로 등록해 둔다 */
    private suspend fun applyDebugStart(state: StartState) {
        tokenStore.clear()
        when (state) {
            StartState.NEED_SIGNUP -> {
                sessionStore.clear()
                deviceKeys.deleteAll()
                bank.reset()
            }
            StartState.NEED_LINK, StartState.READY -> {
                sessionStore.saveUser(1, "김야무", "yamu@ssafy.com", onboardingCompleted = state == StartState.READY)
                deviceKeys.devicePublicKey()
                sessionStore.saveDevice(FAKE_DEVICE_ID, biometricEnabled = deviceKeys.hasBiometricKey())
                if (state == StartState.READY) bank.linkAll() else bank.reset()
            }
        }
        failures = 0
    }

    override suspend fun session(): SessionInfo = sessionStore.read()

    override suspend fun loginChallenge(): ApiResult<StepUpChallenge> {
        delay(200)
        if (sessionStore.read().userDeviceId == null) return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        if (failures >= 5) return locked()
        return ApiResult.Success(StepUpChallenge("lc_${Random.nextInt(100_000)}", newChallenge(), allowsBiometric = biometricReady()))
    }

    override suspend fun loginWithPin(challenge: StepUpChallenge, pin: String): ApiResult<LoginResult> {
        delay(400)
        if (deviceKeys.signWithDeviceKey(challenge.challenge) == null || pin == "000000") {
            forgetDevice()
            return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        }
        if (failures >= 5) return locked()
        if (pin != FakePin.value) {
            failures++
            if (failures >= 5) return locked()
            return ApiResult.Failure(ApiError.Unauthorized("PIN_MISMATCH", buildJsonObject { put("remainingAttempts", 5 - failures) }))
        }
        return loggedIn()
    }

    override suspend fun loginWithBiometric(challenge: StepUpChallenge, signatureBase64: String): ApiResult<LoginResult> {
        delay(300)
        return loggedIn()
    }

    private suspend fun loggedIn(): ApiResult<LoginResult> {
        failures = 0
        tokenStore.save(fakeTokens())
        val done = sessionStore.read().onboardingCompleted
        return ApiResult.Success(LoginResult(onboardingCompleted = done))
    }

    override suspend fun logout() {
        tokenStore.clear()
    }

    override suspend fun forgetAfterWithdrawal() {
        tokenStore.clear()
        sessionStore.clear()
        deviceKeys.deleteAll()
        bank.reset()
    }

    override fun canUseBiometric(): Boolean = deviceKeys.canUseBiometric()

    override suspend fun setBiometric(enabled: Boolean, stepUpToken: String): ApiResult<Unit> {
        delay(300)
        runCatching { if (enabled) deviceKeys.createBiometricKey() else deviceKeys.deleteBiometricKey() }
            .getOrElse { return ApiResult.Failure(ApiError.Unknown("KEY_CREATE_FAILED")) }
        sessionStore.setBiometricEnabled(enabled)
        return ApiResult.Success(Unit)
    }

    override suspend fun biometricReady(): Boolean =
        sessionStore.read().biometricEnabled && deviceKeys.hasBiometricKey() && deviceKeys.canUseBiometric()

    override suspend fun challenge(purpose: String): ApiResult<StepUpChallenge> {
        delay(200)
        if (failures >= 5) return locked()
        lastPurpose = purpose
        val pinOnly = purpose == StepUpPurpose.BARCODE && FakeSecurity.pinOnlyForCode   // M-08 '간편 비밀번호만' — 진짜 서버는 allowedMethods로
        return ApiResult.Success(StepUpChallenge("ch_${Random.nextInt(100_000)}", newChallenge(), allowsBiometric = biometricReady() && !pinOnly))
    }

    override suspend fun verifyBiometric(challengeId: String, signatureBase64: String): ApiResult<StepUpToken> {
        delay(300)
        return ApiResult.Success(StepUpToken("sut_fake", lastPurpose))   // 서명 검증은 서버 몫
    }

    override suspend fun verifyPin(challenge: StepUpChallenge, pin: String): ApiResult<StepUpToken> {
        delay(300)
        if (deviceKeys.signWithDeviceKey(challenge.challenge) == null) return ApiResult.Failure(ApiError.Unauthorized(DEVICE_NOT_REGISTERED))
        if (failures >= 5) return locked()
        if (pin == FakePin.value) {
            failures = 0
            return ApiResult.Success(StepUpToken("sut_fake", lastPurpose))
        }
        failures++
        if (failures >= 5) return locked()
        return ApiResult.Failure(
            ApiError.StepUpRequired("STEP_UP_FAILED", buildJsonObject { put("remainingAttempts", 5 - failures) }),
        )
    }

    /** 본인 확인 토큰을 받은 경우만 바꾼다. 다음 로그인 · 본인 확인부터 새 비밀번호 */
    override suspend fun changePin(pin: String, stepUpToken: String): ApiResult<Unit> {
        delay(300)
        if (stepUpToken != "sut_fake") return ApiResult.Failure(ApiError.StepUpRequired("STEP_UP_REQUIRED"))
        FakePin.value = pin
        return ApiResult.Success(Unit)
    }

    override suspend fun completeOnboarding() = sessionStore.setOnboardingCompleted(true)

    override suspend fun forgetRevokedDevice() = forgetDevice()

    /** 서버처럼 lockedUntil(UTC 초 단위) */
    private fun locked(): ApiResult.Failure {
        val until = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString()
        return ApiResult.Failure(ApiError.StepUpRequired("DEVICE_LOCKED", buildJsonObject { put("lockedUntil", until) }))
    }

    private suspend fun forgetDevice() {
        tokenStore.clear()
        sessionStore.clear()
        deviceKeys.deleteAll()
    }

    /** 서버 challenge처럼 43자 base64url */
    private fun newChallenge(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(Random.nextBytes(32))

    private fun fakeTokens() = AuthTokens("fake-access-${Random.nextInt()}", "fake-refresh-${Random.nextInt()}")

    companion object {
        /** 가짜 서버가 이 폰에 주는 기기 번호 */
        const val FAKE_DEVICE_ID = 7L
    }
}

/** 가짜 서버의 간편 비밀번호(로그인 · 본인 확인 · 가입 · 재등록이 같이 쓴다). 앱을 다시 켜면 135790 */
object FakePin {
    var value = "135790"
}
