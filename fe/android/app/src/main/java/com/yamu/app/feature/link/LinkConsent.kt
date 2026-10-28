package com.yamu.app.feature.link

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.QuietLink
import com.yamu.app.core.designsystem.component.FlowStep
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.MoreDetails
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.sms.rememberOneWonSms
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.StepUpEffect
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkSession
import com.yamu.app.feature.link.data.OneWonSent
import com.yamu.app.navigation.LinkConsent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkConsentState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val session: LinkSession? = null,
    val biometricReady: Boolean = false,
    val agreed: Boolean = false,
    val oneWon: OneWonSent? = null,          // 1원을 보낸 뒤(C-02c)
    val code: String = "",
    val codeFromSms: Boolean = false,
    val sending: Boolean = false,
    val signing: Boolean = false,
    val error: String? = null,
    val codeError: Boolean = false,
    val needResend: Boolean = false,         // 시간 지남 · 5번 틀림
    val verified: Boolean = false,
) {
    /** 이미 본인 계좌를 확인한 사용자는 1원 없이 약관 서명만 */
    val needsOneWon: Boolean get() = session?.ownAccount?.let { it.required && !it.verified } ?: false
    val codeLength: Int get() = oneWon?.codeLength ?: 4
    val readyToSign: Boolean get() = agreed && (!needsOneWon || (oneWon != null && !needResend && code.length == codeLength))
}

/** C-02 · C-02c — 1원 받기(#31 → 세션) · 숫자 채움(문자 자동 · 직접) · 지문 한 번으로 확인 + 약관 서명(추가 3) */
@HiltViewModel
class LinkConsentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val link: LinkRepository,
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<LinkConsent>().linkSessionId
    val stepUp = stepUpFactory.create(viewModelScope)
    private val _state = MutableStateFlow(LinkConsentState())
    val state: StateFlow<LinkConsentState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            val ready = auth.biometricReady()
            when (val r = link.session(sessionId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, session = r.data, biometricReady = ready) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = r.error.userMessage()) }
            }
        }
    }

    fun toggleAgree() = _state.update { it.copy(agreed = !it.agreed, error = null) }

    /** [동의하고 1원 받기] — 화면이 문자 대기를 먼저 켠 뒤 부른다 */
    fun sendOneWon() {
        val s = _state.value
        if (!s.agreed || s.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            when (val r = link.sendOneWon(sessionId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(sending = false, oneWon = r.data, code = "", codeFromSms = false, codeError = false, needResend = false)
                }
                is ApiResult.Failure -> {
                    if (r.error.code == "OWN_ACCOUNT_ALREADY_VERIFIED") return@launch load()
                    val message = if (r.error.code == "AUTH_REQUEST_TOO_FREQUENT") "방금 보냈어요 · 1분 뒤 다시 받아 주세요" else r.error.userMessage()
                    _state.update { it.copy(sending = false, error = message) }
                }
            }
        }
    }

    fun onCode(value: String, fromSms: Boolean = false) =
        _state.update { it.copy(code = value, codeFromSms = fromSms, codeError = false, error = null) }

    /** [지문으로 서명하고 연결] */
    fun sign() {
        val s = _state.value
        if (!s.readyToSign || stepUp.busy) return
        val session = s.session ?: return
        val subtitle = listOfNotNull(session.ownAccount?.label, "약관 ${session.terms.size}건").joinToString(" · ")
        _state.update { it.copy(signing = true, error = null) }
        stepUp.start(
            purpose = StepUpPurpose.ACCOUNT_LINK,
            prompt = StepUpPrompt("지문으로 서명해 주세요", subtitle),
            onError = { e -> _state.update { it.copy(signing = false, error = e.userMessage()) } },
        ) { token ->
            val code = if (s.needsOneWon) s.code else null
            when (val r = link.verify(sessionId, token.token, code, session.terms)) {
                is ApiResult.Success -> _state.update { it.copy(signing = false, verified = true) }
                is ApiResult.Failure -> onVerifyFailed(r.error)
            }
        }
    }

    /** 본인 인증 창을 닫았을 때 버튼을 다시 켠다 */
    fun onStepUpIdle() {
        if (_state.value.signing && !stepUp.busy && !_state.value.verified) _state.update { it.copy(signing = false) }
    }

    private fun onVerifyFailed(e: ApiError) {
        val left = e.detailInt("remainingAttempts")
        _state.update {
            when (e.code) {
                "AUTH_CODE_MISMATCH" ->
                    if (left != null && left <= 0) it.copy(signing = false, code = "", needResend = true, error = "숫자를 5번 틀렸어요 · 1원을 다시 받아 주세요")
                    else it.copy(signing = false, code = "", codeError = true, error = "숫자가 맞지 않아요" + (left?.let { n -> " · ${n}번 남았어요" } ?: ""))
                "AUTH_CODE_EXPIRED" -> it.copy(signing = false, code = "", needResend = true, error = "시간이 지났어요 · 1원을 다시 받아 주세요")
                "STEP_UP_REQUIRED", "STEP_UP_TOKEN_INVALID" -> it.copy(signing = false, error = "본인 확인 시간이 지났어요 · 다시 눌러 주세요")
                else -> it.copy(signing = false, error = e.userMessage())
            }
        }
    }

    fun navigated() = _state.update { it.copy(verified = false) }
}

@Composable
fun LinkConsentRoute(
    onBack: () -> Unit,
    onVerified: (linkSessionId: String) -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    viewModel: LinkConsentViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stepUpUi by viewModel.stepUp.ui.collectAsStateWithLifecycle()
    StepUpEffect(viewModel.stepUp, onOpenPinPad)
    LaunchedEffect(stepUpUi) { viewModel.onStepUpIdle() }
    LaunchedEffect(s.verified) {
        if (s.verified) {
            viewModel.navigated()
            s.session?.let { onVerified(it.id) }
        }
    }
    // C-02b: 1원 문자가 오면 시스템 창 → 허용하면 숫자 채움
    val sms = rememberOneWonSms(prefix = s.oneWon?.prefix ?: "YAMU", length = s.codeLength) { code ->
        viewModel.onCode(code, fromSms = true)
    }
    val c = YamuTheme.colors
    val askOneWon = s.needsOneWon && (s.oneWon == null || s.needResend)
    FlowScreen(
        title = "계좌 · 카드 연결",
        onBack = onBack,
        step = FlowStep(1, 2),
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (s.session != null) {
                BottomCta {
                    if (askOneWon) {
                        PrimaryButton(
                            if (s.needResend) "1원 다시 받기" else "동의하고 1원 받기",
                            onClick = {
                                if (!s.agreed) viewModel.toggleAgree()   // 누르는 것이 동의
                                sms.start()                 // 문자 대기 먼저
                                viewModel.sendOneWon()
                            },
                            loading = s.sending,
                        )
                    } else {
                        PrimaryButton(
                            if (s.biometricReady) "지문으로 서명하고 연결" else "간편 비밀번호로 서명하고 연결",
                            onClick = viewModel::sign,
                            enabled = s.readyToSign,
                            loading = s.signing,
                            icon = if (s.biometricReady) YamuIcons.Fingerprint else null,
                        )
                    }
                }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            // 머리 오른쪽 위에 작은 캐릭터(safe) — 글 흐름 밖(프로토타입 .ch.sm.corner). 숫자를 넣는 단계(C-02c)에선 빼서 칸에 자리를 준다
            val showCharacter = s.oneWon == null
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(end = if (showCharacter) 72.dp else 0.dp)) {
                    Text("본인 확인하고\n동의해 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
                    val count = s.session?.institutionCount ?: 0
                    if (count > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("고른 ${count}곳에 한 번에 적용해요", style = YamuTheme.typography.bodyM, color = c.text2)
                    }
                }
                if (showCharacter) CharacterImage(YamuPose.Safe, size = CharacterSize.S, modifier = Modifier.align(Alignment.TopEnd))
            }
            Spacer(Modifier.height(22.dp))   // 머리 아래 12 + 줄 위 10(.hd · .r)
            val session = s.session
            when {
                s.loading -> {
                    SkeletonBlock(height = 56.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 56.dp)
                }
                s.loadError != null || session == null -> ErrorState(s.loadError ?: "다시 시도해 주세요", onRetry = viewModel::load)
                else -> {
                    val own = session.ownAccount
                    if (s.needsOneWon && own != null) {
                        Text("본인 계좌 확인", style = YamuTheme.typography.titleM, color = c.text1)
                        Spacer(Modifier.height(2.dp))
                        if (s.oneWon == null) {
                            Text("${own.label}로 1원을 보내요", style = YamuTheme.typography.sub, color = c.text3)
                        } else {
                            Text("${own.label} · 1원과 함께 온 숫자", style = YamuTheme.typography.sub, color = c.text3)
                            Spacer(Modifier.height(YamuSpacing.x3))
                            CodeBoxes(
                                value = s.code,
                                length = s.codeLength,
                                onValueChange = { viewModel.onCode(it) },
                                label = "1원과 함께 온 숫자",
                                error = s.codeError,
                            )
                            Spacer(Modifier.height(YamuSpacing.x2))
                            if (s.codeFromSms) {
                                Text("문자에서 채웠어요", style = YamuTheme.typography.sub, color = c.success)
                            } else {
                                Text(
                                    "입금 문자나 거래내역의 '${s.oneWon?.prefix ?: "YAMU"}' 뒤 숫자 ${s.codeLength}자리예요",
                                    style = YamuTheme.typography.sub,
                                    color = c.text3,
                                )
                                // 문자로 채웠으면 다시 받을 일이 없다
                                if (!s.needResend) {
                                    QuietLink("1원 다시 받기", onClick = {
                                        sms.start()
                                        viewModel.sendOneWon()
                                    }, modifier = Modifier.offset(x = (-4).dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(22.dp))   // 줄 아래 10 + 선 위 12(프로토타입 C-02 .r · .divider)
                        HorizontalDivider(color = c.outlineVariant)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                    TermsAllRow(count = session.terms.size, checked = s.agreed, onToggle = viewModel::toggleAgree)
                    MoreDetails {
                        session.terms.forEach { term ->
                            Text(
                                term.title + if (term.required) "" else " (선택)",
                                style = YamuTheme.typography.bodyM,
                                color = c.text2,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                    s.error?.let {
                        Spacer(Modifier.height(YamuSpacing.x3))
                        Text(it, style = YamuTheme.typography.bodyM, color = c.error)
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

@Composable
private fun TermsAllRow(count: Int, checked: Boolean, onToggle: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        RoundCheckbox(checked = checked, onCheckedChange = null)
        Text("필수 약관 모두 동의", style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f))
        Text("${count}건", style = YamuTheme.typography.bodyM, color = c.text3)
    }
}
