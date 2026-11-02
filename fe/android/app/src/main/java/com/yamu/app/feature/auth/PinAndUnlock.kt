package com.yamu.app.feature.auth

import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.TopBarLogo
import com.yamu.app.core.designsystem.component.QuietLink
import com.yamu.app.core.designsystem.component.CompactButton
import com.yamu.app.core.designsystem.component.StartStepper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PinDots
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.BiometricOutcome
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.signWithBiometric
import com.yamu.app.core.ui.SecureScreen
import com.yamu.app.core.ui.findActivity
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.AuthValidation
import com.yamu.app.feature.auth.data.StepUpChallenge
import com.yamu.app.feature.auth.data.DEVICE_NOT_REGISTERED
import com.yamu.app.feature.auth.data.authErrorMessage
import com.yamu.app.feature.auth.data.lockRemainingMillis
import com.yamu.app.feature.auth.data.lockedUntilClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.Signature
import javax.inject.Inject

internal fun ApiError.authMessage(): String =
    authErrorMessage(code, detailInt("remainingAttempts"), lockedUntilClock(detailString("lockedUntil"))) ?: userMessage()

/** DEVICE_LOCKED일 때 풀릴 때까지 남은 시간(ms) — 시각을 모르면 null이라 입력을 막지 않는다 */
internal fun ApiError.lockMillis(): Long? = if (code == "DEVICE_LOCKED") lockRemainingMillis(detailString("lockedUntil")) else null

/** 숫자 키 → 6자리 문자열 */
internal fun String.applyKey(key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> if (length < 6) this + key.value else this
    KeypadKey.DoubleZero -> this
    KeypadKey.Delete -> dropLast(1)
}

// ───────────────────────── A-06 간편 비밀번호 · 지문 ─────────────────────────

/** Biometric = 지문을 같이 쓸지 묻기 · Device = 지문을 못 쓰는 폰에서 기기 키만 등록(실패하면 다시 시도) */
enum class PinSetupStep { First, Confirm, Biometric, Device, Done }

data class PinSetupState(
    val step: PinSetupStep = PinSetupStep.First,
    val input: String = "",
    val error: String? = null,
    val busy: Boolean = false,
    val shuffleKey: Int = 0,
)

@HiltViewModel
class PinSetupViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(PinSetupState())
    val state: StateFlow<PinSetupState> = _state.asStateFlow()
    private var first: String = ""

    fun onKey(key: KeypadKey) {
        val s = _state.value
        if (s.busy || s.step != PinSetupStep.First && s.step != PinSetupStep.Confirm) return
        val next = s.input.applyKey(key)
        _state.update { it.copy(input = next, error = null) }
        if (next.length == 6) onComplete(next)
    }

    private fun onComplete(pin: String) {
        when (_state.value.step) {
            PinSetupStep.First -> {
                val rule = AuthValidation.pin(pin)
                if (rule != null) return reset(rule, PinSetupStep.First)
                first = pin
                _state.update { it.copy(step = PinSetupStep.Confirm, input = "", shuffleKey = it.shuffleKey + 1) }
            }
            PinSetupStep.Confirm -> {
                if (pin != first) return reset("처음 입력과 달라요. 처음부터 다시 만들어 주세요", PinSetupStep.First)
                viewModelScope.launch {
                    _state.update { it.copy(busy = true) }
                    when (val r = auth.setPin(pin)) {
                        is ApiResult.Success -> if (auth.canUseBiometric()) {
                            _state.update { it.copy(busy = false, input = "", step = PinSetupStep.Biometric) }
                        } else {
                            _state.update { it.copy(busy = false, input = "", step = PinSetupStep.Device) }
                            register(biometric = false)
                        }
                        is ApiResult.Failure -> reset(r.error.authMessage(), PinSetupStep.First)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun reset(message: String, step: PinSetupStep) {
        first = ""
        _state.update { PinSetupState(step = step, error = message, shuffleKey = it.shuffleKey + 1) }
    }

    fun enableBiometric() = register(biometric = true)

    /** '나중에' — 지문 없이 기기 키만 등록(로그인에 기기 등록이 꼭 있어야 한다) */
    fun skipBiometric() = register(biometric = false)

    fun retryDevice() = register(biometric = false)

    /** 가입 마지막 단계 — 성공해야 끝. 실패하면 이 단계에 남아 다시 시도(PIN은 이미 저장됨) */
    private fun register(biometric: Boolean) {
        if (_state.value.busy) return   // 연타
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = auth.registerDevice(biometric)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, step = PinSetupStep.Done) }
                is ApiResult.Failure -> _state.update { it.copy(busy = false, error = r.error.authMessage()) }
            }
        }
    }
}

@Composable
fun PinSetupRoute(onBack: () -> Unit, onFinished: () -> Unit, viewModel: PinSetupViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.step) { if (s.step == PinSetupStep.Done) onFinished() }
    SecureScreen()
    val c = YamuTheme.colors
    if (s.step == PinSetupStep.Biometric) {
        Scaffold(
            topBar = { YamuTopBar("회원가입") },
            bottomBar = {
                BottomCta(secondary = { NeutralButton("나중에 할게요", onClick = viewModel::skipBiometric, enabled = !s.busy) }) {
                    PrimaryButton("지문 사용하기", onClick = viewModel::enableBiometric, loading = s.busy)
                }
            },
            containerColor = c.surface,
        ) { inner ->
            CenteredMessage(inner, YamuPose.Safe, "지문으로도 열까요?", s.error ?: "앱을 열 때와 결제 코드를 열 때 지문으로 확인해요", isError = s.error != null)
        }
        return
    }
    if (s.step == PinSetupStep.Device) {
        Scaffold(
            topBar = { YamuTopBar("회원가입") },
            bottomBar = {
                if (s.error != null) BottomCta { PrimaryButton("다시 시도", onClick = viewModel::retryDevice, loading = s.busy) }
            },
            containerColor = c.surface,
        ) { inner ->
            CenteredMessage(inner, YamuPose.Safe, "이 폰을 등록하고 있어요", s.error ?: "다음부터 이 폰에서 간편 비밀번호로 로그인해요", isError = s.error != null)
        }
        return
    }
    Scaffold(
        topBar = {
            Column(Modifier.background(c.surface)) {
                YamuTopBar("회원가입", onBack = onBack)
                StartStepper(step = 3, total = 3, label = "간편 비밀번호")
            }
        },
        bottomBar = { NumberKeypad(KeypadMode.Pin, onKey = viewModel::onKey, shuffleKey = s.shuffleKey, background = c.background, topDivider = true) },
        containerColor = c.surface,
    ) { inner ->
        PinBody(
            inner = inner,
            title = if (s.step == PinSetupStep.First) "간편 비밀번호 6자리를\n만들어 주세요" else "한 번 더 입력해 주세요",
            sub = "앱을 열 때 써요",
            filled = s.input.length,
            error = s.error,
            alignStart = true,
            top = 20.dp,
        )
    }
}

// ───────────────────────── A-03 · A-03b 로그인(앱을 열 때 · 자동 잠금) ─────────────────────────

data class BiometricRequest(val challenge: StepUpChallenge, val signature: Signature)

data class PinLoginState(
    val name: String? = null,
    val input: String = "",
    val error: String? = null,
    val locked: Boolean = false,
    val busy: Boolean = false,
    val biometricReady: Boolean = false,
    val pendingBiometric: BiometricRequest? = null,
    val loggedIn: Boolean? = null,          // 끝나면 초기 설정 완료 여부
    val noDevice: Boolean = false,          // 이 폰에 등록된 기기가 없음(서버에서 해제 등) → 첫 화면
    val shuffleKey: Int = 0,
)

/**
 * 기기 + PIN · 지문 로그인(DEC-001, POST /auth/login/challenge → /auth/login).
 * 앱을 열 때와 자동 잠금 때 같은 화면 · 같은 경로로 매번 새 토큰을 받는다.
 * challenge는 한 번만 쓸 수 있어서 시도할 때마다 새로 받는다.
 */
@HiltViewModel
class PinLoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val deviceKeys: DeviceKeyManager,
) : ViewModel() {
    private val _state = MutableStateFlow(PinLoginState())
    val state: StateFlow<PinLoginState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val session = auth.session()
            if (!session.deviceRegistered) return@launch _state.update { it.copy(noDevice = true) }
            val ready = auth.biometricReady()
            _state.update { it.copy(name = session.name, biometricReady = ready) }
            if (ready) startBiometric()
        }
    }

    fun startBiometric() {
        if (_state.value.busy || _state.value.locked) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = auth.loginChallenge()) {
                is ApiResult.Failure -> fail(r.error)
                is ApiResult.Success -> {
                    val signature = deviceKeys.biometricSignatureForPrompt()
                    if (!r.data.allowsBiometric || signature == null) {
                        _state.update { it.copy(busy = false, biometricReady = false, error = "지문을 쓸 수 없어요 · 간편 비밀번호로 로그인해 주세요") }
                    } else {
                        _state.update { it.copy(busy = false, pendingBiometric = BiometricRequest(r.data, signature)) }
                    }
                }
            }
        }
    }

    fun onBiometricResult(outcome: BiometricOutcome) {
        val request = _state.value.pendingBiometric ?: return
        _state.update { it.copy(pendingBiometric = null) }
        when (outcome) {
            BiometricOutcome.Cancelled, BiometricOutcome.UsePin -> Unit   // 아래 키패드로 넣으면 됨
            is BiometricOutcome.Failed -> _state.update { it.copy(error = outcome.message) }
            is BiometricOutcome.Signed -> viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                when (val v = auth.loginWithBiometric(request.challenge, outcome.signatureBase64)) {
                    is ApiResult.Success -> _state.update { it.copy(busy = false, loggedIn = v.data.onboardingCompleted) }
                    is ApiResult.Failure -> fail(v.error)
                }
            }
        }
    }

    fun onKey(key: KeypadKey) {
        val s = _state.value
        if (s.busy || s.locked) return
        val next = s.input.applyKey(key)
        _state.update { it.copy(input = next, error = null) }
        if (next.length == 6) loginWithPin(next)
    }

    private fun loginWithPin(pin: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val challenge = auth.loginChallenge()
            if (challenge is ApiResult.Failure) return@launch fail(challenge.error)
            when (val v = auth.loginWithPin((challenge as ApiResult.Success).data, pin)) {
                is ApiResult.Success -> _state.update { it.copy(busy = false, loggedIn = v.data.onboardingCompleted) }
                is ApiResult.Failure -> fail(v.error)
            }
        }
    }

    private fun fail(error: ApiError) {
        if (error.code == DEVICE_NOT_REGISTERED) return _state.update { it.copy(busy = false, noDevice = true) }
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

/** [onNoDevice] — 이 폰에 등록된 기기가 없을 때(첫 화면으로). 기기 정보는 저장소가 이미 지웠다 */
@Composable
fun PinLoginRoute(
    onLoggedIn: (onboardingCompleted: Boolean) -> Unit,
    onNoDevice: () -> Unit,
    onDebugMenu: (() -> Unit)?,
    viewModel: PinLoginViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    SecureScreen()
    LaunchedEffect(s.loggedIn) { s.loggedIn?.let(onLoggedIn) }
    LaunchedEffect(s.noDevice) { if (s.noDevice) onNoDevice() }
    LaunchedEffect(s.pendingBiometric) {
        val request = s.pendingBiometric ?: return@LaunchedEffect
        val outcome = activity?.signWithBiometric(
            title = "지문으로 로그인",
            subtitle = "YAMU",
            negativeText = "간편 비밀번호로",
            signature = request.signature,
            challenge = request.challenge.challenge,
        ) ?: BiometricOutcome.Failed("지문 창을 열 수 없어요")
        viewModel.onBiometricResult(outcome)
    }
    val c = YamuTheme.colors
    // A-03b 모양 — 가운데 YAMU 로고, 점 아래에 지문 버튼, 키패드만 아래에
    Scaffold(
        topBar = { YamuTopBar("로그인", logo = TopBarLogo.Center) },
        bottomBar = { NumberKeypad(KeypadMode.Pin, onKey = viewModel::onKey, shuffleKey = s.shuffleKey) },
        containerColor = c.surface,
    ) { inner ->
        PinBody(
            inner = inner,
            title = "${s.name ?: "야무"}님, 다시 만나 반가워요",
            sub = "간편 비밀번호 6자리를 입력해 주세요",
            filled = s.input.length,
            error = s.error,
        ) {
            if (s.biometricReady) {
                CompactButton("지문으로 로그인", onClick = viewModel::startBiometric, icon = YamuIcons.Fingerprint)
                Spacer(Modifier.height(8.dp))
            }
            if (onDebugMenu != null) QuietLink("디버그 메뉴", onClick = onDebugMenu)
        }
    }
}

// ───────────────────────── A-07 가입 완료 ─────────────────────────

@HiltViewModel
class SignupDoneViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name.asStateFlow()
    init { viewModelScope.launch { _name.value = auth.session().name } }
}

@Composable
fun SignupDoneRoute(onLinkNow: () -> Unit, onLater: () -> Unit, viewModel: SignupDoneViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    Scaffold(
        bottomBar = {
            BottomCta(secondary = { TextButtonGrey("나중에 할게요", onClick = onLater, modifier = Modifier.fillMaxWidth(), small = true) }) {
                PrimaryButton("계좌 · 카드 연결하고 시작하기", onClick = onLinkNow)
            }
        },
        containerColor = YamuTheme.colors.surface,
        contentWindowInsets = WindowInsets.statusBars,
    ) { inner ->
        CenteredMessage(inner, YamuPose.Joy, "${name ?: "야무"}님, 가입을 축하해요!", "계좌 · 카드 연결은 약 1분이면 끝나요", animate = true)
    }
}

// ───────────────────────── 공통 조각 ─────────────────────────

/**
 * 간편 비밀번호 화면 몸통 — 위에서부터 쌓는다(앱바 아래 [top]). 잠금 해제 · 본인 확인은 가운데, 만들기(A-06)는 왼쪽 정렬.
 * [below] = 점 아래(지문으로 잠금 해제 · 이메일로 로그인).
 */
@Composable
internal fun PinBody(
    inner: androidx.compose.foundation.layout.PaddingValues,
    title: String,
    sub: String,
    filled: Int,
    error: String?,
    alignStart: Boolean = false,
    top: androidx.compose.ui.unit.Dp = 32.dp,
    below: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val c = YamuTheme.colors
    val align = if (alignStart) TextAlign.Start else TextAlign.Center
    Column(
        Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().padding(horizontal = YamuSpacing.screen),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(top))
        Text(title, style = YamuTheme.typography.headlineS, color = c.text1, textAlign = align, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(if (alignStart) 8.dp else 6.dp))   // 만들기(A-06)는 mt8, 잠금 해제 · 본인 인증(가운데)은 6
        Text(error ?: sub, style = YamuTheme.typography.bodyM, color = if (error != null) c.error else c.text2, textAlign = align, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        PinDots(filled = filled, error = error != null && filled == 0, modifier = Modifier.align(Alignment.CenterHorizontally))
        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, content = below)
    }
}

@Composable
private fun CenteredMessage(
    inner: androidx.compose.foundation.layout.PaddingValues,
    pose: YamuPose,
    title: String,
    sub: String,
    animate: Boolean = false,
    isError: Boolean = false,
) {
    val c = YamuTheme.colors
    // 위에서부터 — 상태 표시줄 아래 72dp에 캐릭터(프로토타입 A-07)
    Column(
        Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().padding(horizontal = YamuSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        CharacterImage(pose, size = CharacterSize.L, animate = animate)
        Spacer(Modifier.height(32.dp))
        Text(title, style = YamuTheme.typography.headlineS, color = c.text1, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = YamuTheme.typography.bodyM, color = if (isError) c.error else c.text2, textAlign = TextAlign.Center)
    }
}
