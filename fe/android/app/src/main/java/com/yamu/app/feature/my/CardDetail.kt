package com.yamu.app.feature.my

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.StepUpEffect
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.link.BrandCardArt
import com.yamu.app.feature.link.data.CardInfo
import com.yamu.app.feature.link.data.CardValidation
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.cardErrorMessage
import com.yamu.app.feature.my.data.MyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// M-03 카드 상세 — 2026-10-26 범위 간략화에서 뺀 원본(2,000dp 넘는 혜택 · 실적 화면)을 API가 주는 값으로 줄였다.
// 결제일 · 결제 계좌는 여기서 바꾸지 않는다. 바뀌는 시점이 BE 코드(바로)와 명세(다음 이용기간부터)에서 달라 안내 문장을 정할 수 없다.
// 계좌 줄은 눌러서 계좌 관리(M-01b)로 간다(2차 QA 23). 계좌 상세 화면은 없다.

/** 카드 상세의 출금 계좌 줄 — 체크카드 '연결 계좌' · 신용카드 '결제 계좌'. [openable]이면 눌러서 계좌 관리로 */
data class CardAccountRow(val key: String, val value: String, val openable: Boolean)

data class CardDetailState(
    val loading: Boolean = true,
    val card: CardInfo? = null,
    val accountLabel: String? = null,              // 계좌 목록에서 찾은 출금 계좌 '신한은행 ·1234'. 계좌가 없거나 목록에 없으면 null
    val otherActiveCards: Int? = null,             // 자동 선택에 포함된 다른 카드 수. 목록을 못 읽으면 null
    val autoSelect: Boolean? = null,               // 결제 카드 자동 선택(카드 관리 M-02). 못 읽으면 null — 켜진 것으로 본다
    val cardsKnown: Boolean = false,               // 카드 목록을 읽었나 — 못 읽으면 다른 기본 카드가 있는지 모른다
    val otherDefaultName: String? = null,          // 지금 기본 카드인 다른 카드 이름
    val confirm: DefaultConfirm? = null,           // 기본 카드를 바꾸기 전 확인창
    val error: String? = null,
    val gone: Boolean = false,                     // 없거나 다른 기기에서 해제한 카드
    val saving: Boolean = false,
    val unregistering: Boolean = false,
    val unregisteredName: String? = null,          // 해제 끝 — 화면을 닫는다
    val message: String? = null,                   // 스낵바 한 번
) {
    /** 자동 선택에 포함된 마지막 카드 — 빠지면 결제할 카드가 없어 BE가 결제 코드를 발급하지 않는다(BarcodeService: NO_ACTIVE_CARD) */
    val isLastActive: Boolean get() = card?.recommended == true && otherActiveCards == 0

    /** 자동 선택을 끄면 기본 카드로만 결제해 카드별 '자동 선택에 포함'은 쓰이지 않는다 — 꺼진 채 막아 둔다(값은 그대로) */
    val autoSelectOff: Boolean get() = autoSelect == false

    /**
     * 계좌 목록에서 찾은 계좌만 눌러서 계좌 관리로 보낸다. 계좌 관리와 같은 모양([com.yamu.app.feature.link.data.LinkedAccount.numberLabel])으로.
     * 계좌가 없거나 목록을 못 읽었으면 글자만 — 체크카드는 카드 상세 응답의 연결 계좌를 그대로 보인다
     */
    fun accountRow(card: CardInfo): CardAccountRow = when {
        accountLabel != null -> CardAccountRow(if (card.isCheck) "연결 계좌" else "결제 계좌", accountLabel, openable = true)
        card.isCheck -> CardAccountRow("연결 계좌", card.checkAccountLabel ?: "연결된 계좌가 없어요", openable = false)
        else -> CardAccountRow("결제 계좌", "확인할 수 없어요", openable = false)
    }
}

/** 기본 카드를 바꾸기 전에 한 번 묻는다(11/10 QA) — 실수로 눌러 결제 카드가 바뀌지 않게 */
sealed interface DefaultConfirm {
    /** 다른 카드에서 이 카드로. [from]은 지금 기본 카드 이름 — 카드 목록을 못 읽으면 null */
    data class Switch(val to: String, val from: String?) : DefaultConfirm

    /** 자동 선택이 꺼진 채 풀기 — 기본 카드가 없어지면 서버가 자동 선택을 다시 켠다 */
    data object Release : DefaultConfirm
}

/**
 * 받침이 있으면 '이에요', 없으면 '예요' — '체크카드예요' · '적립이에요' · '(할인형)이에요'(끝의 닫는 괄호 · 따옴표 · 마침표는 건너뛴다).
 * 숫자와 영문은 읽는 소리로 본다: 0 · 1 · 3 · 6 · 7 · 8(영 · 일 · 삼 · 육 · 칠 · 팔)과 L · M · N · R(엘 · 엠 · 엔 · 알)은 받침.
 * 영문은 한 글자씩 읽는다고 봐서 'Shop' · 'SIMPLE'처럼 단어로 읽어 받침이 생기는 이름은 '예요'로 남는다
 */
internal fun String.copula(): String {
    val last = trimEnd { it.isWhitespace() || it in COPULA_SKIP }.lastOrNull() ?: return "예요"
    val batchim = when (last) {
        in '가'..'힣' -> (last - '가') % 28 != 0
        in "013678" -> true
        in "LMNRlmnr" -> true
        else -> false
    }
    return if (batchim) "이에요" else "예요"
}

private const val COPULA_SKIP = ")]}」』〉》\"'."

/** 없는 카드(404)와 해제한 카드(409)는 고칠 수 없다 — 줄을 되돌리는 대신 '이미 해제'로 */
private fun ApiError.isCardGone(): Boolean = this is ApiError.NotFound || code == "CARD_UNREGISTERED"

@HiltViewModel
class CardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val link: LinkRepository,
    private val my: MyRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    private val userCardId: Long = checkNotNull(savedStateHandle.get<Long>("userCardId"))

    private val _state = MutableStateFlow(CardDetailState())
    val state: StateFlow<CardDetailState> = _state.asStateFlow()
    val stepUp = stepUpFactory.create(viewModelScope)
    private var loadJob: Job? = null

    /** 화면에 돌아올 때마다 — 다른 기기에서 바꾼 값이 보이게. 저장 중이면 그 응답을 기다린다 */
    fun load() {
        if (_state.value.saving) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.card == null, error = null) }
            val overview = async { link.linkedOverview() }
            val me = async { my.me() }
            val detail = link.cardDetail(userCardId)
            val linked = (overview.await() as? ApiResult.Success)?.data
            val autoSelect = (me.await() as? ApiResult.Success)?.data?.autoSelect
            _state.update { s ->
                when (detail) {
                    is ApiResult.Success -> s.copy(
                        loading = false,
                        card = detail.data,
                        gone = false,
                        // 목록을 못 읽었으면 마지막 값을 둔다. 읽었는데 없으면 null — 눌러도 그 계좌를 찾을 수 없다
                        accountLabel = if (linked == null) s.accountLabel
                        else detail.data.settlementAccountId?.let { id -> linked.accounts.firstOrNull { it.accountId == id } }?.numberLabel,
                        otherActiveCards = linked?.cards?.count { it.recommended && it.userCardId != userCardId } ?: s.otherActiveCards,
                        autoSelect = autoSelect ?: s.autoSelect,
                        cardsKnown = linked != null || s.cardsKnown,
                        otherDefaultName = if (linked == null) s.otherDefaultName
                        else linked.cards.firstOrNull { it.isDefault && it.userCardId != userCardId }?.displayName,
                    )
                    is ApiResult.Failure ->
                        if (detail.error.isCardGone()) s.copy(loading = false, gone = true)
                        else s.copy(loading = false, error = detail.error.userMessage())
                }
            }
        }
    }

    /** 앞뒤 공백을 빼고 비면 별칭 지우기 */
    fun rename(value: String) {
        val nickname = CardValidation.nickname(value)
        save({ it.copy(nickname = nickname) }) { link.updateCard(userCardId, nickname = nickname.orEmpty()) }
    }

    /** 마지막 카드는 뺄 수 없다. 빼면 결제 코드가 열리지 않는다. 결제 카드 자동 선택이 꺼져 있으면 바꾸지 않는다 */
    fun setRecommended(on: Boolean) {
        if (!on && _state.value.isLastActive) return
        if (_state.value.autoSelectOff) return
        val wasDefault = _state.value.card?.isDefault == true
        save(
            optimistic = { it.copy(recommended = on) },
            done = { if (!on && wasDefault) "자동 선택에서 빼면서 기본 카드 지정도 풀었어요" else null },
        ) { link.updateCard(userCardId, recommended = on) }
    }

    /**
     * 다른 카드에서 바꿀 때와 자동 선택이 꺼진 채 풀 때는 먼저 묻는다([DefaultConfirm]).
     * 처음 정할 때 · 자동 선택이 켜진 채 풀 때는 바로 저장한다. 카드 목록을 못 읽었으면 다른 기본 카드가 있을 수 있어 묻는다
     */
    fun setDefault(on: Boolean) {
        val s = _state.value
        val card = s.card ?: return
        if (s.saving || s.confirm != null) return
        val ask = when {
            on && (s.otherDefaultName != null || !s.cardsKnown) -> DefaultConfirm.Switch(card.displayName, s.otherDefaultName)
            !on && s.autoSelectOff -> DefaultConfirm.Release
            else -> null
        }
        if (ask != null) _state.update { it.copy(confirm = ask) } else saveDefault(on)
    }

    fun confirmDefault() {
        val ask = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        saveDefault(on = ask is DefaultConfirm.Switch)
    }

    fun dismissDefaultConfirm() = _state.update { it.copy(confirm = null) }

    private fun saveDefault(on: Boolean) {
        val autoSelectOff = _state.value.autoSelectOff
        save(
            optimistic = { it.copy(isDefault = on) },
            done = {
                if (on) _state.update { it.copy(otherDefaultName = null) }   // 서버가 앞선 기본 카드를 풀었다
                if (!on && autoSelectOff) syncAutoSelect() else null
            },
        ) { link.updateCard(userCardId, isDefault = on) }
    }

    /** 자동 선택이 꺼진 채 기본 카드를 풀면 서버가 자동 선택을 다시 켠다(BE 임시 규칙) — 다시 읽어 스위치를 맞춘다 */
    private suspend fun syncAutoSelect(): String? {
        val on = (my.me() as? ApiResult.Success)?.data?.autoSelect ?: return null
        _state.update { it.copy(autoSelect = on) }
        return if (on) "기본 카드를 풀어서 결제 카드 자동 선택이 다시 켜졌어요" else null
    }

    /** 바로 반영하고 서버 응답으로 맞춘다. 실패하면 원래대로. 한 번에 하나만 */
    private fun save(optimistic: (CardInfo) -> CardInfo, done: suspend () -> String? = { null }, call: suspend () -> ApiResult<CardInfo>) {
        val before = _state.value.card ?: return
        if (_state.value.saving) return
        loadJob?.cancel()   // 저장 전에 읽은 값이 늦게 와서 방금 저장한 값을 덮지 않게
        _state.update { it.copy(saving = true, loading = false, card = optimistic(before)) }
        viewModelScope.launch {
            when (val r = call()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(card = r.data) }
                    val message = done()
                    _state.update { it.copy(saving = false, message = message) }
                }
                is ApiResult.Failure -> {
                    _state.update {
                        if (r.error.isCardGone()) it.copy(saving = false, gone = true, card = before)
                        else it.copy(saving = false, card = before, message = "저장하지 못했어요 · ${r.error.userMessage()}")
                    }
                    if (r.error.isOutcomeUnknown) load()   // 서버가 끝냈을 수 있어 실제 값으로 맞춘다
                }
            }
        }
    }

    /**
     * 본인 확인 뒤 해제. 응답을 못 받았으면 서버가 끝냈을 수 있어 카드가 남아 있는지 본다 — 다시 누르게 하면 본인 인증을 한 번 더 받아야 한다.
     * 다시 눌렀을 때 서버가 404를 줘도 처음 요청이 끝난 것이라 해제로 본다. 청구가 남아 막히면(409) 서버는 본인 인증 토큰을 쓰지 않는다.
     * 상세에서 이미 아는 청구는 간편 비밀번호를 받기 전에 막는다
     */
    fun unregister() {
        val card = _state.value.card ?: return
        if (card.hasOpenBill || _state.value.unregistering || stepUp.busy) return
        _state.update { it.copy(unregistering = true) }
        stepUp.start(
            purpose = StepUpPurpose.CARD_DELETE,
            prompt = StepUpPrompt("지문으로 카드를 해제해요", "${card.displayName} ·${card.last4}"),
            onError = { e -> _state.update { it.copy(unregistering = false, message = unregisterFailed(e)) } },
        ) { token ->
            val failure = (link.unregisterCard(userCardId, token.token) as? ApiResult.Failure)?.error
            val finished = failure == null || failure.isCardGone() || (failure.isOutcomeUnknown && cardIsGone())
            _state.update { s ->
                if (finished || failure == null) s.copy(unregistering = false, unregisteredName = card.displayName)
                else s.copy(unregistering = false, message = unregisterFailed(failure))
            }
        }
    }

    private suspend fun cardIsGone() = (link.cardDetail(userCardId) as? ApiResult.Failure)?.error?.isCardGone() == true

    private fun unregisterFailed(e: ApiError) = when {
        e.isOutcomeUnknown -> "해제됐는지 확인하지 못했어요 · 다시 눌러 주세요"
        else -> cardErrorMessage(e.code) ?: "해제하지 못했어요 · ${e.userMessage()}"
    }

    /** 본인 확인을 취소하고 닫았을 때 */
    fun onStepUpIdle() {
        if (_state.value.unregistering && !stepUp.busy) _state.update { it.copy(unregistering = false) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

private enum class CardDetailSheet { NICKNAME, UNREGISTER }

@Composable
fun CardDetailRoute(
    onBack: () -> Unit,
    onUnregistered: (name: String) -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    onBills: (userCardId: Long) -> Unit,
    onPerformance: (userCardId: Long) -> Unit,
    onAccounts: () -> Unit,
    viewModel: CardDetailViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stepUpUi by viewModel.stepUp.ui.collectAsStateWithLifecycle()
    StepUpEffect(viewModel.stepUp, onOpenPinPad)
    LaunchedEffect(stepUpUi) { viewModel.onStepUpIdle() }
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }   // 스낵바가 떠 있는 동안 효과가 취소되지 않게 화면 범위에서
    }
    LaunchedEffect(s.unregisteredName) { s.unregisteredName?.let(onUnregistered) }
    var sheet by rememberSaveable { mutableStateOf<CardDetailSheet?>(null) }
    FlowScreen(title = "카드 상세", onBack = onBack) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            val card = s.card
            when {
                s.gone -> EmptyState("이미 해제했거나 찾을 수 없는 카드예요", actionText = "돌아가기", onAction = onBack)
                card == null && s.loading -> repeat(4) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                card == null -> ErrorState(s.error ?: "카드를 불러오지 못했어요", onRetry = viewModel::load)
                else -> CardDetailBody(
                    card = card,
                    account = s.accountRow(card),
                    lastActive = s.isLastActive,
                    autoSelectOff = s.autoSelectOff,
                    saving = s.saving,
                    unregistering = s.unregistering,
                    onNickname = { sheet = CardDetailSheet.NICKNAME },
                    onRecommended = viewModel::setRecommended,
                    onDefault = viewModel::setDefault,
                    onUnregister = { sheet = CardDetailSheet.UNREGISTER },
                    onBills = { onBills(card.userCardId) },
                    onPerformance = { onPerformance(card.userCardId) },
                    onAccount = onAccounts,
                )
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    val card = s.card
    if (card != null) {
        when (sheet) {
            CardDetailSheet.NICKNAME -> NicknameSheet(
                card = card,
                onSave = { value ->
                    sheet = null
                    viewModel.rename(value)
                },
                onDismiss = { sheet = null },
            )
            CardDetailSheet.UNREGISTER -> UnregisterSheet(
                card = card,
                lastActive = s.isLastActive,
                onConfirm = {
                    sheet = null
                    viewModel.unregister()
                },
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
    }
    when (val ask = s.confirm) {
        is DefaultConfirm.Switch -> YamuDialog(
            title = if (ask.from != null) "${ask.to}${ask.to.directionParticle()} 바꿀까요?"
            else "기본 카드를 ${ask.to}${ask.to.directionParticle()} 바꿀까요?",
            text = ask.from?.let { "지금 기본 카드는 $it${it.copula()}" },
            confirmText = "바꾸기",
            onConfirm = viewModel::confirmDefault,
            onDismiss = viewModel::dismissDefaultConfirm,
        )
        DefaultConfirm.Release -> YamuDialog(
            title = "기본 카드를 풀까요?",
            text = "기본 카드가 없으면 결제 카드 자동 선택이 다시 켜져요",
            confirmText = "풀기",
            onConfirm = viewModel::confirmDefault,
            onDismiss = viewModel::dismissDefaultConfirm,
        )
        null -> Unit
    }
}

@Composable
private fun CardDetailBody(
    card: CardInfo,
    account: CardAccountRow,
    lastActive: Boolean,
    autoSelectOff: Boolean,
    saving: Boolean,
    unregistering: Boolean,
    onNickname: () -> Unit,
    onRecommended: (Boolean) -> Unit,
    onDefault: (Boolean) -> Unit,
    onUnregister: () -> Unit,
    onBills: () -> Unit,
    onPerformance: () -> Unit,
    onAccount: () -> Unit,
) {
    val c = YamuTheme.colors
    Spacer(Modifier.height(YamuSpacing.x2))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BrandCardArt(card.brand, card.productName, card.last4, Modifier.widthIn(max = 240.dp))
    }
    Spacer(Modifier.height(YamuSpacing.x4))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(card.displayName, style = YamuTheme.typography.titleL, color = c.text1, modifier = Modifier.weight(1f, fill = false))
        if (card.stateLabel != null || card.isDefault) StatusTag(card.stateLabel ?: "기본")
    }

    SectionLabel("카드 정보")
    ListRow(title = "별칭", valuePlain = card.nickname ?: "없음", chevron = true, onClick = onNickname)
    if (card.nickname != null) KeyValueRow("상품", card.productName, large = false)
    KeyValueRow("카드 번호", "•••• ${card.last4}", large = false)
    if (card.isCheck) {
        KeyValueRow("결제일", "결제 즉시 출금", large = false)
        AccountLine(account, onAccount)
    } else {
        KeyValueRow("결제일", card.paymentDay?.let { "매월 ${it}일" } ?: "확인할 수 없어요", large = false)
        AccountLine(account, onAccount)
        // 누르면 이 카드 청구서만(M-09). 출금 예정이 없어도 지난 청구서를 볼 수 있게 줄을 남긴다
        val next = card.nextWithdrawal
        if (next != null) {
            KeyValueRow(
                "다음 출금",
                "${next.date.monthValue}월 ${next.date.dayOfMonth}일 · ${next.amount.toWon()}원",
                large = false,
                trailing = { Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3) },
                onClick = onBills,
            )
        } else {
            ListRow(title = "청구서", valuePlain = "보기", chevron = true, onClick = onBills)
        }
    }

    // 실적 내용은 여기서 다시 그리지 않고 실적관리의 카드 실적 상세(R-02)로 보낸다(11/10 QA)
    SectionLabel("실적")
    ListRow(title = "이번 달 실적", valuePlain = "자세히 보기", chevron = true, onClick = onPerformance)

    SectionLabel("결제")
    // 결제 카드 자동 선택이 꺼져 있으면 기본 카드로만 결제한다 — 카드의 포함 값은 두고 꺼진 채 막는다
    SwitchRow(
        "자동 선택에 포함",
        checked = card.recommended && !autoSelectOff,
        onToggle = onRecommended,
        sub = when {
            autoSelectOff -> "결제 카드 자동 선택이 꺼져 있어요 · 카드 관리에서 켤 수 있어요"
            lastActive -> "자동 선택에 포함된 마지막 카드라 뺄 수 없어요"
            else -> "끄면 결제 카드를 고를 때 빠져요"
        },
        enabled = !saving && !lastActive && !autoSelectOff,
    )
    // 기본 카드는 풀 수는 있어도, 빠진 카드 · 정지 카드를 새로 고를 수는 없다
    val canPickDefault = card.recommended && !card.issuerBlocked
    SwitchRow(
        "기본 카드",
        checked = card.isDefault,
        onToggle = onDefault,
        sub = when {
            !card.recommended -> "자동 선택에 포함한 카드만 고를 수 있어요"
            card.issuerBlocked -> "정지되거나 만료된 카드는 고를 수 없어요"
            autoSelectOff && card.isDefault -> "자동 선택이 꺼져 있어 이 카드로 결제해요"
            autoSelectOff -> "자동 선택이 꺼져 있어 기본 카드로 결제해요"
            else -> "혜택이 같을 때만 먼저 골라요 · 혜택이 더 큰 카드가 있으면 그 카드로 결제해요"
        },
        enabled = !saving && (card.isDefault || canPickDefault),
    )

    Spacer(Modifier.height(YamuSpacing.x4))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onUnregister, enabled = !unregistering) {
            Text(if (unregistering) "해제하는 중" else "카드 해제", style = YamuTheme.typography.labelL, color = if (unregistering) c.text3 else c.error)
        }
    }
}

/** 계좌 목록에서 찾은 계좌만 화살표를 달고 누를 수 있다 — '다음 출금' 줄과 같은 모양 */
@Composable
private fun AccountLine(row: CardAccountRow, onOpen: () -> Unit) {
    KeyValueRow(
        row.key,
        row.value,
        large = false,
        trailing = if (row.openable) ({ Icon(YamuIcons.ChevronRight, contentDescription = null, tint = YamuTheme.colors.text3) }) else null,
        onClick = if (row.openable) onOpen else null,
    )
}

@Composable
private fun NicknameSheet(card: CardInfo, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(card.nickname.orEmpty()) }
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("카드 별칭", style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "별칭",
            value = text,
            onValueChange = { text = it.take(CardValidation.NICKNAME_MAX) },
            placeholder = card.productName,
            supporting = "${CardValidation.NICKNAME_MAX}자까지 · 비우면 상품 이름으로 보여요",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("저장", onClick = { onSave(text) })
        Spacer(Modifier.height(YamuSpacing.x2))
    }
}

/** M-03c — 실제 카드 해지로 오해하지 않게 첫 줄에 적는다. BE가 바로 해제해 실행 취소는 없다 */
@Composable
private fun UnregisterSheet(card: CardInfo, lastActive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = YamuTheme.colors
    val name = card.displayName
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("$name${name.objectParticle()} 해제할까요?", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(YamuSpacing.x3))
        Text("카드사 카드는 해지되지 않아요", style = YamuTheme.typography.titleS, color = c.text1)
        Spacer(Modifier.height(YamuSpacing.x2))
        Text("결제 카드 추천과 목록에서 빠지고, 결제 내역은 남아요", style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(YamuSpacing.x1))
        Text("다시 쓰려면 카드를 다시 등록해야 해요", style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(YamuSpacing.x1))
        if (card.hasOpenBill) {
            Text("출금이 끝나지 않은 청구가 있어 지금은 해제할 수 없어요", style = YamuTheme.typography.bodyM, color = c.warning)
        } else {
            Text("출금이 끝나지 않은 청구가 있으면 해제할 수 없어요", style = YamuTheme.typography.bodyM, color = c.text2)
        }
        if (lastActive) {
            Spacer(Modifier.height(YamuSpacing.x1))
            Text("자동 선택에 포함된 마지막 카드예요 · 해제하면 결제 코드를 열 수 없어요", style = YamuTheme.typography.bodyM, color = c.warning)
        }
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("카드 해제하기", onClick = onConfirm, enabled = !card.hasOpenBill)
        Spacer(Modifier.height(YamuSpacing.x2))
        NeutralButton("취소", onClick = onDismiss)
        Spacer(Modifier.height(YamuSpacing.x2))
    }
}
