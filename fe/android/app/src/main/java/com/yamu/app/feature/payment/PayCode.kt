package com.yamu.app.feature.payment

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.yamu.app.core.designsystem.component.CompactButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.yamu.app.core.designsystem.component.CountdownRing
import com.yamu.app.core.designsystem.component.WaitRingDefaults
import com.yamu.app.core.designsystem.component.animateCountdownColor
import com.yamu.app.core.designsystem.component.rememberCountdown
import com.yamu.app.core.designsystem.theme.screenSwapEnter
import com.yamu.app.core.designsystem.theme.screenSwapExit
import com.yamu.app.feature.payment.data.epochMillisOrNull
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CardThumb
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.MaxBrightness
import com.yamu.app.core.ui.SecureScreen
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.StepUpEffect
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.StepUpUi
import com.yamu.app.feature.auth.authMessage
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.link.BrandCardArt
import com.yamu.app.feature.link.BrandMark
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CodeState
import com.yamu.app.feature.payment.data.NO_BENEFIT_NOTICE
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import com.yamu.app.feature.payment.data.formatRemaining
import com.yamu.app.feature.payment.data.groupCode
import com.yamu.app.feature.payment.data.secondsUntil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 결제 코드 화면의 단계 — P-06a 인증 → P-06 코드 → P-06c 결제 요청 확인(10 문서 6-3 · 6-4) */
sealed interface PayPhase {
    data object Auth : PayPhase
    data class Code(val code: PayCode) : PayPhase
    data object CodeExpired : PayPhase
    data object NoCard : PayPhase
    data class Request(val request: PaymentRequest) : PayPhase
}

data class PayCodeState(
    val phase: PayPhase = PayPhase.Auth,
    val biometricReady: Boolean = false,
    val issuing: Boolean = false,
    val error: String? = null,
    val remainingSec: Long? = null,            // 코드 만료 또는 확인 마감까지
    val unstable: Boolean = false,             // 확인이 3번 연속 실패
    val selectedCardId: Long? = null,
    val installmentMonths: Int? = null,        // null = 일시불
    val approving: Boolean = false,
    /** 지금 결제를 맡긴 카드 — 대기 판 알약은 이것만 보인다(거절 응답이 다음 카드를 고르는 순간에도 걷히는 판에는 맡긴 카드) */
    val approvingCardId: Long? = null,
    val declined: String? = null,
    val canRetry: Boolean = true,
    val requestExpired: Boolean = false,
    val rejecting: Boolean = false,
    val paidId: Long? = null,                  // 승인 → P-07
    /**
     * 승인됐고 P-07로 넘어가는 중 — 화면이 바뀔 때까지 대기 판을 붙여 둔다(navigated()가 지우지 않음).
     * 승인 응답은 approving을 끄므로 이것 없이는 화면 교체 동안 판이 먼저 걷혀 요청 화면이 비친다
     */
    val approvedLeaving: Boolean = false,
    val closeMessage: String? = null,          // 거절 → 닫고 스낵바
    /** 비교 못 한 카드로 결제하려 함 — 혜택 없이 결제된다는 확인 창 */
    val askNoBenefit: Boolean = false,
    /** 이 결제에서 혜택 없음을 확인한 카드(같은 카드로 다시 결제할 땐 묻지 않는다) */
    val noBenefitConfirmedIds: Set<Long> = emptySet(),
) {
    val request: PaymentRequest? get() = (phase as? PayPhase.Request)?.request
    val selectedCard: PayCard? get() = request?.cards?.firstOrNull { it.userCardId == selectedCardId }
    val requestDone: Boolean get() = requestExpired || !canRetry
    /** 대기 판이 떠 있음 — 승인을 기다리거나 승인 뒤 화면을 떠나는 중. 이 동안 결제 · 거절 · 뒤로가기는 받지 않는다 */
    val waiting: Boolean get() = approving || approvedLeaving
}

@HiltViewModel
class PayCodeViewModel @Inject constructor(
    private val payments: PaymentRepository,
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    val stepUp = stepUpFactory.create(viewModelScope)
    private val _state = MutableStateFlow(PayCodeState())
    val state: StateFlow<PayCodeState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var visible = false
    private var pollFailures = 0
    /** 같은 결제 · 카드 · 할부 = 같은 키(두 번 눌러도 한 번만 결제), 바꾸면 새 키 */
    private val approveKeys = mutableMapOf<String, String>()
    private val rejectKey = newIdempotencyKey()

    init {
        viewModelScope.launch { _state.update { it.copy(biometricReady = auth.biometricReady()) } }
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
        openCode()
    }

    /** 본인 인증(BARCODE) → 코드 발급(#77). [새 코드]도 여기(1차: 매번 다시 인증) */
    fun openCode() {
        if (stepUp.busy || _state.value.issuing) return
        stopPolling()
        _state.update { it.copy(phase = PayPhase.Auth, error = null, remainingSec = null) }
        stepUp.start(
            purpose = StepUpPurpose.BARCODE,
            prompt = StepUpPrompt("결제 코드 열기", "인증하면 결제 코드가 보여요"),
            onError = { e -> _state.update { it.copy(error = e.authMessage()) } },
        ) { token ->
            _state.update { it.copy(issuing = true) }
            when (val r = payments.issueCode(token.token)) {
                is ApiResult.Success -> {
                    pollFailures = 0
                    _state.update { it.copy(issuing = false, phase = PayPhase.Code(r.data), unstable = false) }
                    tick()
                    restartPolling()
                }
                is ApiResult.Failure -> _state.update {
                    if (r.error.code == "NO_ACTIVE_CARD") it.copy(issuing = false, phase = PayPhase.NoCard)
                    else it.copy(issuing = false, error = r.error.userMessage())
                }
            }
        }
    }

    /** 1초마다 — 남은 시간은 앱 시계가 아니라 서버 시각(expiresAt · confirmDeadline)까지 */
    private fun tick() {
        when (val p = _state.value.phase) {
            is PayPhase.Code -> {
                val left = secondsUntil(p.code.expiresAtIso) ?: return
                if (left <= 0) {
                    stopPolling()
                    _state.update { it.copy(phase = PayPhase.CodeExpired, remainingSec = 0) }
                } else {
                    _state.update { it.copy(remainingSec = left) }
                }
            }
            is PayPhase.Request -> {
                val left = secondsUntil(p.request.confirmDeadlineIso) ?: return
                _state.update { it.copy(remainingSec = left.coerceAtLeast(0), requestExpired = it.requestExpired || left <= 0) }
            }
            else -> Unit
        }
    }

    /** 화면이 보일 때만 확인한다(앱이 뒤로 가면 멈춤) */
    fun setVisible(v: Boolean) {
        visible = v
        if (v) restartPolling() else stopPolling()
    }

    private fun restartPolling() {
        stopPolling()
        val code = (_state.value.phase as? PayPhase.Code)?.code ?: return
        if (!visible) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                when (val r = payments.codeStatus(code.token)) {
                    is ApiResult.Success -> {
                        pollFailures = 0
                        _state.update { it.copy(unstable = false) }
                        when (r.data.state) {
                            CodeState.SCANNED -> {
                                r.data.request?.let(::onScanned)
                                return@launch
                            }
                            CodeState.EXPIRED, CodeState.USED -> {
                                _state.update { it.copy(phase = PayPhase.CodeExpired, remainingSec = 0) }
                                return@launch
                            }
                            CodeState.ISSUED -> Unit
                        }
                    }
                    is ApiResult.Failure -> {
                        pollFailures++
                        if (pollFailures >= 3) _state.update { it.copy(unstable = true) }
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun onScanned(request: PaymentRequest) {
        _state.update {
            it.copy(
                phase = PayPhase.Request(request),
                // 서버 1순위. 혜택을 비교하지 못한 카드는 미리 고르지 않는다(데이터 부족이면 사용자가 직접 고른다)
                selectedCardId = request.cards.firstOrNull { c -> c.autoSelectable }?.userCardId,
                installmentMonths = null,
                remainingSec = secondsUntil(request.confirmDeadlineIso),
                askNoBenefit = false,
                noBenefitConfirmedIds = emptySet(),
            )
        }
    }

    /** 결제 가능 여부를 모르는 카드(selectable false)는 고르지 않는다 */
    fun selectCard(userCardId: Long) {
        if (_state.value.request?.cards?.any { it.userCardId == userCardId && it.selectable } != true) return
        _state.update { it.copy(selectedCardId = userCardId, installmentMonths = null) }
    }

    fun selectInstallment(months: Int?) = _state.update { it.copy(installmentMonths = months) }

    /** P-06c 확정(#1) */
    fun approve() {
        val s = _state.value
        val request = s.request ?: return
        val card = s.selectedCard ?: return
        if (s.waiting || s.rejecting || s.requestDone) return
        // 1초 시계보다 먼저 마감이 지났으면(남은 시간 링은 이미 00:00) 판을 띄우지 않고 만료로
        if ((secondsUntil(request.confirmDeadlineIso) ?: 1) <= 0) {
            _state.update { it.copy(requestExpired = true, remainingSec = 0) }
            return
        }
        if (!card.selectable) return
        // 비교 못 한 카드는 혜택 없이 결제된다는 걸 확인받은 뒤에만(BE noBenefitNotice — 승인 API는 확인 여부를 받지 않는다)
        if (card.benefitUnknown && card.userCardId !in s.noBenefitConfirmedIds) {
            _state.update { it.copy(askNoBenefit = true) }
            return
        }
        val keyName = "${request.paymentId}:${card.userCardId}:${s.installmentMonths}"
        val key = approveKeys.getOrPut(keyName) { newIdempotencyKey() }
        // 코루틴이 돌기 전에 켜 둬야 같은 프레임의 두 번째 누름이 위에서 걸린다
        _state.update { it.copy(approving = true, approvingCardId = card.userCardId, declined = null) }
        viewModelScope.launch {
            when (val r = payments.approve(request, card, s.installmentMonths, key)) {
                is ApiResult.Success -> when (val o = r.data) {
                    is ApproveOutcome.Approved -> _state.update {
                        it.copy(approving = false, approvedLeaving = true, paidId = o.receipt.paymentId)
                    }
                    is ApproveOutcome.Declined -> {
                        approveKeys.remove(keyName)          // 같은 카드로 다시 시도하면 새 시도
                        // 다음 카드도 추천 후보 중에서만 — 비교 못 한 카드로 저절로 옮기지 않는다
                        val next = o.suggestedCardId?.takeIf { id -> request.cards.any { it.userCardId == id && it.autoSelectable } }
                            ?: request.cards.firstOrNull { it.userCardId != card.userCardId && it.autoSelectable }?.userCardId
                        val left = o.remainingAttempts?.let { " · ${it}번 더 할 수 있어요" }.orEmpty()
                        _state.update {
                            it.copy(
                                approving = false,
                                declined = o.message + if (o.retryable) left else "",
                                canRetry = o.retryable && (o.remainingAttempts ?: 1) > 0,
                                selectedCardId = if (o.retryable && next != null) next else it.selectedCardId,
                                installmentMonths = null,
                            )
                        }
                    }
                }
                is ApiResult.Failure -> _state.update {
                    if (r.error.code == "PAYMENT_EXPIRED") it.copy(approving = false, requestExpired = true)
                    else it.copy(approving = false, declined = r.error.userMessage() + " · 다시 눌러 주세요")
                }
            }
        }
    }

    /** 확인 창 [결제] — 이 카드는 이 결제에서 다시 묻지 않는다 */
    fun confirmNoBenefit() {
        val id = _state.value.selectedCard?.userCardId ?: return _state.update { it.copy(askNoBenefit = false) }
        _state.update { it.copy(askNoBenefit = false, noBenefitConfirmedIds = it.noBenefitConfirmedIds + id) }
        approve()
    }

    fun dismissNoBenefit() = _state.update { it.copy(askNoBenefit = false) }

    /** P-06c [거절](추가 7) — 실패해도 요청은 60초 뒤 만료되니 화면은 닫는다 */
    fun reject() {
        val request = _state.value.request ?: return _state.update { it.copy(closeMessage = "") }
        // 승인 요청이 날아가는 중에 거절까지 나가면 결과가 엇갈린다(판이 터치를 막지만 TalkBack · 연타 대비)
        if (_state.value.rejecting || _state.value.waiting) return
        viewModelScope.launch {
            _state.update { it.copy(rejecting = true) }
            val r = payments.reject(request.paymentId, rejectKey)
            val message = if (r is ApiResult.Success) "결제를 거절했어요" else "결제하지 않았어요 · 요청은 곧 만료돼요"
            _state.update { it.copy(rejecting = false, closeMessage = message) }
        }
    }

    fun navigated() = _state.update { it.copy(paidId = null, closeMessage = null) }
}

@Composable
/** [onClose]의 문장은 닫힌 뒤 보여 줄 스낵바(이 화면이 닫히면 여기서 띄운 건 취소되므로 바깥에서) */
fun PayCodeRoute(
    onClose: (message: String?) -> Unit,
    onPaid: (paymentId: Long) -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    onAddCard: () -> Unit,
    viewModel: PayCodeViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stepUpUi by viewModel.stepUp.ui.collectAsStateWithLifecycle()
    var askReject by rememberSaveable { mutableStateOf(false) }
    StepUpEffect(viewModel.stepUp, onOpenPinPad)
    SecureScreen()
    LifecycleResumeEffect(Unit) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    if (s.phase is PayPhase.Code) MaxBrightness()
    LaunchedEffect(s.paidId) {
        s.paidId?.let {
            viewModel.navigated()
            onPaid(it)
        }
    }
    LaunchedEffect(s.closeMessage) {
        val message = s.closeMessage ?: return@LaunchedEffect
        viewModel.navigated()
        onClose(message.ifEmpty { null })
    }
    val close = {
        when {
            s.waiting -> Unit   // 승인을 기다리는 동안은 아무 일도 없음(판에 '결제하고 있어요'가 떠 있음)
            s.request != null && !s.requestDone -> askReject = true
            else -> onClose(null)
        }
    }
    BackHandler(onBack = close)
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val busy = stepUpUi != StepUpUi.Idle || s.issuing
    val confirming = s.request != null && !s.requestDone
    Box(Modifier.fillMaxSize()) {
        FlowScreen(
            title = if (s.request != null) "결제 확인" else "결제하기",
            onBack = if (confirming) null else close,      // 확인 단계는 ✕ 없이 — 뒤로 누르면 거절을 묻는다
            // 대기 판이 떠 있는 동안 아래 화면은 터치뿐 아니라 TalkBack에서도 숨긴다('거절'에 닿지 않게)
            modifier = if (s.waiting) Modifier.clearAndSetSemantics {} else Modifier,
            closeIcon = true,
            actions = { MockPayTag() },
            bottomBar = {
                when (val p = s.phase) {
                    PayPhase.Auth -> BottomCta {
                        PrimaryButton(if (s.biometricReady) "지문으로 결제 코드 열기" else "결제 코드 열기", onClick = viewModel::openCode, loading = busy)
                    }
                    PayPhase.CodeExpired -> BottomCta { PrimaryButton("새 코드 받기", onClick = viewModel::openCode, loading = busy) }
                    is PayPhase.Request -> RequestButtons(p.request, s, onApprove = viewModel::approve, onReject = { if (!s.waiting) askReject = true }, onClose = { onClose(null) })
                    else -> Unit
                }
            },
        ) { inner ->
            Column(
                Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = YamuSpacing.screen),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val p = s.phase) {
                    PayPhase.Auth -> AuthBody(s.error)
                    is PayPhase.Code -> CodeBody(p.code, s.remainingSec, s.unstable, onNewCode = viewModel::openCode)
                    PayPhase.CodeExpired -> MessageBody("시간이 지났어요", "새 코드를 받아 매장에 보여 주세요")
                    PayPhase.NoCard -> EmptyState("결제할 카드가 없어요", actionText = "카드 추가", onAction = onAddCard)
                    is PayPhase.Request -> RequestBody(p.request, s, onSelectCard = viewModel::selectCard, onInstallment = viewModel::selectInstallment)
                }
                Spacer(Modifier.height(YamuSpacing.x6))
            }
        }
        // 결제 승인 대기 판(M-WAIT-ENTER · APPROVED). 누르자마자 덮고, 승인되면 판째로 P-07에 화면 교체 페이드
        AnimatedVisibility(
            visible = s.waiting,
            enter = screenSwapEnter(reduce),
            exit = screenSwapExit(reduce),
        ) {
            // 다시 그려질 때(회전 등)는 들어오는 모션 없이 끝 상태로
            val entering = remember { transition.currentState == EnterExitState.PreEnter }
            val leaving = transition.targetState == EnterExitState.PostExit
            WaitingSurface(
                phase = WaitPhase.Processing,
                title = "결제하고 있어요",
                pill = s.request?.let { request ->
                    request.cards.firstOrNull { it.userCardId == s.approvingCardId }?.let { "${it.brand.name} · ${request.amount.toWon()}원" }
                },
                entering = entering,
                // 승인 뒤 화면을 떠나기 시작하면 아이콘은 되돌리되 터치는 계속 막는다(같은 결제를 또 누르지 않게)
                lightStatusBar = !leaving && !s.approvedLeaving,
                blockTouches = !leaving,
            )
        }
    }
    if (s.askNoBenefit && !s.waiting && !s.requestDone) {   // 창이 떠 있는 동안 마감이 지나면 닫는다
        YamuDialog(
            title = "혜택 없이 결제할까요?",
            text = s.request?.noBenefitNotice ?: NO_BENEFIT_NOTICE,
            confirmText = "결제",
            onConfirm = viewModel::confirmNoBenefit,
            onDismiss = viewModel::dismissNoBenefit,
        )
    }
    if (askReject) {
        YamuDialog(
            title = "결제를 거절할까요?",
            text = "가맹점에는 결제되지 않았다고 표시돼요",
            confirmText = "거절",
            onConfirm = {
                askReject = false
                viewModel.reject()
            },
            onDismiss = { askReject = false },
        )
    }
}

// ───────────────────────── P-06a 인증 ─────────────────────────

@Composable
private fun AuthBody(error: String?) {
    val c = YamuTheme.colors
    Spacer(Modifier.height(72.dp))
    Box(Modifier.size(72.dp).clip(CircleShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.text2, modifier = Modifier.size(32.dp))
    }
    Spacer(Modifier.height(YamuSpacing.x5))
    Text("인증하면 결제 코드가 열려요", style = YamuTheme.typography.titleL, color = c.text1, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text("매장 단말이 찍으면 결제 요청이 와요", style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
    error?.let {
        Spacer(Modifier.height(YamuSpacing.x4))
        Text(it, style = YamuTheme.typography.bodyM, color = c.error, textAlign = TextAlign.Center)
    }
}

// ───────────────────────── P-06 결제 코드 ─────────────────────────

@Composable
private fun CodeBody(code: PayCode, remainingSec: Long?, unstable: Boolean, onNewCode: () -> Unit) {
    val c = YamuTheme.colors
    Spacer(Modifier.height(YamuSpacing.x5))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.card))
            .background(c.qrBackground)
            .padding(top = YamuSpacing.x5),   // 바코드는 카드 끝에서 14dp만 띄워 꽉 채운다(프로토타입 약 300dp)
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PayBarcode(code.barcodeValue, Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(52.dp))
        // 바코드 아래 16 · 번호 줄 24 · QR 위 8(프로토타입 P-06 .qr-box.bar · .num)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("결제번호", style = YamuTheme.typography.sub, color = c.text3, modifier = Modifier.alignByBaseline())
            Text(groupCode(code.barcodeValue), style = YamuTheme.typography.value.copy(letterSpacing = 0.1.em), color = c.text1, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(8.dp))
        PayQrCode(code.qrValue, Modifier.width(200.dp))
    }
    Spacer(Modifier.height(8.dp))   // QR 바로 아래 남은 시간 줄(.tm margin-top 8)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("남은 시간", style = YamuTheme.typography.bodyM, color = c.text3)
        Text(formatRemaining(remainingSec ?: 0), style = YamuTheme.typography.value, color = if ((remainingSec ?: 0) <= 30) c.warning else c.text1)
        CompactButton("새 코드", onClick = onNewCode, small = true)
    }
    Spacer(Modifier.height(16.dp))
    Text("매장에서 스캔하면 바로 넘어가요", style = YamuTheme.typography.titleM, color = c.text1)
    if (unstable) {
        Spacer(Modifier.height(YamuSpacing.x2))
        Text("연결이 불안정해요 · 다시 확인하고 있어요", style = YamuTheme.typography.bodyS, color = c.warning)
    }
}

@Composable
private fun MessageBody(title: String, sub: String) {
    val c = YamuTheme.colors
    Spacer(Modifier.height(96.dp))
    Text(title, style = YamuTheme.typography.titleL, color = c.text1)
    Spacer(Modifier.height(6.dp))
    Text(sub, style = YamuTheme.typography.bodyM, color = c.text3)
}

// ───────────────────────── P-06c 결제 요청 확인 ─────────────────────────

@Composable
private fun RequestBody(request: PaymentRequest, s: PayCodeState, onSelectCard: (Long) -> Unit, onInstallment: (Int?) -> Unit) {
    val c = YamuTheme.colors
    Column(Modifier.fillMaxWidth()) {
        // 무엇을 얼마 결제하는지 — 가운데 묶음(프로토타입 P-06c)
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("결제 요청이 왔어요", style = YamuTheme.typography.headlineS, color = c.text1)
            AmountText(request.amount)
            Text(listOfNotNull(request.merchantName, request.categoryName).joinToString(" · "), style = YamuTheme.typography.bodyL, color = c.text2)
            val deadlineMs = remember(request.confirmDeadlineIso) { request.confirmDeadlineIso?.let(::epochMillisOrNull) }
            if (s.requestExpired) {
                Text("확인 시간이 지났어요 · 결제되지 않았어요", style = YamuTheme.typography.titleS, color = c.error)
            } else if (deadlineMs != null) {
                // 링과 숫자가 같은 틱을 읽어 '00:10'이 되는 순간 둘 다 주황으로(M-WAIT-COUNTDOWN)
                val countdown = rememberCountdown(deadlineMs, key = "pay-${request.paymentId}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountdownRing(countdown, Modifier.size(WaitRingDefaults.miniSize))
                    Text("남은 시간", style = YamuTheme.typography.sub, color = c.text3)
                    Text(formatRemaining(countdown.remainingSec), style = YamuTheme.typography.value, color = animateCountdownColor(countdown))
                }
            } else {
                val left = s.remainingSec ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("남은 시간", style = YamuTheme.typography.sub, color = c.text3)
                    Text(formatRemaining(left), style = YamuTheme.typography.value, color = if (left <= 10) c.warning else c.primary)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        // 고른 카드를 실물 카드 크기로 — 아래 목록에서 바꾸면 같이 바뀐다
        s.selectedCard?.let { selected ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Crossfade(selected, label = "selectedCard") { card ->
                    BrandCardArt(card.brand, card.name, card.last4, Modifier.widthIn(max = 320.dp))
                }
            }
            Spacer(Modifier.height(YamuSpacing.x5))
        }
        Text("결제 카드", style = YamuTheme.typography.sub, color = c.text3)
        // 서버가 추천을 끝낸 결과라 기다려도 바뀌지 않는다(FR-018)
        if (request.cards.isEmpty()) {
            EmptyState(if (request.cardsUnknown) "카드 정보를 확인하지 못해 결제할 카드를 고를 수 없어요" else "이 결제에 쓸 수 있는 카드가 없어요")
        }
        val firstUncompared = request.cards.firstOrNull { it.benefitUnknown }
        request.cards.forEach { card ->
            // 비교 못 한 카드는 순위 없이 뒤에 묶는다(서버 문구는 줄 아래에)
            if (card === firstUncompared) {
                Spacer(Modifier.height(YamuSpacing.x3))
                Text("혜택을 비교하지 못한 카드", style = YamuTheme.typography.sub, color = c.text3)
            }
            PayCardRow(card, selected = card.userCardId == s.selectedCardId, enabled = !s.requestDone && !s.waiting, onClick = { onSelectCard(card.userCardId) })
        }
        if (s.selectedCard?.benefitUnknown == true) {
            Spacer(Modifier.height(YamuSpacing.x2))
            Text(request.noBenefitNotice, style = YamuTheme.typography.bodyM, color = c.warning)
        }
        val options = s.selectedCard?.installmentOptions.orEmpty()
        if (options.isNotEmpty()) {
            Spacer(Modifier.height(YamuSpacing.x3))
            Segmented(
                options = listOf("일시불") + options.map { "${it}개월" },
                selectedIndex = s.installmentMonths?.let { options.indexOf(it) + 1 } ?: 0,
                onSelect = { i -> onInstallment(if (i == 0) null else options[i - 1]) },
            )
        }
        s.declined?.let {
            Spacer(Modifier.height(YamuSpacing.x3))
            Text(it, style = YamuTheme.typography.bodyM, color = c.error)
        }
    }
}

/** 카드 한 줄 — 카드사 · 이름 · 혜택 · 순혜택(서버 값). 고를 수 없는 카드(결제 가능 여부 모름)는 흐리게, 체크 없이 */
@Composable
private fun PayCardRow(card: PayCard, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, enabled = enabled && card.selectable, role = Role.RadioButton, onClick = onClick)
            .alpha(if (card.selectable) 1f else 0.5f)
            .padding(vertical = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        CardThumb(CardImages.forProduct(card.name)) { BrandMark(card.brand, large = true) }
        Column(Modifier.weight(1f)) {
            Text(card.name, style = YamuTheme.typography.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (card.isCheck && card.chargedAmount != null) "${card.benefitName ?: "체크카드"} · ${card.chargedAmount.toWon()}원 출금" else card.benefitName ?: card.reason
            sub?.let { Text(it, style = YamuTheme.typography.sub, color = c.text3) }
        }
        if (card.netBenefit.signum() > 0) Text("−${card.netBenefit.toWon()}원", style = YamuTheme.typography.value, color = c.primary)
        if (card.selectable) RoundCheckbox(checked = selected, onCheckedChange = null)
    }
}

@Composable
private fun RequestButtons(request: PaymentRequest, s: PayCodeState, onApprove: () -> Unit, onReject: () -> Unit, onClose: () -> Unit) {
    if (s.requestDone) {
        BottomCta { PrimaryButton("닫기", onClick = onClose) }
        return
    }
    if (request.cards.none { it.selectable }) {
        BottomCta { PrimaryButton("거절", onClick = onReject, loading = s.rejecting) }
        return
    }
    val card = s.selectedCard
    val name = card?.brand?.name ?: "카드"
    val label = when {
        card == null -> "결제할 카드를 골라 주세요"      // 데이터 부족 — 1순위가 없어 미리 고르지 않았다
        s.declined != null -> "$name${name.directionParticle()} 다시 결제"
        else -> "$name${name.directionParticle()} ${request.amount.toWon()}원 결제"
    }
    BottomCta(secondary = { TextButtonGrey("거절", onClick = onReject, modifier = Modifier.fillMaxWidth()) }) {
        PrimaryButton(label, onClick = onApprove, enabled = card != null && !s.rejecting, loading = s.waiting)
    }
}
