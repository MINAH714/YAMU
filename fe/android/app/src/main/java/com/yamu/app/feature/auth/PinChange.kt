package com.yamu.app.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.SecureScreen
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── M-08 간편 비밀번호 바꾸기(QA 07 · P1-16) ─────────────────────────

/** Verify = 본인 확인 중(지문 창 · 간편 비밀번호 화면) · Create = 새 6자리 두 번 · Saving = 저장 중 · Retry = 실패 · 결과 모름 → 다시 시도 */
enum class PinChangeStep { Verify, Create, Saving, Retry }

/** 화면을 닫는 까닭 — 바꿈 · 그냥 닫음(문장이 있으면 스낵바) */
sealed interface PinChangeEnd {
    data object Changed : PinChangeEnd
    data class Closed(val message: String? = null) : PinChangeEnd
}

data class PinChangeState(
    val step: PinChangeStep = PinChangeStep.Verify,
    /** Retry에서 보여 줄 문장 */
    val message: String? = null,
    /** 연결이 끊겨 바뀌었는지 모른다 — 닫을 때도 그렇게 알린다 */
    val unknown: Boolean = false,
    val end: PinChangeEnd? = null,
)

/**
 * 간편 비밀번호 바꾸기 — 본인 확인(SECURITY_SETTINGS, 지문이 켜져 있으면 지문 · 아니면 지금 비밀번호) → 새 6자리 두 번 → POST /auth/pin.
 * - 본인 확인 토큰은 5분 · 1회용이라 메모리에만 둔다. 저장이 STEP_UP_REQUIRED(시간이 지남 · 이미 씀)면 새 번호는 그대로 두고 본인 확인만 다시 해서 저장한다.
 *   서버는 토큰을 먼저 확인하고 실패하면 저장하지 않는다(BE PinService)
 * - 쉬운 번호(INVALID_PIN_FORMAT)는 서버가 토큰을 쓰기 전에 거절해서 같은 토큰으로 새 번호를 다시 넣게 한다
 * - 연결이 끊기면 바뀌었는지 모른다. '다시 시도'는 같은 토큰으로 다시 보내고, 이미 바뀌었으면(토큰을 씀) 본인 확인부터 다시 한다.
 *   이때 간편 비밀번호로 확인하면 새 번호와 전 번호 중 어느 쪽이 맞는지 사용자가 모른다(한계 — 틀리면 한 번 더 넣으면 된다)
 * - 서버는 이 기기 말고 다른 기기의 로그인을 끊는다. 이 폰의 로그인 · 기기 키는 그대로다
 */
@HiltViewModel
class PinChangeViewModel @Inject constructor(
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(PinChangeState())
    val state: StateFlow<PinChangeState> = _state.asStateFlow()
    val stepUp = stepUpFactory.create(viewModelScope)
    private val entry = NewPinEntry(viewModelScope, onConfirmed = ::onNewPin)
    val pin: StateFlow<NewPinState> = entry.state

    private var token: StepUpToken? = null
    private var newPin: String? = null

    init { verify(again = false) }

    fun onKey(key: KeypadKey) {
        if (_state.value.step == PinChangeStep.Create) entry.onKey(key)
    }

    private fun verify(again: Boolean) {
        if (stepUp.busy) return
        _state.update { it.copy(step = PinChangeStep.Verify, message = null) }
        val subtitle = when {
            !again -> null
            _state.value.unknown -> "한 번 더 확인하면 새 비밀번호로 바꿔요"
            else -> "본인 확인 시간이 지나 한 번 더 확인해요"
        }
        stepUp.start(
            purpose = StepUpPurpose.SECURITY_SETTINGS,
            prompt = StepUpPrompt("간편 비밀번호를 바꿔요", subtitle),
            onError = { e -> onVerifyFailed(e) },
        ) { t ->
            token = t
            val p = newPin
            if (p == null) _state.update { it.copy(step = PinChangeStep.Create) } else save(p)
        }
    }

    private fun onVerifyFailed(e: ApiError) {
        if (newPin == null) return end(PinChangeEnd.Closed(e.authMessage()))
        _state.update { it.copy(step = PinChangeStep.Retry, message = e.authMessage()) }
    }

    /** 본인 확인을 취소하고 닫았을 때(지문 창 취소 · 간편 비밀번호 화면 뒤로) — 화면이 본인 확인 상태를 볼 때마다 부른다 */
    fun onStepUpIdle() {
        if (stepUp.busy || _state.value.step != PinChangeStep.Verify || _state.value.end != null) return
        if (newPin == null) return end(PinChangeEnd.Closed())   // 아무것도 바뀌지 않았다
        _state.update {
            it.copy(
                step = PinChangeStep.Retry,
                message = if (it.unknown) UNKNOWN_MESSAGE else "본인 확인을 마치지 않아 바꾸지 못했어요",
            )
        }
    }

    private fun onNewPin(pin: String) {
        newPin = pin
        save(pin)
    }

    fun retry() {
        if (_state.value.step != PinChangeStep.Retry) return
        save(newPin ?: return)
    }

    private fun save(pin: String) {
        val t = token ?: return verify(again = true)
        _state.update { it.copy(step = PinChangeStep.Saving, message = null) }
        viewModelScope.launch {
            val r = auth.changePin(pin, t.token)
            if (r is ApiResult.Success) {
                token = null
                return@launch end(PinChangeEnd.Changed)
            }
            val error = (r as ApiResult.Failure).error
            when (error.code) {
                "STEP_UP_REQUIRED" -> {
                    token = null
                    verify(again = true)
                }
                "INVALID_PIN_FORMAT" -> {
                    newPin = null
                    _state.update { it.copy(step = PinChangeStep.Create) }
                    entry.fail(error.authMessage())
                }
                "DEVICE_LOCKED" -> end(PinChangeEnd.Closed(error.authMessage()))
                else -> _state.update {
                    val unknown = error.isOutcomeUnknown   // 응답이 끊김 · 프록시 502 · 504 — 서버가 바꿨을 수 있다
                    it.copy(
                        step = PinChangeStep.Retry,
                        unknown = it.unknown || unknown,
                        message = if (unknown) UNKNOWN_MESSAGE else error.authMessage(),
                    )
                }
            }
        }
    }

    /** 뒤로(앱바 · 시스템) — 확인 단계는 첫 입력으로, 저장 중은 막고, 나머지는 닫는다 */
    fun back() {
        val s = _state.value
        when (s.step) {
            PinChangeStep.Saving -> Unit
            PinChangeStep.Create -> if (!entry.back()) end(PinChangeEnd.Closed())
            PinChangeStep.Verify -> end(PinChangeEnd.Closed())
            PinChangeStep.Retry -> end(PinChangeEnd.Closed(if (s.unknown) UNKNOWN_CLOSE else null))
        }
    }

    private fun end(how: PinChangeEnd) {
        token = null
        _state.update { it.copy(end = how) }
    }

    internal companion object {
        const val UNKNOWN_MESSAGE = "연결이 끊겨 바뀌었는지 확인하지 못했어요 · 다시 시도해 주세요"
        const val UNKNOWN_CLOSE = "바뀌었는지 확인하지 못했어요 · 새 비밀번호가 안 되면 전에 쓰던 비밀번호를 넣어 주세요"
    }
}

/** [onEnd] 바꿈 · 닫음 — 부른 쪽이 화면을 닫고 스낵바를 띄운다 */
@Composable
fun PinChangeRoute(onOpenPinPad: (purpose: String) -> Unit, onEnd: (PinChangeEnd) -> Unit, viewModel: PinChangeViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val stepUpUi by viewModel.stepUp.ui.collectAsStateWithLifecycle()
    StepUpEffect(viewModel.stepUp, onOpenPinPad)
    LaunchedEffect(stepUpUi) { viewModel.onStepUpIdle() }
    LaunchedEffect(s.end) { s.end?.let(onEnd) }
    BackHandler(onBack = viewModel::back)
    SecureScreen()
    val c = YamuTheme.colors
    Scaffold(
        topBar = { YamuTopBar("간편 비밀번호 바꾸기", onBack = viewModel::back) },
        bottomBar = {
            if (s.step == PinChangeStep.Retry) BottomCta { PrimaryButton("다시 시도", onClick = viewModel::retry) }
            else NumberKeypad(KeypadMode.Pin, onKey = viewModel::onKey, shuffleKey = pin.shuffleKey, background = c.background, topDivider = true)
        },
        containerColor = c.surface,
    ) { inner ->
        PinBody(
            inner = inner,
            title = if (pin.confirming) "한 번 더 입력해 주세요" else "새 간편 비밀번호 6자리를\n만들어 주세요",
            sub = "앱을 열 때 써요",
            filled = pin.input.length,
            error = s.message ?: pin.error,
            shakeKey = pin.errorKey,
            alignStart = true,
            top = 20.dp,
        )
    }
}
