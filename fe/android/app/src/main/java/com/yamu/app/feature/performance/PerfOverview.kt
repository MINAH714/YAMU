package com.yamu.app.feature.performance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PerformanceGauge
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.staleLine
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.my.TabBell
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.data.manLabel
import com.yamu.app.feature.performance.data.monthOf
import com.yamu.app.feature.performance.data.previousYearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 판정 태그 색 — 달성 추천 파랑 · 달성 초록 · 추가 소비 비추천 빨강(프로토타입 .guide). KEEP_NATURAL은 태그를 그리지 않는다(label null) */
fun PerfGuide.tone(): TagTone = when (this) {
    PerfGuide.ACHIEVE_RECOMMENDED -> TagTone.Primary
    PerfGuide.KEEP_NATURAL -> TagTone.Neutral
    PerfGuide.ALREADY_ACHIEVED -> TagTone.Success
    PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> TagTone.Error
    PerfGuide.INSUFFICIENT_DATA -> TagTone.Neutral
}

data class PerfOverviewState(
    val loading: Boolean = true,
    val overview: PerfOverview? = null,
    val error: String? = null,
    /** 전월 실적 — '전월 실적'을 처음 펼칠 때 받는다(S15P31E103-614) */
    val lastMonth: PerfOverview? = null,
    val lastMonthFailed: Boolean = false,
)

/** R-01 실적 관리 — 탭에 돌아올 때마다 새로 받는다(결제 뒤 값 · 판정이 바뀌어야 하므로) */
@HiltViewModel
class PerfOverviewViewModel @Inject constructor(private val perf: PerformanceRepository, link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(PerfOverviewState(overview = perf.cachedOverview(), loading = perf.cachedOverview() == null))
    val state: StateFlow<PerfOverviewState> = _state.asStateFlow()

    /** 카드 그림 — 실적 응답엔 상품명이 없어 카드 목록에서 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    private var lastMonthJob: Job? = null

    fun refresh() {
        artLoader.ensure(_state.value.overview?.all.orEmpty().map { it.userCardId })
        viewModelScope.launch {
            _state.update { it.copy(loading = it.overview == null, error = null) }
            when (val r = perf.overview()) {
                is ApiResult.Success -> {
                    // 달이 바뀌었으면 받아 둔 전월 실적은 버린다(펼쳐 있으면 화면이 새 전월을 다시 부른다)
                    val previous = previousYearMonth(r.data.yearMonth)
                    _state.update { s -> s.copy(loading = false, overview = r.data, error = null, lastMonth = s.lastMonth?.takeIf { it.yearMonth == previous }) }
                    artLoader.ensure(r.data.all.map { it.userCardId })
                    // 이미 본 전월 실적도 조용히 다시 — 지난달 결제를 취소하면 지난달 실적이 줄어든다
                    if (_state.value.lastMonth != null) loadLastMonth(quiet = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }   // 마지막 값은 그대로
            }
        }
    }

    /**
     * 전월 실적 — 서버가 준 이번 달의 한 달 전(기기 시계 아님). 받았거나 받는 중이면 다시 부르지 않는다.
     * [quiet]면 받아 둔 값을 두고 다시 받는다 — 실패해도 오류를 보이지 않고 받아 둔 값 그대로
     */
    fun loadLastMonth(quiet: Boolean = false) {
        if (lastMonthJob?.isActive == true) return
        if (!quiet && _state.value.lastMonth != null) return
        val month = previousYearMonth(_state.value.overview?.yearMonth) ?: return
        if (!quiet) _state.update { it.copy(lastMonthFailed = false) }
        lastMonthJob = viewModelScope.launch {
            when (val r = perf.overviewOf(month)) {
                // 받는 사이 달이 바뀌었으면 버린다
                is ApiResult.Success -> _state.update { s -> if (previousYearMonth(s.overview?.yearMonth) == month) s.copy(lastMonth = r.data, lastMonthFailed = false) else s }
                is ApiResult.Failure -> if (!quiet) _state.update { it.copy(lastMonthFailed = true) }
            }
        }
    }
}

@Composable
fun PerfOverviewRoute(
    onAnalysis: (userCardId: Long) -> Unit,
    onCardDetail: (userCardId: Long) -> Unit,
    onAddCard: () -> Unit,
    onBenefitLimits: () -> Unit = {},
    onNotifications: () -> Unit = {},
    viewModel: PerfOverviewViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    var lastMonthOpen by rememberSaveable { mutableStateOf(false) }
    // 펼쳤을 때 받는다 — 이번 달 목록(yearMonth)이 와야 전월을 안다
    LaunchedEffect(lastMonthOpen, s.overview?.yearMonth) { if (lastMonthOpen) viewModel.loadLastMonth() }
    val c = YamuTheme.colors
    TabScreen(title = "실적 관리", actions = { TabBell(onNotifications) }) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val o = s.overview
            when {
                o == null && s.loading -> YamuCard { repeat(3) { SkeletonBlock(height = 56.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
                o == null -> YamuCard { ErrorState(s.error ?: "실적을 불러오지 못했어요", onRetry = viewModel::refresh) }
                o.all.isEmpty() -> YamuCard { EmptyState("연결한 카드가 없어요", actionText = "카드 추가", onAction = onAddCard) }
                else -> {
                    // 사실만 — 설명 문장 · 마감 D-n 없이 카드마다 이번 달 실적 / 기준 · 게이지. 한 목록으로, 채운 비율이 높은 카드부터
                    YamuCard {
                        Text(monthOf(o.yearMonth)?.let { "${it}월 실적" } ?: "이번 달 실적", style = YamuTheme.typography.cardTitle, color = c.text1)
                        // 새로고침이 실패해도 마지막 값은 그대로 — 이유와 언제 값인지(홈과 같은 한 줄)
                        s.error?.let { Text(staleLine(it, o.syncedAtIso), style = YamuTheme.typography.bodyS, color = c.warning) }
                        Spacer(Modifier.height(4.dp))
                        val cards = o.all.byFill()
                        cards.forEachIndexed { i, card ->
                            val open: (() -> Unit)? = when (card.rowTarget) {
                                PerfRowTarget.ANALYSIS -> ({ onAnalysis(card.userCardId) })
                                PerfRowTarget.CARD_DETAIL -> ({ onCardDetail(card.userCardId) })
                                null -> null
                            }
                            PerfRow(card, arts[card.userCardId], last = i == cards.lastIndex, onClick = open)
                        }
                    }
                    LastMonthCard(
                        open = lastMonthOpen,
                        onToggle = { lastMonthOpen = !lastMonthOpen },
                        last = s.lastMonth,
                        failed = s.lastMonthFailed,
                        onRetry = { viewModel.loadLastMonth() },
                        arts = arts,
                    )
                    // 혜택 한도(R-06)는 10/26 간략화 때 알림 · 홈에서만 열었는데, 실적을 보러 온 탭에서 찾지 못해 여기에도 둔다(docs/fe/ui-ux-rationale.md V-01).
                    // 소비분석 아래 혜택 · 결제 내역 줄과 같은 링크 카드(.card.tight 16)
                    YamuCard(contentPadding = TIGHT_CARD) {
                        ListRow(title = "남은 혜택 한도", chevron = true, onClick = onBenefitLimits)
                    }
                }
            }
        }
    }
}

/** 링크 줄 하나짜리 카드 안쪽 여백(.card.tight 16) */
private val TIGHT_CARD = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)

/** 채운 비율(실적 ÷ 기준) — 실적 조건이 없으면 null(목록 맨 아래) */
internal fun CardPerf.fillRatio(): Double? = target?.takeIf { it > 0 }?.let { recognized.toDouble() / it }

/** 목록 차례 — 채운 비율이 높은 카드부터, 같으면 실적이 큰 카드부터. 실적 조건 없는 카드는 맨 아래 */
internal fun List<CardPerf>.byFill(): List<CardPerf> =
    sortedWith(compareByDescending<CardPerf> { it.fillRatio() ?: -1.0 }.thenByDescending { it.recognized })

/** 줄을 누르면 가는 곳 */
internal enum class PerfRowTarget { ANALYSIS, CARD_DETAIL }

/**
 * 기준 전 카드는 유불리 분석(R-03), 달성 카드는 카드 실적 상세(R-02, 홈 내 카드에서 여는 곳과 같은 화면 · S15P31E103-451).
 * 실적 조건 없는 카드는 R-02에 보일 실적이 없어 누를 수 없다(null)
 */
internal val CardPerf.rowTarget: PerfRowTarget?
    get() = when {
        noTarget -> null
        pending -> PerfRowTarget.ANALYSIS
        else -> PerfRowTarget.CARD_DETAIL
    }

/** 오른쪽 상태 — 달성 · 여러 단계 중 넘긴 단계까지 '20만원 달성' · 미달성 · 실적 조건 없음 */
internal val CardPerf.stateLabel: String
    get() = when {
        noTarget -> NO_TARGET_LABEL
        achieved -> "달성"
        else -> reachedThreshold?.let { "${manLabel(it)} 달성" } ?: "미달성"
    }

/** 게이지 아래 한 줄 — '320,000 / 300,000원'. 실적 조건이 없으면 쓴 돈만 */
internal fun CardPerf.amountLine(thisMonth: Boolean): String = when {
    target != null -> "${recognized.toWon()} / ${target.toWon()}원"
    thisMonth -> "이번 달 ${recognized.toWon()}원 썼어요"
    else -> "${recognized.toWon()}원 썼어요"
}

/** 줄 읽기 이름 — 보이는 차례대로 '카드 이름 320,000 / 300,000원 달성'. 전월에 실적 기록이 없는 카드는 '카드 이름 기록 없음' */
internal fun CardPerf.rowLabel(thisMonth: Boolean): String =
    if (!thisMonth && noRecord) "$name $NO_RECORD_LABEL" else "$name ${amountLine(thisMonth)} $stateLabel"

private const val NO_TARGET_LABEL = "실적 조건 없음"
private const val NO_RECORD_LABEL = "기록 없음"

/**
 * 카드 한 줄 — 위: 그림 · 이름 · 상태 태그 · › / 가운데: 게이지 / 아래: '320,000 / 400,000원'.
 * 여러 단계 카드(노리2 20만~80만)는 막대 대신 계단 게이지 — 단계마다 같은 폭 · 넘긴 단계는 점을 채운다.
 * 달성 카드의 막대는 차오르다 기준에 닿을 때 초록으로 바뀐다(PerformanceGauge가 보이는 값으로 색을 고름).
 * 판정 · 혜택 설명은 목록에 두지 않는다(카드를 눌러 들어간 화면에서)
 */
@Composable
private fun PerfRow(card: CardPerf, art: CardArtRef?, last: Boolean, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val target = card.target
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = card.rowLabel(thisMonth = true) }
            .padding(top = 14.dp, bottom = if (last) 6.dp else 14.dp),   // 카드 마지막 줄은 아래 6(프로토타입 .cr:last-child)
    ) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {   // 윗줄 28(.cr .l1)
            PerfThumb(card, art)
            Text(card.name, style = t.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            PerfStateTag(card)
            if (onClick != null) Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
        }
        // 게이지는 화면에 나올 때 0에서 차오르고, 결제 뒤 값이 바뀌면 보이던 값에서 새 값으로 간다(animatedFill). 금액 글자는 바로 새 값
        if (card.thresholds.size > 1) TierStepper(card.recognized, card.thresholds, Modifier.padding(top = 10.dp))
        else target?.let {
            val shown = animatedFill(card.recognized.toFloat())
            PerformanceGauge(if (shown == card.recognized.toFloat()) card.recognized else shown.toLong(), it, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), showCaption = false)
        }
        Text(card.amountLine(thisMonth = true), style = t.bodyM, color = c.text2, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 상태 태그 — 달성 · 'n만원 달성'은 초록, 미달성은 회색, 실적 조건 없음은 태그 없이 글자 */
@Composable
private fun PerfStateTag(card: CardPerf) {
    when {
        card.noTarget -> Text(NO_TARGET_LABEL, style = YamuTheme.typography.bodyM, color = YamuTheme.colors.text3)
        card.achieved || card.reachedThreshold != null -> StatusTag(card.stateLabel, tone = TagTone.Success, guide = true)
        else -> StatusTag(card.stateLabel, tone = TagTone.Neutral, guide = true)
    }
}

/**
 * 전월 실적 — 이번 달 카드 아래 접힌 카드. 누르면 펼쳐 카드마다 그림 · 이름 · 실적 / 기준 · 달성.
 * 처음 펼칠 때 #65를 전월로 받고(받는 동안 뼈대), 실패하면 다시 시도
 */
@Composable
private fun LastMonthCard(
    open: Boolean,
    onToggle: () -> Unit,
    last: PerfOverview?,
    failed: Boolean,
    onRetry: () -> Unit,
    arts: Map<Long, CardArtRef>,
) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = TIGHT_CARD) {
        ListRow(
            title = "전월 실적",
            modifier = Modifier.semantics { stateDescription = if (open) "펼침" else "접힘" },
            trailing = { Icon(if (open) YamuIcons.ExpandLess else YamuIcons.ExpandMore, contentDescription = null, tint = c.text3) },
            onClick = onToggle,
        )
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 8.dp)) {
                when {
                    last != null -> last.all.byFill().forEach { LastMonthRow(it, arts[it.userCardId]) }
                    failed -> ErrorState("전월 실적을 불러오지 못했어요", onRetry = onRetry)
                    else -> repeat(3) {
                        SkeletonBlock(height = 40.dp)
                        Spacer(Modifier.height(YamuSpacing.x2))
                    }
                }
            }
        }
    }
}

/** 전월 실적 한 줄 — 그림 · 이름 / 실적 / 기준(기록이 없으면 '기록 없음') · 오른쪽 상태 */
@Composable
private fun LastMonthRow(card: CardPerf, art: CardArtRef?) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = card.rowLabel(thisMonth = false) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PerfThumb(card, art)
        Column(Modifier.weight(1f)) {
            Text(card.name, style = t.bodyL, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (card.noRecord) NO_RECORD_LABEL else card.amountLine(thisMonth = false), style = t.bodyM, color = c.text3)
        }
        if (!card.noRecord) PerfStateTag(card)
    }
}

/**
 * 줄 앞 카드 그림 44dp(높이 28 — 윗줄 높이 그대로). 상품명은 카드 목록에서 찾고, 모르면 nickname(별명이 없으면 상품명)으로.
 * 그림이 없으면 같은 크기의 카드사 색 카드
 */
@Composable
private fun PerfThumb(card: CardPerf, art: CardArtRef?) =
    CardListThumb(card.brand, art?.productName ?: card.nickname ?: card.name, width = 44.dp, dimmed = art?.dimmed == true)
