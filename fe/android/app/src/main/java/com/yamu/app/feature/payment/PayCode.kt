package com.yamu.app.feature.payment

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.yamu.app.core.designsystem.component.CompactButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.yamu.app.core.designsystem.component.LiveText
import androidx.compose.ui.unit.IntOffset
import com.yamu.app.core.designsystem.component.CountdownRing
import com.yamu.app.core.designsystem.component.WaitRing
import com.yamu.app.core.designsystem.component.WaitRingDefaults
import com.yamu.app.core.designsystem.theme.YamuMotion
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
import androidx.lifecycle.SavedStateHandle
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
import com.yamu.app.core.network.ApiError
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
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
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
import com.yamu.app.feature.payment.data.withListedCards
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 보낸 승인이 서버에서 어떻게 됐는지 모르는 실패 — 응답이 끊김 · 읽지 못함(isOutcomeUnknown) · 같은 키가 아직 처리 중(BE IDEMPOTENCY_IN_PROGRESS) ·
 * 서버나 금융망 오류(5xx). BE 승인은 출금 실행 예외(502 FIN_API_UNAVAILABLE)와 출금 뒤 결과 저장 실패(500 INTERNAL_ERROR)에서
 * 결제를 PROCESSING에 둔 채 답하므로 코드가 붙어 있어도 돈이 빠졌을 수 있다. 금융망에 보내기 전 실패도 같은 502라 가르지 않고 열어 둔다
 * (같은 키로 다시 보내면 BE가 복구 결과 · 처음 응답 · 409로 답하거나, 시도가 지워졌으면 그때 처음 승인하므로 두 번 결제되지 않는다).
 * 이때는 '결제되지 않았다'고 말하지 않고 같은 키로 다시 확인한다(S15P31E103-433)
 */
private fun ApiError.leavesOutcomeOpen(): Boolean =
    isOutcomeUnknown || code == "IDEMPOTENCY_IN_PROGRESS" || this is ApiError.Server || this is ApiError.BankUnavailable

private const val OUTCOME_UNKNOWN = "결제됐는지 확인하지 못했어요"

/**
 * 결과를 모를 때 판에 남아 같은 키로 저절로 다시 묻는 횟수와 간격(M-WAIT-UNKNOWN). 다 묻고도 모르면 판에 [결과 다시 확인]을 둔다.
 * 마감 뒤에는 저절로 묻지 않고 1초 시계가 한 번만 확인한다
 */
internal const val AUTO_RECHECKS = 2
internal const val RECHECK_DELAY_MS = 2_000L

/** 처음 열 때 카드 목록을 기다리는 상한 — 넘기면 목록 없이 인증부터(평소 응답은 이보다 훨씬 빠르다) */
internal const val CARD_CHECK_MS = 1_500L

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
    /** 처음 열 때 인증 전에 카드 목록을 보는 중 — 이 동안은 인증을 띄우지 않는다(S15P31E103-450) */
    val checkingCards: Boolean = false,
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
    /** 이 결제에서 거절된 카드 전부 — BE도 같은 결제의 거절된 카드는 다시 받지 않는다(ALREADY_DECLINED) */
    val declinedCardIds: Set<Long> = emptySet(),
    /**
     * 승인을 보냈지만 결과를 모른다(응답이 끊김 · 읽지 못함 · 처리 중) — 서버는 승인했을 수 있다.
     * 이 동안 카드 · 할부를 바꾸지 않고 같은 키로만 다시 보내 결과를 확인한다(S15P31E103-433)
     */
    val outcomeUnknown: Boolean = false,
    /** 마감 뒤 같은 키로 결과를 다시 확인하는 중 */
    val checkingOutcome: Boolean = false,
    /** 결과를 모를 때 같은 키로 저절로 다시 물을 차례를 기다리는 중 — 판은 '확인하고 있어요'에 머문다 */
    val recheckScheduled: Boolean = false,
    /** 판이 걷힌 뒤 한 번 흔들 거절된 카드 줄(M-WAIT-DECLINED) — 다시 고를 수 있는 거절만. [shakeSeq]가 오를 때마다 흔든다 */
    val shakeCardId: Long? = null,
    val shakeSeq: Int = 0,
) {
    val request: PaymentRequest? get() = (phase as? PayPhase.Request)?.request
    val selectedCard: PayCard? get() = request?.cards?.firstOrNull { it.userCardId == selectedCardId }
    val requestDone: Boolean get() = requestExpired || !canRetry
    /** 승인 응답을 기다리거나 승인 뒤 화면을 떠나는 중. 이 동안 결제 · 거절 · 뒤로가기는 받지 않는다(판은 결과 불명일 때도 떠 있다 — [waitPanel]) */
    val waiting: Boolean get() = approving || approvedLeaving
}

/** 대기 판에 그릴 것. [actions]면 판 아래에 [결과 다시 확인] · [닫기](결과를 모르는데 지금 묻고 있지 않을 때) */
internal data class WaitPanel(val phase: WaitPhase, val title: String, val pill: String?, val actions: Boolean = false)

/**
 * 대기 판(M-WAIT-*). 승인을 기다리는 동안은 처리 중이고, 결과를 모르면 같은 판에 남아 결과 불명으로 바뀐다(링은 주황 점선, 헤일로는 사라짐).
 * 거절 · 만료 · 결과 확정이면 null이라 판이 걷힌다. 승인 뒤 화면을 떠나는 동안은 부르는 쪽이 마지막 모습을 붙여 둔다
 */
internal fun PayCodeState.waitPanel(): WaitPanel? {
    val request = request ?: return null
    if (outcomeUnknown) {
        return if (approving || checkingOutcome || recheckScheduled) {
            WaitPanel(WaitPhase.Unknown, "결제됐는지 확인하고 있어요", "확인 중 · 응답을 받지 못했어요")
        } else {
            WaitPanel(
                WaitPhase.Unknown,
                "결제됐는지 확인하지 못했어요",
                if (requestExpired) "새로 결제하기 전에 결제 내역을 먼저 봐 주세요" else "다시 확인해도 두 번 결제되지 않아요",
                actions = true,
            )
        }
    }
    if (!waiting) return null
    val card = request.cards.firstOrNull { it.userCardId == approvingCardId }
    return WaitPanel(WaitPhase.Processing, "결제하고 있어요", card?.let { "${it.brand.name} · ${request.amount.toWon()}원" })
}

/**
 * 결제 코드 본인 확인 문구. 결제 상세의 '결제 취소'에서 열면(CancelCode) 취소라고 말한다(S15P31E103-464).
 * 인증 목적은 둘 다 BARCODE — 취소 코드도 같은 결제 코드를 매장이 찍는 것이라 서버 인증은 같다
 */
internal fun payCodePrompt(forCancel: Boolean): StepUpPrompt =
    if (forCancel) StepUpPrompt("결제 취소", "인증하면 취소 코드가 보여요", pinTitle = "간편 비밀번호로\n취소 코드를 열어요")
    else StepUpPrompt("결제 코드 열기", "인증하면 결제 코드가 보여요")

@HiltViewModel
class PayCodeViewModel @Inject constructor(
    private val payments: PaymentRepository,
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
    private val link: LinkRepository,
    /** 취소 코드 화면(CancelCode)에서 만들면 경로에 paymentId가 있다 — 본인 확인 문구만 취소로 바꾼다 */
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val stepUp = stepUpFactory.create(viewModelScope)
    private val _state = MutableStateFlow(PayCodeState(checkingCards = true))
    val state: StateFlow<PayCodeState> = _state.asStateFlow()

    /** 카드 목록(userCardId별) — 결제 요청 카드에 카드사 · 상품명을 붙인다(Q-19). 못 받았으면 비어 있고 응답 값만 보인다 */
    private var listedCards: Map<Long, LinkedCard> = emptyMap()
    private var listedCardsJob: Job? = null
    private var pollJob: Job? = null
    private var visible = false
    private var pollFailures = 0
    /** 같은 결제 · 카드 · 할부 = 같은 키(두 번 눌러도 한 번만 결제), 바꾸면 새 키 */
    private val approveKeys = mutableMapOf<String, String>()
    private val rejectKey = newIdempotencyKey()

    /** 결과를 모르는 채 끝난 승인 — 마감 뒤에도 이 카드 · 할부 · 키 그대로 다시 보내 결과를 받는다(BE는 같은 키에 처음 결과를 돌려준다) */
    private data class OpenAttempt(val card: PayCard, val installmentMonths: Int?, val key: String)
    private var openAttempt: OpenAttempt? = null
    /** 결과를 모를 때 같은 키로 저절로 다시 묻기 — 이 결제에서 [AUTO_RECHECKS]번까지, 손으로 다시 물으면 기다리던 것은 지운다 */
    private var recheckJob: Job? = null
    private var autoRechecks = 0

    init {
        viewModelScope.launch { _state.update { it.copy(biometricReady = auth.biometricReady()) } }
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
        openFirstCode()
    }

    /**
     * 처음 열 때만 인증보다 카드 목록을 먼저 본다. 결제에 쓸 수 있는 카드(status ACTIVE = recommended)가 0장이면
     * 간편 비밀번호 없이 '결제할 카드가 없어요'로(S15P31E103-450). BE도 같은 기준으로 인증 토큰을 보기 전에 발급을 막는다(NO_ACTIVE_CARD).
     * 목록을 못 받았거나 [CARD_CHECK_MS] 안에 오지 않으면 지금처럼 인증부터 하고, 그때 카드가 없으면 서버 NO_ACTIVE_CARD가 같은 화면을 띄운다.
     * 받은 목록은 요청 화면 카드에 카드사 · 상품명을 붙일 때도 쓴다(Q-19). [새 코드]는 다시 보지 않고 바로 인증한다
     */
    private fun openFirstCode() = viewModelScope.launch {
        val r = withTimeoutOrNull(CARD_CHECK_MS) { link.cards() }
        if (r is ApiResult.Success) listedCards = r.data.associateBy { it.userCardId }
        val noCard = r is ApiResult.Success && r.data.none { it.recommended }
        _state.update { it.copy(checkingCards = false, phase = if (noCard) PayPhase.NoCard else it.phase) }
        if (!noCard) openCode()
    }

    /** 본인 인증(BARCODE) → 코드 발급(#77). [새 코드]도 여기(1차: 매번 다시 인증) */
    fun openCode() {
        if (stepUp.busy || _state.value.issuing || _state.value.checkingCards) return
        stopPolling()
        _state.update { it.copy(phase = PayPhase.Auth, error = null, remainingSec = null) }
        stepUp.start(
            purpose = StepUpPurpose.BARCODE,
            prompt = payCodePrompt(forCancel = savedStateHandle.contains("paymentId")),
            onError = { e -> _state.update { it.copy(error = e.authMessage()) } },
        ) { token ->
            _state.update { it.copy(issuing = true) }
            when (val r = payments.issueCode(token.token)) {
                is ApiResult.Success -> {
                    pollFailures = 0
                    _state.update { it.copy(issuing = false, phase = PayPhase.Code(r.data), unstable = false) }
                    tick()
                    restartPolling()
                    loadListedCards()
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
                val justExpired = left <= 0 && !_state.value.requestExpired
                _state.update { it.copy(remainingSec = left.coerceAtLeast(0), requestExpired = it.requestExpired || left <= 0) }
                // 결과를 모르는 채 마감이 지났으면 '결제되지 않았어요'로 단정하지 않고 같은 키로 한 번 확인한다
                if (justExpired && _state.value.outcomeUnknown) confirmOutcome()
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

    /**
     * 처음 열 때 목록을 못 받았으면 코드를 띄운 동안 다시 받아 둔다. 보통 가맹점이 찍기 전에 끝나고, 늦게 오면 그때 요청 화면 카드에 붙인다.
     * 실패하면 다음 코드 · 다음 찍힘에서 다시 받는다(그 사이엔 응답 값만으로 그린다)
     */
    private fun loadListedCards() {
        if (listedCards.isNotEmpty() || listedCardsJob?.isActive == true) return
        listedCardsJob = viewModelScope.launch {
            val r = link.cards()
            if (r !is ApiResult.Success) return@launch
            listedCards = r.data.associateBy { it.userCardId }
            _state.update { s ->
                val p = s.phase as? PayPhase.Request ?: return@update s
                s.copy(phase = PayPhase.Request(p.request.withListedCards(listedCards)))
            }
        }
    }

    private fun onScanned(request: PaymentRequest) {
        loadListedCards()
        _state.update {
            it.copy(
                phase = PayPhase.Request(request.withListedCards(listedCards)),
                // 서버 1순위. 혜택을 비교하지 못한 카드는 미리 고르지 않는다(데이터 부족이면 사용자가 직접 고른다)
                selectedCardId = request.cards.firstOrNull { c -> c.autoSelectable }?.userCardId,
                installmentMonths = null,
                remainingSec = secondsUntil(request.confirmDeadlineIso),
                askNoBenefit = false,
                noBenefitConfirmedIds = emptySet(),
                declinedCardIds = emptySet(),
            )
        }
    }

    /** 결제 가능 여부를 모르는 카드(selectable false)와 이 결제에서 거절된 카드는 고르지 않는다 */
    fun selectCard(userCardId: Long) {
        val s = _state.value
        if (s.outcomeUnknown) return   // 결과를 모르는 동안 다른 카드로 바꾸면 새 키가 되어 두 번 결제될 수 있다
        if (userCardId in s.declinedCardIds) return
        if (s.request?.cards?.any { it.userCardId == userCardId && it.selectable } != true) return
        _state.update { it.copy(selectedCardId = userCardId, installmentMonths = null) }
    }

    fun selectInstallment(months: Int?) {
        if (_state.value.outcomeUnknown) return
        _state.update { it.copy(installmentMonths = months) }
    }

    /** P-06c 확정(#1) */
    fun approve() {
        val s = _state.value
        val request = s.request ?: return
        val card = s.selectedCard ?: return
        if (s.waiting || s.rejecting || s.requestDone) return
        // 1초 시계보다 먼저 마감이 지났으면(남은 시간 링은 이미 00:00) 판을 띄우지 않고 만료로
        if ((secondsUntil(request.confirmDeadlineIso) ?: 1) <= 0) {
            _state.update { it.copy(requestExpired = true, remainingSec = 0) }
            if (s.outcomeUnknown) confirmOutcome()   // 1초 시계보다 먼저 눌렀어도 결과는 확인한다
            return
        }
        if (!card.selectable || card.userCardId in s.declinedCardIds) return
        // 비교 못 한 카드는 혜택 없이 결제된다는 걸 확인받은 뒤에만(BE noBenefitNotice — 승인 API는 확인 여부를 받지 않는다)
        if (card.benefitUnknown && card.userCardId !in s.noBenefitConfirmedIds) {
            _state.update { it.copy(askNoBenefit = true) }
            return
        }
        val keyName = "${request.paymentId}:${card.userCardId}:${s.installmentMonths}"
        val key = approveKeys.getOrPut(keyName) { newIdempotencyKey() }
        cancelRecheck()
        if (!s.outcomeUnknown) autoRechecks = 0   // 새 시도 — 결과를 모르는 동안 다시 보내는 건 같은 시도
        // 코루틴이 돌기 전에 켜 둬야 같은 프레임의 두 번째 누름이 위에서 걸린다
        _state.update { it.copy(approving = true, approvingCardId = card.userCardId, declined = null) }
        viewModelScope.launch {
            val r = payments.approve(request, card, s.installmentMonths, key)
            openAttempt = if (r is ApiResult.Failure && r.error.leavesOutcomeOpen()) OpenAttempt(card, s.installmentMonths, key) else null
            when (r) {
                is ApiResult.Success -> when (val o = r.data) {
                    is ApproveOutcome.Approved -> _state.update {
                        it.copy(approving = false, outcomeUnknown = false, approvedLeaving = true, paidId = o.receipt.paymentId)
                    }
                    is ApproveOutcome.Declined -> {
                        _state.update { it.copy(outcomeUnknown = false) }
                        approveKeys.remove(keyName)          // 같은 카드로 다시 시도하면 새 시도
                        // 다음 카드는 아직 거절되지 않은 추천 후보 중에서만 — 비교 못 한 카드로 저절로 옮기지 않는다.
                        // 이번 카드만 빼면 앞서 거절된 후보로 되돌아가 BE가 ALREADY_DECLINED로 막는다
                        val declinedIds = _state.value.declinedCardIds + card.userCardId
                        fun usable(c: PayCard) = c.autoSelectable && c.userCardId !in declinedIds
                        val next = o.suggestedCardId?.takeIf { id -> request.cards.any { it.userCardId == id && usable(it) } }
                            ?: request.cards.firstOrNull(::usable)?.userCardId
                        val left = o.remainingAttempts?.let { " · ${it}번 더 할 수 있어요" }.orEmpty()
                        val canRetry = o.retryable && (o.remainingAttempts ?: 1) > 0
                        _state.update {
                            it.copy(
                                approving = false,
                                declined = o.message + if (o.retryable) left else "",
                                canRetry = canRetry,
                                declinedCardIds = declinedIds,
                                // 다시 결제할 수 있는데 저절로 고를 카드가 없으면 비워 둔다(비교 못 한 카드는 사용자가 직접)
                                selectedCardId = if (o.retryable) next else it.selectedCardId,
                                installmentMonths = null,
                                // 다시 고를 수 있을 때만 거절된 줄을 흔든다(마지막 거절은 닫기만 남아 흔들지 않음)
                                shakeCardId = if (canRetry) card.userCardId else it.shakeCardId,
                                shakeSeq = if (canRetry) it.shakeSeq + 1 else it.shakeSeq,
                            )
                        }
                    }
                }
                is ApiResult.Failure -> _state.update {
                    when {
                        // 같은 키로 다시 보냈는데 만료 — 처음 시도는 서버에 닿지 않았다(닿았으면 처음 결과가 왔다)
                        r.error.code == "PAYMENT_EXPIRED" -> it.copy(approving = false, outcomeUnknown = false, requestExpired = true)
                        r.error.leavesOutcomeOpen() -> it.copy(approving = false, outcomeUnknown = true, declined = OUTCOME_UNKNOWN)
                        else -> it.copy(approving = false, outcomeUnknown = false, declined = r.error.userMessage() + " · 다시 눌러 주세요")
                    }
                }
            }
            scheduleRecheck()
        }
    }

    /**
     * 결과를 모르면 판에 남은 채 [RECHECK_DELAY_MS] 뒤 같은 카드 · 할부 · 키로 다시 보낸다(M-WAIT-UNKNOWN). 이 결제에서 [AUTO_RECHECKS]번까지이고,
     * 마감 뒤에는 묻지 않는다(마감은 1초 시계가 한 번 확인한다). 같은 키라 BE는 처음 결과를 돌려주고, 처음 시도가 닿지 않았으면 그때 처음 승인한다
     */
    private fun scheduleRecheck() {
        val s = _state.value
        if (!s.outcomeUnknown || s.requestExpired || s.waiting || openAttempt == null || autoRechecks >= AUTO_RECHECKS) return
        autoRechecks++
        _state.update { it.copy(recheckScheduled = true) }
        recheckJob = viewModelScope.launch {
            delay(RECHECK_DELAY_MS)
            recheckJob = null
            _state.update { it.copy(recheckScheduled = false) }
            approve()
        }
    }

    private fun cancelRecheck() {
        recheckJob?.cancel()
        recheckJob = null
        if (_state.value.recheckScheduled) _state.update { it.copy(recheckScheduled = false) }
    }

    /** 결과 불명 판의 [결과 다시 확인] — 마감 전에는 같은 카드 · 키로 승인을 다시 보내고, 마감 뒤에는 결과만 확인한다 */
    fun recheckOutcome() {
        if (_state.value.requestExpired) confirmOutcome() else approve()
    }

    /**
     * 마감 뒤 결과 확인 — 결과를 모르는 채 끝난 승인을 같은 카드 · 할부 · 키로 다시 보낸다(마감 검사 없이).
     * BE는 같은 키면 처음 결과를 돌려주고, 처음 시도가 닿지 않았으면 만료(PAYMENT_EXPIRED)를 준다
     */
    fun confirmOutcome() {
        val s = _state.value
        val attempt = openAttempt ?: return
        val request = s.request ?: return
        if (s.checkingOutcome || s.waiting) return
        cancelRecheck()
        _state.update { it.copy(checkingOutcome = true) }
        viewModelScope.launch {
            val r = payments.approve(request, attempt.card, attempt.installmentMonths, attempt.key)
            if (r !is ApiResult.Failure || !r.error.leavesOutcomeOpen()) openAttempt = null
            _state.update {
                when {
                    r is ApiResult.Success && r.data is ApproveOutcome.Approved ->
                        it.copy(checkingOutcome = false, outcomeUnknown = false, declined = null, approvedLeaving = true, paidId = (r.data as ApproveOutcome.Approved).receipt.paymentId)
                    r is ApiResult.Success && r.data is ApproveOutcome.Declined ->
                        it.copy(checkingOutcome = false, outcomeUnknown = false, declined = (r.data as ApproveOutcome.Declined).message, canRetry = false)
                    r is ApiResult.Failure && r.error.code == "PAYMENT_EXPIRED" ->
                        it.copy(checkingOutcome = false, outcomeUnknown = false, declined = null, requestExpired = true)
                    r is ApiResult.Failure && r.error.leavesOutcomeOpen() -> it.copy(checkingOutcome = false)
                    // 서버가 다른 이유로 거절했다(5xx는 위에서 열어 둔다) — 결제되지 않았다
                    r is ApiResult.Failure -> it.copy(checkingOutcome = false, outcomeUnknown = false, declined = r.error.userMessage(), canRetry = false)
                    else -> it.copy(checkingOutcome = false)
                }
            }
            scheduleRecheck()   // 마감 전에 확인했는데 또 모르면(마감 뒤에는 묻지 않음)
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
/**
 * [onClose]의 문장은 닫힌 뒤 보여 줄 스낵바(이 화면이 닫히면 여기서 띄운 건 취소되므로 바깥에서).
 * [cancelHint]가 있으면 결제 상세에서 취소하러 연 것(CancelCodeRoute) — 코드는 같고 제목 · 코드 아래 안내만 바뀐다.
 */
fun PayCodeRoute(
    onClose: (message: String?) -> Unit,
    onPaid: (paymentId: Long) -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    onAddCard: () -> Unit,
    cancelHint: String? = null,
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
            // 이미 승인됐을 수 있어 거절을 묻지 않는다 — 나가면서 내역 확인을 안내(S15P31E103-433)
            s.outcomeUnknown -> onClose("결제됐는지 결제 내역에서 확인해 주세요")
            s.request != null && !s.requestDone -> askReject = true
            else -> onClose(null)
        }
    }
    BackHandler(onBack = close)
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val busy = stepUpUi != StepUpUi.Idle || s.issuing || s.checkingCards
    val confirming = s.request != null && !s.requestDone
    val livePanel = s.waitPanel()
    // 판이 걷히는 동안(거절 · 만료)과 승인 뒤 화면을 떠나는 동안은 마지막 모습 그대로 — 결과 불명에서 승인돼도 점선이 실선으로 돌아가 보이지 않게
    var heldPanel by remember { mutableStateOf<WaitPanel?>(null) }
    val panel = if (livePanel == null || s.approvedLeaving) heldPanel ?: livePanel else livePanel
    SideEffect { if (livePanel != null && !s.approvedLeaving) heldPanel = livePanel }
    Box(Modifier.fillMaxSize()) {
        FlowScreen(
            title = when {
                s.request != null -> "결제 확인"
                cancelHint != null -> "결제 취소"
                else -> "결제하기"
            },
            onBack = if (confirming) null else close,      // 확인 단계는 ✕ 없이 — 뒤로 누르면 거절을 묻는다
            // 대기 판이 떠 있는 동안 아래 화면은 터치뿐 아니라 TalkBack에서도 숨긴다('거절'에 닿지 않게)
            modifier = if (livePanel != null) Modifier.clearAndSetSemantics {} else Modifier,
            closeIcon = true,
            actions = { MockPayTag() },
            bottomBar = {
                when (val p = s.phase) {
                    PayPhase.Auth -> BottomCta {
                        PrimaryButton(if (s.biometricReady) "지문으로 결제 코드 열기" else "결제 코드 열기", onClick = viewModel::openCode, loading = busy)
                    }
                    PayPhase.CodeExpired -> BottomCta { PrimaryButton("새 코드 받기", onClick = viewModel::openCode, loading = busy) }
                    is PayPhase.Request -> RequestButtons(
                        p.request, s,
                        onApprove = viewModel::approve,
                        onReject = { if (!s.waiting) askReject = true },
                        onClose = { onClose(null) },
                        onConfirmOutcome = viewModel::confirmOutcome,
                    )
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
                    // 카드 목록을 보는 동안은 본문을 비운다 — 0장이면 '인증하면 결제 코드가 열려요'를 잠깐 보였다가 카드 없음으로 바뀌지 않게
                    PayPhase.Auth -> if (!s.checkingCards) AuthBody(s.error)
                    is PayPhase.Code -> CodeBody(p.code, s.remainingSec, s.unstable, onNewCode = viewModel::openCode, hint = cancelHint ?: "매장에서 스캔하면 바로 넘어가요")
                    PayPhase.CodeExpired -> MessageBody("시간이 지났어요", "새 코드를 받아 매장에 보여 주세요")
                    // 취소도 같은 결제 코드라 BE가 카드 0장이면 발급하지 않는다(NO_ACTIVE_CARD) — 카드를 다시 넣어야 매장에서 취소할 수 있다
                    PayPhase.NoCard -> EmptyState(
                        if (cancelHint != null) "매장에서 취소하려면 결제에 쓸 카드가 한 장 이상 있어야 해요" else "결제할 카드가 없어요",
                        actionText = "카드 추가",
                        onAction = onAddCard,
                    )
                    is PayPhase.Request -> RequestBody(p.request, s, onSelectCard = viewModel::selectCard, onInstallment = viewModel::selectInstallment)
                }
                Spacer(Modifier.height(YamuSpacing.x6))
            }
        }
        // 결제 승인 대기 판(M-WAIT-ENTER · APPROVED). 누르자마자 덮고, 승인되면 판째로 P-07에 화면 교체 페이드.
        // 결과를 모르면 판에 남아 결과 불명으로 바뀌고(M-WAIT-UNKNOWN), 거절 · 만료면 0.2초에 걷힌다(M-WAIT-DECLINED · EXPIRED)
        AnimatedVisibility(
            visible = livePanel != null,
            enter = screenSwapEnter(reduce),
            exit = screenSwapExit(reduce),
        ) {
            val shown = panel ?: return@AnimatedVisibility
            // 다시 그려질 때(회전 등)는 들어오는 모션 없이 끝 상태로
            val entering = remember { transition.currentState == EnterExitState.PreEnter }
            val leaving = transition.targetState == EnterExitState.PostExit
            WaitingSurface(
                phase = shown.phase,
                title = shown.title,
                pill = shown.pill,
                entering = entering,
                // 승인 뒤 화면을 떠나기 시작하면 아이콘은 되돌리되 터치는 계속 막는다(같은 결제를 또 누르지 않게)
                lightStatusBar = !leaving && !s.approvedLeaving,
                blockTouches = !leaving,
                // 걷히는 동안은 판에 누를 것을 두지 않는다(아래 화면이 바로 눌리게)
                bottom = if (shown.actions && !leaving) {
                    { WaitPanelActions("결과 다시 확인", onPrimary = viewModel::recheckOutcome, secondary = "닫기", onSecondary = close) }
                } else {
                    null
                },
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
private fun CodeBody(code: PayCode, remainingSec: Long?, unstable: Boolean, onNewCode: () -> Unit, hint: String) {
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
    Text(hint, style = YamuTheme.typography.titleM, color = c.text1, textAlign = TextAlign.Center)
    // 화면 읽기가 저절로 읽게 줄은 늘 두고 글자만 바꾼다(LiveText, S15P31E103-513)
    LiveText(
        if (unstable) "연결이 불안정해요 · 다시 확인하고 있어요" else null,
        style = YamuTheme.typography.bodyS,
        color = c.warning,
        top = YamuSpacing.x2,
    )
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
    // 결제 대기 판이 덮고 있으면 아래 화면은 화면 읽기에서 숨겨진다(PayCodeRoute의 clearAndSetSemantics).
    // 그동안 결과 · 거절 문장은 화면 읽기 쪽만 비워 두었다가 판이 걷힌 뒤 채워 읽게 한다(LiveText speakable)
    val underPanel = s.waitPanel() != null
    Column(Modifier.fillMaxWidth()) {
        // 무엇을 얼마 결제하는지 — 가운데 묶음(프로토타입 P-06c)
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("결제 요청이 왔어요", style = YamuTheme.typography.headlineS, color = c.text1)
            AmountText(request.amount)
            Text(listOfNotNull(request.merchantName, request.categoryName).joinToString(" · "), style = YamuTheme.typography.bodyL, color = c.text2)
            val deadlineMs = remember(request.confirmDeadlineIso) { request.confirmDeadlineIso?.let(::epochMillisOrNull) }
            // 결과를 모르면 '결제되지 않았어요'로 단정하지 않는다 — 다시 결제하면 두 번 결제될 수 있다(S15P31E103-433)
            val outcome = when {
                !s.requestExpired -> null
                s.checkingOutcome -> "결제됐는지 확인하고 있어요"
                s.outcomeUnknown -> "결제됐는지 아직 몰라요 · 새로 결제하기 전에 결제 내역을 먼저 봐 주세요"
                else -> "확인 시간이 지났어요 · 결제되지 않았어요"
            }
            // 남은 시간 줄과 마감 뒤 결과 문장은 한 자리에 겹쳐 둔다. 결과 문장 줄은 마감 전에도 빈 줄로 남아 있어야
            // 문장이 들어올 때 화면 읽기가 저절로 읽는다(LiveText, S15P31E103-513)
            Box(contentAlignment = Alignment.Center) {
                if (!s.requestExpired) {
                    if (deadlineMs != null) {
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 마감되면 바로 회색(M-WAIT-EXPIRED) — 남은 시간 링은 빈 회색 원으로 남는다
                    if (s.requestExpired && !s.checkingOutcome && !s.outcomeUnknown) {
                        WaitRing(
                            progress = { 0f },
                            color = c.text3,
                            modifier = Modifier.size(WaitRingDefaults.miniSize),
                            trackColor = c.text3,
                            stroke = WaitRingDefaults.miniStroke,
                        )
                    }
                    LiveText(
                        outcome,
                        style = YamuTheme.typography.titleS,
                        color = if (s.outcomeUnknown) c.warning else c.text2,
                        textAlign = if (s.outcomeUnknown) TextAlign.Center else null,
                        modifier = Modifier.weight(1f, fill = false),
                        speakable = !underPanel,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        // 고른 카드를 실물 카드 크기로 — 아래 목록에서 바꾸면 같이 바뀐다
        s.selectedCard?.let { selected ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Crossfade(selected, label = "selectedCard") { card ->
                    BrandCardArt(card.brand, card.name, card.last4, Modifier.widthIn(max = 320.dp), productName = card.imageName)
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
            PayCardRow(
                card, selected = card.userCardId == s.selectedCardId, enabled = !s.requestDone && !s.waiting && !s.outcomeUnknown,
                declined = card.userCardId in s.declinedCardIds, onClick = { onSelectCard(card.userCardId) },
                shakeKey = s.shakeSeq.takeIf { card.userCardId == s.shakeCardId },
            )
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
        // 마감 뒤 결과를 모르면 위 머리 문장이 말한다(같은 말을 두 번 하지 않게)
        // 거절 · 결과 불명 문장은 화면 읽기가 저절로 읽게 줄은 늘 두고 글자만 바꾼다(LiveText, S15P31E103-513)
        LiveText(
            s.declined?.takeUnless { s.requestExpired && s.outcomeUnknown },
            style = YamuTheme.typography.bodyM,
            color = if (s.outcomeUnknown) c.warning else c.error,
            top = YamuSpacing.x3,
            speakable = !underPanel,
        )
    }
}

/**
 * 카드 한 줄 — 카드사 · 이름 · 혜택 · 순혜택(서버 값).
 * 고를 수 없는 카드(결제 가능 여부 모름 · 이 결제에서 [declined])는 흐리게, 체크 없이.
 * [shakeKey]가 새 값으로 바뀌면 판이 걷힌 뒤 좌우로 한 번 흔든다(M-WAIT-DECLINED). 처음 그릴 때 · 회전 뒤에는 흔들지 않고, 모션 줄이기면 흐려지기만 한다
 */
@Composable
private fun PayCardRow(card: PayCard, selected: Boolean, enabled: Boolean, declined: Boolean, onClick: () -> Unit, shakeKey: Int? = null) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val usable = card.selectable && !declined
    val shake = remember { Animatable(0f) }
    var shownShake by rememberSaveable { mutableStateOf(shakeKey) }
    LaunchedEffect(shakeKey) {
        if (shakeKey == null || shakeKey == shownShake) return@LaunchedEffect
        shownShake = shakeKey
        if (reduce) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = YamuMotion.SHAKE_MS
                delayMillis = YamuMotion.SCREEN_SWAP_MS   // 판 걷힘(200ms)이 끝난 뒤
                YamuMotion.SHAKE_DP at 50
                -YamuMotion.SHAKE_DP at 110
                YamuMotion.SHAKE_SETTLE_DP at 170
                -YamuMotion.SHAKE_SETTLE_DP at 220
            },
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
            .heightIn(min = 64.dp)
            .selectable(selected = selected, enabled = enabled && usable, role = Role.RadioButton, onClick = onClick)
            .alpha(if (usable) 1f else 0.5f)
            .padding(vertical = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        CardThumb(CardImages.forProduct(card.imageName)) { BrandMark(card.brand, large = true) }
        Column(Modifier.weight(1f)) {
            Text(card.name, style = YamuTheme.typography.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                declined -> "이 결제에서 거절된 카드예요"
                card.isCheck && card.chargedAmount != null -> "${card.benefitName ?: "체크카드"} · ${card.chargedAmount.toWon()}원 출금"
                else -> card.benefitName ?: card.reason
            }
            sub?.let { Text(it, style = YamuTheme.typography.sub, color = c.text3) }
        }
        // 비교한 카드는 어느 카드를 골라도 예상 혜택이 보이게 — 없으면(0원 이하) '혜택 없음'. 비교 못 한 카드는 줄 아래 서버 문구로 대신한다
        if (!declined && !card.benefitUnknown) {
            if (card.netBenefit.signum() > 0) Text("−${card.netBenefit.toWon()}원", style = YamuTheme.typography.value, color = c.primary)
            else Text("혜택 없음", style = YamuTheme.typography.sub, color = c.text3)
        }
        if (usable) RoundCheckbox(checked = selected, onCheckedChange = null)
    }
}

@Composable
private fun RequestButtons(
    request: PaymentRequest,
    s: PayCodeState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
    onConfirmOutcome: () -> Unit,
) {
    if (s.requestDone) {
        // 마감 뒤에도 결과를 모르면 같은 키로 다시 확인할 길을 남긴다
        if (s.outcomeUnknown) {
            BottomCta(secondary = { TextButtonGrey("닫기", onClick = onClose, modifier = Modifier.fillMaxWidth()) }) {
                PrimaryButton("다시 확인", onClick = onConfirmOutcome, loading = s.checkingOutcome)
            }
        } else {
            BottomCta { PrimaryButton("닫기", onClick = onClose) }
        }
        return
    }
    // 결과를 모르는 동안은 같은 카드 · 같은 키로 결과만 확인한다. 거절은 숨긴다(이미 승인됐을 수 있다)
    if (s.outcomeUnknown) {
        BottomCta { PrimaryButton("결제 결과 확인", onClick = onApprove, loading = s.waiting) }
        return
    }
    // 고를 카드가 하나도 없으면(결제 가능 여부를 모르는 카드뿐 · 남은 카드가 모두 거절됨) 거절만
    if (request.cards.none { it.selectable && it.userCardId !in s.declinedCardIds }) {
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
