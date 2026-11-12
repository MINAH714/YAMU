package com.yamu.app.feature.performance

import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.SectionBlock
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PerformanceGauge
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toManWon
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BrandCardArt
import com.yamu.app.feature.link.DIMMED_CARD_ALPHA
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.performance.data.AT_RISK_LABEL
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.PerfDetail
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.navigation.CardPerformance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CardPerfState(val loading: Boolean = true, val card: CardPerf? = null, val detail: PerfDetail? = null, val error: String? = null)

/** R-02 카드 실적 상세(#66) — 카드 이름 · 판정은 실적관리 목록(#65)에서 */
@HiltViewModel
class CardPerformanceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val perf: PerformanceRepository,
    link: LinkRepository,
) : ViewModel() {
    private val userCardId = savedStateHandle.toRoute<CardPerformance>().userCardId
    private val _state = MutableStateFlow(CardPerfState())
    val state: StateFlow<CardPerfState> = _state.asStateFlow()

    /** 별명 카드의 그림 — 실적 응답엔 상품명이 없어 카드 목록에서 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    init {
        artLoader.ensure(listOf(userCardId))
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val overview = perf.cachedOverview() ?: (perf.overview() as? ApiResult.Success)?.data
            val card = overview?.all?.firstOrNull { it.userCardId == userCardId }
            when (val r = perf.detail(userCardId)) {
                is ApiResult.Success -> _state.update { CardPerfState(loading = false, card = card, detail = r.data) }
                is ApiResult.Failure -> _state.update { CardPerfState(loading = false, card = card, error = r.error.userMessage()) }
            }
        }
    }
}

@Composable
fun CardPerformanceRoute(onBack: () -> Unit, onAnalysis: (userCardId: Long) -> Unit, viewModel: CardPerformanceViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val card = s.card
    val d = s.detail
    FlowScreen(
        title = "${card?.name ?: "카드"} 실적",
        onBack = onBack,
        bottomBar = {
            if (card != null && card.pending && d != null) {
                val label = if (card.guide == PerfGuide.ACHIEVE_RECOMMENDED) "추가 지출 0원으로 채우는 방법 보기" else "실적 유불리 분석 보기"
                BottomCta { PrimaryButton(label, onClick = { onAnalysis(card.userCardId) }) }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
          Column(Modifier.padding(horizontal = YamuSpacing.screen)) {
            Spacer(Modifier.height(YamuSpacing.x6))
            card?.let {
                PerfCardArt(it, art = arts[it.userCardId])
                Spacer(Modifier.height(YamuSpacing.x6))
            }
            when {
                s.loading -> repeat(3) {
                    SkeletonBlock(height = 48.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                }
                d == null -> ErrorState(s.error ?: "실적을 불러오지 못했어요", onRetry = viewModel::load)
                d.noTarget -> Text("이 카드는 실적 조건이 없어요 · 쓴 만큼 혜택을 받아요", style = YamuTheme.typography.bodyL, color = c.text2)
                else -> {
                    // 서버 상태 AT_RISK면 오른쪽에 '미달 예상'(목록 R-01과 같은 태그)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.month?.let { "${it}월 인정 실적" } ?: "이번 달 인정 실적", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.weight(1f))
                        if (d.atRisk) StatusTag(AT_RISK_LABEL, tone = TagTone.Warning, guide = true)
                    }
                    Spacer(Modifier.height(YamuSpacing.x2))
                    // 큰 숫자와 '/ 400,000원'은 글자 바닥을 맞춘다(프로토타입 R-02 .big baseline)
                    Row {
                        AmountText(d.recognized, Modifier.alignByBaseline())
                        Spacer(Modifier.width(6.dp))
                        d.target?.let { Text("/ ${it.toWon()}원", style = YamuTheme.typography.bodyL, color = c.text3, modifier = Modifier.alignByBaseline()) }
                    }
                    d.target?.let {
                        Spacer(Modifier.height(16.dp))
                        PerformanceGauge(d.recognized, it, showCaption = false, height = 4.dp)
                        Spacer(Modifier.height(10.dp))
                        d.headline()?.let { line ->
                            Text(line, style = YamuTheme.typography.titleM, color = if (d.achieved) c.success else c.primary)
                        }
                    }
                    Spacer(Modifier.height(20.dp))   // 덩어리 아래 20(.section) — 다음 덩어리 위 24는 SectionBlock이
                }
            }
          }
            // 다음 달 혜택 구간 — 화면 폭 선 + 키–값(달성 구간엔 초록 태그). 키는 '30만 · 정수기 할인'(프로토타입 R-02)
            // 금액은 실적이 그 구간일 때 한 달에 받을 수 있는 최대치(아래 구간까지 누적, BE !227)라 달성 여부와 상관없이 '최대 n원'
            if (d != null && !d.noTarget && d.tiers.isNotEmpty()) {
                SectionBlock(title = d.month?.let { "${it % 12 + 1}월 혜택" } ?: "다음 달 혜택") {
                    d.tiers.forEach { t ->
                        KeyValueRow(
                            listOfNotNull(t.label.removeSuffix(" 이상").removeSuffix(" 원"), t.benefit).joinToString(" · "),
                            t.amountText,
                            keyTag = if (t.achieved) ({ StatusTag("달성", tone = TagTone.Success) }) else null,
                        )
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/**
 * 게이지 아래 한 줄 — 서버 다음 달 문구('150,000원 더 쓰면 다음 달 50만원 이상 구간 혜택을 받아요')가 있으면 그대로,
 * 없으면 앱이 만든 남은 금액 문구. 실적 기준이 없으면 null
 */
internal fun PerfDetail.headline(): String? {
    val target = target ?: return null
    nextMonthMessage?.let { return it }
    val goal = targetLabel?.removeSuffix(" 이상") ?: target.toManWon()
    return if (achieved) "$goal 기준을 채웠어요" else "${goal}까지 ${remaining.toWon()}원 남았어요"
}

/**
 * 실적 화면 머리의 카드 그림 — 어떤 카드인지 실물 카드로(R-02 · R-03). 폭은 최대 280dp, 탭을 바꾸면 같이 바뀐다.
 * name은 카드사 이름이라 그림은 [art](카드 목록)의 상품명으로, 모르면 nickname(별명이 없으면 상품명)으로 찾는다.
 * 끝자리는 실적 응답에 없어 빼고 보인다. 정지 · 만료 카드는 흐리게
 */
@Composable
internal fun PerfCardArt(card: CardPerf, modifier: Modifier = Modifier, art: CardArtRef? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Crossfade(card, label = "perfCard") { c ->
            BrandCardArt(
                c.brand,
                c.nickname ?: c.name,
                last4 = null,
                modifier = Modifier.widthIn(max = 280.dp).alpha(if (art?.dimmed == true) DIMMED_CARD_ALPHA else 1f),
                productName = art?.productName ?: c.nickname ?: c.name,
            )
        }
    }
}
