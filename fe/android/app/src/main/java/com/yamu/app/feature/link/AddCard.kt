package com.yamu.app.feature.link

import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.KeyValueRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CardArt
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.StepUpEffect
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.link.data.CardAsset
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.linkButtonLabel
import com.yamu.app.navigation.AddCardDone
import com.yamu.app.navigation.AddCardFound
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** C-12 · C-14 보조 줄 — '매월 14일 결제 · 실적 0원부터' */
private fun CardAsset.summaryLine(): String = listOfNotNull(
    paymentDay?.let { "매월 ${it}일 결제" } ?: if (isCheck) "결제 즉시 출금" else null,
    if (hasPriorHistory) "지난 실적 이어 받기" else "실적 0원부터",
).joinToString(" · ")

// ───────────────────────── C-12 새로 찾은 카드 ─────────────────────────

data class AddCardFoundState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val issuerName: String? = null,
    val cards: List<CardAsset> = emptyList(),      // 새 카드만
    val excluded: Set<String> = emptySet(),
    val biometricReady: Boolean = false,
    val linking: Boolean = false,
    val message: String? = null,
    val addedUserCardId: Long? = null,
) {
    val selectedIds: List<String> get() = cards.map { it.assetId }.filter { it !in excluded }
}

@HiltViewModel
class AddCardFoundViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val link: LinkRepository,
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<AddCardFound>().linkSessionId
    val stepUp = stepUpFactory.create(viewModelScope)
    private val _state = MutableStateFlow(AddCardFoundState())
    val state: StateFlow<AddCardFoundState> = _state.asStateFlow()
    private var idempotencyKey = newIdempotencyKey()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            val ready = auth.biometricReady()
            when (val r = link.assets(sessionId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        issuerName = r.data.cards.firstOrNull()?.brand?.name,
                        cards = r.data.cards.filterNot { c -> c.alreadyRegistered },
                        biometricReady = ready,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = r.error.userMessage()) }
            }
        }
    }

    fun toggle(assetId: String) {
        idempotencyKey = newIdempotencyKey()
        _state.update { it.copy(excluded = if (assetId in it.excluded) it.excluded - assetId else it.excluded + assetId) }
    }

    /** [지문으로 연결하기] — 본인 인증(CARD_REGISTER) → 연결 확정(추가 5, 헤더 X-Step-Up-Token) */
    fun connect() {
        val s = _state.value
        val ids = s.selectedIds
        if (ids.isEmpty() || s.linking || stepUp.busy) return
        val one = s.cards.singleOrNull { it.assetId in ids }
        val subtitle = one?.let { "${it.productName} ·${it.last4}" } ?: "카드 ${ids.size}장"
        _state.update { it.copy(linking = true) }
        stepUp.start(
            purpose = StepUpPurpose.CARD_REGISTER,
            prompt = StepUpPrompt("지문으로 카드를 연결해요", subtitle),
            onError = { e -> _state.update { it.copy(linking = false, message = e.userMessage()) } },
        ) { token ->
            when (val r = link.complete(sessionId, emptyList(), ids, null, idempotencyKey, token.token)) {
                is ApiResult.Success -> _state.update { it.copy(linking = false, addedUserCardId = r.data.userCardIds.firstOrNull() ?: -1L) }
                is ApiResult.Failure -> _state.update { it.copy(linking = false, message = r.error.userMessage()) }
            }
        }
    }

    fun onStepUpIdle() {
        if (_state.value.linking && !stepUp.busy && _state.value.addedUserCardId == null) _state.update { it.copy(linking = false) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun navigated() = _state.update { it.copy(addedUserCardId = null) }
}

@Composable
fun AddCardFoundRoute(
    onBack: () -> Unit,
    onAdded: (userCardId: Long) -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    viewModel: AddCardFoundViewModel = hiltViewModel(),
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
    LaunchedEffect(s.addedUserCardId) {
        s.addedUserCardId?.let {
            viewModel.navigated()
            onAdded(it)
        }
    }
    val c = YamuTheme.colors
    val issuer = s.issuerName ?: "카드사"
    FlowScreen(
        title = "카드 추가",
        onBack = onBack,
        bottomBar = {
            if (!s.loading && s.loadError == null) {
                BottomCta {
                    if (s.cards.isEmpty()) {
                        PrimaryButton("다른 카드사 고르기", onClick = onBack)
                    } else {
                        val label = if (s.biometricReady) "지문으로 연결하기" else "간편 비밀번호로 연결하기"
                        PrimaryButton(
                            if (s.cards.size > 1) linkButtonLabel(0, s.selectedIds.size) else label,
                            onClick = viewModel::connect,
                            enabled = s.selectedIds.isNotEmpty(),
                            loading = s.linking,
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
            when {
                s.loading -> SearchingState("카드사에서 내 카드를 찾고 있어요")
                s.loadError != null -> ErrorState(s.loadError.orEmpty(), onRetry = viewModel::load)
                s.cards.isEmpty() -> EmptyState("${issuer}에 새로 연결할 카드가 없어요")
                else -> {
                    Spacer(Modifier.height(20.dp))
                    Text("${issuer}에서\n새 카드 ${s.cards.size}장을 찾았어요", style = YamuTheme.typography.headlineS, color = c.text1)
                    Spacer(Modifier.height(6.dp))
                    Text("연결할 카드를 확인해 주세요", style = YamuTheme.typography.bodyM, color = c.text2)
                    Spacer(Modifier.height(12.dp))   // 머리 아래 12(프로토타입 C-12 .hd)
                    // 한 장이고 상품 그림이 있으면 큰 카드 그림(09 문서 C-12 '카드 그림') — 없으면 지금처럼 줄만
                    s.cards.singleOrNull()?.let { one ->
                        CardImages.forProduct(one.productName)?.let { image ->
                            CardArt(image, one.productName, Modifier.padding(vertical = YamuSpacing.x2))
                        }
                    }
                    s.cards.forEach { card ->
                        SelectRow(
                            brand = card.brand,
                            title = card.productName,
                            suffix = "·${card.last4}",
                            sub = card.summaryLine(),
                            checked = card.assetId !in s.excluded,
                            onToggle = { viewModel.toggle(card.assetId) },
                            chipSlot = 44.dp,
                            cardImage = CardImages.forProduct(card.productName),
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── C-14 카드 추가 완료 ─────────────────────────

data class AddCardDoneState(val loading: Boolean = true, val card: LinkedCard? = null, val total: Int? = null)

/** 연결된 카드 정보는 서버에서 다시(#7) — 인자는 userCardId만 */
@HiltViewModel
class AddCardDoneViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val link: LinkRepository) : ViewModel() {
    private val userCardId = savedStateHandle.toRoute<AddCardDone>().userCardId
    private val _state = MutableStateFlow(AddCardDoneState())
    val state: StateFlow<AddCardDoneState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val r = link.linkedOverview()
            val overview = (r as? ApiResult.Success)?.data
            _state.update {
                AddCardDoneState(loading = false, card = overview?.cards?.firstOrNull { c -> c.userCardId == userCardId }, total = overview?.cards?.size)
            }
        }
    }
}

@Composable
fun AddCardDoneRoute(onDone: () -> Unit, viewModel: AddCardDoneViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val card = s.card
    FlowScreen(
        title = "카드 추가",
        onBack = onDone,
        closeIcon = true,                    // 완료 화면 — 뒤로가 아니라 닫기
        bottomBar = { BottomCta { PrimaryButton("완료", onClick = onDone) } },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            CharacterImage(YamuPose.Card, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(12.dp))
            Text(
                card?.let { "${it.productName}${it.productName.objectParticle()}\n추가했어요" } ?: "카드를 추가했어요",
                style = YamuTheme.typography.headlineS,
                color = c.text1,
            )
            card?.let {
                Spacer(Modifier.height(6.dp))
                Text("${it.brand.name} ·${it.last4}", style = YamuTheme.typography.sub, color = c.text3)
            }
            Spacer(Modifier.height(24.dp))   // 머리 아래 8 + 덩어리 위 16(프로토타입 C-14 .hd · .blk)
            when {
                s.loading -> SkeletonBlock(height = 72.dp)
                card != null -> Column {
                    // 키–값(프로토타입 .kv) — 첫 청구일은 서버 값이 생기면 줄을 더한다(14 문서 14-6)
                    card.paymentDay?.let { KeyValueRow("결제일", "매월 ${it}일", large = false, minHeight = 32.dp) }   // 줄 32(.kv 위아래 6)
                    card.recognizedAmount?.let { amount ->
                        val month = card.yearMonth?.takeLast(2)?.toIntOrNull()?.let { m -> "${m}월 " } ?: "이번 달 "
                        KeyValueRow("${month}실적", "${amount.toWon()}원부터", large = false, minHeight = 32.dp)
                    }
                }
            }
        }
    }
}
