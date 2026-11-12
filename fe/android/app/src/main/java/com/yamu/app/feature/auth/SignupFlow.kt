package com.yamu.app.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.QuietLink
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.StartStepper
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.Carrier
import com.yamu.app.feature.auth.data.CodeSent
import com.yamu.app.feature.auth.data.IdentityDraft
import com.yamu.app.feature.auth.data.SIGNUP_TOKEN_INVALID
import com.yamu.app.feature.auth.data.SignupMode
import com.yamu.app.feature.auth.data.SignupOneWon
import com.yamu.app.feature.auth.data.SignupRepository
import com.yamu.app.feature.auth.data.SignupRestart
import com.yamu.app.feature.auth.data.SignupValidation
import com.yamu.app.feature.auth.data.demoUserNotFoundMessage
import com.yamu.app.feature.auth.data.phoneVerifyGuide
import com.yamu.app.feature.auth.data.secondsLeft
import com.yamu.app.feature.auth.data.signupErrorMessage
import com.yamu.app.feature.link.BankMark
import com.yamu.app.feature.link.CodeBoxes
import com.yamu.app.feature.link.SearchingState
import com.yamu.app.feature.link.SelectRow
import com.yamu.app.feature.my.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * 가입 · 기기 재등록(Task 8.2.9, API 명세 V2 「휴대폰 본인인증」 「계좌 연결」 「기기 등록」).
 * 가입: A-04 약관 → A-05 본인인증 → A-05b 계좌 고르기 → A-05c 1원 → A-06 간편 비밀번호 → A-06b 지문 → A-07 완료
 * 재등록: A-05 본인인증 → A-08 이 폰 등록(비밀번호 그대로/새로 · 지문) → A-05b 연결된 계좌 하나 → A-05c 1원 숫자로 등록 → 로그인
 * 가입 진행 토큰은 SignupRepository가 메모리에 들고 있다. 앱이 꺼지면 본인인증부터 다시.
 */

internal fun ApiError.signupMessage(): String =
    signupErrorMessage(code, detailInt("remainingAttempts"), detailInt("retryAfterSeconds")) ?: authMessage()

private const val CODE_VALID_SECONDS = 180      // 인증번호 3분
private const val RESEND_AFTER_SECONDS = 60     // 다시 받기 1분 뒤

/** 가입 단계 표시 — 약관 1 · 본인인증 2 · 계좌 연결(계좌 고르기 · 1원) 3 · 간편 비밀번호 4 */
internal const val SIGNUP_STEPS = 4

/** 남은 시간 '2:05' */
private fun mmss(seconds: Int): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"

/** 1초마다 지금 시각 — 남은 시간 · 다시 받기 표시용 */
@Composable
private fun rememberNow(key: Any?): Long {
    val now by produceState(System.currentTimeMillis(), key) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

private fun secondsSince(startMillis: Long, now: Long): Int = ((now - startMillis) / 1_000).toInt().coerceAtLeast(0)

// ───────────────────────── 시연용 문자 알림 ─────────────────────────

/**
 * 실제 문자는 오지 않아서 BE가 준 시연용 숫자를 화면 위에 문자처럼 잠깐 띄운다. 누르면 숫자를 채운다.
 * 본인인증은 띄울 때 칸도 같이 채우고(누르면 지운 칸을 다시 채움), 1원은 계좌 확인이라 누를 때만 채운다.
 * 6초 뒤 저절로 닫힌다.
 */
@Composable
internal fun SmsBanner(message: String?, onFill: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    LaunchedEffect(message) {
        if (message != null) {
            delay(6_000)
            onDismiss()
        }
    }
    val c = YamuTheme.colors
    val shape = RoundedCornerShape(16.dp)
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .shadow(6.dp, shape)
                .clip(shape)
                .background(c.surface)
                .clickable(onClickLabel = "숫자 채우기", onClick = onFill)
                .padding(16.dp),
        ) {
            Text("메시지 · 지금", style = YamuTheme.typography.bodyS, color = c.text3)
            Spacer(Modifier.height(4.dp))
            Text(last, style = YamuTheme.typography.bodyM, color = c.text1)
        }
    }
}

// ───────────────────────── A-01 · A-04 진입 ─────────────────────────

@HiltViewModel
class WelcomeViewModel @Inject constructor(private val signup: SignupRepository) : ViewModel() {
    /** 첫 화면에 오면 이전 가입 진행은 버린다 */
    fun reset() = signup.clear()
}

@Composable
fun WelcomeRoute(onStart: () -> Unit, onLogin: () -> Unit, onDebugMenu: (() -> Unit)?, viewModel: WelcomeViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.reset() }
    WelcomeScreen(onStart = onStart, onLogin = onLogin, onDebugMenu = onDebugMenu)
}

@HiltViewModel
class TermsViewModel @Inject constructor(private val signup: SignupRepository) : ViewModel() {
    /**
     * 동의. 본인인증을 이미 마친 가입이면(첫 화면 '로그인'으로 들어온 사람) true — 계좌 고르기로.
     * 계좌 연결까지 마치고 PIN 만들기에서 돌아온 사람은 본인인증 화면('다음' → PIN 만들기)으로 보낸다(1원을 다시 받지 않게)
     */
    fun agree(): Boolean {
        signup.termsAgreed = true
        return signup.mode == SignupMode.SIGNUP && !signup.accountLinked
    }
}

@Composable
fun TermsRoute(
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onAccounts: () -> Unit,
    onOpenTerm: (String) -> Unit,
    viewModel: TermsViewModel = hiltViewModel(),
) {
    TermsScreen(onBack = onBack, onAgree = { if (viewModel.agree()) onAccounts() else onVerify() }, onOpenTerm = onOpenTerm)
}

// ───────────────────────── A-05 휴대폰 본인인증 ─────────────────────────

/** 본인인증 다음 — 가입인데 약관 전이면 약관, 가입이면 계좌 고르기(계좌 연결을 마쳤으면 PIN 만들기), 이미 가입했으면 이 폰 등록 */
enum class SignupNext { TERMS, ACCOUNTS, PIN, RECOVERY }

data class PhoneVerifyState(
    val name: String = "",
    val birth: String = "",
    val carrier: Carrier? = null,
    val phone: String = "",
    val showErrors: Boolean = false,
    val sending: Boolean = false,
    val sent: CodeSent? = null,
    val sentAtMillis: Long = 0,
    val code: String = "",
    val codeError: Boolean = false,
    val confirming: Boolean = false,
    val error: String? = null,
    val banner: String? = null,
    val next: SignupNext? = null,
    /** 약관을 보고 왔나(가입 단계 표시) — 첫 화면 '로그인'으로 오면 false */
    val fromTerms: Boolean = false,
    /**
     * 인증을 마쳤나(가입 · 재등록). 서버는 맞힌 인증번호를 바로 지워서 같은 번호로 다시 확인하면 VERIFICATION_EXPIRED다.
     * 뒤로 돌아와도 서버를 부르지 않고 받아 둔 가입 진행 토큰으로 다음 단계로 잇는다(다시 확인하면 새 토큰이라 1원부터 다시)
     */
    val verified: SignupMode? = null,
    /** 본인인증부터 다시 하게 된 까닭 — 새 인증번호를 받을 때까지 화면 위에 띄운다 */
    val notice: String? = null,
)

/** 본인인증부터 다시 할 때 화면 위 안내. [keptInput] = 넣었던 칸을 채워 두었나 */
internal fun restartNotice(reason: SignupRestart, keptInput: Boolean): String {
    val why = when (reason) {
        SignupRestart.EXPIRED -> "본인인증한 지 30분이 지나 다시 확인해야 해요"
        SignupRestart.LOST -> "앱이 다시 켜져 본인인증부터 다시 해야 해요"
    }
    val todo = if (keptInput) "넣으신 정보는 그대로 두었어요 · 인증번호만 다시 받아 주세요" else "인증번호를 다시 받아 주세요"
    return "$why\n$todo"
}

@HiltViewModel
class PhoneVerifyViewModel @Inject constructor(private val signup: SignupRepository) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PhoneVerifyState> = _state.asStateFlow()

    /**
     * 화면을 새로 열면(약관을 거쳐 다시 들어옴 · 본인인증부터 다시) 마지막으로 고친 칸을 채우고, 다시 왔으면 까닭을 띄운다.
     * 인증을 마친 채 다시 열리면 마친 상태로 — 칸을 고치면 저장소가 인증을 버리니 토큰이 있으면 지금 칸으로 받은 인증이다
     */
    private fun initialState(): PhoneVerifyState {
        val draft = signup.draft
        return PhoneVerifyState(
            name = draft.name,
            birth = draft.birth,
            carrier = draft.carrier,
            phone = draft.phone,
            fromTerms = signup.termsAgreed,
            verified = signup.mode,
            notice = signup.takeRestartReason()?.let { restartNotice(it, keptInput = !draft.isEmpty) },
        )
    }

    // 보낸 뒤 · 인증을 마친 뒤 칸을 고치면 그 인증은 버린다(다른 사람 · 번호로 다음 단계에 가지 않게)
    fun onName(v: String) = edit { it.copy(name = v.take(20)) }
    fun onBirth(v: String) = edit { it.copy(birth = v.filter(Char::isDigit).take(8)) }
    fun onCarrier(v: Carrier) = edit { it.copy(carrier = v) }
    fun onPhone(v: String) = edit { it.copy(phone = v.filter(Char::isDigit).take(11)) }

    private fun edit(change: (PhoneVerifyState) -> PhoneVerifyState) {
        val before = _state.value
        val changed = change(before)
        if (changed == before) return
        if (before.verified != null) signup.dropVerification()   // 약관으로 돌아가 '동의하고 계속'을 눌러도 옛 토큰으로 가지 않게
        // 고친 값을 바로 적어 둔다 — 인증번호를 받기 전에 약관을 거쳐 다시 들어와도 옛 값으로 되돌아가지 않게
        signup.draft = IdentityDraft(changed.name, changed.birth, changed.carrier, changed.phone)
        _state.value = changed.copy(sent = null, verified = null, code = "", codeError = false, error = null, banner = null)
    }

    private fun nextOf(mode: SignupMode): SignupNext = when (mode) {
        SignupMode.DEVICE_RECOVERY -> SignupNext.RECOVERY
        SignupMode.SIGNUP -> when {
            signup.accountLinked -> SignupNext.PIN   // PIN 만들기에서 뒤로 왔다 — 1원은 이미 마쳤다
            signup.termsAgreed -> SignupNext.ACCOUNTS
            else -> SignupNext.TERMS
        }
    }

    /** 인증을 마친 뒤 '다음' — 서버를 부르지 않는다 */
    fun proceed() {
        val mode = _state.value.verified ?: return
        if (signup.mode != mode) {   // 그사이 저장소가 진행을 버렸다
            return _state.update {
                it.copy(verified = null, sent = null, code = "", error = "인증 시간이 지났어요 · 인증번호를 다시 받아 주세요")
            }
        }
        _state.update { it.copy(next = nextOf(mode)) }
    }

    /** 인증번호 받기 · 다시 받기 */
    fun send() {
        val s = _state.value
        if (s.sending || s.confirming || s.verified != null) return   // 연타 · 이미 마침
        val birth = SignupValidation.birthDate(s.birth)
        val carrier = s.carrier
        if (SignupValidation.name(s.name) != null || birth == null || carrier == null || SignupValidation.phone(s.phone) != null) {
            return _state.update { it.copy(showErrors = true) }
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            when (val r = signup.requestCode(s.name, birth, carrier, s.phone)) {
                is ApiResult.Success -> _state.update {
                    val demo = r.data.demoCode
                    it.copy(
                        sending = false, sent = r.data, sentAtMillis = System.currentTimeMillis(), codeError = false,
                        // 시연 서버는 문자 대신 응답으로 번호를 준다 — 문자 알림을 띄우면서 칸도 바로 채운다(확인은 버튼으로, QA 11/10)
                        code = demo?.filter(Char::isDigit)?.take(6).orEmpty(),
                        banner = demo?.let { code -> "[YAMU] 인증번호 [$code]을 입력해 주세요" }, notice = null,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(sending = false, error = r.error.signupMessage()) }
            }
        }
    }

    fun onCode(v: String) = _state.update { it.copy(code = v, codeError = false, error = null) }

    fun fillFromBanner() {
        val code = _state.value.sent?.demoCode ?: return
        _state.update { it.copy(code = code, banner = null, codeError = false, error = null) }
    }

    fun bannerGone() = _state.update { it.copy(banner = null) }

    fun confirm() {
        val s = _state.value
        if (s.verified != null) return proceed()   // 이미 마쳤으면 다시 확인하지 않는다
        val sent = s.sent ?: return
        if (s.confirming || s.code.length != 6) return
        viewModelScope.launch {
            _state.update { it.copy(confirming = true, error = null) }
            when (val r = signup.confirmCode(sent.verificationId, s.code)) {
                is ApiResult.Success -> {
                    if (_state.value.sent?.verificationId != sent.verificationId) {
                        signup.dropVerification()   // 확인하는 사이 칸을 고쳤다 — 고친 값으로 다시 받게
                        _state.update { it.copy(confirming = false) }
                    } else {
                        _state.update { it.copy(confirming = false, verified = r.data, next = nextOf(r.data)) }
                    }
                }
                is ApiResult.Failure -> _state.update {
                    // 시간이 지났거나 시연 사용자가 아니면 칸은 그대로 두고 인증번호만 다시 받게 한다(오타만 고치면 된다, B-03)
                    val notFound = r.error.code == "DEMO_USER_NOT_FOUND"
                    val again = r.error.code == "VERIFICATION_EXPIRED" || notFound
                    it.copy(
                        confirming = false, code = "", codeError = !again,
                        error = if (notFound) demoUserNotFoundMessage(signup = it.fromTerms) else r.error.signupMessage(),
                        sent = if (again) null else it.sent,
                    )
                }
            }
        }
    }

    fun navigated() = _state.update { it.copy(next = null) }
}

@Composable
fun PhoneVerifyRoute(onBack: () -> Unit, onNext: (SignupNext) -> Unit, viewModel: PhoneVerifyViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.next) {
        s.next?.let {
            onNext(it)
            viewModel.navigated()
        }
    }
    val now = rememberNow(s.sentAtMillis)
    val elapsed = secondsSince(s.sentAtMillis, now)
    val codeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val c = YamuTheme.colors
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),   // 키보드가 올라와도 아래 버튼이 키보드 위에
            topBar = {
                Column(Modifier.background(c.surface)) {
                    YamuTopBar(if (s.fromTerms) "회원가입" else "본인 확인", onBack = onBack)
                    if (s.fromTerms) StartStepper(step = 2, total = SIGNUP_STEPS, label = "본인인증")
                }
            },
            bottomBar = {
                BottomCta {
                    when {
                        s.verified != null -> PrimaryButton("다음", onClick = viewModel::proceed)
                        s.sent == null -> PrimaryButton("인증번호 받기", onClick = viewModel::send, loading = s.sending)
                        else -> PrimaryButton("확인", onClick = viewModel::confirm, enabled = s.code.length == 6, loading = s.confirming)
                    }
                }
            },
            containerColor = c.surface,
        ) { inner ->
            Column(
                Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = YamuSpacing.screen),
            ) {
                s.notice?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        it,
                        style = YamuTheme.typography.bodyM,
                        color = c.onWarningContainer,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warningContainer).padding(16.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("본인 확인을 할게요", style = YamuTheme.typography.headlineS, color = c.text1)
                Spacer(Modifier.height(8.dp))
                Text(phoneVerifyGuide(signup = s.fromTerms), style = YamuTheme.typography.bodyM, color = c.text2)
                Spacer(Modifier.height(24.dp))
                YamuTextField(
                    label = "이름", value = s.name, onValueChange = viewModel::onName, placeholder = "김야무",
                    error = if (s.showErrors) SignupValidation.name(s.name) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(20.dp))
                YamuTextField(
                    label = "생년월일", value = s.birth, onValueChange = viewModel::onBirth, placeholder = "19980101",
                    supporting = "숫자 8자리",
                    error = if (s.showErrors) SignupValidation.birthDateError(s.birth) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(20.dp))
                Text("통신사", style = YamuTheme.typography.bodyS, color = c.text2)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = Carrier.entries.map { it.label },
                    selectedIndex = Carrier.entries.indexOf(s.carrier),
                    onSelect = { viewModel.onCarrier(Carrier.entries[it]) },
                )
                if (s.showErrors && s.carrier == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("통신사를 골라 주세요", style = YamuTheme.typography.bodyS, color = c.error)
                }
                Spacer(Modifier.height(20.dp))
                YamuTextField(
                    label = "휴대폰 번호", value = s.phone, onValueChange = viewModel::onPhone, placeholder = "01012345678",
                    error = if (s.showErrors) SignupValidation.phone(s.phone) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                )
                if (s.verified != null) {
                    Spacer(Modifier.height(28.dp))
                    Text("본인인증을 마쳤어요", style = YamuTheme.typography.titleM, color = c.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("칸을 고치면 인증번호를 다시 받아야 해요", style = YamuTheme.typography.sub, color = c.text3)
                } else if (s.sent != null) {
                    Spacer(Modifier.height(28.dp))
                    Text("인증번호", style = YamuTheme.typography.bodyS, color = c.text2)
                    Spacer(Modifier.height(8.dp))
                    CodeBoxes(
                        value = s.code, length = 6, onValueChange = viewModel::onCode, label = "인증번호 6자리", error = s.codeError,
                        modifier = Modifier.focusRequester(codeFocus),
                    )
                    // 받으면 커서를 인증번호 칸으로 — 휴대폰 번호 칸에 남으면 이 칸이 키보드 · 버튼 뒤에 숨는다(QA 04 · P2-39).
                    // 칸에 포커스가 가면 스크롤이 이 칸을 키보드 위로 올린다. 다시 받을 때도 옮긴다
                    LaunchedEffect(s.sent?.verificationId) {
                        runCatching { codeFocus.requestFocus() }
                        keyboard?.show()
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val left = CODE_VALID_SECONDS - elapsed
                        Text(
                            if (left > 0) "남은 시간 ${mmss(left)}" else "시간이 지났어요 · 다시 받아 주세요",
                            style = YamuTheme.typography.sub,
                            color = if (left > 0) c.text3 else c.error,
                            modifier = Modifier.weight(1f),
                        )
                        val wait = RESEND_AFTER_SECONDS - elapsed
                        if (wait > 0) Text("${wait}초 뒤 다시 받기", style = YamuTheme.typography.sub, color = c.text3)
                        else QuietLink("다시 받기", onClick = viewModel::send)
                    }
                }
                s.error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = YamuTheme.typography.bodyM, color = c.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        SmsBanner(s.banner, onFill = viewModel::fillFromBanner, onDismiss = viewModel::bannerGone, modifier = Modifier.statusBarsPadding())
    }
}

// ───────────────────────── A-08 이 폰 등록(이미 가입) ─────────────────────────

@HiltViewModel
class RecoveryIntroViewModel @Inject constructor(private val signup: SignupRepository, auth: AuthRepository) : ViewModel() {
    val name: String? = signup.name
    val canUseBiometric: Boolean = auth.canUseBiometric()

    /** 새 PIN을 이미 맡겼나 — 서버에 취소 방법이 없어 '쓰던 비밀번호'를 고를 수 없다. 뒤로 돌아올 때 다시 읽는다 */
    val pinStaged: Boolean get() = signup.pinStaged
    private val _biometric = MutableStateFlow(canUseBiometric)
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    fun setBiometric(on: Boolean) {
        _biometric.value = on
    }

    /** 다음 단계로 — 지문을 같이 등록할지 맡겨 둔다(1원 숫자를 넣을 때 기기 등록) */
    fun commit() {
        signup.recoveryBiometric = canUseBiometric && _biometric.value
    }
}

@Composable
fun RecoveryIntroRoute(onBack: () -> Unit, onKeepPin: () -> Unit, onNewPin: () -> Unit, viewModel: RecoveryIntroViewModel = hiltViewModel()) {
    val biometric by viewModel.biometric.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    Scaffold(
        topBar = { YamuTopBar("이 폰 등록", onBack = onBack) },
        bottomBar = {
            BottomCta(
                secondary = {
                    NeutralButton(if (viewModel.pinStaged) "간편 비밀번호 다시 만들기" else "간편 비밀번호 새로 만들기", onClick = {
                        viewModel.commit()
                        onNewPin()
                    })
                },
            ) {
                PrimaryButton(if (viewModel.pinStaged) "새로 만든 간편 비밀번호로 계속" else "쓰던 간편 비밀번호로 계속", onClick = {
                    viewModel.commit()
                    onKeepPin()
                })
            }
        },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            CharacterImage(YamuPose.Safe, size = CharacterSize.L)
            Spacer(Modifier.height(32.dp))
            Text("${viewModel.name ?: "야무"}님은 이미 가입하셨어요", style = YamuTheme.typography.headlineS, color = c.text1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "이 폰에서 쓸 수 있게 다시 등록할게요.\n가입할 때 연결한 계좌로 1원을 보내 본인인지 확인해요",
                style = YamuTheme.typography.bodyM, color = c.text2, textAlign = TextAlign.Center,
            )
            if (viewModel.canUseBiometric) {
                Spacer(Modifier.height(24.dp))
                SwitchRow("지문으로도 열기", checked = biometric, onToggle = viewModel::setBiometric, sub = "앱을 열 때와 결제 코드를 열 때")
            }
        }
    }
}

// ───────────────────────── A-05b 계좌 고르기 ─────────────────────────

data class SignupAccountsState(
    val loading: Boolean = true,
    val recovery: Boolean = false,
    val candidates: List<AccountCandidate> = emptyList(),
    val selected: List<String> = emptyList(),   // 고른 순서 — 대표 기본값은 처음 고른 계좌
    val primary: String? = null,
    val sending: Boolean = false,
    val loadFailed: Boolean = false,
    val error: String? = null,
    val sent: Boolean = false,
    val restart: Boolean = false,
)

/** 가입: 내 명의 계좌를 여러 개 고르고 1원 받을 대표 하나. 재등록: 연결된 계좌 하나 */
@HiltViewModel
class SignupAccountsViewModel @Inject constructor(private val signup: SignupRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignupAccountsState(recovery = signup.mode == SignupMode.DEVICE_RECOVERY))
    val state: StateFlow<SignupAccountsState> = _state.asStateFlow()

    init { load() }

    fun load(message: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadFailed = false, error = message) }
            when (val r = signup.candidates()) {
                is ApiResult.Success -> _state.update {
                    val only = r.data.singleOrNull()?.id   // 계좌가 하나면(가입 · 재등록 모두) 미리 골라 둔다
                    it.copy(
                        loading = false, candidates = r.data,
                        selected = listOfNotNull(only), primary = only,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    if (r.error.code == SIGNUP_TOKEN_INVALID) it.copy(loading = false, restart = true)
                    else it.copy(loading = false, loadFailed = true, error = r.error.signupMessage())
                }
            }
        }
    }

    fun toggle(candidate: AccountCandidate) {
        val s = _state.value
        if (s.sending) return
        if (s.recovery) return _state.update { it.copy(selected = listOf(candidate.id), primary = candidate.id, error = null) }
        // 이미 연결된 계좌도 고른다 — 연결해 두고 멈춘 가입은 그 계좌로 1원 인증을 다시 해야 이어진다(BE AccountLinkService)
        val selected = if (candidate.id in s.selected) s.selected - candidate.id else s.selected + candidate.id
        val primary = s.primary?.takeIf { it in selected } ?: selected.firstOrNull()
        _state.update { it.copy(selected = selected, primary = primary, error = null) }
    }

    fun choosePrimary(id: String) {
        if (id in _state.value.selected) _state.update { it.copy(primary = id) }
    }

    fun send() {
        val s = _state.value
        val primary = s.primary ?: return
        if (s.sending) return   // 연타
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            when (val r = signup.sendOneWon(s.selected, primary)) {
                is ApiResult.Success -> _state.update { it.copy(sending = false, sent = true) }
                is ApiResult.Failure -> when (r.error.code) {
                    SIGNUP_TOKEN_INVALID -> _state.update { it.copy(sending = false, restart = true) }
                    "ACCOUNT_CANDIDATE_EXPIRED" -> {
                        _state.update { it.copy(sending = false, selected = emptyList(), primary = null) }
                        load(r.error.signupMessage())
                    }
                    else -> _state.update { it.copy(sending = false, error = r.error.signupMessage()) }
                }
            }
        }
    }

    fun navigated() = _state.update { it.copy(sent = false, restart = false) }
}

@Composable
fun SignupAccountsRoute(
    onBack: () -> Unit,
    onSent: () -> Unit,
    onRestart: () -> Unit,
    viewModel: SignupAccountsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.sent, s.restart) {
        if (s.sent) onSent()
        if (s.restart) onRestart()
        if (s.sent || s.restart) viewModel.navigated()
    }
    val c = YamuTheme.colors
    Scaffold(
        topBar = {
            Column(Modifier.background(c.surface)) {
                YamuTopBar(if (s.recovery) "이 폰 등록" else "회원가입", onBack = onBack)
                if (!s.recovery) StartStepper(step = 3, total = SIGNUP_STEPS, label = "계좌 연결")
            }
        },
        bottomBar = { BottomCta { PrimaryButton("1원 보내기", onClick = viewModel::send, enabled = s.primary != null, loading = s.sending) } },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                if (s.recovery) "1원을 받을 계좌를 골라 주세요" else "연결할 계좌를 골라 주세요",
                style = YamuTheme.typography.headlineS, color = c.text1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.recovery) "가입할 때 연결한 계좌로 1원을 보내 본인인지 확인해요"
                else "고른 계좌 중 대표 계좌로 1원을 보내 내 계좌인지 확인해요",
                style = YamuTheme.typography.bodyM, color = c.text2,
            )
            Spacer(Modifier.height(16.dp))
            when {
                s.loading -> SearchingState("내 명의 계좌를 찾고 있어요", Modifier.padding(top = 24.dp))
                s.loadFailed -> {
                    Text(s.error ?: "계좌를 불러오지 못했어요", style = YamuTheme.typography.bodyM, color = c.error)
                    Spacer(Modifier.height(8.dp))
                    QuietLink("다시 불러오기", onClick = { viewModel.load() })
                }
                s.candidates.isEmpty() -> Text("연결할 수 있는 계좌가 없어요", style = YamuTheme.typography.bodyM, color = c.text2)
                else -> {
                    s.candidates.forEach { cnd ->
                        SelectRow(
                            brand = cnd.brand,
                            title = cnd.name,
                            checked = cnd.id in s.selected,
                            onToggle = { viewModel.toggle(cnd) },
                            suffix = "·${cnd.last4}",
                            sub = when {
                                cnd.alreadyLinked && !s.recovery -> "이미 연결된 계좌"
                                cnd.id == s.primary && s.selected.size > 1 -> "대표 계좌 · 1원을 받아요"
                                else -> null
                            },
                        )
                    }
                    if (!s.recovery && s.selected.size > 1) {
                        Spacer(Modifier.height(24.dp))
                        Text("1원 받을 대표 계좌", style = YamuTheme.typography.titleM, color = c.text1)
                        Spacer(Modifier.height(4.dp))
                        Text("1원은 대표 계좌 한 곳으로만 보내요", style = YamuTheme.typography.sub, color = c.text3)
                        s.selected.mapNotNull { id -> s.candidates.firstOrNull { it.id == id } }.forEach { cnd ->
                            PrimaryRow(cnd, selected = cnd.id == s.primary, onSelect = { viewModel.choosePrimary(cnd.id) })
                        }
                    }
                    s.error?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, style = YamuTheme.typography.bodyM, color = c.error)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 대표 계좌 라디오 줄 — 은행 원 · 이름 ·끝자리 · 라디오 */
@Composable
private fun PrimaryRow(candidate: AccountCandidate, selected: Boolean, onSelect: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BankMark(candidate.brand) }
        Text("${candidate.name} ·${candidate.last4}", style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.outline))
    }
}

// ───────────────────────── A-05c 1원 인증 ─────────────────────────

data class SignupOneWonState(
    val info: SignupOneWon? = null,
    val recovery: Boolean = false,
    val sentAtMillis: Long = System.currentTimeMillis(),
    val code: String = "",
    val codeError: Boolean = false,
    val needResend: Boolean = false,
    val confirming: Boolean = false,
    val resending: Boolean = false,
    val error: String? = null,
    val banner: String? = null,
    val done: Boolean = false,
    val restart: Boolean = false,
)

private fun SignupOneWon.bannerText(): String? = demoCode?.let { "[$bankName] 입금 1원 $prefix$it" }

/** '신한은행 ·4450 계좌로 / 1원을 보냈어요' — 끝자리 숫자에 조사를 붙이지 않게 '계좌로'로 받는다 */
internal fun SignupOneWon.headline(): String {
    val account = listOf(bankName.trim(), last4.takeIf { it.isNotEmpty() }?.let { "·$it" }.orEmpty())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return (if (account.isEmpty()) "계좌로" else "$account 계좌로") + "\n1원을 보냈어요"
}

/** 가입: 숫자로 계좌 연결(같은 멱등 키로 재시도) → 간편 비밀번호. 재등록: 숫자로 이 폰을 등록 → 로그인 */
@HiltViewModel
class SignupOneWonViewModel @Inject constructor(private val signup: SignupRepository) : ViewModel() {
    private val _state = MutableStateFlow(
        signup.oneWon.let { info ->
            if (info == null) signup.restart(SignupRestart.LOST)   // 앱이 다시 켜져 진행 상태가 없으면 본인인증부터
            SignupOneWonState(
                info = info,
                recovery = signup.mode == SignupMode.DEVICE_RECOVERY,
                sentAtMillis = info?.sentAtMillis ?: System.currentTimeMillis(),
                banner = info?.bannerText(),
                restart = info == null,
            )
        },
    )
    val state: StateFlow<SignupOneWonState> = _state.asStateFlow()

    fun onCode(v: String) = _state.update { it.copy(code = v, codeError = false, error = null) }

    fun fillFromBanner() {
        val code = _state.value.info?.demoCode ?: return
        _state.update { it.copy(code = code, banner = null, codeError = false, error = null) }
    }

    fun bannerGone() = _state.update { it.copy(banner = null) }

    fun resend() {
        val s = _state.value
        if (s.resending || s.confirming) return
        viewModelScope.launch {
            _state.update { it.copy(resending = true, error = null) }
            when (val r = signup.resendOneWon()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        resending = false, info = r.data, sentAtMillis = r.data.sentAtMillis,
                        code = "", codeError = false, needResend = false, banner = r.data.bannerText(),
                    )
                }
                is ApiResult.Failure -> _state.update {
                    if (r.error.code == SIGNUP_TOKEN_INVALID) it.copy(resending = false, restart = true)
                    else it.copy(resending = false, error = r.error.signupMessage())
                }
            }
        }
    }

    fun confirm() {
        val s = _state.value
        val length = s.info?.codeLength ?: return
        if (s.confirming || s.needResend || s.code.length != length) return   // 연타
        viewModelScope.launch {
            _state.update { it.copy(confirming = true, error = null) }
            when (val r = signup.confirmOneWon(s.code)) {
                is ApiResult.Success -> _state.update { it.copy(confirming = false, done = true) }
                is ApiResult.Failure -> _state.update {
                    when (r.error.code) {
                        SIGNUP_TOKEN_INVALID -> it.copy(confirming = false, restart = true)
                        "AUTH_CODE_MISMATCH" -> it.copy(confirming = false, code = "", codeError = true, error = r.error.signupMessage())
                        "AUTH_CODE_EXPIRED", "VERIFICATION_TOKEN_EXPIRED" ->
                            it.copy(confirming = false, code = "", needResend = true, error = r.error.signupMessage())
                        else -> it.copy(confirming = false, error = r.error.signupMessage())   // 네트워크면 같은 키로 다시 누르면 된다
                    }
                }
            }
        }
    }

    fun navigated() = _state.update { it.copy(done = false, restart = false) }
}

@Composable
fun SignupOneWonRoute(
    onBack: () -> Unit,
    onLinked: () -> Unit,
    onRecovered: () -> Unit,
    onRestart: () -> Unit,
    viewModel: SignupOneWonViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done, s.restart) {
        if (s.done) if (s.recovery) onRecovered() else onLinked()
        if (s.restart) onRestart()
        if (s.done || s.restart) viewModel.navigated()
    }
    val now = rememberNow(s.sentAtMillis)
    val elapsed = secondsSince(s.sentAtMillis, now)
    val info = s.info
    val codeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val c = YamuTheme.colors
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                Column(Modifier.background(c.surface)) {
                    YamuTopBar(if (s.recovery) "이 폰 등록" else "회원가입", onBack = onBack)
                    if (!s.recovery) StartStepper(step = 3, total = SIGNUP_STEPS, label = "계좌 연결")
                }
            },
            bottomBar = {
                BottomCta {
                    PrimaryButton(
                        if (s.recovery) "확인하고 이 폰 등록" else "확인",
                        onClick = viewModel::confirm,
                        enabled = info != null && s.code.length == info.codeLength && !s.needResend,
                        loading = s.confirming,
                    )
                }
            },
            containerColor = c.surface,
        ) { inner ->
            Column(
                Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = YamuSpacing.screen),
            ) {
                if (info == null) return@Column
                Spacer(Modifier.height(20.dp))
                Text(info.headline(), style = YamuTheme.typography.headlineS, color = c.text1)
                Spacer(Modifier.height(8.dp))
                Text("입금자명 ${info.prefix} 뒤 숫자 ${info.codeLength}자리를 넣어 주세요", style = YamuTheme.typography.bodyM, color = c.text2)
                info.hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = YamuTheme.typography.sub, color = c.text3)
                }
                if (info.deliveryUncertain) {
                    Spacer(Modifier.height(8.dp))
                    Text("은행 응답이 늦어요 · 1원이 안 보이면 1분 뒤 다시 받아 주세요", style = YamuTheme.typography.sub, color = c.warning)
                }
                Spacer(Modifier.height(28.dp))
                CodeBoxes(
                    value = s.code, length = info.codeLength, onValueChange = viewModel::onCode,
                    label = "입금자명 숫자 ${info.codeLength}자리", error = s.codeError,
                    modifier = Modifier.focusRequester(codeFocus),
                )
                // 1원 숫자는 계좌 확인이라 칸을 채우지 않는다 — 열리자마자 칸에 커서를 두고 숫자 키패드를 올려 바로 치게(QA 11/10)
                LaunchedEffect(codeFocus) {
                    runCatching { codeFocus.requestFocus() }
                    keyboard?.show()
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 남은 시간은 안내(최종 만료는 SSAFY) — 지나도 확인은 막지 않고 다시 받기로 이끈다. 서버가 끝났다고 하면 아래 오류 문장이 대신한다
                    val left = info.secondsLeft(now)
                    Text(
                        when {
                            s.needResend -> ""
                            left > 0 -> "남은 시간 ${mmss(left)}"
                            else -> "시간이 지났어요 · 1원을 다시 받아 주세요"
                        },
                        style = YamuTheme.typography.sub,
                        color = if (left > 0) c.text3 else c.error,
                        modifier = Modifier.weight(1f),
                    )
                    val wait = RESEND_AFTER_SECONDS - elapsed
                    if (wait > 0) Text("${wait}초 뒤 다시 받기", style = YamuTheme.typography.sub, color = c.text3)
                    else QuietLink(if (s.resending) "보내고 있어요…" else "1원 다시 받기", onClick = viewModel::resend)
                }
                s.error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = YamuTheme.typography.bodyM, color = c.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        SmsBanner(s.banner, onFill = viewModel::fillFromBanner, onDismiss = viewModel::bannerGone, modifier = Modifier.statusBarsPadding())
    }
}
