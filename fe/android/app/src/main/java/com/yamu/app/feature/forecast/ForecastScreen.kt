package com.yamu.app.feature.forecast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.staleLine
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowRow
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastDay
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.LineGroup
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.RiskWindow
import com.yamu.app.feature.forecast.data.cashflowRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

data class ForecastState(
    val loading: Boolean = true,
    val forecast: Forecast? = null,
    val error: String? = null,
    val noAccount: Boolean = false,
    val selection: ForecastSelection = ForecastSelection(),
    val accounts: List<ForecastAccountOption>? = null,  // 계좌 고르기 시트 — 열 때 부른다
    val accountsError: String? = null,
    val entries: List<CashflowEntry>? = null,     // F-02 가계부 회차(고칠 수 있는 줄) — 못 받으면 예보 줄만(읽기 전용)
    val pastEntries: List<CashflowEntry> = emptyList(),   // F-02 지난 7일 카드 예정 지출 — 결제와 연결만
    val entriesLoading: Boolean = false,
    val entriesError: String? = null,
) {
    /** F-02 — 예보가 실패해도 회차만으로 목록을 그리므로 보여 줄 것이 없으면 회차 응답까지 기다린다 */
    val cashflowLoading: Boolean
        get() = forecast == null && entries == null && (loading || entriesLoading)

    /** F-02 — 이 화면은 회차를 보여 주므로 둘 다 실패하면 회차 쪽 오류가 먼저 */
    val cashflowError: String?
        get() = entriesError ?: error
}

/** F-01 · F-02 — 돌아올 때마다 새로(결제 · 계획 저장 · 지우기 · 추가 · 수정 뒤 바뀌므로). 새로 부르면 앞 요청은 버린다 */
@HiltViewModel
class ForecastViewModel @Inject constructor(private val repo: ForecastRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForecastState(forecast = repo.cached(), loading = repo.cached() == null, selection = repo.selection()))
    val state: StateFlow<ForecastState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { refreshForecast() }
    }

    /** F-02 — 예보(합계 · 카드 청구)를 받은 뒤 같은 기간(오늘 ~ 다음 월급날)의 가계부 회차. 예보가 없으면 오늘부터 30일 */
    fun refreshWithEntries() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(entriesLoading = true) }
            refreshForecast()
            if (_state.value.noAccount) return@launch
            // 예보가 실패하면 메모리에 지난 값이 남아 있다 — 그 from을 쓰면 자정을 넘겼을 때 어제 회차를 읽는다
            val f = _state.value.forecast.takeIf { _state.value.error == null }
            val from = f?.from ?: LocalDate.now(KST)
            val to = f?.incomeDate ?: f?.to ?: from.plusDays(29)   // 월급날 줄까지 보이게 하루 더
            when (val r = repo.cashflowEntries(from, to)) {
                is ApiResult.Success -> _state.update { it.copy(entries = r.data, entriesLoading = false, entriesError = null) }
                is ApiResult.Failure -> _state.update { it.copy(entriesLoading = false, entriesError = r.error.userMessage()) }
            }
            // 지난 7일 카드 예정 지출 — 결제와 연결할 회차. 못 받아도 위 목록은 그대로라 조용히 비운다
            val today = LocalDate.now(KST)
            when (val r = repo.pastCardEntries(today.minusDays(PAST_MATCH_DAYS), today.minusDays(1))) {
                is ApiResult.Success -> _state.update { it.copy(pastEntries = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(pastEntries = emptyList()) }
            }
        }
    }

    /** 계좌 고르기 시트를 열 때 */
    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(accountsError = null) }
            when (val r = repo.accounts()) {
                is ApiResult.Success -> _state.update { it.copy(accounts = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(accountsError = r.error.userMessage()) }
            }
        }
    }

    /** 시트에서 고른 기간 · 계좌 — 계좌를 모두 골랐으면 '전체'(null)로 둬 새로 연결한 계좌도 들어가게 한다 */
    fun apply(period: ForecastPeriod?, accountIds: Set<Long>?) {
        val all = _state.value.accounts?.map { it.accountId }?.toSet()
        val selection = ForecastSelection(period, accountIds?.takeIf { it.isNotEmpty() && it != all })
        repo.select(selection)
        _state.update { it.copy(selection = selection) }
        refresh()
    }

    private suspend fun refreshForecast() {
        _state.update { it.copy(loading = it.forecast == null, error = null) }
        when (val r = repo.forecast()) {
            is ApiResult.Success -> _state.update { it.copy(loading = false, forecast = r.data, noAccount = false) }
            is ApiResult.Failure -> _state.update {
                if (r.error.code in NO_ACCOUNT_CODES) ForecastState(loading = false, noAccount = true, selection = it.selection)
                else it.copy(loading = false, error = r.error.userMessage())
            }
        }
    }

    private companion object {
        /** 계좌를 잇지 않았다 — 앱이 먼저 알아챈 경우 · 서버가 accountIds 없음으로 거절한 경우 · 옛 서버 코드 */
        val NO_ACCOUNT_CODES = setOf(ForecastRepository.NO_ACCOUNT, "BASE_ACCOUNT_NOT_SET")
    }
}

private fun LocalDate.daysFromToday(): Long = ChronoUnit.DAYS.between(LocalDate.now(KST), this)

/** '11월 20일 (금)' */
internal fun LocalDate.toKoreanDateWeek(): String = "${toKoreanDate()} (${dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"

/** '11월 20일 ~ 24일' · 달이 다르면 '11월 28일 ~ 12월 2일' · 하루면 '11월 20일' */
internal fun dateRange(from: LocalDate, to: LocalDate): String = when {
    from == to -> from.toKoreanDate()
    from.month == to.month && from.year == to.year -> "${from.toKoreanDate()} ~ ${to.dayOfMonth}일"
    else -> "${from.toKoreanDate()} ~ ${to.toKoreanDate()}"
}

/** 기간 칩 · 문구 — 고른 값이 없으면 서버가 실제로 계산한 기간으로 */
internal fun periodLabel(f: Forecast?, selection: ForecastSelection): String =
    selection.period?.label ?: when {
        f == null -> "기간"
        f.isNextIncome -> ForecastPeriod.NEXT_INCOME.label
        else -> "${f.horizonDays ?: 30}일"
    }

/**
 * F-02 머리의 '나갈 돈 · 출금 N건' — 금액과 건수를 같은 기준으로. 예보가 있으면 기간 안에 실제로 빠지는 날(카드는 청구 출금일) 기준,
 * 없으면 가계부 줄 기준. 카드 예정 지출은 사용일 줄로 보이지만 출금은 청구일이라 기간 밖이면 둘 다에서 빠진다
 */
internal fun cashflowOutflow(f: Forecast?, rows: List<CashflowRow>): Pair<Long, Int> =
    if (f != null) {
        f.scheduledOutflow to f.scheduledOutflows.size   // 합계와 건수가 같은 목록(Forecast.scheduledOutflows)
    } else {
        rows.filter { it.amount < 0 }.let { out -> -out.sumOf { it.amount } to out.size }
    }

/** F-01 · F-02 — 예정된 돈의 기간. 월급날 기준이 아니면 실제 일수(예보가 없으면 서버 기본값처럼 30일) */
internal fun cashflowPeriod(f: Forecast?): String =
    if (f != null && f.incomeDate != null) "월급 전까지" else "${f?.horizonDays ?: 30}일 동안"

// ───────────────────────── F-01 잔고예보 ─────────────────────────

@Composable
fun ForecastRoute(
    onBack: () -> Unit,
    onBuyCheck: () -> Unit,
    onCashflowList: () -> Unit,
    onLink: () -> Unit,
    onAlertSettings: () -> Unit = {},
    onLivingCost: () -> Unit = {},
    viewModel: ForecastViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    var filtering by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    // 회색 바탕 + 카드: 고르기 칩 · 예상 잔액 · 낮아지는 때 · 선 설명 · 예정된 돈 · 빼고 계산한 것. 가상 구매 입구는 아래 버튼(2026-11-08 개편)
    FlowScreen(
        title = "잔고예보",
        onBack = onBack,
        cards = true,
        actions = {
            IconButton(onClick = onAlertSettings) { Icon(YamuIcons.Notifications, contentDescription = "잔액 알림 설정", tint = c.text1) }
        },
        bottomBar = {
            if (!s.noAccount) BottomCta { PrimaryButton("이거 사면 잔고는? 비교해 보기", onClick = onBuyCheck) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val f = s.forecast
            when {
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 예보를 보여 드려요", actionText = "계좌 연결", onAction = onLink) }
                f == null && s.loading -> YamuCard {
                    SkeletonBlock(height = 28.dp, widthFraction = 0.5f)
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 140.dp)
                }
                f == null -> YamuCard { ErrorState(s.error ?: "예보를 불러오지 못했어요", onRetry = viewModel::refresh) }
                else -> {
                    FilterChips(f, s.selection, onOpen = {
                        filtering = true
                        viewModel.loadAccounts()
                    })
                    HeroCard(f, s.error)
                    if (f.riskWindows.isNotEmpty()) RiskCard(f)
                    LinesCard(f)
                    LivingCostEntry(f, onLivingCost)   // F-01b 생활비 예측 입구
                    UpcomingCard(f, onCashflowList)
                    NoticeCard(f, s.selection)
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    if (filtering) {
        FilterSheet(
            forecast = s.forecast,
            selection = s.selection,
            accounts = s.accounts,
            accountsError = s.accountsError,
            onRetry = viewModel::loadAccounts,
            onDismiss = { filtering = false },
            onApply = { period, ids ->
                filtering = false
                viewModel.apply(period, ids)
            },
        )
    }
}

/** 기간 · 계좌 칩 — 회색 바탕 위 흰 알약, 누르면 시트 */
@Composable
private fun FilterChips(f: Forecast, selection: ForecastSelection, onOpen: () -> Unit) {
    val n = f.accountIds.size
    Row(Modifier.padding(horizontal = YamuSpacing.screen).padding(bottom = YamuSpacing.blockGap), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(periodLabel(f, selection), contentDescription = "예보 기간 ${periodLabel(f, selection)}, 바꾸기", onClick = onOpen)
        FilterChip(if (selection.accountIds == null && n > 1) "계좌 ${n}개 전체" else "계좌 ${n}개", contentDescription = "계좌 ${n}개로 보는 중, 바꾸기", onClick = onOpen)
    }
}

@Composable
private fun FilterChip(text: String, contentDescription: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = YamuTheme.typography.labelL, color = c.text1)
        Icon(YamuIcons.ExpandMore, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

/** 예상 잔액 — 가장 나쁜 선의 최저 잔액, 상태, 세 선 그래프, 고른 날 */
@Composable
private fun HeroCard(f: Forecast, error: String?) {
    val c = YamuTheme.colors
    val short = (f.minBalance ?: 0) < 0
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (f.isNextIncome) "월급 전 가장 낮을 때" else "${f.horizonDays ?: 30}일 동안 가장 낮을 때", style = YamuTheme.typography.titleS, color = c.text2)
            if (f.isEstimate) {
                Spacer(Modifier.width(6.dp))
                EstimateTag()
            }
        }
        Spacer(Modifier.height(4.dp))
        AmountText(f.minBalance ?: 0, shortage = short, color = c.primary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            statusTag(f)?.let { (text, tone) -> StatusTag(text, tone = tone) }
            Text(
                listOfNotNull(f.minBalanceDate?.toKoreanDate(), f.incomeDate?.let { "월급 D-${it.daysFromToday()}" }).joinToString(" · "),
                style = YamuTheme.typography.bodyS,
                color = c.text3,
            )
        }
        error?.let { Text(staleLine(it, f.calculatedAtIso), style = YamuTheme.typography.bodyS, color = c.warning, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(YamuSpacing.x5))
        ForecastLines(f)
    }
}

/** 상태 태그 — 사실만(권하거나 말리지 않음). 모르는 수준이면 태그 없음 */
internal fun statusTag(f: Forecast): Pair<String, TagTone>? {
    val whole = if (f.isNextIncome) "월급날까지" else "${f.horizonDays ?: 30}일 동안"
    val below = f.riskFrom?.let { "${it.toKoreanDate()}부터 안전 잔액 아래" }
        ?: if (f.hasAccountRisk) "계좌 ${f.atRiskAccountCount}개가 안전 잔액 아래" else "안전 잔액 아래로 내려가요"
    return when (f.status) {
        RiskLevel.SAFE -> "$whole 안전 잔액 위" to TagTone.Success
        RiskLevel.WARNING -> below to TagTone.Warning
        RiskLevel.DANGER -> below to TagTone.Error
        RiskLevel.UNKNOWN -> null
    }
}

/** 세 선 + 범례 + 고른 날. 보수적 선이 없으면 그 선만 빼고 범례에 취소선 */
@Composable
private fun ForecastLines(f: Forecast) {
    val c = YamuTheme.colors
    val days = f.days
    if (days.size < 2) return
    val key = listOf(f.from, f.to, f.accountIds)
    val initial = days.indexOfFirst { it.date == f.minBalanceDate }.takeIf { it >= 0 } ?: days.lastIndex
    var selected by rememberSaveable(key.toString()) { mutableIntStateOf(initial) }
    val lines = buildList {
        add(ChartLine("확정", days.map { it.confirmed }, c.text3, width = 1.6.dp))
        add(ChartLine("예상", days.map { it.expected }, c.primary, width = 2.2.dp))
        if (f.hasConservative) add(ChartLine("보수적", days.map { it.conservative }, c.primary, dashed = true, width = 2.2.dp))
    }
    val ranges = f.riskWindows.mapNotNull { w ->
        val a = days.indexOfFirst { !it.date.isBefore(w.from) }
        val b = days.indexOfLast { !it.date.isAfter(w.to) }
        if (a < 0 || b < a) null else a..b
    }
    BalanceChart(
        dates = days.map { it.date },
        lines = lines,
        description = "예상 잔액 그래프, 가장 낮을 때 ${(f.minBalance ?: 0).toWon()}원" + (f.riskFrom?.let { ", ${it.toKoreanDate()}부터 안전 잔액 아래" } ?: ""),
        threshold = f.alertThreshold,
        riskRanges = ranges,
        selected = selected,
        onSelect = { selected = it },
        incomeArrow = f.incomeDate != null,
    )
    Spacer(Modifier.height(12.dp))
    ChartLegend(lines, missing = if (f.hasConservative) emptyList() else listOf("보수적"))
    Spacer(Modifier.height(14.dp))
    DayPanel(days[selected.coerceIn(0, days.lastIndex)], hasConservative = f.hasConservative)
}

/** 고른 날 — 그날 끝 잔액(선마다)과 그날 입출금 */
@Composable
private fun DayPanel(day: ForecastDay, hasConservative: Boolean) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.date.toKoreanDateWeek(), style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
            Text("그래프를 눌러 날짜 바꾸기", style = YamuTheme.typography.bodyS, color = c.text3)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayValue("확정", day.confirmed, c.text1, Modifier.weight(1f))
            DayValue("예상", day.expected, c.onPrimaryContainer, Modifier.weight(1f))
            if (hasConservative) DayValue("보수적", day.conservative, c.onWarningContainer, Modifier.weight(1f))
        }
        val events = day.events.filter { it.amount != 0L }
        if (events.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = c.outlineVariant)
            Spacer(Modifier.height(6.dp))
            events.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label + if (e.isEstimate) " · 추정" else if (e.isExpected) " · 예상" else "", style = YamuTheme.typography.bodyM, color = c.text1, modifier = Modifier.weight(1f))
                    Text("${e.amount.toWon(withPlusSign = true)}원", style = YamuTheme.typography.bodyM, color = if (e.amount > 0) c.primary else c.text1)
                }
            }
        }
    }
}

@Composable
private fun DayValue(label: String, value: Long?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = YamuTheme.typography.bodyS, color = YamuTheme.colors.text3)
        Text(value?.let { "${it.toWon()}원" } ?: "—", style = YamuTheme.typography.titleS, color = if ((value ?: 0) < 0) YamuTheme.colors.error else color)
    }
}

/** 잔액이 낮아지는 때 — 위험 구간마다 선 · 부족액 · 원인(서버 causes 그대로) */
@Composable
private fun RiskCard(f: Forecast) {
    val c = YamuTheme.colors
    val worst = if (f.riskWindows.any { it.level == RiskLevel.DANGER }) RiskLevel.DANGER else RiskLevel.WARNING
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("잔액이 낮아지는 때", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            StatusTag(if (worst == RiskLevel.DANGER) "위험" else "주의", tone = if (worst == RiskLevel.DANGER) TagTone.Error else TagTone.Warning)
        }
        f.riskWindows.take(3).forEachIndexed { i, w ->
            Spacer(Modifier.height(if (i == 0) 10.dp else 16.dp))
            Text(dateRange(w.from, w.to), style = YamuTheme.typography.titleM, color = c.text1)
            Text(riskLine(w), style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(4.dp))
            w.causes.forEachIndexed { j, cause ->
                HorizontalDivider(color = c.outlineVariant)
                ListRow(
                    title = cause.label,
                    sub = cause.sub(f.livingCost?.method),
                    value = "${cause.amount.toWon(withPlusSign = true)}원",
                    valueColor = if (cause.amount > 0) c.primary else c.text1,
                    dense = true,
                )
            }
        }
    }
}

/** '보수적으로 보면 안전 잔액보다 최대 70,000원 모자라요' */
internal fun riskLine(w: RiskWindow): String {
    val lead = when (w.line) {
        FlowConfidence.CONFIRMED -> "확정된 돈만으로도"
        FlowConfidence.EXPECTED -> "예상대로라면"
        FlowConfidence.CONSERVATIVE -> "보수적으로 보면"
        FlowConfidence.UNKNOWN -> null
    }
    val gap = w.shortfall?.takeIf { it > 0 }?.let { "안전 잔액보다 최대 ${it.toWon()}원 모자라요" } ?: "안전 잔액 아래로 내려가요"
    return listOfNotNull(lead, gap).joinToString(" ")
}

/** 세 선은 이렇게 계산했어요 — 지금 잔액에서 선마다 무엇을 뺐는지(FR-047 확정 · 예측 구분) */
@Composable
private fun LinesCard(f: Forecast) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        Text("세 선은 이렇게 계산했어요", style = YamuTheme.typography.cardTitle, color = c.text1)
        Spacer(Modifier.height(12.dp))
        f.currentBalance?.let { b ->
            StepRow("지금 잔액 · 계좌 ${f.accountIds.size}개", "${b.toWon()}원", strong = true)
            Spacer(Modifier.height(12.dp))
        }
        f.lineGroups().forEachIndexed { i, g ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            LineGroupRow(g, hasConservative = f.hasConservative)
        }
    }
}

@Composable
private fun LineGroupRow(g: LineGroup, hasConservative: Boolean) {
    val c = YamuTheme.colors
    val (name, color, dashed) = when (g.line) {
        FlowConfidence.CONFIRMED -> Triple("확정 선", c.text3, false)
        FlowConfidence.EXPECTED -> Triple("예상 선", c.primary, false)
        else -> Triple("보수적 선", c.primary, true)
    }
    Row {
        Canvas(Modifier.padding(top = 9.dp).size(width = 18.dp, height = 4.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.6.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            g.steps.forEach { s -> StepRow(listOfNotNull(s.label, s.note).joinToString(" · "), "${s.amount.toWon(withPlusSign = true)}원") }
            if (g.line == FlowConfidence.CONSERVATIVE && !hasConservative) {
                Text("생활비 기록이 없어 이번에는 그리지 않았어요", style = YamuTheme.typography.bodyM, color = c.text3)
            } else {
                StepRow("$name 끝", g.end?.let { "${it.toWon()}원" } ?: "—", muted = true)
            }
        }
    }
}

@Composable
private fun StepRow(label: String, value: String, strong: Boolean = false, muted: Boolean = false) {
    val c = YamuTheme.colors
    val style = if (muted) YamuTheme.typography.bodyS else YamuTheme.typography.bodyM
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = style, color = if (muted) c.text3 else if (strong) c.text2 else c.text1, modifier = Modifier.weight(1f))
        Text(value, style = if (strong) YamuTheme.typography.titleS else style, color = if (muted) c.text3 else c.text1)
    }
}

/** 예정된 돈 — 위 5줄. 카드 사용 예상은 '추정' 태그와 근거 문장(서버 title 그대로) */
@Composable
private fun UpcomingCard(f: Forecast, onCashflowList: () -> Unit) {
    val c = YamuTheme.colors
    // 목록 카드는 안쪽 16(프로토타입 .card.tight) · 머리 줄 24dp. '전체 보기' 터치 칸(48)은 머리 줄 위아래로 넘친다(.card-hd .more 음수 여백)
    YamuCard(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${cashflowPeriod(f)} 예정된 돈", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .overflowHeight(24.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(YamuRadius.s))
                    .clickable(role = Role.Button, onClick = onCashflowList)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("전체 보기", style = YamuTheme.typography.link, color = c.text3)
                Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
            }
        }
        val rows = f.upcoming
        if (rows.isEmpty()) {
            Text("${cashflowPeriod(f)} 예정된 돈이 없어요", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(vertical = 12.dp))
        }
        rows.take(5).forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            EventRow(e)
        }
    }
}

@Composable
private fun EventRow(e: ForecastEvent) {
    val c = YamuTheme.colors
    ListRow(
        title = e.label,
        sub = listOfNotNull(e.date.toMonthDayWeek(), e.basis).joinToString(" · "),
        value = "${e.amount.toWon(withPlusSign = true)}원",
        valueColor = when {
            e.amount > 0 -> c.primary
            e.isEstimate -> c.text2
            else -> c.text1
        },
        titleTag = when {
            e.isEstimate -> ({ StatusTag("추정", tone = TagTone.Neutral) })
            e.isExpected -> ({ StatusTag("예상", tone = TagTone.Neutral) })
            else -> null
        },
        dense = true,
    )
}

/** 빼고 계산한 것 — 생활비 미반영(dataOrigin 기준) · 월급을 못 찾음 · excluded 이유마다 한 줄 */
@Composable
private fun NoticeCard(f: Forecast, selection: ForecastSelection) {
    val notes = noticeLines(f, selection)
    if (notes.isEmpty()) return
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text("빼고 계산한 것", style = YamuTheme.typography.titleS, color = c.text1)
        Spacer(Modifier.height(8.dp))
        notes.forEach { Text(it, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

internal fun noticeLines(f: Forecast, selection: ForecastSelection): List<String> {
    val living = f.livingCost
    val livingLine = when {
        living == null || !living.insufficient -> null
        f.hasConservative -> "생활비 기록이 없어 보수적 선에는 카드 사용 예상만 넣었어요."
        // 어느 계좌에 기록이 있는지는 이 응답만으로 모른다 — 모든 계좌에 없을 수도 있어 '고르면 된다'고 약속하지 않는다
        f.accountIds.size > 1 -> "생활비 기록이 없는 계좌가 있어 생활비는 빼고 계산했어요. 계좌를 따로 골라 보면 기록이 있는 계좌는 생활비까지 넣어 계산해요."
        else -> "생활비 기록이 아직 없어 생활비는 빼고 계산했어요. 체크카드로 결제하면 다음 예보부터 들어가요."
    }
    val fallback = if (selection.period == ForecastPeriod.NEXT_INCOME && !f.isNextIncome) "월급날을 찾지 못해 ${f.horizonDays ?: 30}일로 계산했어요." else null
    return listOfNotNull(livingLine, fallback) + f.excluded.map { it.text }.distinct()
}

/** 기간 · 계좌 고르기 — 고른 값은 앱을 켜 둔 동안만 기억(서버 설정은 그대로) */
@Composable
private fun FilterSheet(
    forecast: Forecast?,
    selection: ForecastSelection,
    accounts: List<ForecastAccountOption>?,
    accountsError: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (ForecastPeriod?, Set<Long>?) -> Unit,
) {
    val c = YamuTheme.colors
    val periods = ForecastPeriod.entries
    val shown = selection.period ?: when {
        forecast == null -> null
        forecast.isNextIncome -> ForecastPeriod.NEXT_INCOME
        else -> periods.firstOrNull { it.days == forecast.horizonDays }
    }
    var period by rememberSaveable { mutableIntStateOf(shown?.ordinal ?: -1) }
    var checked by remember(accounts) { mutableStateOf(selection.accountIds ?: forecast?.accountIds?.toSet() ?: accounts.orEmpty().map { it.accountId }.toSet()) }
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("예보 기간", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(14.dp))
        Segmented(periods.map { it.label }, selectedIndex = period, onSelect = { period = it })
        Spacer(Modifier.height(24.dp))
        Text("계좌", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(6.dp))
        when {
            accounts == null && accountsError != null -> ErrorState(accountsError, onRetry = onRetry)
            accounts == null -> repeat(2) {
                SkeletonBlock(height = 48.dp)
                Spacer(Modifier.height(8.dp))
            }
            else -> accounts.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                val on = a.accountId in checked
                ListRow(
                    title = a.name,
                    sub = listOfNotNull(a.bankName, a.last4.takeIf { it.isNotEmpty() }?.let { "··$it" }, a.balance?.let { "${it.toWon()}원" }).joinToString(" · "),
                    titleTag = if (a.isPrimary) ({ StatusTag("기준", tone = TagTone.Neutral) }) else null,
                    lead = { RoundCheckbox(checked = on, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = on, role = Role.Checkbox, onValueChange = { checked = if (it) checked + a.accountId else checked - a.accountId }),
                )
            }
        }
        Spacer(Modifier.height(YamuSpacing.x4))
        PrimaryButton(
            "이대로 보기",
            onClick = { onApply(periods.getOrNull(period), checked.takeIf { accounts != null }) },
            enabled = accounts == null || checked.isNotEmpty(),
        )
        if (accounts != null && checked.isEmpty()) {
            Text("계좌를 하나 이상 골라 주세요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ───────────────────────── F-02 예정 수입 · 지출 ─────────────────────────

/** F-02 한 줄 — 가계부 항목이면 눌러서 고치고(F-02b), 카드 청구는 읽기 전용. 반복이면 날짜 옆에 '매월 말일'처럼 */
@Composable
private fun CashflowRowLine(row: CashflowRow, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    ListRow(
        title = row.title,
        sub = listOfNotNull(row.date?.toMonthDayWeek(), row.repeatLabel, row.cardNote).joinToString(" · "),
        value = "${row.amount.toWon(withPlusSign = true)}원",
        valueColor = if (row.amount > 0) c.primary else c.text1,
        dense = true,
        onClick = onClick,
    )
}

@Composable
fun CashflowListRoute(
    onBack: () -> Unit,
    onLink: () -> Unit = {},
    onAdd: () -> Unit = {},
    onEdit: (itemId: Long, occurrenceDate: LocalDate) -> Unit = { _, _ -> },
    viewModel: ForecastViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshWithEntries()   // 추가(F-02a) · 수정(F-02b)하고 돌아오면 새로
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    // 회색 바탕 + 카드: 요약 · 달마다 하나(프로토타입 F-02). 계좌를 이었을 때만 + (추가는 계좌 · 카드가 있어야 한다)
    FlowScreen(
        title = "예정 수입·지출",
        onBack = onBack,
        cards = true,
        actions = {
            if (!s.noAccount) IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, contentDescription = "예정 수입·지출 추가", tint = c.text1) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val f = s.forecast
            when {
                s.cashflowLoading -> YamuCard {
                    repeat(4) {
                        SkeletonBlock(height = 48.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                // 계좌를 아직 안 이었으면 오류가 아니라 빈 상태(F-01과 같은 문장 · 행동)
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 예정된 돈을 보여 드려요", actionText = "계좌 연결", onAction = onLink) }
                f == null && s.entries == null -> YamuCard { ErrorState(s.cashflowError ?: "예정된 돈을 불러오지 못했어요", onRetry = viewModel::refreshWithEntries) }
                else -> {
                    // 가계부 회차를 받았으면 그 줄(고칠 수 있음) + 카드 청구, 못 받았으면 예보 줄만(읽기 전용, 추정은 빼고)
                    val entries = s.entries
                    val rows = if (entries != null) cashflowRows(entries, f) else {
                        requireNotNull(f)   // 둘 다 없으면 위에서 오류 화면
                        f.upcoming.filter { !it.isEstimate }.map { CashflowRow(it.date, it.title, it.amount, null, null) }
                    }
                    val period = cashflowPeriod(f)
                    YamuCard {
                        Text("$period 나갈 돈", style = YamuTheme.typography.titleS, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        val (outAmount, outCount) = cashflowOutflow(f, rows)
                        AmountText(outAmount)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                if (f?.from != null && f.to != null) "${f.from.toMonthDay()} ~ ${f.to.toMonthDay()}" else null,
                                "출금 ${outCount}건",
                            ).joinToString(" · "),
                            style = YamuTheme.typography.bodyM,
                            color = c.text2,
                        )
                    }
                    s.entriesError?.let { YamuCard { ErrorState("고칠 수 있는 목록을 불러오지 못했어요 · $it", onRetry = viewModel::refreshWithEntries) } }
                    if (rows.isEmpty()) YamuCard { EmptyState("$period 예정된 돈이 없어요", actionText = "추가", onAction = onAdd) }
                    rows.groupBy { it.date?.monthValue }.forEach { (month, monthRows) ->
                        YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
                            month?.let { Text("${it}월", style = YamuTheme.typography.cardTitle, color = c.text1) }
                            monthRows.forEachIndexed { i, row ->
                                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                                val entry = row.entry
                                CashflowRowLine(row, onClick = entry?.let { { onEdit(it.itemId, it.occurrenceDate) } })
                            }
                        }
                    }
                    // 지난 7일 카드 예정 지출 — 결제와 연결만(계좌를 일부만 골랐으면 그 계좌 카드만)
                    val scope = f?.scope
                    PastCardEntriesCard(s.pastEntries.filter { scope?.includes(it) ?: true }, onOpen = { onEdit(it.itemId, it.occurrenceDate) })
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/** 보이는 높이는 [visible]로 두고 실제 칸(터치 48dp)은 위아래로 넘치게 놓는다 — 프로토타입의 음수 여백 링크(.card-hd .more) */
private fun Modifier.overflowHeight(visible: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = visible.roundToPx()
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}
