package com.yamu.app.feature.performance

import androidx.compose.foundation.layout.offset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.yamu.app.core.designsystem.component.ExpandToggle
import com.yamu.app.core.format.toManWon
import com.yamu.app.feature.performance.data.MovableItem
import com.yamu.app.core.designsystem.component.QuietLink
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.SectionBlock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.MoreDetails
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuTabs
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.clipName
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.LastMonthPerf
import com.yamu.app.feature.performance.data.MonthRecognized
import com.yamu.app.feature.performance.data.lastMonthPerf
import com.yamu.app.feature.performance.data.previousYearMonth
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfDecision
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.navigation.PerfAnalysis as PerfAnalysisRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PerfAnalysisState(
    val tabs: List<CardPerf> = emptyList(),
    val selectedId: Long,
    val loading: Boolean = true,
    val analysis: PerfAnalysis? = null,
    val noData: Boolean = false,                 // 422 — 판단 보류(빨갛지 않게)
    /** 서버에 분석 API가 아직 없다(404 API_NOT_FOUND · 405, BE 375 전) — 막다른 길 대신 R-02로 이어 준다(S15P31E103-432) */
    val notReady: Boolean = false,
    val error: String? = null,
    val acting: Boolean = false,
    val message: String? = null,                 // 스낵바 한 번
    /** 지난달 카드별 인정 실적(기록 있는 카드만) — 못 받았으면 null이라 지난달 줄을 숨긴다(S15P31E103-602) */
    val lastMonth: MonthRecognized? = null,
    /** 카드별 실적 구간 기준 금액(R-02 #66) — 카드 id로 둬서 늦게 온 응답이 다른 카드에 붙지 않는다 */
    val thresholds: Map<Long, List<Long>> = emptyMap(),
) {
    val selected: CardPerf? get() = tabs.firstOrNull { it.userCardId == selectedId }

    /** 카드 그림 아래 지난달 한 줄 — 지난달 값이나 구간을 아직 · 못 받았으면 null */
    fun lastMonthOf(userCardId: Long): LastMonthPerf? = lastMonth?.let { m ->
        thresholds[userCardId]?.let { lastMonthPerf(m.yearMonth, m.byCard[userCardId], it) }
    }
}

/** R-03 유불리 분석 — 카드 탭(기준 전 카드) · 판정은 서버(#18) 그대로 · 판단 기록(#35) */
@HiltViewModel
class PerfAnalysisViewModel internal constructor(
    initialCardId: Long,
    private val perf: PerformanceRepository,
) : ViewModel() {
    // 경로 값만 꺼내 넘긴다 — 단위 테스트가 SavedStateHandle.toRoute(안드로이드 Bundle) 없이 만들 수 있게(BuyConditionsViewModel과 같은 방식)
    @Inject constructor(savedStateHandle: SavedStateHandle, perf: PerformanceRepository) :
        this(savedStateHandle.toRoute<PerfAnalysisRoute>().userCardId, perf)

    private val _state = MutableStateFlow(PerfAnalysisState(selectedId = initialCardId))
    val state: StateFlow<PerfAnalysisState> = _state.asStateFlow()
    private var analysisRequest = 0L
    /** 구간을 받는 중인 카드 — init보다 먼저 만들어 둔다(init의 코루틴이 바로 부를 수 있어서) */
    private val thresholdRequests = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            val overview = perf.cachedOverview() ?: (perf.overview() as? ApiResult.Success)?.data
            val pending = overview?.pending.orEmpty()
            val selected = overview?.all?.firstOrNull { it.userCardId == _state.value.selectedId }
            // 기준 전 카드가 탭. 고른 카드가 이미 달성이면 그 카드 하나만
            val tabs = if (selected != null && selected !in pending) listOf(selected) else pending
            _state.update { it.copy(tabs = tabs) }
            // 지난달은 기기 시계가 아니라 서버가 준 이번 달에서 한 달 전(S15P31E103-602). 못 받으면 줄만 숨기고 분석은 그대로
            previousYearMonth(overview?.yearMonth)?.let { month ->
                (perf.recognizedIn(month) as? ApiResult.Success)?.data?.let { last ->
                    _state.update { it.copy(lastMonth = last) }
                    loadThresholds(_state.value.selectedId)
                }
            }
        }
        load()
    }

    fun select(userCardId: Long) {
        if (userCardId == _state.value.selectedId) return
        _state.update { it.copy(selectedId = userCardId, message = null) }
        load()
        loadThresholds(userCardId)
    }

    /** 지난달 기록이 있는 카드만 구간을 받는다 — 한 번 받은 카드 · 받는 중인 카드는 다시 부르지 않는다. 실패하면 줄만 숨긴다 */
    private fun loadThresholds(userCardId: Long) {
        val s = _state.value
        if (s.lastMonth?.byCard?.containsKey(userCardId) != true || userCardId in s.thresholds || !thresholdRequests.add(userCardId)) return
        viewModelScope.launch {
            val tiers = (perf.detail(userCardId) as? ApiResult.Success)?.data?.tiers
            thresholdRequests.remove(userCardId)
            tiers?.mapNotNull { it.threshold }?.let { t -> _state.update { it.copy(thresholds = it.thresholds + (userCardId to t)) } }
        }
    }

    fun load() {
        val id = _state.value.selectedId
        val request = ++analysisRequest
        _state.update { it.copy(loading = true, error = null, noData = false, notReady = false, analysis = null) }
        viewModelScope.launch {
            when (val r = perf.analysis(id)) {
                is ApiResult.Success -> _state.update { if (analysisRequest == request && it.selectedId == id) it.copy(loading = false, analysis = r.data) else it }
                is ApiResult.Failure -> _state.update {
                    if (analysisRequest != request || it.selectedId != id) it
                    else if (r.error is ApiError.DataInsufficient) it.copy(loading = false, noData = true)
                    else if (r.error is ApiError.NotSupported) it.copy(loading = false, notReady = true)
                    else it.copy(loading = false, error = r.error.userMessage())
                }
            }
        }
    }

    /** [채우지 않을게요] · [다시 판단하기](null) */
    fun decide(decision: PerfDecision?) {
        val a = _state.value.analysis ?: return
        if (_state.value.acting) return
        val request = analysisRequest
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            when (val r = perf.decide(a.userCardId, decision, a.version)) {
                is ApiResult.Success -> {
                    finishAction(a.userCardId, request) { it.copy(analysis = a.copy(decision = decision), message = if (decision == null) "다시 판단할 수 있어요 · 알림도 다시 받아요" else "이번 달 판단을 기록했어요") }
                }
                is ApiResult.Failure -> finishAction(a.userCardId, request) { it.copy(message = r.error.userMessage()) }
            }
        }
    }

    /** [통신비 우리카드로 옮기기] — 예정 지출의 결제 카드를 바꾸고(#8) '채우기'로 기록(#35) → 다시 분석 */
    fun moveAndFill() {
        val a = _state.value.analysis ?: return
        val item = a.movable ?: return
        val card = _state.value.selected?.name ?: "이 카드"
        if (_state.value.acting) return
        val request = analysisRequest
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            val moved = perf.moveCashflow(item.cashflowItemId, a.userCardId)
            if (moved is ApiResult.Failure) {
                val message = if (moved.error.isOutcomeUnknown) "${item.title} 결제 카드 변경 결과를 확인하지 못했어요 · 예정 지출에서 확인해 주세요" else moved.error.userMessage()
                finishAction(a.userCardId, request, refresh = moved.error.isOutcomeUnknown) { it.copy(message = message) }
                return@launch
            }
            val recorded = perf.decide(a.userCardId, PerfDecision.FILL, a.version)
            val deadline = parseDate(item.deadlineIso)?.toMonthDay()?.let { "${it}까지 " }.orEmpty()
            // 앱의 예정 지출만 바뀐 것이므로 판단 기록 결과와 관계없이 결제처 변경 안내를 남긴다.
            val movedMessage = "${item.title} 결제 카드를 $card${card.directionParticle()} 바꿔 뒀어요 · ${deadline}${item.title}${item.title.objectParticle()} 내는 곳에서도 카드를 바꿔 주세요"
            val message = when (recorded) {
                is ApiResult.Success -> movedMessage
                is ApiResult.Failure -> if (recorded.error.isOutcomeUnknown) {
                    "$movedMessage · 판단 기록 결과는 확인하지 못했어요 · 다시 불러온 분석을 확인해 주세요"
                } else {
                    "$movedMessage · 판단은 기록하지 못했어요 · ${recorded.error.userMessage()}"
                }
            }
            finishAction(a.userCardId, request, refresh = true, completionMessage = message) { it }
        }
    }

    /** 쓰기는 끝까지 기다리되, 그사이 바뀐 카드/요청의 본문은 덮지 않는다. 이동 완료 안내는 선택 카드와 독립적이다. */
    private fun finishAction(id: Long, request: Long, refresh: Boolean = false, completionMessage: String? = null, update: (PerfAnalysisState) -> PerfAnalysisState) {
        _state.update {
            val idle = it.copy(acting = false, message = completionMessage ?: it.message)
            if (it.selectedId == id && analysisRequest == request) update(idle) else idle
        }
        // 지출 이동은 출발 카드의 분석도 바꾼다. 다른 카드를 보고 있어도 현재 카드를 다시 받는다.
        // 일반 판단 저장은 A→B→A로 돌아왔을 때만 다시 받는다.
        if (refresh || (_state.value.selectedId == id && analysisRequest != request)) load()
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun PerfAnalysisRoute(onBack: () -> Unit, onCardDetail: (userCardId: Long) -> Unit, viewModel: PerfAnalysisViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    val cardName = s.selected?.name ?: "카드"
    FlowScreen(
        title = "더 써야 할까요?",
        onBack = onBack,
        bottomBar = {
            val a = s.analysis
            if (a != null) ActionButtons(a, cardName, s.acting, viewModel::decide, viewModel::moveAndFill, onDetail = { onCardDetail(a.userCardId) })
            else if (s.noData || s.notReady) BottomCta { PrimaryButton("${cardName.clipName()} 자세히 보기", onClick = { onCardDetail(s.selectedId) }) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (s.tabs.isEmpty()) {
                // 실적관리 목록을 아직 · 못 받았을 때 — 탭 · 카드 그림 없이 분석만
                AnalysisPage(card = null, current = s, cardName, lastMonth = null, onRetry = viewModel::load, onCardDetail = onCardDetail)
            } else {
                // 카드를 옆으로 밀어 넘기면 탭이 따라가고, 탭을 누르면 카드가 미끄러진다.
                // 분석은 멈춘 쪽(settledPage) 카드만 불러온다 — 넘기는 중인 옆 카드는 그림 + 뼈대만
                val pager = rememberPagerState(initialPage = s.tabs.indexOfFirst { it.userCardId == s.selectedId }.coerceAtLeast(0)) { s.tabs.size }
                val scope = rememberCoroutineScope()
                val tabs by rememberUpdatedState(s.tabs)
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.collect { page -> tabs.getOrNull(page)?.let { viewModel.select(it.userCardId) } }
                }
                if (s.tabs.size > 1) {
                    YamuTabs(
                        tabs = s.tabs.map { it.name },
                        selectedIndex = pager.currentPage,
                        onSelect = { i -> scope.launch { pager.animateScrollToPage(i) } },
                    )
                }
                HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                    val card = s.tabs[page]
                    AnalysisPage(card, current = s.takeIf { card.userCardId == it.selectedId }, cardName, lastMonth = s.lastMonthOf(card.userCardId), onRetry = viewModel::load, onCardDetail = onCardDetail)
                }
            }
        }
    }
}

/** 카드 한 장의 분석 쪽 — 카드 그림 + 지난달 한 줄 + 판정. [current]가 null이면(넘기는 중인 옆 카드) 뼈대만 */
@Composable
private fun AnalysisPage(card: CardPerf?, current: PerfAnalysisState?, cardName: String, lastMonth: LastMonthPerf?, onRetry: () -> Unit, onCardDetail: (Long) -> Unit) {
    val c = YamuTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 어떤 카드의 분석인지 — 카드 그림을 판정 위에
        card?.let { PerfCardArt(it, Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x6)) }
        // 지난달 실적 — 채운 가장 높은 구간 기준(S15P31E103-602). 기록 · 구간이 없으면 줄을 두지 않는다
        lastMonth?.let {
            Text(
                it.text,
                style = YamuTheme.typography.bodyM,
                color = c.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x3),
            )
        }
        val a = current?.analysis
        when {
            current == null || current.loading -> SectionBlock(divider = false) {
                repeat(4) {
                    SkeletonBlock(height = 40.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                }
            }
            current.noData -> SectionBlock(divider = false) {
                Text("이 카드는 쓴 기록이 아직 적어요", style = YamuTheme.typography.headlineS, color = c.text1)
                Spacer(Modifier.height(8.dp))
                Text("한 달쯤 쓰면 더 써야 할지 알려 드릴게요", style = YamuTheme.typography.bodyL, color = c.text2)
            }
            // 분석 API가 아직 없는 서버 — 다시 해도 같아서 다시 시도 대신 아래 버튼으로 R-02(S15P31E103-432)
            current.notReady -> SectionBlock(divider = false) {
                Text("유불리 분석은 곧 열려요", style = YamuTheme.typography.titleL, color = c.text1)
                Text("지금은 이번 달 실적과 남은 금액을 먼저 볼 수 있어요", style = YamuTheme.typography.bodyM, color = c.text3)
            }
            a == null -> SectionBlock(divider = false) { ErrorState(current.error ?: "분석을 불러오지 못했어요", onRetry = onRetry) }
            else -> AnalysisBody(a, cardName, onDetail = { onCardDetail(a.userCardId) })
        }
        Spacer(Modifier.height(YamuSpacing.x6))
    }
}

@Composable
private fun AnalysisBody(a: PerfAnalysis, cardName: String, onDetail: () -> Unit) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val thisMonth = a.month?.let { "${it}월" } ?: "이번 달"
    val next = a.month?.let { "${it % 12 + 1}월" } ?: "다음 달"
    val card = cardName.clipName()
    // ① 답 먼저 — 더 써야 하나? 쓰면 무엇을 받나? 판정 이름 · 실적 · 기준 같은 말은 쓰지 않는다
    SectionBlock(divider = false) {
        Text(a.answerTitle(next), style = t.headlineS, color = c.text1)
        a.answerBody(card, thisMonth, next)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = t.bodyL, color = c.text2)
        }
        val move = a.movable
        when {
            // 더 안 쓰고 받는 길 — 할 일 두 단계
            a.guide == PerfGuide.ACHIEVE_RECOMMENDED && move != null -> {
                Spacer(Modifier.height(20.dp))
                HowTo(move, card)
            }
            // 더 쓸 돈 vs 더 받는 혜택 — 막대 두 개로 바로 비교
            a.guide == PerfGuide.ACHIEVE_RECOMMENDED || a.guide == PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> {
                Spacer(Modifier.height(20.dp))
                CompareBars(spend = a.shortfall ?: 0, gain = a.extraBenefit, next = next)
            }
        }
        if (a.benefitNames.isNotEmpty() && a.guide != PerfGuide.ALREADY_ACHIEVED) {
            Spacer(Modifier.height(16.dp))
            BenefitBox(next, a.benefitNames, a.extraBenefit.takeIf { a.guide == PerfGuide.ACHIEVE_RECOMMENDED && move != null })
        }
        a.decision?.let { d ->
            Spacer(Modifier.height(YamuSpacing.x3))
            Text("이번 달은 '${d.label}'로 정했어요 · $cardName 실적 알림을 멈췄어요", style = YamuTheme.typography.bodyM, color = c.text2)
        }
        if (a.detailLinkPlace() == DetailLinkPlace.HEAD) {
            Spacer(Modifier.height(YamuSpacing.x2))
            DetailLink(cardName, onDetail)
        }
    }
    // ② 계산은 접어 둔다 — 궁금한 사람만 펼친다
    if (a.drawsReasons) {
        SectionBlock {
            CalcDetails { CalcRows(a, thisMonth) }
            if (a.detailLinkPlace() == DetailLinkPlace.REASONS) DetailLink(cardName, onDetail)
        }
    }
}

/** 계산 근거 — 지금까지 쓴 돈 + 월말까지 더 쓸 돈 + 예정 지출 = 이번 달 예상 → 혜택 조건 */
@Composable
private fun CalcRows(a: PerfAnalysis, thisMonth: String) {
    val estimate: (@Composable () -> Unit)? = if (a.isEstimate) ({ EstimateTag() }) else null
    a.recognized?.let { KeyValueRow("지금까지 쓴 돈", "${it.toWon()}원") }
    a.natural?.let { KeyValueRow("월말까지 더 쓸 것 같은 돈", "+${it.toWon()}원", keyTag = estimate, keySub = "지난 3개월 이맘때 평균") }
    a.scheduled?.takeIf { it > 0 }?.let { KeyValueRow("이 카드로 나갈 예정 지출", "+${it.toWon()}원") }
    a.projected?.let { KeyValueRow("$thisMonth 예상 합계", "${it.toWon()}원", keyTag = estimate) }
    a.target?.let { KeyValueRow("혜택 조건", "${it.toManWon()} 이상") }
    a.movable?.let { m -> a.projectedIfMoved?.let { KeyValueRow("${m.title} 옮기면 합계", "${it.toWon()}원") } }
}

/**
 * 카드 실적 한 화면(R-02 + R-03 합침)의 '더 써야 할까요?' 덩어리 — 답 한 문장 + 이유 + (할 일 | 막대 비교) + 계산 접기.
 * 판정 행동 버튼은 화면 아래 [CardVerdictActions]
 */
@Composable
internal fun VerdictBlock(a: PerfAnalysis, cardName: String) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val thisMonth = a.month?.let { "${it}월" } ?: "이번 달"
    val next = a.month?.let { "${it % 12 + 1}월" } ?: "다음 달"
    val card = cardName.clipName()
    Text(a.answerTitle(next), style = t.titleL, color = c.text1)
    a.answerBody(card, thisMonth, next)?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = t.bodyL, color = c.text2)
    }
    val move = a.movable
    when {
        a.guide == PerfGuide.ACHIEVE_RECOMMENDED && move != null -> {
            Spacer(Modifier.height(16.dp))
            HowTo(move, card)
        }
        a.guide == PerfGuide.ACHIEVE_RECOMMENDED || a.guide == PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> {
            Spacer(Modifier.height(16.dp))
            CompareBars(spend = a.shortfall ?: 0, gain = a.extraBenefit, next = next)
        }
    }
    a.decision?.let { d ->
        Spacer(Modifier.height(12.dp))
        Text("이번 달은 '${d.label}'로 정했어요 · 이 카드 알림을 멈췄어요", style = t.bodyM, color = c.text2)
    }
    Spacer(Modifier.height(8.dp))
    CalcDetails { CalcRows(a, thisMonth) }
}

/** 합친 화면 아래 버튼 — 판정 행동이 있을 때만(옮기기 · 안 쓰기 · 다시 판단). 자세히 보기는 이미 이 화면이라 두지 않는다 */
@Composable
internal fun CardVerdictActions(a: PerfAnalysis, cardName: String, acting: Boolean, onDecide: (PerfDecision?) -> Unit, onMove: () -> Unit) {
    if (a.primaryAction() == PerfAction.DETAIL) return
    ActionButtons(a, cardName, acting, onDecide, onMove, onDetail = {})
}

/** 머리 문장 — 판정을 사용자 질문(더 써야 해?)의 답으로 */
internal fun PerfAnalysis.answerTitle(next: String): String = when (guide) {
    PerfGuide.ACHIEVE_RECOMMENDED -> if (movable != null) "돈을 더 안 써도 $next 혜택을 받을 수 있어요" else "조금 더 쓰는 게 이득이에요"
    PerfGuide.KEEP_NATURAL -> "평소처럼만 쓰면 돼요"
    PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> "이번 달은 더 안 써도 돼요"
    PerfGuide.ALREADY_ACHIEVED -> "이미 $next 혜택을 받아요"
    PerfGuide.INSUFFICIENT_DATA -> "아직 판단하기 일러요"
}

/** 머리 아래 한 문장 — 왜 그런지(무엇을 하면 · 얼마를 쓰면 · 무엇을 받는지) */
internal fun PerfAnalysis.answerBody(card: String, thisMonth: String, next: String): String? {
    val goal = target?.toManWon() ?: "조건"
    val gap = shortfall ?: 0
    return when (guide) {
        PerfGuide.ACHIEVE_RECOMMENDED -> movable?.let { m ->
            "${m.title}(${m.amount.toWon()}원) 결제 카드만 $card${card.directionParticle()} 바꾸면 ${thisMonth}에 ${goal}을 넘겨요."
        // '남은 돈'(기준 − 지금)과 헷갈리지 않게 — 여기 금액은 평소 쓰는 만큼을 빼고도 모자라는 돈
        } ?: extraBenefit?.let { "평소처럼 써도 ${gap.toWon()}원이 모자라요. 그만큼 더 쓰면 ${next}에 최대 ${it.toWon()}원 혜택을 더 받아서 이득이에요." }
        PerfGuide.KEEP_NATURAL -> projected?.let { "이대로면 ${thisMonth}에 ${it.toWon()}원쯤 써서 ${goal}을 넘겨요." }
        PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> extraBenefit?.let { e ->
            "평소처럼 써도 ${gap.toWon()}원이 모자라요. 그만큼 더 써도 ${next}에 받는 혜택은 최대 ${e.toWon()}원이라 ${if (gap >= e * 2) "훨씬 " else ""}손해예요."
        } ?: "평소처럼 써도 ${gap.toWon()}원이 모자라요. 받는 혜택을 금액으로 셀 수 없어서, 꼭 필요한 결제가 아니면 안 써도 돼요."
        PerfGuide.ALREADY_ACHIEVED -> "${thisMonth}에 ${goal}을 넘겼어요."
        PerfGuide.INSUFFICIENT_DATA -> null
    }
}

/** 더 안 쓰고 받는 길 — 앱에서 바꾸기 + 내는 곳에서 바꾸기 */
@Composable
internal fun HowTo(m: MovableItem, card: String) {
    val deadline = parseDate(m.deadlineIso)?.toMonthDay()?.let { "${it}까지 " }.orEmpty()
    Text("이렇게 하면 돼요", style = YamuTheme.typography.titleS, color = YamuTheme.colors.text1)
    Spacer(Modifier.height(4.dp))
    StepLine(1, "아래 버튼으로 이 앱의 ${m.title} 결제 카드를 $card${card.directionParticle()} 바꿔요")
    StepLine(2, "$deadline${m.title}${m.title.objectParticle()} 내는 곳에서도 결제 카드를 바꿔요")
}

@Composable
private fun StepLine(no: Int, text: String) {
    val c = YamuTheme.colors
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$no", style = YamuTheme.typography.labelL, color = c.primary)
        }
        Text(text, style = YamuTheme.typography.bodyL, color = c.text1, modifier = Modifier.weight(1f))
    }
}

/** 더 써야 하는 돈 vs 더 받는 혜택 — 길이로 바로 비교 */
@Composable
internal fun CompareBars(spend: Long, gain: Long?, next: String) {
    val c = YamuTheme.colors
    val max = maxOf(spend, gain ?: 0L, 1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Bar("평소보다 더 써야 하는 돈", spend, spend / max, c.text3)
        if (gain != null) Bar("${next}에 더 받는 혜택 (최대)", gain, gain / max, c.primary)
        else Text("더 받는 혜택은 금액으로 셀 수 없어요", style = YamuTheme.typography.bodyM, color = c.text3)
    }
}

@Composable
private fun Bar(label: String, amount: Long, fraction: Float, color: Color) {
    val c = YamuTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.weight(1f))
            Text("${amount.toWon()}원", style = YamuTheme.typography.titleM, color = c.text1)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
    }
}

/** 받는 혜택 이름 — 회색 상자 안에 */
@Composable
private fun BenefitBox(next: String, names: List<String>, extra: Long?) {
    val c = YamuTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant).padding(16.dp)) {
        Text("${next}에 받는 혜택", style = YamuTheme.typography.bodyM, color = c.text3)
        Spacer(Modifier.height(4.dp))
        names.forEach { Text(it, style = YamuTheme.typography.titleM, color = c.text1) }
        extra?.let { Text("한 달 최대 ${it.toWon()}원 더", style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** '어떻게 계산했나요?' 접기 */
@Composable
internal fun CalcDetails(initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyExpanded) }
    ExpandToggle(if (open) "접기" else "어떻게 계산했나요?", expanded = open, onToggle = { open = !open })
    AnimatedVisibility(open) { Column(content = content) }
}

/**
 * '채우면 받는 혜택' 줄 — 혜택 이름을 한 줄에 하나씩 오른쪽에 쌓는다.
 * [KeyValueRow]는 값 칸을 먼저 재서 값이 두 줄을 넘으면 키가 '채우면 / 받는 / 혜택'으로 접힌다.
 * 여기서는 키를 먼저 재고 값이 남은 폭 안에서 줄을 바꾼다. 한 줄일 때 높이(44dp)와 글자 모양은 [KeyValueRow]와 같다
 */
@Composable
private fun BenefitNamesRow(names: List<String>) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("채우면 받는 혜택", style = t.bodyL, color = c.text2, modifier = Modifier.alignByBaseline())
        Column(
            Modifier.weight(1f).alignByBaseline(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            names.forEach { Text(it, style = t.titleM, color = c.text1, textAlign = TextAlign.End) }
        }
    }
}

@Composable
private fun DetailLink(cardName: String, onDetail: () -> Unit) =
    QuietLink("${cardName.clipName()} 자세히 보기", onClick = onDetail, modifier = Modifier.offset(x = (-4).dp))

/**
 * R-03 아래 버튼 하나 — 기록했으면 다시 판단, 아니면 판정별 행동, 행동이 없는 판정은 카드 실적 상세(R-02).
 * KEEP_NATURAL의 [평소대로 쓸게요]는 헷갈린다는 의견으로 빼서 R-02로 간다
 */
internal enum class PerfAction { REJUDGE, MOVE, SKIP, DETAIL }

internal fun PerfAnalysis.primaryAction(): PerfAction = when {
    decision != null -> PerfAction.REJUDGE
    guide == PerfGuide.ACHIEVE_RECOMMENDED && movable != null -> PerfAction.MOVE
    guide == PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> PerfAction.SKIP
    else -> PerfAction.DETAIL
}

/** ② '이렇게 판단했어요'를 그리는 판정 — 달성은 근거를 보이지 않는다 */
internal val PerfAnalysis.drawsReasons: Boolean get() = guide != PerfGuide.ALREADY_ACHIEVED

/** 본문 'OO 실적 자세히 보기'를 둘 곳 — 판정 · 기록과 상관없이 R-02로 가는 길을 하나 둔다(S15P31E103-451) */
internal enum class DetailLinkPlace { NONE, HEAD, REASONS }

internal fun PerfAnalysis.detailLinkPlace(): DetailLinkPlace = when {
    primaryAction() == PerfAction.DETAIL -> DetailLinkPlace.NONE   // 아래 버튼이 이미 R-02라 겹쳐 두지 않는다
    drawsReasons -> DetailLinkPlace.REASONS
    else -> DetailLinkPlace.HEAD                                     // ②가 없으면 ① 판정 아래
}

@Composable
private fun BigNumber(label: String, amount: Long?, plus: Boolean = false, color: androidx.compose.ui.graphics.Color? = null) {
    val c = YamuTheme.colors
    Spacer(Modifier.height(20.dp))
    Text(label, style = YamuTheme.typography.bodyM, color = c.text3)
    Spacer(Modifier.height(2.dp))   // 프로토타입 R-03 .key .v margin-top 2
    AmountText(amount ?: 0, withPlusSign = plus && (amount ?: 0) > 0, color = color)
}

/** 판정별 행동 하나 — 기록했으면 [다시 판단하기] */
@Composable
private fun ActionButtons(
    a: PerfAnalysis,
    cardName: String,
    acting: Boolean,
    onDecide: (PerfDecision?) -> Unit,
    onMove: () -> Unit,
    onDetail: () -> Unit,
) {
    val detail = "${cardName.clipName()} 자세히 보기"
    when (a.primaryAction()) {
        PerfAction.REJUDGE -> BottomCta {
            PrimaryButton("다시 판단하기", onClick = { onDecide(null) }, loading = acting)
        }
        PerfAction.MOVE -> a.movable?.let { m ->
            BottomCta { PrimaryButton("${m.title} 결제 카드 ${cardName.clipName()}${cardName.directionParticle()} 바꾸기", onClick = onMove, loading = acting) }
        }
        PerfAction.SKIP -> BottomCta {
            // 누르면 무엇이 바뀌는지 먼저 — 판단을 기록하고 이 카드 실적 알림을 멈춘다(#35 muteNotifications)
            Text(
                "누르면 이번 달 ${cardName.clipName()} 알림을 그만 보내요",
                style = YamuTheme.typography.bodyM,
                color = YamuTheme.colors.text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            PrimaryButton("이번 달은 더 안 쓸게요", onClick = { onDecide(PerfDecision.SKIP) }, loading = acting)
        }
        PerfAction.DETAIL -> BottomCta { PrimaryButton(detail, onClick = onDetail) }
    }
}
