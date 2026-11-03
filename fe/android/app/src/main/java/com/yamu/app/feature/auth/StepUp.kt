package com.yamu.app.feature.auth

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.BiometricOutcome
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// 본인 인증(step-up) 공통 — 09 문서 5-3. 지문(기기 키 서명) 먼저, 안 되면 간편 비밀번호 화면(PinPad).
// 토큰은 1회용이라 메모리에만 두고, 화면 인자 · 저장 상태로 넘기지 않는다.

/** 간편 비밀번호 화면이 끝났을 때 */
sealed interface PinPadResult {
    val purpose: String
    data class Verified(override val purpose: String, val token: StepUpToken) : PinPadResult
    data class Cancelled(override val purpose: String) : PinPadResult
}

/** 간편 비밀번호 화면(PinPad) → 부른 화면으로 결과 전달 */
@Singleton
class StepUpResults @Inject constructor() {
    /** 간편 비밀번호 화면 보조 줄 — 무엇을 확인하는지('KB국민은행 ·1234 · 약관 3건'). 화면 글자뿐이라 비밀이 아님 */
    @Volatile var detail: String? = null
    private val _results = MutableSharedFlow<PinPadResult>(extraBufferCapacity = 1)
    val results: SharedFlow<PinPadResult> = _results.asSharedFlow()
    fun deliver(result: PinPadResult) {
        _results.tryEmit(result)
    }
}

/** 지문 창에 띄울 글자 — '지문으로 카드를 등록해요' · '지문으로 계좌를 연결해요' */
data class StepUpPrompt(val title: String, val subtitle: String?)

sealed interface StepUpUi {
    data object Idle : StepUpUi
    data object Working : StepUpUi
    /** 화면이 지문 창을 열어야 함 */
    data class Biometric(val request: BiometricRequest, val prompt: StepUpPrompt) : StepUpUi
    /** 화면이 간편 비밀번호 화면을 열어야 함 */
    data class OpenPin(val purpose: String) : StepUpUi
    /** 간편 비밀번호 화면이 열려 있음 — 결과를 기다림 */
    data object WaitingPin : StepUpUi
}

/**
 * ViewModel 안에서 쓰는 본인 인증 한 번. 화면은 [StepUpEffect]로 지문 창 · 간편 비밀번호 화면만 붙인다.
 * ```
 * stepUp.start(StepUpPurpose.ACCOUNT_LINK, prompt, onError = { … }) { token -> repo.linkAccounts(…, token.token) }
 * ```
 */
class StepUpController(
    private val auth: AuthRepository,
    private val deviceKeys: DeviceKeyManager,
    private val results: StepUpResults,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow<StepUpUi>(StepUpUi.Idle)
    val ui: StateFlow<StepUpUi> = _ui.asStateFlow()
    val busy: Boolean get() = _ui.value != StepUpUi.Idle

    private var purpose: String? = null
    private var onToken: (suspend (StepUpToken) -> Unit)? = null
    private var onError: ((ApiError) -> Unit)? = null

    init {
        scope.launch {
            results.results.collect { r ->
                if (_ui.value != StepUpUi.WaitingPin || r.purpose != purpose) return@collect
                when (r) {
                    is PinPadResult.Verified -> finish(r.token)
                    is PinPadResult.Cancelled -> reset()
                }
            }
        }
    }

    fun start(purpose: String, prompt: StepUpPrompt, onError: (ApiError) -> Unit, onToken: suspend (StepUpToken) -> Unit) {
        if (busy) return
        this.purpose = purpose
        results.detail = prompt.subtitle
        this.onToken = onToken
        this.onError = onError
        _ui.value = StepUpUi.Working
        scope.launch {
            if (!auth.biometricReady()) return@launch openPin()
            when (val r = auth.challenge(purpose)) {
                is ApiResult.Failure -> fail(r.error)
                is ApiResult.Success -> {
                    val signature = deviceKeys.biometricSignatureForPrompt()
                    if (!r.data.allowsBiometric || signature == null) openPin()
                    else _ui.value = StepUpUi.Biometric(BiometricRequest(r.data, signature), prompt)
                }
            }
        }
    }

    fun onBiometricResult(outcome: BiometricOutcome) {
        val request = (_ui.value as? StepUpUi.Biometric)?.request ?: return
        when (outcome) {
            BiometricOutcome.Cancelled -> reset()
            BiometricOutcome.UsePin, is BiometricOutcome.Failed -> openPin()   // 지문이 막혀도 간편 비밀번호로
            is BiometricOutcome.Signed -> scope.launch {
                _ui.value = StepUpUi.Working
                when (val v = auth.verifyBiometric(request.challenge.challengeId, outcome.signatureBase64)) {
                    is ApiResult.Success -> finish(v.data)
                    is ApiResult.Failure -> fail(v.error)
                }
            }
        }
    }

    /** 화면이 간편 비밀번호 화면을 열었음 */
    fun onPinOpened() {
        if (_ui.value is StepUpUi.OpenPin) _ui.value = StepUpUi.WaitingPin
    }

    private fun openPin() {
        _ui.value = StepUpUi.OpenPin(purpose ?: return reset())
    }

    private suspend fun finish(token: StepUpToken) {
        _ui.value = StepUpUi.Working
        val block = onToken
        clear()
        block?.invoke(token)
        _ui.value = StepUpUi.Idle
    }

    private fun fail(error: ApiError) {
        val block = onError
        reset()
        block?.invoke(error)
    }

    private fun reset() {
        clear()
        _ui.value = StepUpUi.Idle
    }

    private fun clear() {
        onToken = null
        onError = null
    }
}

/** ViewModel에 주입해서 `stepUpFactory.create(viewModelScope)`로 만든다 */
class StepUpFactory @Inject constructor(
    private val auth: AuthRepository,
    private val deviceKeys: DeviceKeyManager,
    private val results: StepUpResults,
) {
    fun create(scope: CoroutineScope) = StepUpController(auth, deviceKeys, results, scope)
}
