package com.yamu.app.feature.performance

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.PerformanceGauge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.staleLine
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.performance.data.AT_RISK_LABEL
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.data.monthOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 판정 태그 색 — 달성 추천 파랑 · 자연 소비 유지 회색 · 달성 초록 · 추가 소비 비추천 빨강(프로토타입 .guide) */
fun PerfGuide.tone(): TagTone = when (this) {
    PerfGuide.ACHIEVE_RECOMMENDED -> TagTone.Primary
    PerfGuide.KEEP_NATURAL -> TagTone.Neutral
    PerfGuide.ALREADY_ACHIEVED -> TagTone.Success
    PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> TagTone.Error
    PerfGuide.INSUFFICIENT_DATA -> TagTone.Neutral
}

data class PerfOverviewState(val loading: Boolean = true, val overview: PerfOverview? = null, val error: String? = null)

/** R-01 실적 관리 — 탭에 돌아올 때마다 새로 받는다(결제 뒤 값 · 판정이 바뀌어야 하므로) */
@HiltViewModel
class PerfOverviewViewModel @Inject constructor(private val perf: PerformanceRepository, link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(PerfOverviewState(overview = perf.cachedOverview(), loading = perf.cachedOverview() == null))
    val state: StateFlow<PerfOverviewState> = _state.asStateFlow()

    /** 카드 그림 — 실적 응답엔 상품명이 없어 카드 목록에서 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    fun refresh() {
        artLoader.ensure(_state.value.overview?.all.orEmpty().map { it.userCardId })
        viewModelScope.launch {
            _state.update { it.copy(loading = it.overview == null, error = null) }
            when (val r = perf.overview()) {
                is ApiResult.Success -> {
                    _state.update { PerfOverviewState(loading = false, overview = r.data) }
                    artLoader.ensure(r.data.all.map { it.userCardId })
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }   // 마지막 값은 그대로
            }
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun PerfOverviewRoute(
    onAnalysis: (userCardId: Long) -> Unit,
    onCardDetail: (userCardId: Long) -> Unit,
    onAddCard: () -> Unit,
    onBenefitLimits: () -> Unit = {},
    viewModel: PerfOverviewViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    TabScreen(title = "실적 관리") { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val o = s.overview
            when {
                o == null && s.loading -> YamuCard { repeat(3) { SkeletonBlock(height = 56.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
                o == null -> YamuCard { ErrorState(s.error ?: "실적을 불러오지 못했어요", onRetry = viewModel::refresh) }
                o.all.isEmpty() -> YamuCard { EmptyState("연결한 카드가 없어요", actionText = "카드 추가", onAction = onAddCard) }
                else -> {
                    YamuCard {
                        val month = monthOf(o.yearMonth)?.let { "${it}월 실적" } ?: "이번 달 실적"
                        val reflected = o.reflectedAtIso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST).format(HM) }.getOrNull() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listOfNotNull(month, o.daysLeft?.let { "마감 D-$it" }).joinToString(" · "), style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.weight(1f))
                            reflected?.let { StatusTag("$it 결제 반영", tone = TagTone.Primary) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (o.pending.isEmpty()) "모든 카드가 기준을 채웠어요" else "아직 기준 전 카드가 ${o.pending.size}장이에요",
                            style = YamuTheme.typography.headlineS,
                            color = c.text1,
                        )
                        // 새로고침이 실패해도 마지막 값은 그대로 — 이유와 언제 값인지(홈과 같은 한 줄)
                        s.error?.let { Text(staleLine(it, o.syncedAtIso), style = YamuTheme.typography.bodyS, color = c.warning) }
                        Spacer(Modifier.height(YamuSpacing.x2))
                        o.pending.forEachIndexed { i, card -> PendingRow(card, arts[card.userCardId], last = i == o.pending.lastIndex, onClick = { onAnalysis(card.userCardId) }) }
                    }
                    if (o.others.isNotEmpty()) {
                        YamuCard {
                            Text("그 밖의 카드", style = YamuTheme.typography.cardTitle, color = c.text1)
                            o.others.forEachIndexed { i, card ->
                                OtherRow(card, arts[card.userCardId], last = i == o.others.lastIndex, onClick = if (card.opensCardDetail) ({ onCardDetail(card.userCardId) }) else null)
                            }
                        }
                    }
                    // 혜택 한도(R-06)는 10/26 간략화 때 알림 · 홈에서만 열었는데, 실적을 보러 온 탭에서 찾지 못해 여기에도 둔다(docs/fe/ui-ux-rationale.md V-01).
                    // 소비분석 아래 혜택 · 결제 내역 줄과 같은 링크 카드(.card.tight 16)
                    YamuCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                        ListRow(title = "남은 혜택 한도", chevron = true, onClick = onBenefitLimits)
                    }
                }
            }
        }
    }
}

/**
 * 기준 전 카드 — 위: 칩 · 이름 · '+15,000' · 태그 · › / 아래: 게이지 + 'n원 남음'(프로토타입 R-01).
 * 태그는 하나만 — 판정이 있으면 판정, 없으면 서버 상태 AT_RISK일 때 '미달 예상'
 */
@Composable
private fun PendingRow(card: CardPerf, art: CardArtRef?, last: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${card.name} ${card.tagLabel ?: ""} ${card.remaining.toWon()}원 남음" }
            .padding(top = 14.dp, bottom = if (last) 6.dp else 14.dp),   // 카드 마지막 줄은 아래 6(프로토타입 .cr:last-child)
    ) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {   // 윗줄 28(.cr .l1)
            PerfThumb(card, art)
            Text(card.name, style = YamuTheme.typography.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            card.recentChange?.let { Text(it.toWon(withPlusSign = true), style = YamuTheme.typography.labelL.copy(fontWeight = FontWeight.Bold), color = c.primary, maxLines = 1) }
            when {
                card.guide != null -> StatusTag(card.guide.label, tone = card.guide.tone(), guide = true)
                card.atRisk -> StatusTag(AT_RISK_LABEL, tone = TagTone.Warning, guide = true)
            }
            Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
        }
        GaugeLine(card)
    }
}

/**
 * 그 밖의 카드 — 달성(초록 태그 · 게이지) · 실적 조건 없음(오른쪽 글자).
 * 달성 카드는 누르면 카드 실적 상세(R-02, 홈 내 카드에서 여는 곳과 같은 화면) — 기준 전 카드처럼 ›를 붙인다(S15P31E103-451).
 * [onClick]이 null이면(실적 조건 없음) 누를 수 없다
 */
@Composable
private fun OtherRow(card: CardPerf, art: CardArtRef?, last: Boolean, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = card.otherRowLabel() }
            .padding(top = 14.dp, bottom = if (last) 6.dp else 14.dp),
    ) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {   // 윗줄 28(.cr .l1)
            PerfThumb(card, art)
            Text(card.name, style = YamuTheme.typography.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            card.recentChange?.let { Text(it.toWon(withPlusSign = true), style = YamuTheme.typography.labelL.copy(fontWeight = FontWeight.Bold), color = c.primary) }
            if (card.noTarget) Text(NO_TARGET_LABEL, style = YamuTheme.typography.bodyM, color = c.text3)
            else StatusTag(ACHIEVED_LABEL, tone = TagTone.Success, guide = true)
            if (onClick != null) Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
        }
        if (!card.noTarget) GaugeLine(card)
    }
}

private const val NO_TARGET_LABEL = "실적 조건 없음"
private const val ACHIEVED_LABEL = "달성"

/** 그 밖의 카드 줄을 눌러 R-02를 여나 — 실적 조건 없는 카드는 R-02에 보일 실적이 없어 열지 않는다(홈 내 카드와 같게) */
internal val CardPerf.opensCardDetail: Boolean get() = !noTarget

/** 그 밖의 카드 줄의 읽기 이름 — 보이는 차례대로 '카드 이름 +15,000원 달성' · '카드 이름 실적 조건 없음' */
internal fun CardPerf.otherRowLabel(): String =
    listOfNotNull(name, recentChange?.let { "${it.toWon(withPlusSign = true)}원" }, if (noTarget) NO_TARGET_LABEL else ACHIEVED_LABEL).joinToString(" ")

/**
 * 줄 앞 카드 그림 44dp(높이 28 — 윗줄 높이 그대로). 상품명은 카드 목록에서 찾고, 모르면 nickname(별명이 없으면 상품명)으로.
 * 그림이 없으면 같은 크기의 카드사 색 카드
 */
@Composable
private fun PerfThumb(card: CardPerf, art: CardArtRef?) =
    CardListThumb(card.brand, art?.productName ?: card.nickname ?: card.name, width = 44.dp, dimmed = art?.dimmed == true)

/** 6dp 게이지 + 'n원 남음'(방금 결제분은 연한 색 · 파란 글자) */
@Composable
private fun GaugeLine(card: CardPerf) {
    val c = YamuTheme.colors
    val target = card.target ?: return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PerformanceGauge(card.recognized, target, modifier = Modifier.weight(1f), showCaption = false, recent = card.recentChange)
        Text(
            "${card.remaining.toWon()}원 남음",
            style = YamuTheme.typography.labelL,
            color = if (card.recentChange != null) c.primary else c.text2,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 88.dp),
        )
    }
}
