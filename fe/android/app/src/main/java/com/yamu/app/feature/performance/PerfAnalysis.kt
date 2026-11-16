package com.yamu.app.feature.performance

import androidx.compose.foundation.layout.offset
import com.yamu.app.core.designsystem.component.QuietLink
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.SectionBlock
import androidx.compose.foundation.layout.Column
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
) {
    val selected: CardPerf? get() = tabs.firstOrNull { it.userCardId == selectedId }
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

    init {
        viewModelScope.launch {
            val overview = perf.cachedOverview() ?: (perf.overview() as? ApiResult.Success)?.data
            val pending = overview?.pending.orEmpty()
            val selected = overview?.all?.firstOrNull { it.userCardId == _state.value.selectedId }
            // 기준 전 카드가 탭. 고른 카드가 이미 달성이면 그 카드 하나만
            val tabs = if (selected != null && selected !in pending) listOf(selected) else pending
            _state.update { it.copy(tabs = tabs) }
        }
        load()
    }

    fun select(userCardId: Long) {
        if (userCardId == _state.value.selectedId) return
        _state.update { it.copy(selectedId = userCardId) }
        load()
    }

    fun load() {
        val id = _state.value.selectedId
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, noData = false, notReady = false, analysis = null) }
            when (val r = perf.analysis(id)) {
                is ApiResult.Success -> _state.update { if (it.selectedId == id) it.copy(loading = false, analysis = r.data) else it }
                is ApiResult.Failure -> _state.update {
                    if (it.selectedId != id) it
                    else if (r.error is ApiError.DataInsufficient) it.copy(loading = false, noData = true)
                    else if (r.error is ApiError.NotSupported) it.copy(loading = false, notReady = true)
                    else it.copy(loading = false, error = r.error.userMessage())
                }
            }
        }
    }

    /** [채우지 않을게요] · [평소대로 쓸게요] · [다시 판단하기](null) */
    fun decide(decision: PerfDecision?) {
        val a = _state.value.analysis ?: return
        if (_state.value.acting) return
        viewModelScope.launch {
            _state.update { it.copy(acting = true) }
            when (val r = perf.decide(a.userCardId, decision, a.version)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(acting = false, analysis = a.copy(decision = decision), message = if (decision == null) "다시 판단할 수 있어요 · 알림도 다시 받아요" else "이번 달 판단을 기록했어요") }
                }
                is ApiResult.Failure -> _state.update { it.copy(acting = false, message = r.error.userMessage()) }
            }
        }
    }

    /** [통신비 우리카드로 옮기기] — 예정 지출의 결제 카드를 바꾸고(#8) '채우기'로 기록(#35) → 다시 분석 */
    fun moveAndFill() {
        val a = _state.value.analysis ?: return
        val item = a.movable ?: return
        val card = _state.value.selected?.name ?: "이 카드"
        if (_state.value.acting) return
        viewModelScope.launch {
            _state.update { it.copy(acting = true) }
            val moved = perf.moveCashflow(item.cashflowItemId, a.userCardId)
            if (moved is ApiResult.Failure) return@launch _state.update { it.copy(acting = false, message = moved.error.userMessage()) }
            perf.decide(a.userCardId, PerfDecision.FILL, a.version)
            val deadline = parseDate(item.deadlineIso)?.toMonthDay()?.let { "${it}까지 " }.orEmpty()
            _state.update { it.copy(acting = false, message = "${item.title} 결제 카드를 $card${card.directionParticle()} 바꿔 뒀어요 · ${deadline}${item.title}${item.title.objectParticle()} 내는 곳에서도 카드를 바꿔 주세요") }
            load()
        }
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
        title = "실적 분석",
        onBack = onBack,
        bottomBar = {
            val a = s.analysis
            if (a != null) ActionButtons(a, cardName, s.acting, viewModel::decide, viewModel::moveAndFill, onDetail = { onCardDetail(a.userCardId) })
            else if (s.noData || s.notReady) BottomCta { PrimaryButton("${cardName.clipName()} 실적 자세히 보기", onClick = { onCardDetail(s.selectedId) }) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (s.tabs.isEmpty()) {
                // 실적관리 목록을 아직 · 못 받았을 때 — 탭 · 카드 그림 없이 분석만
                AnalysisPage(card = null, current = s, cardName, onRetry = viewModel::load, onCardDetail = onCardDetail)
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
                    AnalysisPage(card, current = s.takeIf { card.userCardId == it.selectedId }, cardName, onRetry = viewModel::load, onCardDetail = onCardDetail)
                }
            }
        }
    }
}

/** 카드 한 장의 분석 쪽 — 카드 그림 + 판정. [current]가 null이면(넘기는 중인 옆 카드) 뼈대만 */
@Composable
private fun AnalysisPage(card: CardPerf?, current: PerfAnalysisState?, cardName: String, onRetry: () -> Unit, onCardDetail: (Long) -> Unit) {
    val c = YamuTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 어떤 카드의 분석인지 — 카드 그림을 판정 위에
        card?.let { PerfCardArt(it, Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x6)) }
        val a = current?.analysis
        when {
            current == null || current.loading -> SectionBlock(divider = false) {
                repeat(4) {
                    SkeletonBlock(height = 40.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                }
            }
            current.noData -> SectionBlock(divider = false) {
                StatusTag(PerfGuide.INSUFFICIENT_DATA.label, tone = PerfGuide.INSUFFICIENT_DATA.tone())
                Spacer(Modifier.height(YamuSpacing.x2))
                Text("데이터가 더 모이면 판단해 드려요", style = YamuTheme.typography.titleL, color = c.text1)
                Text("거래가 한 달쯤 쌓이면 이 카드의 유불리를 알려 드려요", style = YamuTheme.typography.bodyM, color = c.text3)
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
    val nextMonth = a.month?.let { it % 12 + 1 }
    // ① 판정 · 머리 문장 · 큰 숫자
    SectionBlock(divider = false) {
        StatusTag(a.guide.label, tone = a.guide.tone(), large = true)
        Spacer(Modifier.height(12.dp))
        a.headline?.let { Text(it, style = YamuTheme.typography.headlineS, color = c.text1) }
        // 큰 숫자 — 판정마다 다르다(11 문서 7-1 표)
        when (a.guide) {
            PerfGuide.ACHIEVE_RECOMMENDED -> BigNumber(nextMonth?.let { "${it}월에 더 받는 혜택" } ?: "더 받는 혜택", a.extraBenefit, plus = true, color = c.primary)
            PerfGuide.KEEP_NATURAL, PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> BigNumber("채우려면 더 써야 하는 돈", a.shortfall ?: 0)
            else -> Unit
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
    // ② 이렇게 판단했어요 — 키–값(근거는 서버 값, '예측'은 키 뒤)
    if (a.drawsReasons) {
        SectionBlock(title = "이렇게 판단했어요") {
            a.target?.let { KeyValueRow("실적 기준", "${it.toWon()}원") }
            a.projected?.let { KeyValueRow("월말 예상", "${it.toWon()}원", keyTag = if (a.isEstimate) ({ EstimateTag() }) else null) }
            when (a.guide) {
                PerfGuide.ACHIEVE_RECOMMENDED -> a.movable?.let { m ->
                    val deadline = parseDate(m.deadlineIso)?.toMonthDay()?.let { " (${it}까지)" }.orEmpty()
                    KeyValueRow("${m.title} 옮기면$deadline", a.projectedIfMoved?.let { "${it.toWon()}원" } ?: "+${m.amount.toWon()}원")
                }
                PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> a.extraBenefit?.let {
                    KeyValueRow("채우면 받는 혜택", "약 ${it.toWon()}원", keyTag = { EstimateTag() })
                }
                PerfGuide.KEEP_NATURAL -> if (a.benefitNames.isNotEmpty()) KeyValueRow("채우면 받는 혜택", a.benefitNames.joinToString(" · "))
                else -> Unit
            }
            if (a.detailLinkPlace() == DetailLinkPlace.REASONS) DetailLink(cardName, onDetail)
        }
    }
}

@Composable
private fun DetailLink(cardName: String, onDetail: () -> Unit) =
    QuietLink("${cardName.clipName()} 실적 자세히 보기", onClick = onDetail, modifier = Modifier.offset(x = (-4).dp))

/** R-03 아래 버튼 하나 — 기록했으면 다시 판단, 아니면 판정별 행동, 행동이 없는 판정은 카드 실적 상세(R-02) */
internal enum class PerfAction { REJUDGE, MOVE, SKIP, KEEP, DETAIL }

internal fun PerfAnalysis.primaryAction(): PerfAction = when {
    decision != null -> PerfAction.REJUDGE
    guide == PerfGuide.ACHIEVE_RECOMMENDED && movable != null -> PerfAction.MOVE
    guide == PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> PerfAction.SKIP
    guide == PerfGuide.KEEP_NATURAL -> PerfAction.KEEP
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
    val detail = "${cardName.clipName()} 실적 자세히 보기"
    when (a.primaryAction()) {
        PerfAction.REJUDGE -> BottomCta {
            PrimaryButton("다시 판단하기", onClick = { onDecide(null) }, loading = acting)
        }
        PerfAction.MOVE -> a.movable?.let { m ->
            BottomCta { PrimaryButton("${m.title} ${cardName.clipName()}${cardName.directionParticle()} 옮기기", onClick = onMove, loading = acting) }
        }
        PerfAction.SKIP -> BottomCta {
            PrimaryButton("채우지 않을게요", onClick = { onDecide(PerfDecision.SKIP) }, loading = acting)
        }
        PerfAction.KEEP -> BottomCta {
            PrimaryButton("평소대로 쓸게요", onClick = { onDecide(PerfDecision.KEEP) }, loading = acting)
        }
        PerfAction.DETAIL -> BottomCta { PrimaryButton(detail, onClick = onDetail) }
    }
}
