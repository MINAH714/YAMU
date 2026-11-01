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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val error: String? = null,
    val acting: Boolean = false,
    val message: String? = null,                 // 스낵바 한 번
) {
    val selected: CardPerf? get() = tabs.firstOrNull { it.userCardId == selectedId }
}

/** R-03 유불리 분석 — 카드 탭(기준 전 카드) · 판정은 서버(#18) 그대로 · 판단 기록(#35) */
@HiltViewModel
class PerfAnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val perf: PerformanceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PerfAnalysisState(selectedId = savedStateHandle.toRoute<PerfAnalysisRoute>().userCardId))
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
            _state.update { it.copy(loading = true, error = null, noData = false, analysis = null) }
            when (val r = perf.analysis(id)) {
                is ApiResult.Success -> _state.update { if (it.selectedId == id) it.copy(loading = false, analysis = r.data) else it }
                is ApiResult.Failure -> _state.update {
                    if (it.selectedId != id) it
                    else if (r.error is ApiError.DataInsufficient) it.copy(loading = false, noData = true)
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
    val c = YamuTheme.colors
    val cardName = s.selected?.name ?: "카드"
    FlowScreen(
        title = "실적 분석",
        onBack = onBack,
        bottomBar = {
            val a = s.analysis
            if (a != null) ActionButtons(a, cardName, s.acting, viewModel::decide, viewModel::moveAndFill, onDetail = { onCardDetail(a.userCardId) })
            else if (s.noData) BottomCta { PrimaryButton("$cardName 실적 자세히 보기", onClick = { onCardDetail(s.selectedId) }) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (s.tabs.size > 1) {
                YamuTabs(
                    tabs = s.tabs.map { it.name },
                    selectedIndex = s.tabs.indexOfFirst { it.userCardId == s.selectedId }.coerceAtLeast(0),
                    onSelect = { i -> viewModel.select(s.tabs[i].userCardId) },
                )
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val a = s.analysis
                // 어떤 카드의 분석인지 — 카드 그림을 판정 위에(탭을 바꾸면 같이 바뀐다)
                s.selected?.let { PerfCardArt(it, Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x6)) }
                when {
                    s.loading -> SectionBlock(divider = false) {
                        repeat(4) {
                            SkeletonBlock(height = 40.dp)
                            Spacer(Modifier.height(YamuSpacing.x3))
                        }
                    }
                    s.noData -> SectionBlock(divider = false) {
                        StatusTag(PerfGuide.INSUFFICIENT_DATA.label, tone = PerfGuide.INSUFFICIENT_DATA.tone())
                        Spacer(Modifier.height(YamuSpacing.x2))
                        Text("데이터가 더 모이면 판단해 드려요", style = YamuTheme.typography.titleL, color = c.text1)
                        Text("거래가 한 달쯤 쌓이면 이 카드의 유불리를 알려 드려요", style = YamuTheme.typography.bodyM, color = c.text3)
                    }
                    a == null -> SectionBlock(divider = false) { ErrorState(s.error ?: "분석을 불러오지 못했어요", onRetry = viewModel::load) }
                    else -> AnalysisBody(a, cardName, onDetail = { onCardDetail(a.userCardId) })
                }
                Spacer(Modifier.height(YamuSpacing.x6))
            }
        }
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
    }
    // ② 이렇게 판단했어요 — 키–값(근거는 서버 값, '예측'은 키 뒤)
    if (a.guide != PerfGuide.ALREADY_ACHIEVED) {
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
            // 카드 상세(R-02)가 있는 판정만 — 달성 추천
            if (a.guide == PerfGuide.ACHIEVE_RECOMMENDED) {
                QuietLink("$cardName 실적 자세히 보기", onClick = onDetail, modifier = Modifier.offset(x = (-4).dp))
            }
        }
    }
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
    val detail = "$cardName 실적 자세히 보기"
    when {
        a.decision != null -> BottomCta {
            PrimaryButton("다시 판단하기", onClick = { onDecide(null) }, loading = acting)
        }
        a.guide == PerfGuide.ACHIEVE_RECOMMENDED && a.movable != null -> BottomCta {
            PrimaryButton("${a.movable.title} $cardName${cardName.directionParticle()} 옮기기", onClick = onMove, loading = acting)
        }
        a.guide == PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED -> BottomCta {
            PrimaryButton("채우지 않을게요", onClick = { onDecide(PerfDecision.SKIP) }, loading = acting)
        }
        a.guide == PerfGuide.KEEP_NATURAL -> BottomCta {
            PrimaryButton("평소대로 쓸게요", onClick = { onDecide(PerfDecision.KEEP) }, loading = acting)
        }
        else -> BottomCta { PrimaryButton(detail, onClick = onDetail) }
    }
}
