package com.yamu.app.feature.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.feature.payment.data.PaymentEdits
import com.yamu.app.feature.report.data.CsvDocuments
import com.yamu.app.feature.report.data.defaultCsvName
import com.yamu.app.feature.report.data.exportErrorMessage
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import com.yamu.app.core.designsystem.component.LinkButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.theme.LocalYamuReduceMotion
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BrandMark
import androidx.compose.runtime.saveable.rememberSaveable
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.report.data.AssetFlow
import com.yamu.app.feature.report.data.AssetFlowRepository
import com.yamu.app.feature.report.data.MonthFlow
import com.yamu.app.feature.report.data.MonthFlowResult
import com.yamu.app.feature.report.data.assetDateIn
import java.time.LocalDate
import com.yamu.app.feature.report.data.BenefitReport
import com.yamu.app.feature.report.data.CategoryShare
import com.yamu.app.feature.report.data.MissedBenefit
import com.yamu.app.feature.report.data.MonthlySpend
import com.yamu.app.feature.report.data.PaymentItem
import com.yamu.app.feature.report.data.ReportJump
import com.yamu.app.feature.report.data.ReportMonth
import com.yamu.app.feature.report.data.ReportSection
import com.yamu.app.feature.report.data.ReportRepository
import com.yamu.app.feature.report.data.ShownMonth
import com.yamu.app.feature.report.data.monthTitle
import com.yamu.app.feature.report.data.mergePayments
import com.yamu.app.feature.report.data.paymentDayLabel
import com.yamu.app.feature.report.data.periodLabel
import com.yamu.app.feature.report.data.spendDiffSentence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.time.YearMonth
import javax.inject.Inject

// ───────────────────────── S-01 소비분석(탭) ─────────────────────────

/**
 * 자산 흐름 칸 상태 — 사용금액과 따로 받아 한쪽이 실패해도 다른 쪽은 그대로 보인다(COMMON-08).
 * 30일 예보([flow], 7 · 14 · 30일 뒤용)는 달과 상관없어 돌아올 때만 받고, 그달 자산 흐름([month], asset-flows)은 달마다 받는다.
 * [month]가 없으면(옛 서버 404 · 실패) 예보만으로 그린다 — 지난날은 빈 날
 */
data class AssetFlowUi(
    val loading: Boolean = true,
    val flow: AssetFlow? = null,
    val error: String? = null,
    val noAccount: Boolean = false,
    val month: MonthFlow? = null,
    /** 그달 자산 흐름을 받지 못함(은행 · 네트워크) — 옛 서버의 404는 오류가 아니라 null */
    val monthError: String? = null,
)

data class ReportState(
    val shown: ShownMonth,
    val loading: Boolean = true,
    val spend: MonthlySpend? = null,
    val error: String? = null,
    val asset: AssetFlowUi = AssetFlowUi(),
    /** 오늘(한국 날짜) — 자산 흐름의 오늘 기준선 · '오늘로' */
    val today: LocalDate = shown.thisMonth.atDay(1),
    /** 자산 흐름에서 고른 날 — null이면 그달 처음 고를 날(이번 달 오늘 · 지난달 말일 · 다음 달 1일) */
    val pickedDate: LocalDate? = null,
    /**
     * 바깥에서 날을 바꾼 횟수('오늘로' · 홈에서 내려옴) — 칸이 이 값이 바뀔 때만 그 날로 따라간다.
     * 칸이 고른 날은 ViewModel을 거쳐 한 박자 늦게 돌아와서, 그 값을 따라가면 빠르게 끄는 동안 손잡이가 앞 날로 되돌아간다
     */
    val assetReset: Int = 0,
) {
    /** 자산 흐름이 보여 줄 날 — 늘 보이는 달 안 */
    val assetDate: LocalDate get() = assetDateIn(shown.month, today, pickedDate)
}

/**
 * 탭에 돌아올 때마다 새로 받는다(방금 한 결제 · 바꾼 예정 수입·지출이 바로 보이게) — 같은 달이 실패하면 마지막 값을 그대로.
 * 달은 [ReportMonth]에서 읽어 S-04 · S-05에서 바꾼 달도 돌아오면 이어 본다. S-01만 다음 달(예보만)까지 간다.
 * 달을 빠르게 넘기면 앞 요청을 취소하고, 그래도 늦게 온 응답은 지금 보이는 달과 다르면 버린다.
 * 자산 흐름에서 고른 날은 여기 남아 잔고예보에 다녀와도 그대로다(ANALYSIS-14)
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repo: ReportRepository,
    private val months: ReportMonth,
    private val assets: AssetFlowRepository,
    private val jumps: ReportJump = ReportJump(months),
) : ViewModel() {
    init { months.reset() }   // 새로 만들어질 때(로그인 · 앱 새로 켜기)는 이번 달부터 — 앞사람이 고른 달이 남지 않게

    private val _state = MutableStateFlow(ReportState(months.currentWithNext(), today = months.today()))
    val state: StateFlow<ReportState> = _state.asStateFlow()
    private var job: Job? = null
    private var assetJob: Job? = null
    private var monthJob: Job? = null

    /** 다른 화면이 보낸 '이 칸으로 내려가기'(홈 '자산 흐름 보기', S15P31E103-479) — 화면이 내려간 뒤 [jumped] */
    val jump: StateFlow<ReportSection?> = jumps.pending

    /** 내려간 뒤. 자산 흐름으로 왔으면 오늘을 고른다(홈 '자산 흐름 보기'는 이번 달 · 오늘, ReportJump가 달을 이번 달로 돌려 둠) */
    fun jumped(section: ReportSection) {
        jumps.consume(section)
        if (section == ReportSection.ASSET_FLOW) _state.update { it.copy(pickedDate = null, assetReset = it.assetReset + 1) }
    }

    /** 탭에 돌아올 때 — 사용금액 · 자산 흐름을 같이 새로(FORECAST-04 · COMMON-09) */
    fun refresh() {
        loadSpend()
        loadAsset()
        loadMonth()
    }

    fun select(month: YearMonth) {
        months.select(month)
        loadSpend()
        loadMonth()
    }

    /** 그달 자산 흐름만 다시(지난 잔고를 못 받았을 때 '다시 시도') */
    fun retryMonth() = loadMonth()

    /** 자산 흐름에서 날을 고름(그래프 · 날짜 띠 · 알약) */
    fun selectDate(date: LocalDate) = _state.update { it.copy(pickedDate = date) }

    /** '오늘로' — 이번 달 · 오늘(ANALYSIS-11) */
    fun goToday() {
        _state.update { it.copy(pickedDate = null, assetReset = it.assetReset + 1) }
        val thisMonth = YearMonth.from(months.today())
        if (_state.value.shown.month != thisMonth) select(thisMonth)
    }

    private fun loadSpend() {
        val shown = months.currentWithNext()
        val today = months.today()
        job?.cancel()
        _state.update {
            if (it.shown.month == shown.month) it.copy(shown = shown, today = today, loading = it.spend == null && !shown.isNextMonth, error = null)
            // 다른 달 숫자를 새 달 머리 아래 두지 않는다. 고른 날은 새 달의 처음 고를 날로, 예보는 달과 상관없어 그대로
            else ReportState(shown, loading = !shown.isNextMonth, asset = it.asset.copy(month = null, monthError = null), today = today, assetReset = it.assetReset)
        }
        if (shown.isNextMonth) return   // 아직 쓴 돈이 없는 달 — 월 API를 부르지 않는다
        job = viewModelScope.launch {
            val r = repo.monthly(shown.month)
            ensureActive()
            if (_state.value.shown.month != shown.month) return@launch
            when (r) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, spend = r.data, error = null) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    private fun loadAsset() {
        assetJob?.cancel()
        _state.update { it.copy(asset = it.asset.copy(loading = it.asset.flow == null, error = null)) }
        assetJob = viewModelScope.launch {
            val r = assets.load()
            ensureActive()
            when (r) {
                is ApiResult.Success -> _state.update { it.copy(asset = it.asset.copy(loading = false, flow = r.data, error = null, noAccount = false)) }
                is ApiResult.Failure -> _state.update {
                    if (r.error.code in NO_ACCOUNT_CODES) it.copy(asset = AssetFlowUi(loading = false, noAccount = true))
                    else it.copy(asset = it.asset.copy(loading = false, error = r.error.userMessage()))   // 마지막 값은 그대로
                }
            }
        }
    }

    /**
     * 그달 자산 흐름(asset-flows) — 달을 빠르게 넘기면 앞 요청을 취소하고, 그래도 늦게 온 응답은 지금 달과 다르면 버린다.
     * 옛 서버(404)면 비워 두어 예보만으로 그리고, 실패면 같은 달의 마지막 값을 두고 '다시 시도'를 보인다
     */
    private fun loadMonth() {
        val month = _state.value.shown.month
        monthJob?.cancel()
        _state.update { it.copy(asset = it.asset.copy(month = it.asset.month?.takeIf { m -> m.month == month }, monthError = null)) }
        monthJob = viewModelScope.launch {
            val r = assets.month(month)
            ensureActive()
            if (_state.value.shown.month != month) return@launch
            _state.update {
                it.copy(
                    asset = when (r) {
                        is MonthFlowResult.Loaded -> it.asset.copy(month = r.flow, monthError = null)
                        MonthFlowResult.Unsupported -> it.asset.copy(month = null, monthError = null)
                        is MonthFlowResult.Failed -> it.asset.copy(monthError = r.error.userMessage())
                    },
                )
            }
        }
    }

    private companion object {
        /** 계좌를 잇지 않았다 — 잔고예보와 같은 코드(옛 서버 코드 포함) */
        val NO_ACCOUNT_CODES = setOf(ForecastRepository.NO_ACCOUNT, "BASE_ACCOUNT_NOT_SET")
    }
}

/**
 * S-01 소비분석 — 사용금액 → 자산 흐름(ANALYSIS-01) → 많이 쓴 곳 · 혜택 · 결제 내역. 돌아올 때마다 다시 받는다
 * (예정 수입·지출 · 결제가 바뀐 뒤에도 — 잔고예보 · 홈도 같은 규칙, FORECAST-04).
 * [onForecast]는 자산 흐름 칸의 'AI 잔고예보 자세히 보기'(S15P31E103-483). 잔고예보(F-01)를 이 화면 위에 쌓아서
 * 뒤로 오면 보던 달(ReportMonth) · 고른 날(ViewModel) · 스크롤(rememberScrollState)이 그대로다(ANALYSIS-14 · FORECAST-05).
 * 달 이름을 누르면 연 · 월 시트(ANALYSIS-06)
 */
@Composable
fun ReportRoute(
    onBenefits: () -> Unit,
    onPayments: () -> Unit,
    onForecast: () -> Unit = {},
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val scroll = rememberScrollState()
    // 자산 흐름 칸이 스크롤 안에서 시작하는 자리 — 홈 '자산 흐름 보기'로 들어오면 여기까지 내려간다
    var assetFlowY by remember { mutableStateOf<Int?>(null) }
    val jump by viewModel.jump.collectAsStateWithLifecycle()
    val reduceMotion = LocalYamuReduceMotion.current
    LaunchedEffect(jump, assetFlowY, s.loading) {
        val target = jump ?: return@LaunchedEffect
        if (s.loading) return@LaunchedEffect   // 그릴 내용이 오면 자리를 안다
        assetFlowY?.let { y -> if (reduceMotion) scroll.scrollTo(y) else scroll.animateScrollTo(y) }
        viewModel.jumped(target)   // 칸이 없는 상태(빈 달 · 오류)여도 요청은 버린다 — 나중에 엉뚱하게 내려가지 않게
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    TabScreen(title = "소비분석") { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            MonthSwitcher(s.shown, onSelect = viewModel::select, onPick = { picking = true })
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                val sp = s.spend
                val spendShown = sp != null && !(sp.paymentCount == 0 || (sp.spent == 0L && sp.categories.isEmpty()))
                when {
                    s.shown.isNextMonth -> YamuCard { NextMonthSpend(s.shown) }
                    sp == null && s.loading -> YamuCard {
                        SkeletonBlock(height = 20.dp, widthFraction = 0.5f)
                        Spacer(Modifier.height(YamuSpacing.x2))
                        SkeletonBlock(height = 38.dp, widthFraction = 0.6f)
                    }
                    sp == null -> YamuCard { ErrorState(s.error ?: "소비분석을 불러오지 못했어요", onRetry = viewModel::refresh) }
                    !spendShown -> YamuCard { MonthEmpty(s.shown, onSelect = viewModel::select) }
                    else -> YamuCard {
                        Text("${sp.periodLabel} ${if (sp.isLiving) "생활 소비" else "카드 결제"}", style = YamuTheme.typography.bodyM, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        AmountText(sp.spent)
                        sp.diffFromPrev?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(boldAmount(spendDiffSentence(it, sp.prevPeriod)), style = YamuTheme.typography.bodyM, color = c.text2)
                        }
                        s.error?.let { Text("$it · 마지막 값을 보여 드려요", style = YamuTheme.typography.bodyS, color = c.warning) }
                    }
                }
                // 자산 흐름(ANALYSIS-01: 사용금액 바로 아래) — 사용금액이 비거나 실패해도 따로 보인다. 홈 '자산 흐름 보기'는 이 칸 위까지 내려온다
                AssetFlowCard(
                    s,
                    onSelectDate = viewModel::selectDate,
                    onMonth = viewModel::select,
                    onToday = viewModel::goToday,
                    onRetry = viewModel::refresh,
                    onRetryMonth = viewModel::retryMonth,
                    onForecast = onForecast,
                    modifier = Modifier.onPlaced { assetFlowY = it.positionInParent().y.roundToInt() },
                )
                // 많이 쓴 곳 · 혜택 · 결제 내역은 그대로(ANALYSIS-02). 다음 달은 쓴 돈이 없어 두 화면으로 가는 줄도 없다
                if (sp != null && spendShown && !s.shown.isNextMonth) {
                    if (sp.categories.isNotEmpty()) {
                        YamuCard {
                            Text("많이 쓴 곳", style = YamuTheme.typography.cardTitle, color = c.text1)
                            Spacer(Modifier.height(YamuSpacing.x3))
                            ShareBar(sp.categories, total = sp.spent)
                            Spacer(Modifier.height(YamuSpacing.x2))
                            sp.categories.forEachIndexed { i, cat -> CategoryRow(cat, chartColor(i)) }
                        }
                    }
                    YamuCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {   // 링크 카드는 .card.tight(16)
                        sp.benefit?.let {
                            ListRow(
                                title = sp.month?.let { m -> "${m}월에 받은 혜택" } ?: "이번 달 받은 혜택",
                                value = "${it.toWon()}원",
                                valueColor = c.primary,
                                chevron = true,
                                onClick = onBenefits,
                            )
                        }
                        ListRow(title = "결제 내역", value = sp.paymentCount?.let { "${it}건" }, chevron = true, onClick = onPayments)
                    }
                }
            }
        }
    }
    if (picking) MonthSheet(s.shown, onPick = { m -> picking = false; viewModel.select(m) }, onDismiss = { picking = false })
}

/** 문장 속 'n원 더 · 덜'만 굵게 */
private fun boldAmount(sentence: String): AnnotatedString {
    val m = Regex("[0-9,]+원( 더| 덜)?").find(sentence) ?: return AnnotatedString(sentence)
    return buildAnnotatedString {
        append(sentence.substring(0, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) }
        append(sentence.substring(m.range.last + 1))
    }
}

/** 막대 색 — 프로토타입 --chart-1 · --chart-5 · --chart-2(나머지는 회색) */
@Composable
private fun chartColor(index: Int): Color {
    val dark = YamuTheme.colors.isDark
    val light = listOf(0xFF3182F6, 0xFF64A8FF, 0xFF90C2FF)
    val night = listOf(0xFF4593FC, 0xFF90C2FF, 0xFF2B5A9E)
    return Color((if (dark) night else light)[index % 3])
}

/** 비중 막대 1개 — 상위 3곳 + 그 외(회색). 색만으로 전하지 않게 아래 줄에 이름 · 비율 · 금액 */
@Composable
private fun ShareBar(categories: List<CategoryShare>, total: Long) {
    val fractions = categories.map { cat -> cat.ratio?.let { it / 100f } ?: if (total > 0) cat.amount.toFloat() / total else 0f }
    val rest = (1f - fractions.sum()).coerceAtLeast(0f)
    val label = (categories.zip(fractions).map { (cat, f) -> "${cat.name} ${(f * 100).toInt()}%" } + "그 외 ${(rest * 100).toInt()}%").joinToString(", ")
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).semantics { contentDescription = "카테고리 비중: $label" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fractions.forEachIndexed { i, f ->
            if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(chartColor(i)))
        }
        if (rest > 0.001f) Box(Modifier.weight(rest).fillMaxHeight().background(YamuTheme.colors.outlineVariant))
    }
}

@Composable
private fun CategoryRow(cat: CategoryShare, color: Color) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(cat.name, style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f))
        cat.ratio?.let { Text("$it%", style = YamuTheme.typography.bodyM, color = c.text3) }
        Text("${cat.amount.toWon()}원", style = YamuTheme.typography.value, color = c.text1, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 92.dp))
    }
}

// ───────────────────────── S-04 혜택 리포트 ─────────────────────────

data class BenefitReportState(val shown: ShownMonth, val loading: Boolean = true, val report: BenefitReport? = null, val error: String? = null)

/** S-01에서 고른 달로 연다. 결제 내역(S-05)에서 달을 바꾸고 돌아오면 그 달로 다시 받는다([resume]) */
@HiltViewModel
class BenefitReportViewModel @Inject constructor(private val repo: ReportRepository, private val months: ReportMonth) : ViewModel() {
    private val _state = MutableStateFlow(BenefitReportState(months.current()))
    val state: StateFlow<BenefitReportState> = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun resume() {
        if (months.current().month != _state.value.shown.month) load()
    }

    fun load() {
        val shown = months.current()
        job?.cancel()
        _state.update {
            if (it.shown.month == shown.month) it.copy(shown = shown, loading = true, error = null)
            else BenefitReportState(shown)
        }
        job = viewModelScope.launch {
            val r = repo.benefits(shown.month)
            ensureActive()
            if (_state.value.shown.month != shown.month) return@launch   // 그사이 달을 넘겼으면 버린다
            when (r) {
                is ApiResult.Success -> _state.update { BenefitReportState(shown, loading = false, report = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun select(month: YearMonth) {
        months.select(month)
        load()
    }
}

@Composable
fun BenefitReportRoute(
    onBack: () -> Unit,
    onPayments: () -> Unit,
    viewModel: BenefitReportViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.resume()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    val r = s.report
    val empty = r != null && r.received == 0L && r.missed.isEmpty() && r.byPlace.isEmpty()
    FlowScreen(
        title = "혜택 리포트",
        onBack = onBack,
        cards = true,
        bottomBar = { if (r != null && !empty) BottomCta { PrimaryButton("결제 내역 보기", onClick = onPayments) } },
    ) { inner ->
        // 회색 바탕 위 카드 3개: 받은 혜택 · 놓친 혜택 · 어디서 받았나요 — 프로토타입 S-04
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            MonthSwitcher(s.shown, onSelect = viewModel::select)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    r == null && s.loading -> {
                        YamuCard {
                            SkeletonBlock(height = 20.dp, widthFraction = 0.5f)
                            Spacer(Modifier.height(YamuSpacing.x2))
                            SkeletonBlock(height = 38.dp, widthFraction = 0.6f)
                        }
                        YamuCard { repeat(3) { SkeletonBlock(height = 44.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
                    }
                    r == null -> YamuCard { ErrorState(s.error ?: "혜택 리포트를 불러오지 못했어요", onRetry = viewModel::load) }
                    // 결제는 있는데 혜택만 없는 달도 여기로 온다 — 이번 달 문구도 '혜택' 기준으로
                    empty -> YamuCard {
                        MonthEmpty(
                            s.shown,
                            onSelect = viewModel::select,
                            thisMessage = "이번 달 받은 혜택이 아직 없어요",
                            pastMessage = "${monthTitle(s.shown.month, s.shown.thisMonth)}에 받은 혜택이 없어요",
                        )
                    }
                    else -> {
                        YamuCard {
                            Text("${r.periodLabel} 받은 혜택", style = YamuTheme.typography.bodyM, color = c.text2)
                            Spacer(Modifier.height(4.dp))
                            AmountText(r.received)
                            r.yamuExtra?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    buildAnnotatedString {
                                        append("야무 추천으로 ")
                                        withStyle(SpanStyle(color = c.primary, fontWeight = FontWeight.Bold)) { append("${it.toWon()}원 더") }
                                        append(" 받았어요")
                                    },
                                    style = YamuTheme.typography.bodyM,
                                    color = c.text2,
                                )
                            }
                        }
                        if (r.missedTotal > 0) {
                            YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("놓친 혜택", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
                                    Text("${r.missedTotal.toWon()}원", style = YamuTheme.typography.cardTitle, color = c.error)
                                }
                                Spacer(Modifier.height(YamuSpacing.x1))   // 머리 아래 4(프로토타입 S-04 .card-hd)
                                r.missed.forEach { m -> MissedRow(m) }
                            }
                        }
                        if (r.byPlace.isNotEmpty()) {
                            YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
                                Text("어디서 받았나요", style = YamuTheme.typography.cardTitle, color = c.text1)
                                Spacer(Modifier.height(YamuSpacing.x1))
                                r.byPlace.forEach { (name, amount) -> ListRow(title = name, value = "${amount.toWon()}원", minHeight = 52.dp) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(YamuSpacing.x6))
            }
        }
    }
}

/** 놓친 혜택 한 줄 — 가맹점 · 날짜 · 이유 / −금액(검정, 합만 빨강) */
@Composable
private fun MissedRow(m: MissedBenefit) {
    val c = YamuTheme.colors
    // 52dp · 위아래 6(프로토타입 .row3)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.merchant, style = YamuTheme.typography.titleM, color = c.text1)
            Text(listOfNotNull(m.date?.toMonthDay(), m.reason).joinToString(" · "), style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp))
        }
        Text("−${m.amount.toWon()}원", style = YamuTheme.typography.value, color = c.text1)
    }
}

// ───────────────────────── S-05 결제 내역 ─────────────────────────

data class PaymentsState(
    /** 보이는 달 — 목록 · 합계 · CSV가 모두 이 달이다 */
    val shown: ShownMonth,
    val loading: Boolean = true,
    val count: Int = 0,
    val amount: Long = 0,
    val items: List<PaymentItem> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    val error: String? = null,
    val exporting: Boolean = false,
    /** 저장 창을 띄울 파일 이름 — 띄우면 비운다 */
    val saveAs: String? = null,
    /** 스낵바 한 번 */
    val message: String? = null,
) {
    /** '11월 1~12일'(이번 달) · '10월'(끝난 달) */
    val periodLabel: String get() = periodLabel(shown.month.toString(), partial = shown.isThisMonth)
}

/**
 * 쪽마다 20건. 돌아올 때마다 첫 쪽을 다시 받아 맨 위에 합친다(이미 펼친 쪽은 그대로).
 * 그사이 결제를 취소했거나 메모를 바꿨으면([PaymentEdits.version]) 펼친 쪽을 버리고 첫 쪽부터 다시 — 뒤쪽 줄이 옛 상태로 남지 않게.
 * 달([ReportMonth])이 바뀌면 쪽 번호 · 펼친 목록 · 합계를 비우고 그 달 첫 쪽부터. 앞 달 요청(첫 쪽 · 더 보기)은 취소하고,
 * 그래도 늦게 온 응답은 지금 보이는 달과 다르면 버린다.
 */
@HiltViewModel
class PaymentsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repo: ReportRepository,
    private val edits: PaymentEdits,
    private val documents: CsvDocuments,
    private val months: ReportMonth,
) : ViewModel() {
    private val _state = MutableStateFlow(PaymentsState(months.current()))
    val state: StateFlow<PaymentsState> = _state.asStateFlow()
    private var seenVersion = edits.version.value
    private var job: Job? = null
    private var moreJob: Job? = null

    fun refresh() {
        val shown = months.current()
        val version = edits.version.value
        val monthChanged = shown.month != _state.value.shown.month
        val reset = monthChanged || version != seenVersion
        job?.cancel()
        if (reset) moreJob?.cancel()   // 첫 쪽부터 다시 받으면 앞 목록 뒤에 붙일 더 보기도 버린다(달 바뀜 · 취소 · 메모)
        if (monthChanged) {
            _state.update {
                it.copy(shown = shown, loading = true, count = 0, amount = 0, items = emptyList(), page = 0, hasNext = false, loadingMore = false, moreError = null, error = null)
            }
        } else {
            _state.update { it.copy(shown = shown, loading = it.items.isEmpty(), error = null, loadingMore = it.loadingMore && !reset) }
        }
        job = viewModelScope.launch {
            val r = repo.payments(page = 1, month = shown.month)
            ensureActive()
            if (_state.value.shown.month != shown.month) return@launch
            when (r) {
                is ApiResult.Success -> {
                    seenVersion = version
                    _state.update {
                        if (reset) {
                            it.copy(loading = false, count = r.data.count, amount = r.data.amount, items = r.data.items, page = 1, hasNext = r.data.hasNext, moreError = null)
                        } else {
                            it.copy(
                                loading = false,
                                count = r.data.count,
                                amount = r.data.amount,
                                items = mergePayments(r.data.items, it.items),
                                page = maxOf(it.page, 1),
                                hasNext = if (it.page > 1) it.hasNext else r.data.hasNext,
                            )
                        }
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun select(month: YearMonth) {
        months.select(month)
        refresh()
    }

    /**
     * '이 조건으로 CSV 저장' — 저장 창을 먼저 띄우고 고른 뒤에 서버에서 받는다.
     * 창을 닫으면 받지 않아 분당 3번 한도를 쓰지 않고, 창이 열린 사이 앱이 정리돼도 돌아와서 이어 받는다.
     * 이름은 화면에 보이는 달의 파일 이름(서버가 붙이는 이름과 같다). 그 달을 [SavedStateHandle]에 두었다가 받을 때 넘긴다 —
     * 창이 열린 사이 달을 넘기거나 말일 자정이 지나도 이름과 내용의 달이 같게(앱이 정리됐다 돌아와도)
     */
    fun requestCsv() {
        if (_state.value.exporting) return
        val month = _state.value.shown.month
        savedStateHandle[CSV_MONTH] = month.toString()
        _state.update { it.copy(saveAs = defaultCsvName(month)) }
    }

    fun saveAsShown() = _state.update { it.copy(saveAs = null) }

    /**
     * 저장 창에서 고른 곳([uri], 닫았으면 null). 받기 · 쓰기에 실패하면 창이 새로 만든 빈 파일만 지운다 —
     * 이미 있는 파일을 골라 덮어쓰기를 눌렀으면 그 파일은 남긴다([CsvDocuments.discardIfEmpty])
     */
    fun saveCsvTo(uri: String?) {
        if (_state.value.exporting) return
        val month = savedStateHandle.remove<String>(CSV_MONTH)?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: _state.value.shown.month
        if (uri == null) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val message = when (val r = repo.exportCsv(month)) {
                is ApiResult.Success -> if (documents.write(uri, r.data.bytes)) {
                    "CSV 파일을 저장했어요"
                } else {
                    documents.discardIfEmpty(uri)
                    "파일을 저장하지 못했어요. 다시 저장해 주세요"
                }
                is ApiResult.Failure -> {
                    documents.discardIfEmpty(uri)
                    exportErrorMessage(r.error)
                }
            }
            _state.update { it.copy(exporting = false, message = message) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || !s.hasNext) return
        val month = s.shown.month
        _state.update { it.copy(loadingMore = true, moreError = null) }   // 연타 — 다음 호출은 위에서 막힌다
        moreJob = viewModelScope.launch {
            val r = repo.payments(page = s.page + 1, month = month)
            ensureActive()
            if (_state.value.shown.month != month) return@launch   // 앞 달 둘째 쪽을 새 달 목록에 붙이지 않는다
            when (r) {
                is ApiResult.Success -> _state.update {
                    it.copy(loadingMore = false, items = mergePayments(it.items, r.data.items), page = r.data.page, hasNext = r.data.hasNext)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, moreError = r.error.userMessage()) }
            }
        }
    }

    companion object {
        /** 저장 창을 띄울 때 정한 달(YYYY-MM) — 고른 뒤 받을 때 쓰고 지운다 */
        const val CSV_MONTH = "csvMonth"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaymentsRoute(
    onBack: () -> Unit,
    onDetail: (paymentId: Long) -> Unit,
    viewModel: PaymentsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    // 저장 위치는 사용자가 고른다(SAF) — 저장소 권한이 필요 없다. 창을 닫으면 아무것도 받지 · 쓰지 않는다
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        viewModel.saveCsvTo(uri?.toString())
    }
    LaunchedEffect(s.saveAs) {
        val name = s.saveAs ?: return@LaunchedEffect
        viewModel.saveAsShown()
        saveCsv.launch(name)
    }
    FlowScreen(
        title = "결제 내역",
        onBack = onBack,
        cards = true,
        actions = {
            if (s.items.isNotEmpty()) {
                LinkButton(if (s.exporting) "저장하는 중…" else "CSV 저장", onClick = viewModel::requestCsv, modifier = Modifier.padding(end = 4.dp))
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            MonthSwitcher(s.shown, onSelect = viewModel::select)
            when {
                s.items.isEmpty() && s.loading -> Column {
                    YamuCard {
                        SkeletonBlock(height = 20.dp, widthFraction = 0.6f)
                        Spacer(Modifier.height(YamuSpacing.x2))
                        SkeletonBlock(height = 38.dp, widthFraction = 0.5f)
                    }
                    YamuCard { repeat(6) { SkeletonBlock(height = 52.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
                }
                s.items.isEmpty() && s.error != null -> YamuCard { ErrorState(s.error.orEmpty(), onRetry = viewModel::refresh) }
                s.items.isEmpty() -> YamuCard { MonthEmpty(s.shown, onSelect = viewModel::select) }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    // 회색 바탕: 합계 카드 → 날짜 머리(회색) → 그날 결제(흰 줄 · 사이 선) — 프로토타입 S-05
                    item(key = "head") {
                        YamuCard {
                            Text("${s.periodLabel} 카드 결제 · ${s.count}건", style = YamuTheme.typography.bodyM, color = c.text2)
                            Spacer(Modifier.height(4.dp))
                            AmountText(s.amount)
                        }
                    }
                    s.items.groupBy { it.date }.forEach { (date, rows) ->
                        stickyHeader(key = "day-$date") { DayHeader(paymentDayLabel(date), rows.filter { !it.canceled }.sumOf { it.amount }) }
                        itemsIndexed(rows, key = { _, p -> p.paymentId }) { i, p ->
                            PaymentRow(p, divider = i > 0, onClick = { onDetail(p.paymentId) })
                        }
                    }
                    item(key = "more") {
                        Column(Modifier.padding(YamuSpacing.screen)) {
                            if (s.hasNext) {
                                // 작은 파란 글자 버튼, 가운데(프로토타입 S-05)
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    LinkButton(if (s.loadingMore) "불러오는 중…" else "이전 결제 더 보기", onClick = { if (!s.loadingMore) viewModel.loadMore() })
                                }
                            }
                            s.moreError?.let {
                                Spacer(Modifier.height(YamuSpacing.x2))
                                Text("$it · 다시 눌러 주세요", style = YamuTheme.typography.bodyS, color = c.error)
                            }
                            Spacer(Modifier.height(YamuSpacing.bottomSafe))
                        }
                    }
                }
            }
        }
    }
}

/** 날짜 · 그날 합(불러온 결제 기준) — 스크롤해도 위에 붙어 있다 */
@Composable
private fun DayHeader(label: String, total: Long) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.background).padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x4, bottom = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
        Text("${total.toWon()}원", style = YamuTheme.typography.labelM, color = c.text2)
    }
}

/** 가맹점 · 카드사 · 시각 / 금액 · 할인 · 적립 — 줄 전체가 거래 상세(P-08)로 */
@Composable
private fun PaymentRow(p: PaymentItem, divider: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val benefitText = p.benefit?.let { "${p.benefitLabel} ${it.toWon()}" }
    if (divider) HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(
                    p.merchant, p.brand?.shortName, p.time, "${p.amount.toWon()}원", benefitText,
                    "실적 제외".takeIf { p.excluded }, "취소".takeIf { p.canceled },
                ).joinToString(", ")
            }
            .padding(horizontal = YamuSpacing.screen, vertical = YamuSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.merchant, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.excluded) StatusTag("실적 제외")
                if (p.canceled) StatusTag("취소")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.brand?.let { BrandMark(it) }
                p.time?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text3) }
            }
        }
        // 금액 / 혜택 두 줄 사이 4dp — 왼쪽 가맹점 / 칩 · 시각 줄과 높이를 맞춘다(프로토타입 .txr .end small)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${p.amount.toWon()}원",
                style = YamuTheme.typography.titleS.copy(fontWeight = FontWeight.Bold),
                color = if (p.canceled) c.text3 else c.text1,
                textDecoration = if (p.canceled) TextDecoration.LineThrough else null,
            )
            benefitText?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.primary) }
        }
    }
}
