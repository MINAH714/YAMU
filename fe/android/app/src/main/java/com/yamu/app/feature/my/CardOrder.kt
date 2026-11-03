package com.yamu.app.feature.my

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.cardErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// M-02g 카드 순서 — 목록 표시 순서이자 추천 동률의 마지막 기준(BE FIXED_ORDER). 길게 눌러 끌지 않아도 되게 위 · 아래 버튼으로

data class CardOrderState(
    val loading: Boolean = true,
    val original: List<LinkedCard> = emptyList(),
    val cards: List<LinkedCard> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,                   // 스낵바 한 번
) {
    val changed: Boolean get() = cards.map { it.userCardId } != original.map { it.userCardId }
}

@HiltViewModel
class CardOrderViewModel @Inject constructor(private val link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(CardOrderState())
    val state: StateFlow<CardOrderState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = link.linkedOverview()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, original = r.data.cards, cards = r.data.cards) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    /** [delta] -1 위로 · +1 아래로. 끝을 넘으면 그대로 */
    fun move(index: Int, delta: Int) {
        _state.update { s ->
            val target = index + delta
            if (s.saving || index !in s.cards.indices || target !in s.cards.indices) return@update s
            s.copy(cards = s.cards.toMutableList().apply { add(target, removeAt(index)) })
        }
    }

    /** 같은 순서를 다시 보내도 결과가 같아 응답을 못 받으면 그대로 다시 누르면 된다 */
    fun save() {
        val s = _state.value
        if (s.saving || !s.changed) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val r = link.saveCardOrder(s.cards.map { it.userCardId })) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is ApiResult.Failure -> {
                    _state.update { it.copy(saving = false, message = cardErrorMessage(r.error.code) ?: "저장하지 못했어요 · ${r.error.userMessage()}") }
                    if (r.error.code == "ORDER_INCOMPLETE") load()   // 다른 기기에서 카드를 추가 · 해제함 — 지금 목록으로 다시
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun CardOrderRoute(onBack: () -> Unit, onSaved: () -> Unit, viewModel: CardOrderViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    LaunchedEffect(s.saved) { if (s.saved) onSaved() }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val close = { if (s.changed) askDiscard = true else onBack() }
    BackHandler(enabled = s.changed) { askDiscard = true }
    FlowScreen(
        title = "카드 순서",
        onBack = close,
        closeIcon = true,
        bottomBar = {
            BottomCta {
                PrimaryButton("이 순서로 저장", onClick = viewModel::save, enabled = s.changed, loading = s.saving)
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(YamuSpacing.x2))
            Text("다른 조건이 모두 같을 때 위 카드부터 골라요 · 내 카드 목록도 이 순서예요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(YamuSpacing.x3))
            when {
                s.loading -> repeat(4) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                s.cards.isEmpty() && s.error != null -> ErrorState(s.error.orEmpty(), onRetry = viewModel::load)
                else -> s.cards.forEachIndexed { i, card ->
                    ListRow(
                        title = card.displayName,
                        sub = "·${card.last4}",
                        lead = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3)) {
                                Text("${i + 1}", style = YamuTheme.typography.titleM, color = c.text3, modifier = Modifier.width(16.dp))
                                CardListThumb(card.brand, card.productName, width = 44.dp)
                            }
                        },
                        trailing = {
                            IconButton(onClick = { viewModel.move(i, -1) }, enabled = i > 0) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "${card.displayName} 위로", tint = if (i > 0) c.text1 else c.outline)
                            }
                            IconButton(onClick = { viewModel.move(i, 1) }, enabled = i < s.cards.lastIndex) {
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "${card.displayName} 아래로", tint = if (i < s.cards.lastIndex) c.text1 else c.outline)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    if (askDiscard) {
        YamuDialog(
            title = "바꾼 순서를 버릴까요?",
            confirmText = "버리기",
            onConfirm = {
                askDiscard = false
                onBack()
            },
            onDismiss = { askDiscard = false },
            dismissText = "계속 고치기",
        )
    }
}
