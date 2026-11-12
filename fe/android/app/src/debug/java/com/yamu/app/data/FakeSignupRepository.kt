package com.yamu.app.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.AuthTokens
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.auth.data.Carrier
import com.yamu.app.feature.auth.data.CodeSent
import com.yamu.app.feature.auth.data.IdentityDraft
import com.yamu.app.feature.auth.data.SIGNUP_TOKEN_INVALID
import com.yamu.app.feature.auth.data.SignupMode
import com.yamu.app.feature.auth.data.SignupOneWon
import com.yamu.app.feature.auth.data.SignupRepository
import com.yamu.app.feature.auth.data.SignupRestart
import com.yamu.app.feature.link.data.brandOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * debug 전용 가짜 가입 · 기기 재등록 — BE 규칙을 흉내 낸다(토큰 · 세션 · 기기 키는 진짜 저장소).
 *
 * 시험용 값(이름은 아무거나, 번호로 나눈다):
 * - 01012345678 → 가입 전 시드 사용자(가입) · 01099998888 → 이미 가입(이 폰 다시 등록) · 그 밖 → 시연 사용자가 아님
 * - 인증번호: 문자 알림으로 오는 6자리 · 5번 틀리면 처음부터 · 맞히면 바로 지운다(서버처럼 같은 번호로 다시 확인하면 시간 지남)
 * - 1원 숫자 1004 · 0000은 시간 지남 · 9999는 가입 진행 토큰 만료(본인인증부터 다시) · 그 밖은 틀림(3번)
 */
@Singleton
class FakeSignupRepository @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionStore: SessionStore,
    private val deviceKeys: DeviceKeyManager,
) : SignupRepository {
    private var token: String? = null
    private var pendingName: String? = null
    private var pendingMode: SignupMode? = null
    private var sentCode: String? = null
    private var codeTries = 0
    private var oneWonSent = false
    private var oneWonTries = 0
    private var lastOneWonAt = 0L
    private var verified = false             // 가입: 1원으로 계좌 연결을 마쳤나
    private var lastPrimary: String? = null

    override var oneWon: SignupOneWon? = null
        private set
    override var mode: SignupMode? = null
        private set
    override var name: String? = null
        private set
    override var termsAgreed = false
    override var draft = IdentityDraft()
    private var restartReason: SignupRestart? = null
    override var recoveryBiometric = false
    override var pinStaged = false
        private set
    override val accountLinked: Boolean get() = verified && mode == SignupMode.SIGNUP

    override suspend fun requestCode(name: String, birthDate: LocalDate, carrier: Carrier, phoneNumber: String): ApiResult<CodeSent> {
        delay(500)
        pendingName = name.trim()
        pendingMode = when (phoneNumber) {
            SIGNUP_PHONE -> SignupMode.SIGNUP
            RECOVERY_PHONE -> SignupMode.DEVICE_RECOVERY
            else -> null                                   // 서버는 여기서 알려 주지 않는다(시드 여부 숨김)
        }
        sentCode = (100_000 + Random.nextInt(900_000)).toString()
        codeTries = 0
        return ApiResult.Success(CodeSent("pv_${Random.nextInt(100_000)}", sentCode))
    }

    override suspend fun confirmCode(verificationId: String, code: String): ApiResult<SignupMode> {
        delay(400)
        if (sentCode == null) return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_EXPIRED"))
        if (code != sentCode) {
            codeTries++
            if (codeTries >= 5) {
                sentCode = null
                return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_EXPIRED"))
            }
            return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_CODE_MISMATCH", buildJsonObject { put("remainingAttempts", 5 - codeTries) }))
        }
        sentCode = null
        val m = pendingMode ?: return ApiResult.Failure(ApiError.NotFound("DEMO_USER_NOT_FOUND"))
        token = "sgt_fake_${Random.nextInt()}"
        pinStaged = false
        mode = m
        name = pendingName
        oneWonSent = false
        verified = false
        return ApiResult.Success(m)
    }

    override suspend fun candidates(): ApiResult<List<AccountCandidate>> {
        delay(400)
        if (token == null) return tokenInvalid()
        val all = listOf(
            AccountCandidate("cand_kb", brandOf("KB국민은행"), "KB국민 입출금", "1234", alreadyLinked = mode == SignupMode.DEVICE_RECOVERY),
            AccountCandidate("cand_sh", brandOf("신한은행"), "신한 주거래 통장", "5678", alreadyLinked = false),
        )
        // 재등록은 연결된 계좌만 나온다
        return ApiResult.Success(if (mode == SignupMode.DEVICE_RECOVERY) all.filter { it.alreadyLinked } else all)
    }

    override suspend fun sendOneWon(candidateIds: List<String>, primaryId: String): ApiResult<SignupOneWon> {
        val sentAt = System.currentTimeMillis()   // 실제 저장소처럼 요청을 보낸 때부터 센다
        delay(500)
        if (token == null) return tokenInvalid()
        val waited = (System.currentTimeMillis() - lastOneWonAt) / 1000
        if (oneWonSent && waited < 60) {
            return ApiResult.Failure(ApiError.Unknown("AUTH_REQUEST_TOO_FREQUENT", 429, buildJsonObject { put("retryAfterSeconds", 60 - waited) }))
        }
        lastOneWonAt = System.currentTimeMillis()
        oneWonSent = true
        oneWonTries = 0
        lastPrimary = primaryId
        val kb = primaryId == "cand_kb"
        val sent = SignupOneWon(
            bankName = if (kb) "KB국민은행" else "신한은행",
            last4 = if (kb) "1234" else "5678",
            prefix = "YAMU",
            codeLength = 4,
            hint = null,
            deliveryUncertain = false,
            demoCode = ONE_WON_CODE,                   // BE S15P31E103-350 시연용 값을 흉내
            sentAtMillis = sentAt,
        )
        oneWon = sent
        return ApiResult.Success(sent)
    }

    override suspend fun resendOneWon(): ApiResult<SignupOneWon> {
        val primary = lastPrimary ?: return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        oneWonSent = oneWon != null   // 시간이 지나 끊겼어도 1분 규칙은 마지막으로 보낸 시각 기준
        return sendOneWon(listOf(primary), primary)
    }

    override suspend fun confirmOneWon(code: String): ApiResult<Unit> {
        delay(500)
        if (token == null) return tokenInvalid()
        if (!oneWonSent) return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        when {
            code == "0000" -> return expired()
            code == TOKEN_EXPIRED_CODE -> return tokenInvalid(SignupRestart.EXPIRED)
            code != ONE_WON_CODE -> {
                oneWonTries++
                if (oneWonTries >= 3) return expired()
                return ApiResult.Failure(ApiError.InvalidRequest("AUTH_CODE_MISMATCH", buildJsonObject { put("remainingAttempts", 3 - oneWonTries) }))
            }
        }
        if (mode == SignupMode.DEVICE_RECOVERY) return register(recoveryBiometric)
        verified = true
        return ApiResult.Success(Unit)
    }

    override suspend fun setPin(pin: String): ApiResult<Unit> {
        delay(300)
        if (token == null) return tokenInvalid()
        if (mode == SignupMode.SIGNUP && !verified) {
            return ApiResult.Failure(ApiError.Conflict("SIGNUP_STEP_INCOMPLETE", buildJsonObject { put("nextStep", "ACCOUNT_LINK") }))
        }
        FakePin.value = pin
        if (mode == SignupMode.DEVICE_RECOVERY) pinStaged = true
        return ApiResult.Success(Unit)
    }

    override suspend fun registerDevice(biometric: Boolean): ApiResult<Unit> = register(biometric)

    private suspend fun register(biometric: Boolean): ApiResult<Unit> {
        delay(400)
        if (token == null) return tokenInvalid()
        runCatching {
            deviceKeys.devicePublicKey()
            if (biometric) deviceKeys.createBiometricKey() else deviceKeys.deleteBiometricKey()
        }.getOrElse { return ApiResult.Failure(ApiError.Unknown("KEY_CREATE_FAILED")) }
        sessionStore.saveDevice(FakeAuthRepository.FAKE_DEVICE_ID, biometricEnabled = biometric)
        sessionStore.saveUser(1, name, null, onboardingCompleted = false)
        tokenStore.save(AuthTokens("fake-access-${Random.nextInt()}", "fake-refresh-${Random.nextInt()}"))
        clear()
        return ApiResult.Success(Unit)
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
        pendingMode = null
        sentCode = null
        draft = IdentityDraft()
        termsAgreed = false
        restartReason = null
    }

    /** 본인인증으로 받은 것만 지운다 — 넣은 값 · 약관 동의는 남긴다 */
    private fun forgetProgress() {
        token = null
        pinStaged = false
        mode = null
        name = null
        oneWonSent = false
        verified = false
        lastPrimary = null
        oneWon = null
        recoveryBiometric = false
    }

    private fun expired(): ApiResult.Failure {
        oneWonSent = false
        return ApiResult.Failure(ApiError.InvalidRequest("AUTH_CODE_EXPIRED"))
    }

    /** 실제 저장소처럼 토큰이 없으면 LOST, 서버가 거절하면 EXPIRED로 본인인증부터 */
    private fun tokenInvalid(reason: SignupRestart = SignupRestart.LOST): ApiResult.Failure {
        restart(reason)
        return ApiResult.Failure(ApiError.Unauthorized(SIGNUP_TOKEN_INVALID))
    }

    companion object {
        const val SIGNUP_PHONE = "01012345678"
        const val RECOVERY_PHONE = "01099998888"
        const val ONE_WON_CODE = "1004"
        const val TOKEN_EXPIRED_CODE = "9999"
    }
}
