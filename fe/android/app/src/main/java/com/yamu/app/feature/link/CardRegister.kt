package com.yamu.app.feature.link

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.FlowStep
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.SecureScreen
import com.yamu.app.feature.auth.StepUpEffect
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.signupMessage
import com.yamu.app.feature.link.data.CardLookup
import com.yamu.app.feature.link.data.CardRegisterFailure
import com.yamu.app.feature.link.data.CardValidation
import com.yamu.app.feature.link.data.IssuerAccount
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.RegisteredCard
import com.yamu.app.feature.link.data.cardErrorMessage
import com.yamu.app.feature.link.data.cardRegisterFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// 카드 등록 — 번호 입력 → 카드 확인 → (결제 계좌 연결) → 본인 인증 → 등록 → 완료.
// 카드번호 · 확인 토큰을 화면 인자 · 저장 상태로 넘기지 않으려고 경로 하나 안에서 단계만 바꾼다(결제 코드 PayCode와 같은 방식).

enum class CardRegisterStep { Input, Confirm, Accounts, Done }

data class CardRegisterState(
    val step: CardRegisterStep = CardRegisterStep.Input,
    val number: String = "",                       // 숫자만 · 확인이 끝나면 지운다
    val expiry: String = "",                       // 숫자만 MMYY · 확인이 끝나면 지운다
    val showErrors: Boolean = false,
    val looking: Boolean = false,
    val inputError: String? = null,                // 입력 화면 오류 한 줄
    val lookup: CardLookup? = null,                // 확인 토큰이 여기 든다
    val nickname: String = "",
    val biometricReady: Boolean = false,
    val registering: Boolean = false,
    val registered: RegisteredCard? = null,        // 마지막으로 등록한 카드
    val accountsLoading: Boolean = false,
    val accountsError: String? = null,
    val candidates: List<AccountCandidate> = emptyList(),
    val selected: Set<String> = emptySet(),
    val linking: Boolean = false,
    val message: String? = null,                   // 스낵바 한 번
)

/** 서버 오류 → 문장. 카드 코드를 먼저 보고 나머지는 가입 · 인증 · 공통 문장 */
private fun ApiError.cardMessage(): String = cardErrorMessage(code, detailString("reason")) ?: signupMessage()

@HiltViewModel
class CardRegisterViewModel @Inject constructor(
    private val link: LinkRepository,
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    val stepUp = stepUpFactory.create(viewModelScope)
    private val _state = MutableStateFlow(CardRegisterState())
    val state: StateFlow<CardRegisterState> = _state.asStateFlow()

    /** 계좌 연결 한 번의 멱등 키 — 같은 계좌들로 다시 시도하면 같은 값, 고른 계좌가 바뀌면 새 값(BE: 같은 키로 다른 요청은 409) */
    private var linkKey = newIdempotencyKey()

    init {
        viewModelScope.launch {
            val ready = auth.biometricReady()
            _state.update { it.copy(biometricReady = ready) }
        }
    }

    fun onNumber(value: String) = _state.update {
        it.copy(number = value.filter(Char::isDigit).take(CardValidation.NUMBER_LENGTH), inputError = null)
    }

    fun onExpiry(value: String) = _state.update {
        it.copy(expiry = value.filter(Char::isDigit).take(CardValidation.EXPIRY_LENGTH), inputError = null)
    }

    fun onNickname(value: String) = _state.update { it.copy(nickname = value.take(CardValidation.NICKNAME_MAX)) }

    /** [카드 확인하기] — 형식이 맞을 때만 서버로(형식 오류도 1분 5번에 세므로) */
    fun lookup() {
        val s = _state.value
        if (s.looking) return
        if (CardValidation.number(s.number) != null || CardValidation.expiry(s.expiry) != null) {
            return _state.update { it.copy(showErrors = true) }
        }
        viewModelScope.launch {
            _state.update { it.copy(looking = true, showErrors = false, inputError = null) }
            when (val r = link.lookupCard(s.number, s.expiry.take(2), s.expiry.takeLast(2))) {
                is ApiResult.Success -> _state.update {
                    // 확인이 끝나면 번호는 더 쓰지 않는다 — 이 뒤로는 확인 토큰만 있으면 된다
                    it.copy(looking = false, number = "", expiry = "", lookup = r.data, nickname = "", step = CardRegisterStep.Confirm)
                }
                is ApiResult.Failure -> _state.update { it.copy(looking = false, inputError = r.error.cardMessage()) }
            }
        }
    }

    /** [등록하기] — 본인 인증(CARD_REGISTER) → 등록. 연타는 registering · stepUp.busy로 막는다 */
    fun register() {
        val s = _state.value
        val lookup = s.lookup ?: return
        if (!lookup.registrable || s.registering || stepUp.busy) return
        val nickname = CardValidation.nickname(s.nickname)
        _state.update { it.copy(registering = true) }
        stepUp.start(
            purpose = StepUpPurpose.CARD_REGISTER,
            prompt = StepUpPrompt("지문으로 카드를 등록해요", "${lookup.productName} ·${lookup.last4}"),
            onError = { e -> _state.update { it.copy(registering = false, message = e.cardMessage()) } },
        ) { token ->
            when (val r = link.registerCard(lookup.verificationToken, nickname, token.token)) {
                is ApiResult.Success -> {
                    auth.completeOnboarding()      // 카드가 등록되면 서버도 초기 설정 끝으로 본다 — 다시 켜도 홈으로
                    _state.update {
                        it.copy(registering = false, lookup = null, nickname = "", registered = r.data, step = CardRegisterStep.Done)
                    }
                }
                is ApiResult.Failure -> onRegisterFailed(r.error, lookup)
            }
        }
    }

    private fun onRegisterFailed(error: ApiError, lookup: CardLookup) {
        val message = error.cardMessage()
        when (cardRegisterFailure(error.code)) {
            CardRegisterFailure.RESTART -> restart(message)
            CardRegisterFailure.ACCOUNT_NOT_LINKED -> {
                // 어느 계좌인지는 서버 details가 더 최신. 확인 토큰은 그대로라 계좌만 연결하면 이어서 등록한다
                val account = IssuerAccount(
                    bankCode = error.detailString("bankCode") ?: lookup.issuerAccount?.bankCode,
                    last4 = error.detailString("accountLast4") ?: lookup.issuerAccount?.last4.orEmpty(),
                    linked = false,
                )
                _state.update { it.copy(registering = false, lookup = lookup.copy(issuerAccount = account), message = message) }
            }
            CardRegisterFailure.ALREADY_REGISTERED ->
                _state.update { it.copy(registering = false, lookup = lookup.copy(alreadyRegistered = true), message = message) }
            CardRegisterFailure.RETRY -> _state.update { it.copy(registering = false, message = message) }
        }
    }

    /** 확인 토큰을 버리고 입력 화면으로 — 번호는 이미 지웠으니 다시 넣는다 */
    private fun restart(message: String?) = _state.update {
        it.copy(
            step = CardRegisterStep.Input, lookup = null, nickname = "", registering = false, linking = false,
            showErrors = false, inputError = message,
        )
    }

    /** [다른 카드 입력하기] · 카드 다시 확인 */
    fun reenterCard() = restart(null)

    /** [다른 카드 등록] */
    fun anotherCard() = _state.update { it.copy(step = CardRegisterStep.Input, showErrors = false, inputError = null) }

    // ── 결제 계좌 연결 ──

    fun openAccounts() {
        _state.update { it.copy(step = CardRegisterStep.Accounts) }
        loadCandidates()
    }

    /** 목록을 새로 받으면 candidateId가 바뀌므로 멱등 키도 새로. 이 카드의 결제 계좌는 미리 골라 둔다 */
    fun loadCandidates(message: String? = null) {
        linkKey = newIdempotencyKey()
        viewModelScope.launch {
            _state.update { it.copy(accountsLoading = true, accountsError = null, candidates = emptyList(), selected = emptySet(), message = message) }
            when (val r = link.accountCandidates()) {
                is ApiResult.Success -> _state.update { s ->
                    val candidates = r.data.filterNot { it.alreadyLinked }
                    val account = s.lookup?.issuerAccount
                    val required = candidates.filter { account != null && it.isAccount(account) }.map { it.id }.toSet()
                    s.copy(accountsLoading = false, candidates = candidates, selected = required)
                }
                is ApiResult.Failure -> _state.update { it.copy(accountsLoading = false, accountsError = r.error.cardMessage()) }
            }
        }
    }

    fun toggleAccount(id: String) {
        if (_state.value.linking) return
        linkKey = newIdempotencyKey()
        _state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    /** [계좌 연결하기] — 본인 인증(ACCOUNT_LINK) → 연결(1원 없음). 응답을 못 받아 다시 눌러도 같은 키라 서버가 처음 결과를 돌려준다 */
    fun linkAccounts() {
        val s = _state.value
        val ids = s.candidates.map { it.id }.filter { it in s.selected }
        if (ids.isEmpty() || s.linking || stepUp.busy) return
        val key = linkKey
        _state.update { it.copy(linking = true) }
        stepUp.start(
            purpose = StepUpPurpose.ACCOUNT_LINK,
            prompt = StepUpPrompt("지문으로 계좌를 연결해요", "계좌 ${ids.size}개"),
            onError = { e -> _state.update { it.copy(linking = false, message = e.cardMessage()) } },
        ) { token ->
            when (val r = link.linkAccounts(ids, key, token.token)) {
                is ApiResult.Success -> {
                    linkKey = newIdempotencyKey()
                    _state.update { cur ->
                        val account = cur.lookup?.issuerAccount
                        val covered = account != null && r.data.covers(account)
                        cur.copy(
                            linking = false,
                            step = CardRegisterStep.Confirm,
                            lookup = if (covered) cur.lookup.copy(issuerAccount = account.copy(linked = true)) else cur.lookup,
                            message = if (covered || account == null) "계좌를 연결했어요" else "계좌를 연결했어요 · 이 카드의 결제 계좌는 아직이에요",
                        )
                    }
                }
                is ApiResult.Failure -> when (r.error.code) {
                    "ACCOUNT_CANDIDATE_EXPIRED", "ACCOUNT_ALREADY_LINKED" -> {
                        _state.update { it.copy(linking = false) }
                        loadCandidates(r.error.cardMessage())
                    }
                    else -> _state.update { it.copy(linking = false, message = r.error.cardMessage()) }
                }
            }
        }
    }

    /** 뒤로 — 단계 안에서 처리했으면 true, 경로를 떠나야 하면 false. 서버 요청 중에는 그대로 둔다(결과를 잃지 않게) */
    fun back(): Boolean {
        val s = _state.value
        return when (s.step) {
            CardRegisterStep.Input -> {
                if (s.registered == null) return false
                _state.update { it.copy(step = CardRegisterStep.Done, number = "", expiry = "", inputError = null) }
                true
            }
            CardRegisterStep.Confirm -> {
                if (!s.registering) restart(null)
                true
            }
            CardRegisterStep.Accounts -> {
                if (!s.linking) _state.update { it.copy(step = CardRegisterStep.Confirm) }
                true
            }
            CardRegisterStep.Done -> false
        }
    }

    /** 본인 인증이 취소되면 버튼을 되살린다 */
    fun onStepUpIdle() {
        if (!stepUp.busy && (_state.value.registering || _state.value.linking)) {
            _state.update { it.copy(registering = false, linking = false) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

private fun AccountCandidate.isAccount(account: IssuerAccount): Boolean =
    last4 == account.last4 && (bankCode == null || bankCode == account.bankCode)

/** 4자리씩 띄워 보이기 — 값은 숫자만 */
internal object CardNumberFormat : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        AnnotatedString(text.text.chunked(4).joinToString(" ")),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset == 0) 0 else offset + (offset - 1) / 4
            override fun transformedToOriginal(offset: Int): Int = offset - offset / 5
        },
    )
}

/** MM/YY로 보이기 — 값은 숫자만 */
internal object ExpiryFormat : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        return TransformedText(
            AnnotatedString(if (raw.length <= 2) raw else raw.take(2) + "/" + raw.drop(2)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = if (offset <= 2) offset else offset + 1
                override fun transformedToOriginal(offset: Int): Int = if (offset <= 2) offset else offset - 1
            },
        )
    }
}

/**
 * 가입 뒤 연결(온보딩)과 카드 추가가 같이 쓴다.
 * [onLeave] 입력 단계에서 뒤로 · [onFinished] 완료 단계 주 버튼 · [onCloseDone] 완료 단계에서 뒤로 · [onSkip] 온보딩 '나중에 할게요'.
 */
@Composable
fun CardRegisterRoute(
    onboarding: Boolean,
    onLeave: () -> Unit,
    onFinished: () -> Unit,
    onCloseDone: () -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    onSkip: (() -> Unit)? = null,
    viewModel: CardRegisterViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stepUpUi by viewModel.stepUp.ui.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    StepUpEffect(viewModel.stepUp, onOpenPinPad)
    LaunchedEffect(stepUpUi) { viewModel.onStepUpIdle() }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    val back: () -> Unit = {
        if (!viewModel.back()) {
            if (s.step == CardRegisterStep.Done) onCloseDone() else onLeave()
        }
    }
    BackHandler(onBack = back)
    val title = if (onboarding) "카드 등록" else "카드 추가"
    val flowStep = if (onboarding) FlowStep(1, 2) else null
    when (s.step) {
        CardRegisterStep.Input -> CardInputStep(s, title, flowStep, back, onSkip.takeIf { s.registered == null }, viewModel)
        CardRegisterStep.Confirm -> s.lookup?.let { CardConfirmStep(s, it, title, flowStep, back, viewModel) }
        CardRegisterStep.Accounts -> AccountLinkStep(s, title, flowStep, back, viewModel)
        CardRegisterStep.Done -> s.registered?.let { card ->
            CardDoneStep(card, title, flowStep, finishLabel = if (onboarding) "월급 · 고정 지출 확인하기" else "완료", back, onFinished, viewModel::anotherCard)
        }
    }
}

// ───────────────────────── 번호 입력 ─────────────────────────

@Composable
private fun CardInputStep(
    s: CardRegisterState,
    title: String,
    flowStep: FlowStep?,
    onBack: () -> Unit,
    onSkip: (() -> Unit)?,
    vm: CardRegisterViewModel,
) {
    SecureScreen()   // 번호가 보이는 동안 화면 캡처 · 최근 앱 미리보기를 막는다
    val c = YamuTheme.colors
    FlowScreen(
        title = title,
        onBack = onBack,
        modifier = Modifier.imePadding(),
        step = flowStep,
        bottomBar = {
            BottomCta(secondary = if (onSkip != null) ({ TextButtonGrey("나중에 할게요", onClick = onSkip, modifier = Modifier.fillMaxWidth(), small = true) }) else null) {
                PrimaryButton("카드 확인하기", onClick = vm::lookup, loading = s.looking)
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("카드 번호를 넣어 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(8.dp))
            Text("본인 명의 카드만 등록할 수 있어요 · CVC와 카드 비밀번호는 묻지 않아요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(24.dp))
            YamuTextField(
                label = "카드 번호",
                value = s.number,
                onValueChange = vm::onNumber,
                placeholder = "숫자 16자리",
                error = if (s.showErrors) CardValidation.number(s.number) else null,
                // NumberPassword — 키보드가 입력을 기억하거나 추천에 쓰지 않게(칸에는 그대로 보인다)
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                visualTransformation = CardNumberFormat,
            )
            Spacer(Modifier.height(20.dp))
            YamuTextField(
                label = "유효기간",
                value = s.expiry,
                onValueChange = vm::onExpiry,
                placeholder = "MM/YY",
                error = if (s.showErrors) CardValidation.expiry(s.expiry) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                visualTransformation = ExpiryFormat,
            )
            s.inputError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = YamuTheme.typography.bodyM, color = c.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────── 카드 확인 ─────────────────────────

@Composable
private fun CardConfirmStep(
    s: CardRegisterState,
    card: CardLookup,
    title: String,
    flowStep: FlowStep?,
    onBack: () -> Unit,
    vm: CardRegisterViewModel,
) {
    val c = YamuTheme.colors
    FlowScreen(
        title = title,
        onBack = onBack,
        modifier = Modifier.imePadding(),
        step = flowStep,
        bottomBar = {
            BottomCta {
                when {
                    card.blocked -> PrimaryButton("다른 카드 입력하기", onClick = vm::reenterCard)
                    card.accountMissing -> PrimaryButton("결제 계좌 연결하기", onClick = vm::openAccounts)
                    else -> PrimaryButton(
                        if (s.biometricReady) "지문으로 등록하기" else "간편 비밀번호로 등록하기",
                        onClick = vm::register,
                        loading = s.registering,
                        icon = if (s.biometricReady) YamuIcons.Fingerprint else null,
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("이 카드가 맞나요?", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(16.dp))
            BrandCardArt(card.brand, card.productName, card.last4, Modifier.widthIn(max = 320.dp))
            Spacer(Modifier.height(16.dp))
            KeyValueRow("카드사", card.brand.name, large = false)
            KeyValueRow("상품", card.productName, large = false)
            KeyValueRow("카드 번호", card.maskedNumber, large = false)
            KeyValueRow("결제일", card.paymentDay?.let { "매월 ${it}일" } ?: if (card.isCheck) "결제 즉시 출금" else "확인할 수 없어요", large = false)
            card.issuerAccount?.let { KeyValueRow(if (card.isCheck) "연결 계좌" else "결제 계좌", it.label, large = false) }
            KeyValueRow("지난 실적", if (card.hasPriorHistory) "이어 받아요" else "이전 거래가 없어요", large = false)
            Spacer(Modifier.height(16.dp))
            when {
                !card.holderMatched -> Text("본인 명의 카드만 등록할 수 있어요 · 카드 명의를 확인해 주세요", style = YamuTheme.typography.bodyM, color = c.error)
                card.alreadyRegistered -> Text("이미 등록한 카드예요 · 카드 관리에서 볼 수 있어요", style = YamuTheme.typography.bodyM, color = c.error)
                card.accountMissing -> {
                    Text(
                        "이 카드의 결제 계좌(${card.issuerAccount?.label.orEmpty()})를 먼저 연결해 주세요 · 연결하면 이 화면으로 돌아와요",
                        style = YamuTheme.typography.bodyM,
                        color = c.error,
                    )
                }
                else -> YamuTextField(
                    label = "카드 별칭(선택)",
                    value = s.nickname,
                    onValueChange = vm::onNickname,
                    placeholder = card.productName,
                    supporting = "${CardValidation.NICKNAME_MAX}자까지",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────── 결제 계좌 연결 ─────────────────────────

@Composable
private fun AccountLinkStep(
    s: CardRegisterState,
    title: String,
    flowStep: FlowStep?,
    onBack: () -> Unit,
    vm: CardRegisterViewModel,
) {
    val c = YamuTheme.colors
    val account = s.lookup?.issuerAccount
    FlowScreen(
        title = title,
        onBack = onBack,
        step = flowStep,
        bottomBar = {
            if (s.candidates.isNotEmpty()) {
                BottomCta {
                    PrimaryButton(
                        if (s.selected.isEmpty()) "연결할 계좌를 골라 주세요" else "계좌 ${s.selected.size}개 연결하기",
                        onClick = vm::linkAccounts,
                        enabled = s.selected.isNotEmpty(),
                        loading = s.linking,
                        icon = if (s.biometricReady) YamuIcons.Fingerprint else null,
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("연결할 계좌를 골라 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
            account?.let {
                Spacer(Modifier.height(8.dp))
                Text("이 카드는 ${it.label} 계좌에서 출금돼요", style = YamuTheme.typography.bodyM, color = c.text2)
            }
            Spacer(Modifier.height(16.dp))
            val accountsError = s.accountsError
            when {
                s.accountsLoading -> SearchingState("내 명의 계좌를 찾고 있어요", Modifier.padding(top = 24.dp))
                accountsError != null -> ErrorState(accountsError, onRetry = { vm.loadCandidates() })
                s.candidates.isEmpty() -> EmptyState(
                    "연결할 수 있는 계좌가 없어요 · 이미 연결했다면 카드를 다시 확인해 주세요",
                    actionText = "카드 다시 확인하기",
                    onAction = vm::reenterCard,
                )
                else -> s.candidates.forEach { candidate ->
                    SelectRow(
                        brand = candidate.brand,
                        title = candidate.name,
                        checked = candidate.id in s.selected,
                        onToggle = { vm.toggleAccount(candidate.id) },
                        suffix = "·${candidate.last4}",
                        sub = if (account != null && candidate.isAccount(account)) "이 카드의 결제 계좌" else null,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────── 등록 완료 ─────────────────────────

@Composable
private fun CardDoneStep(
    card: RegisteredCard,
    title: String,
    flowStep: FlowStep?,
    finishLabel: String,
    onClose: () -> Unit,
    onFinished: () -> Unit,
    onAnother: () -> Unit,
) {
    val c = YamuTheme.colors
    FlowScreen(
        title = title,
        onBack = onClose,
        closeIcon = true,                    // 완료 화면 — 뒤로가 아니라 닫기
        step = flowStep,
        bottomBar = {
            BottomCta(secondary = { TextButtonGrey("다른 카드 등록", onClick = onAnother, modifier = Modifier.fillMaxWidth(), small = true) }) {
                PrimaryButton(finishLabel, onClick = onFinished)
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            CharacterImage(YamuPose.Card, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(12.dp))
            Text("${card.productName}${card.productName.objectParticle()}\n등록했어요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(6.dp))
            Text("${card.brand.name} ·${card.last4}", style = YamuTheme.typography.sub, color = c.text3)
            Spacer(Modifier.height(24.dp))
            KeyValueRow("결제일", card.paymentDay?.let { "매월 ${it}일" } ?: if (card.isCheck) "결제 즉시 출금" else "확인할 수 없어요", large = false, minHeight = 32.dp)
            // 서버가 NONE이면 0원이 아니라 모르는 값이다
            KeyValueRow("지난달 실적", card.prevMonthAmount?.let { "${it.toWon()}원" } ?: "확인할 수 없어요", large = false, minHeight = 32.dp)
        }
    }
}
