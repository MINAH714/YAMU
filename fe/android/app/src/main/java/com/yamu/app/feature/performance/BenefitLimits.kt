package com.yamu.app.feature.performance

import com.yamu.app.core.designsystem.component.SectionBlock
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.FoldToggle
import com.yamu.app.feature.performance.data.BenefitLimits
import com.yamu.app.feature.performance.data.LimitCard
import com.yamu.app.feature.performance.data.LimitTag
import com.yamu.app.feature.performance.data.PerformanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LimitsState(val loading: Boolean = true, val limits: BenefitLimits? = null, val error: String? = null)

/** R-06 혜택 한도(#32) — 아직 받을 수 있는 혜택 · 카드별 남은 한도 */
@HiltViewModel
class BenefitLimitsViewModel @Inject constructor(private val perf: PerformanceRepository, link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(LimitsState())
    val state: StateFlow<LimitsState> = _state.asStateFlow()

    /** 카드 그림 — 한도 응답엔 상품명이 없어 카드 목록에서 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    init { load() }

    fun load() {
        artLoader.ensure()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = perf.limits()) {
                is ApiResult.Success -> {
                    _state.update { LimitsState(loading = false, limits = r.data) }
                    artLoader.ensure(r.data.cards.map { it.userCardId })
                }
                is ApiResult.Failure -> _state.update { LimitsState(loading = false, error = r.error.userMessage()) }
            }
        }
    }
}

@Composable
fun BenefitLimitsRoute(onBack: () -> Unit, onPay: () -> Unit, viewModel: BenefitLimitsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    var all by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    FlowScreen(title = "혜택 한도", onBack = onBack, bottomBar = { BottomCta { PrimaryButton("QR 결제 열기", onClick = onPay) } }) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val l = s.limits
            when {
                s.loading -> SectionBlock(divider = false) {
                    repeat(4) {
                        SkeletonBlock(height = 48.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                l == null -> SectionBlock(divider = false) { ErrorState(s.error ?: "혜택 한도를 불러오지 못했어요", onRetry = viewModel::load) }
                else -> {
                    SectionBlock(divider = false) {
                        Text(l.month?.let { "${it}월에 아직 받을 수 있는 혜택" } ?: "아직 받을 수 있는 혜택", style = YamuTheme.typography.bodyM, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        AmountText(l.remaining, color = c.primary)
                    }
                    SectionBlock(title = "카드별 남은 한도") {
                        if (l.cards.isEmpty()) EmptyState("한도가 있는 카드가 없어요")
                        val shown = if (all) l.cards else l.cards.take(VISIBLE)
                        shown.forEach { LimitRow(it, arts[it.userCardId]) }
                        val hidden = l.cards.size - VISIBLE
                        if (hidden > 0) FoldToggle("모두 보기", expanded = all, onToggle = { all = !all })
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

@Composable
private fun LimitRow(card: LimitCard, art: CardArtRef?) {
    val c = YamuTheme.colors
    // 태그는 이름 바로 뒤(임박 · 소진). 한도 게이지는 한도 총액이 오면 이름 아래에(14 문서 14-6)
    ListRow(
        title = card.name,
        // 카드 그림 44dp — 상품명은 카드 목록에서, 모르면 응답 이름(별명이 없으면 상품명)으로 찾는다
        lead = { CardListThumb(card.brand, art?.productName ?: card.name, width = 44.dp, dimmed = art?.dimmed == true) },
        sub = card.lockedReason,
        titleTag = card.tag?.takeIf { it != LimitTag.UNLIMITED }?.let { tag ->
            { StatusTag(tag.label, tone = if (tag == LimitTag.NEAR_END) TagTone.Warning else TagTone.Neutral) }
        },
        value = card.remaining?.let { "${it.toWon()}원" } ?: LimitTag.UNLIMITED.label,
        valueColor = if (card.remaining == null) c.text3 else c.text1,
        minHeight = 64.dp,
    )
}

private const val VISIBLE = 3
