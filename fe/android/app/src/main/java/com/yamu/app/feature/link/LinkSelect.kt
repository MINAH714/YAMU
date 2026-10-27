package com.yamu.app.feature.link

import androidx.compose.ui.text.font.FontWeight
import com.yamu.app.core.designsystem.component.FlowStep
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.link.data.LinkAssets
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.linkButtonLabel
import com.yamu.app.navigation.LinkSelect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkSelectState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val userName: String = "야무",
    val assets: LinkAssets? = null,
    val excluded: Set<String> = emptySet(),   // 뺀 것(처음엔 모두 체크)
    val completing: Boolean = false,
    val message: String? = null,
    val completed: Boolean = false,
) {
    val accountIds: List<String> get() = assets?.accounts.orEmpty().map { it.assetId }.filter { it !in excluded }
    val cardIds: List<String> get() = assets?.cards.orEmpty().map { it.assetId }.filter { it !in excluded }
}

/** C-03 내 명의 계좌 · 카드 — 뺄 것만 누르고 한 번에 연결(추가 4 → 추가 5) */
@HiltViewModel
class LinkSelectViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val link: LinkRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<LinkSelect>().linkSessionId
    private val _state = MutableStateFlow(LinkSelectState())
    val state: StateFlow<LinkSelectState> = _state.asStateFlow()

    /** 같은 고름을 다시 시도할 땐 같은 키(두 번 눌러도 한 번만 연결), 고름이 바뀌면 새 키 */
    private var idempotencyKey: String
        get() = savedStateHandle[KEY] ?: newIdempotencyKey().also { savedStateHandle[KEY] = it }
        set(value) { savedStateHandle[KEY] = value }

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            val name = auth.session().name ?: "야무"
            when (val r = link.assets(sessionId)) {
                is ApiResult.Success -> {
                    // 이미 연결된 카드는 목록에서 뺀다(마이 › 카드 관리에 있음)
                    val assets = r.data.copy(cards = r.data.cards.filterNot { it.alreadyRegistered })
                    _state.update { it.copy(loading = false, userName = name, assets = assets) }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, userName = name, loadError = r.error.userMessage()) }
            }
        }
    }

    fun toggle(assetId: String) {
        idempotencyKey = newIdempotencyKey()
        _state.update { it.copy(excluded = if (assetId in it.excluded) it.excluded - assetId else it.excluded + assetId) }
    }

    fun complete() {
        val s = _state.value
        if (s.completing || (s.accountIds.isEmpty() && s.cardIds.isEmpty())) return
        val primary = s.assets?.accounts?.filter { it.assetId in s.accountIds }?.let { list ->
            (list.firstOrNull { it.suggestedPrimary } ?: list.firstOrNull())?.assetId
        }
        viewModelScope.launch {
            _state.update { it.copy(completing = true) }
            when (val r = link.complete(sessionId, s.accountIds, s.cardIds, primary, idempotencyKey)) {
                is ApiResult.Success -> {
                    auth.completeOnboarding()      // 연결이 끝나면 다시 켜도 홈으로(09 문서 5-6)
                    _state.update { it.copy(completing = false, completed = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(completing = false, message = r.error.userMessage() + " · 다시 눌러 주세요") }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun navigated() = _state.update { it.copy(completed = false) }

    private companion object { const val KEY = "idempotencyKey" }
}

@Composable
fun LinkSelectRoute(onBack: () -> Unit, onCompleted: () -> Unit, onReselect: () -> Unit, viewModel: LinkSelectViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    LaunchedEffect(s.completed) {
        if (s.completed) {
            viewModel.navigated()
            onCompleted()
        }
    }
    var moreCards by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    val assets = s.assets
    val empty = assets != null && assets.accounts.isEmpty() && assets.cards.isEmpty()
    FlowScreen(
        title = "계좌 · 카드 연결",
        onBack = onBack,
        step = FlowStep(1, 2),
        bottomBar = {
            if (assets != null && !empty) {
                BottomCta {
                    PrimaryButton(
                        linkButtonLabel(s.accountIds.size, s.cardIds.size),
                        onClick = viewModel::complete,
                        enabled = s.accountIds.isNotEmpty() || s.cardIds.isNotEmpty(),
                        loading = s.completing,
                    )
                }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            when {
                s.loading -> SearchingState("내 명의 계좌 · 카드를 찾고 있어요")
                s.loadError != null -> ErrorState(s.loadError.orEmpty(), onRetry = viewModel::load)
                assets == null || empty -> EmptyState("연결할 계좌 · 카드가 없어요", actionText = "기관 다시 고르기", onAction = onReselect)
                else -> {
                    Spacer(Modifier.height(20.dp))
                    Text("${s.userName}님 명의 계좌 · 카드를\n찾았어요", style = YamuTheme.typography.headlineS, color = c.text1)
                    Spacer(Modifier.height(6.dp))
                    Text("뺄 것만 눌러 주세요", style = YamuTheme.typography.bodyM, color = c.text2)
                    if (assets.accounts.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        Text("계좌", style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold), color = c.text3)
                        Spacer(Modifier.height(4.dp))   // 라벨 아래 4(프로토타입 C-03 .sec)
                        assets.accounts.forEach { a ->
                            SelectRow(
                                brand = a.brand,
                                title = "${a.brand.name} ${a.name}",
                                suffix = "·${a.last4}",
                                checked = a.assetId !in s.excluded,
                                onToggle = { viewModel.toggle(a.assetId) },
                            )
                        }
                        if (s.accountIds.isEmpty()) {
                            Text("계좌를 빼면 잔고예보와 카드대금 출금을 볼 수 없어요", style = YamuTheme.typography.bodyS, color = c.warning)
                        }
                    }
                    if (assets.cards.isNotEmpty()) {
                        Spacer(Modifier.height(YamuSpacing.x5))
                        Text("카드", style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold), color = c.text3)
                        Spacer(Modifier.height(4.dp))
                        val shown = if (moreCards) assets.cards else assets.cards.take(VISIBLE_CARDS)
                        shown.forEach { card ->
                            SelectRow(
                                brand = card.brand,
                                title = card.productName,
                                suffix = "·${card.last4}",
                                checked = card.assetId !in s.excluded,
                                onToggle = { viewModel.toggle(card.assetId) },
                            )
                        }
                        val hidden = assets.cards.size - VISIBLE_CARDS
                        if (hidden > 0) FoldToggle("${hidden}장 더 보기", expanded = moreCards, onToggle = { moreCards = !moreCards })
                    }
                    Spacer(Modifier.height(YamuSpacing.x6))
                }
            }
        }
    }
}

private const val VISIBLE_CARDS = 3
