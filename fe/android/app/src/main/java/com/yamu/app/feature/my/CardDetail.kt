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
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// M-03 카드 상세 — 2026-10-26 범위 간략화에서 뺀 원본(2,000dp 넘는 혜택 · 실적 화면)을 API가 주는 값으로 줄였다.
// 결제일 · 결제 계좌는 보여 주기만 한다. 바뀌는 시점이 BE 코드(바로)와 명세(다음 이용기간부터)에서 달라 안내 문장을 정할 수 없다.

data class CardDetailState(
    val loading: Boolean = true,
    val card: CardInfo? = null,
    val settlementAccountLabel: String? = null,    // 신용카드 결제 계좌 — 계좌 목록을 못 읽으면 null
    val otherActiveCards: Int? = null,             // 자동 선택에 포함된 다른 카드 수. 목록을 못 읽으면 null
    val error: String? = null,
    val gone: Boolean = false,                     // 없거나 다른 기기에서 해제한 카드
    val saving: Boolean = false,
    val unregistering: Boolean = false,
    val unregisteredName: String? = null,          // 해제 끝 — 화면을 닫는다
    val message: String? = null,                   // 스낵바 한 번
) {
    /**
     * 자동 선택에 포함된 마지막 카드 — 빼거나 해제하면 BE가 초기 설정 전으로 본다(OnboardingStatusReader: ACTIVE 카드 1장 이상, 임시 기준).
     * 다시 로그인하면 카드 등록으로 가는데, 추천에서만 뺀 카드는 등록돼 있어 다시 등록할 수 없다
     */
    val isLastActive: Boolean get() = card?.recommended == true && otherActiveCards == 0
}

/** 없는 카드(404)와 해제한 카드(409)는 고칠 수 없다 — 줄을 되돌리는 대신 '이미 해제'로 */
private fun ApiError.isCardGone(): Boolean = this is ApiError.NotFound || code == "CARD_UNREGISTERED"

@HiltViewModel
class CardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val link: LinkRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    private val userCardId: Long = checkNotNull(savedStateHandle.get<Long>("userCardId"))

    private val _state = MutableStateFlow(CardDetailState())
    val state: StateFlow<CardDetailState> = _state.asStateFlow()
    val stepUp = stepUpFactory.create(viewModelScope)

    /** 화면에 돌아올 때마다 — 다른 기기에서 바꾼 값이 보이게. 저장 중이면 그 응답을 기다린다 */
    fun load() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(loading = it.card == null, error = null) }
            val overview = async { link.linkedOverview() }
            val detail = link.cardDetail(userCardId)
            val linked = (overview.await() as? ApiResult.Success)?.data
            _state.update { s ->
                when (detail) {
                    is ApiResult.Success -> s.copy(
                        loading = false,
                        card = detail.data,
                        gone = false,
                        settlementAccountLabel = linked?.accounts?.firstOrNull { it.accountId == detail.data.settlementAccountId }
                            ?.let { "${it.brand.name} ·${it.last4}" } ?: s.settlementAccountLabel,
                        otherActiveCards = linked?.cards?.count { it.recommended && it.userCardId != userCardId } ?: s.otherActiveCards,
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

    /** 마지막 카드는 뺄 수 없다(임시). 결제할 카드가 없어지고 다시 로그인하면 등록에서 막힌다 */
    fun setRecommended(on: Boolean) {
        if (!on && _state.value.isLastActive) return
        val wasDefault = _state.value.card?.isDefault == true
        save(
            optimistic = { it.copy(recommended = on) },
            done = { if (!on && wasDefault) "자동 선택에서 빼면서 기본 카드 지정도 풀었어요" else null },
        ) { link.updateCard(userCardId, recommended = on) }
    }

    fun setDefault(on: Boolean) = save({ it.copy(isDefault = on) }) { link.updateCard(userCardId, isDefault = on) }

    /** 바로 반영하고 서버 응답으로 맞춘다. 실패하면 원래대로. 한 번에 하나만 */
    private fun save(optimistic: (CardInfo) -> CardInfo, done: () -> String? = { null }, call: suspend () -> ApiResult<CardInfo>) {
        val before = _state.value.card ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, card = optimistic(before)) }
        viewModelScope.launch {
            when (val r = call()) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, card = r.data, message = done()) }
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
     * 다시 눌렀을 때 서버가 404를 줘도 처음 요청이 끝난 것이라 해제로 본다. 청구가 남아 막히면(409) 서버는 본인 인증 토큰을 쓰지 않는다
     */
    fun unregister() {
        val card = _state.value.card ?: return
        if (_state.value.unregistering || stepUp.busy) return
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
                    settlementAccountLabel = s.settlementAccountLabel,
                    lastActive = s.isLastActive,
                    saving = s.saving,
                    unregistering = s.unregistering,
                    onNickname = { sheet = CardDetailSheet.NICKNAME },
                    onRecommended = viewModel::setRecommended,
                    onDefault = viewModel::setDefault,
                    onUnregister = { sheet = CardDetailSheet.UNREGISTER },
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
}

@Composable
private fun CardDetailBody(
    card: CardInfo,
    settlementAccountLabel: String?,
    lastActive: Boolean,
    saving: Boolean,
    unregistering: Boolean,
    onNickname: () -> Unit,
    onRecommended: (Boolean) -> Unit,
    onDefault: (Boolean) -> Unit,
    onUnregister: () -> Unit,
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
        KeyValueRow("연결 계좌", card.checkAccountLabel ?: "연결된 계좌가 없어요", large = false)
    } else {
        KeyValueRow("결제일", card.paymentDay?.let { "매월 ${it}일" } ?: "확인할 수 없어요", large = false)
        KeyValueRow("결제 계좌", settlementAccountLabel ?: "확인할 수 없어요", large = false)
        card.nextWithdrawal?.let { n ->
            KeyValueRow("다음 출금", "${n.date.monthValue}월 ${n.date.dayOfMonth}일 · ${n.amount.toWon()}원", large = false)
        }
    }

    SectionLabel("결제")
    SwitchRow(
        "자동 선택에 포함",
        checked = card.recommended,
        onToggle = onRecommended,
        sub = if (lastActive) "자동 선택에 포함된 마지막 카드라 뺄 수 없어요" else "끄면 결제 카드를 고를 때 빠져요",
        enabled = !saving && !lastActive,
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
        Text("출금이 끝나지 않은 청구가 있으면 해제할 수 없어요", style = YamuTheme.typography.bodyM, color = c.text2)
        if (lastActive) {
            Spacer(Modifier.height(YamuSpacing.x1))
            Text("자동 선택에 포함된 마지막 카드예요 · 다시 로그인하면 카드 등록부터 해요", style = YamuTheme.typography.bodyM, color = c.warning)
        }
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("카드 해제하기", onClick = onConfirm)
        Spacer(Modifier.height(YamuSpacing.x2))
        NeutralButton("취소", onClick = onDismiss)
        Spacer(Modifier.height(YamuSpacing.x2))
    }
}
