package com.yamu.app.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.BiometricOutcome
import com.yamu.app.core.security.signWithBiometric
import com.yamu.app.core.ui.SecureScreen
import com.yamu.app.core.ui.findActivity
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.navigation.PinPad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 본인 인증을 쓰는 화면에 한 줄로 붙인다 — 지문 창(C-02d · C-13b)을 열고, 간편 비밀번호가 필요하면 [onOpenPinPad].
 * 지문 창 아래 버튼은 '간편 비밀번호 사용'(누르면 PinPad 화면).
 */
@Composable
fun StepUpEffect(controller: StepUpController, onOpenPinPad: (purpose: String) -> Unit) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(ui) {
        when (val u = ui) {
            is StepUpUi.Biometric -> {
                val outcome = activity?.signWithBiometric(
                    title = u.prompt.title,
                    subtitle = u.prompt.subtitle,
                    negativeText = "간편 비밀번호 사용",
                    signature = u.request.signature,
                    challenge = u.request.challenge.challenge,
                ) ?: BiometricOutcome.UsePin
                controller.onBiometricResult(outcome)
            }
            is StepUpUi.OpenPin -> {
                controller.onPinOpened()
                onOpenPinPad(u.purpose)
            }
            else -> Unit
        }
    }
}

// ───────────────────────── 간편 비밀번호로 본인 인증(PinPad) ─────────────────────────

data class PinPadState(
    val input: String = "",
    val error: String? = null,
    val busy: Boolean = false,
    val locked: Boolean = false,
    val done: Boolean = false,
    val shuffleKey: Int = 0,
)

/** #46 → 6자리 → #47. 성공하면 토큰을 [StepUpResults]로 부른 화면에 넘기고 닫힌다 */
@HiltViewModel
class PinPadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val results: StepUpResults,
) : ViewModel() {
    val purpose: String = savedStateHandle.toRoute<PinPad>().purpose
    val detail: String? = results.detail
    private val _state = MutableStateFlow(PinPadState())
    val state: StateFlow<PinPadState> = _state.asStateFlow()

    fun onKey(key: KeypadKey) {
        val s = _state.value
        if (s.busy || s.locked || s.done) return
        val next = s.input.applyKey(key)
        _state.update { it.copy(input = next, error = null) }
        if (next.length == 6) verify(next)
    }

    private fun verify(pin: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val challenge = auth.challenge(purpose)
            if (challenge is ApiResult.Failure) return@launch fail(challenge.error)
            val issued = (challenge as ApiResult.Success).data   // PIN도 기기 키로 이 challenge에 서명해 보낸다
            when (val v = auth.verifyPin(issued, pin)) {
                is ApiResult.Success -> {
                    results.deliver(PinPadResult.Verified(purpose, v.data))
                    _state.update { it.copy(busy = false, done = true) }
                }
                is ApiResult.Failure -> fail(v.error)
            }
        }
    }

    fun cancel() {
        if (!_state.value.done) results.deliver(PinPadResult.Cancelled(purpose))
    }

    private fun fail(error: ApiError) {
        val lockMillis = error.lockMillis()
        _state.update {
            it.copy(
                busy = false,
                input = "",
                error = error.authMessage(),
                locked = lockMillis != null,
                shuffleKey = it.shuffleKey + 1,
            )
        }
        if (lockMillis != null) viewModelScope.launch {
            delay(lockMillis)
            _state.update { it.copy(locked = false, error = null) }
        }
    }
}

private fun pinPadTitle(purpose: String) = when (purpose) {
    StepUpPurpose.ACCOUNT_LINK -> "간편 비밀번호로\n계좌를 연결해요"
    StepUpPurpose.CARD_REGISTER -> "간편 비밀번호로\n카드를 등록해요"
    StepUpPurpose.BARCODE -> "간편 비밀번호로\n결제 코드를 열어요"
    else -> "간편 비밀번호를\n넣어 주세요"
}

/** 지문 대신 간편 비밀번호 — A-06 키패드(배열 섞기) · 화면 캡처 막음 */
@Composable
fun PinPadRoute(onClose: () -> Unit, viewModel: PinPadViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val close = {
        viewModel.cancel()
        onClose()
    }
    LaunchedEffect(s.done) { if (s.done) onClose() }
    BackHandler(onBack = close)
    SecureScreen()
    Scaffold(
        topBar = { YamuTopBar("본인 확인", onBack = close) },   // 결제 코드면 앱바 오른쪽에 '모의 결제'는 두지 않는다(코드 화면이 보여 줌)
        bottomBar = { NumberKeypad(KeypadMode.Pin, onKey = viewModel::onKey, shuffleKey = s.shuffleKey) },
        containerColor = YamuTheme.colors.surface,
    ) { inner ->
        PinBody(
            inner = inner,
            title = pinPadTitle(viewModel.purpose),
            // 무엇을 확인하는지 먼저(지문 창 부제와 같은 말) — 결제 코드는 열리는 시간을 알려 준다
            sub = when {
                viewModel.purpose == StepUpPurpose.BARCODE -> "인증하면 3분 동안 결제 코드가 보여요"
                viewModel.detail != null -> viewModel.detail
                else -> "가입할 때 만든 6자리예요"
            },
            filled = s.input.length,
            error = s.error,
        )
    }
}
