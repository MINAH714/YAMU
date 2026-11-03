package com.yamu.app.feature.forecast

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.YamuCard
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.MoreDetails
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.formatSyncedAt
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.staleLine
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowRow
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.cashflowRows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class ForecastState(
    val loading: Boolean = true,
    val forecast: Forecast? = null,
    val error: String? = null,
    val noAccount: Boolean = false,
    val entries: List<CashflowEntry>? = null,     // F-02 가계부 회차(고칠 수 있는 줄) — 못 받으면 예보 줄만(읽기 전용)
    val entriesError: String? = null,
)

/** F-01 · F-02 — 돌아올 때마다 새로(결제 · 계획 저장 · 지우기 · 추가 · 수정 뒤 바뀌므로) */
@HiltViewModel
class ForecastViewModel @Inject constructor(private val repo: ForecastRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForecastState(forecast = repo.cached(), loading = repo.cached() == null))
    val state: StateFlow<ForecastState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch { refreshForecast() }
    }

    /** F-02 — 예보(합계 · 카드대금)를 받은 뒤 같은 기간(오늘 ~ 다음 월급날)의 가계부 회차. 예보가 없으면 오늘부터 30일 */
    fun refreshWithEntries() {
        viewModelScope.launch {
            refreshForecast()
            if (_state.value.noAccount) return@launch
            // 예보가 실패하면 메모리에 지난 값이 남아 있다 — 그 from을 쓰면 자정을 넘겼을 때 어제 회차를 읽는다
            val f = _state.value.forecast.takeIf { _state.value.error == null }
            val from = f?.from ?: LocalDate.now(KST)
            val to = f?.incomeDate ?: f?.to ?: from.plusDays(29)   // 서버 기본값처럼 첫날을 넣어 30일
            when (val r = repo.cashflowEntries(from, to)) {
                is ApiResult.Success -> _state.update { it.copy(entries = r.data, entriesError = null) }
                is ApiResult.Failure -> _state.update { it.copy(entriesError = r.error.userMessage()) }
            }
        }
    }

    private suspend fun refreshForecast() {
        _state.update { it.copy(loading = it.forecast == null, error = null) }
        when (val r = repo.forecast()) {
            is ApiResult.Success -> _state.update { it.copy(loading = false, forecast = r.data, noAccount = false) }
            is ApiResult.Failure -> _state.update {
                if (r.error.code == "BASE_ACCOUNT_NOT_SET") ForecastState(loading = false, noAccount = true)
                else it.copy(loading = false, error = r.error.userMessage())
            }
        }
    }
}

private fun LocalDate.daysFromToday(): Long = ChronoUnit.DAYS.between(LocalDate.now(KST), this)

// ───────────────────────── F-01 잔고예보 ─────────────────────────

@Composable
fun ForecastRoute(
    onBack: () -> Unit,
    onBuyCheck: () -> Unit,
    onCashflowList: () -> Unit,
    onPlan: (planId: Long) -> Unit,
    onLink: () -> Unit,
    onAlertSettings: () -> Unit = {},
    viewModel: ForecastViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    // 회색 바탕 + 카드 셋: 이거 사도 될까? 입구 · 예상 잔액 · 예정된 돈(프로토타입 F-01)
    FlowScreen(
        title = "잔고예보",
        onBack = onBack,
        cards = true,
        actions = {
            IconButton(onClick = onAlertSettings) { Icon(YamuIcons.Notifications, contentDescription = "잔액 알림 설정", tint = c.text1) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {   // 첫 카드는 앱바 바로 아래(프로토타입 F-01 — 위 여백 없음)
            BuyCheckEntry(onClick = onBuyCheck)
            val f = s.forecast
            when {
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 예보를 보여 드려요", actionText = "계좌 연결", onAction = onLink) }
                f == null && s.loading -> YamuCard {
                    SkeletonBlock(height = 28.dp, widthFraction = 0.5f)
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 96.dp)
                }
                f == null -> YamuCard { ErrorState(s.error ?: "예보를 불러오지 못했어요", onRetry = viewModel::refresh) }
                else -> ForecastBody(f, s.error, onCashflowList, onPlan)
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/** 맨 위 '이거 사도 될까?' 입구 — 흰 카드(안쪽 16), 가격 칸을 누르면 I-01 */
@Composable
private fun BuyCheckEntry(onClick: () -> Unit) {
    val c = YamuTheme.colors
    YamuCard(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "이거 사도 될까? 살 물건 가격 넣기" },
        onClick = onClick,
        contentPadding = PaddingValues(YamuSpacing.x4),
    ) {
        Text("이거 사도 될까?", style = YamuTheme.typography.cardTitle, color = c.text1)
        Spacer(Modifier.height(2.dp))
        Text("얼마짜리를 살지 넣으면 바로 알려 드려요", style = YamuTheme.typography.sub, color = c.text3)
        Spacer(Modifier.height(YamuSpacing.x3))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(YamuRadius.input))
                .background(c.surfaceVariant)
                .padding(horizontal = YamuSpacing.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("살 물건 가격", style = YamuTheme.typography.bodyL, color = c.text3, modifier = Modifier.weight(1f))
            Text("원", style = YamuTheme.typography.titleM, color = c.text2)
        }
    }
}

@Composable
private fun ForecastBody(f: Forecast, error: String?, onCashflowList: () -> Unit, onPlan: (Long) -> Unit) {
    val c = YamuTheme.colors
    val short = (f.minBalance ?: 0) < 0 || f.shortfallFrom != null
    // '자세히'로 끝나는 카드는 아래 8(프로토타입 .card:has(> details.more:last-child))
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (f.fallback || f.incomeDate == null) "30일 뒤까지 예상 잔액" else "월급 전 예상 잔액", style = YamuTheme.typography.titleS, color = c.text2)
            if (f.isEstimate) {
                Spacer(Modifier.width(6.dp))
                EstimateTag()
            }
        }
        Spacer(Modifier.height(4.dp))
        AmountText(f.minBalance ?: 0, shortage = short, color = c.primary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTag(
                f.shortfallFrom?.let { "${it.toMonthDayWeek()}부터 부족 예상" } ?: if (f.incomeDate != null) "월급날까지 부족 없음" else "부족 없음",
                tone = if (short) TagTone.Error else TagTone.Success,
            )
            f.incomeDate?.let { Text("${f.incomeTitle} D-${it.daysFromToday()}", style = YamuTheme.typography.bodyS, color = c.text3) }
        }
        error?.let { Text(staleLine(it, f.balanceAsOfIso), style = YamuTheme.typography.bodyS, color = c.warning, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(YamuSpacing.x5))
        ForecastChart(f.points, f.incomeDate, planDates = f.plans.mapNotNull { it.date })

        // 구매 계획(F-01d · F-01i) — 월급 뒤면 이번 숫자에 안 들어감. 계획마다 위 12 · 선 · 8 · 줄 48(프로토타입 .plan)
        f.plans.forEach { p ->
            Spacer(Modifier.height(YamuSpacing.x3))
            HorizontalDivider(color = c.outlineVariant)
            Spacer(Modifier.height(YamuSpacing.x2))
            PlanRow(p, onClick = { p.planId?.let(onPlan) })
        }

        Spacer(Modifier.height(YamuSpacing.x2))
        MoreDetails {
            val sub = YamuTheme.typography.sub
            f.currentBalance?.let { b ->
                Text(listOfNotNull("현재 잔액 ${b.toWon()}원", formatSyncedAt(f.balanceAsOfIso)?.replace(" 반영", "")).joinToString(" · "), style = sub, color = c.text2)
            }
            Text(
                listOfNotNull("예정 지출 ${(-f.scheduledOutflow).toWon()}원", f.livingCost?.let { "생활비 ${(-it).toWon()}원 예측" }).joinToString(" · "),
                style = sub,
                color = c.text2,
            )
            // 계좌별(10/26 회의) — 한 줄씩
            f.accounts.forEach { a ->
                Text(
                    listOfNotNull(
                        listOfNotNull(a.name, a.last4.takeIf { it.isNotEmpty() }?.let { "·$it" }).joinToString(" "),
                        a.minBalance?.let { "월급 전 ${it.toWon()}원" },
                        a.shortfallFrom?.let { "${it.toMonthDay()}부터 부족" },
                    ).joinToString(" · "),
                    style = sub,
                    color = if ((a.minBalance ?: 0) < 0) c.error else c.text2,
                )
            }
        }
    }
    // 목록 카드는 안쪽 16(프로토타입 .card.tight) · 머리 줄 24dp. '전체 보기' 터치 칸(48)은 머리 줄 위아래로 넘친다(.card-hd .more 음수 여백)
    YamuCard(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("월급 전까지 예정된 돈", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            if (!short) {
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
        }
        f.upcoming.take(3).forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            EventRow(e)
        }
    }
}

@Composable
private fun PlanRow(p: ForecastEvent, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val title = if (p.title.endsWith("계획")) p.title else "${p.title} 계획"
    val sub = listOfNotNull(p.date?.toMonthDayWeek(), if (p.afterIncome) "월급 뒤" else null).joinToString(" · ")
    // 48dp — 이름 16 / 2 / 보조 13, 값 16(프로토타입 .plan b · .t-sub · .val)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = YamuTheme.typography.titleM, color = c.text1)
            Text(sub, style = YamuTheme.typography.sub, color = c.text3, modifier = Modifier.padding(top = 2.dp))
        }
        Text("${p.amount.toWon()}원", style = YamuTheme.typography.titleM, color = c.text1)
    }
}

@Composable
private fun EventRow(e: ForecastEvent) {
    val c = YamuTheme.colors
    ListRow(
        title = e.title,
        sub = e.date?.toMonthDayWeek(),
        value = "${e.amount.toWon(withPlusSign = true)}원",
        valueColor = if (e.amount > 0) c.primary else c.text1,
        dense = true,
    )
}

// ───────────────────────── F-02 예정 수입 · 지출 ─────────────────────────

/** F-02 한 줄 — 가계부 항목이면 눌러서 고치고(F-02b), 카드대금 · 월급 미리보기는 읽기 전용. 반복이면 날짜 옆에 '매월 말일'처럼 */
@Composable
private fun CashflowRowLine(row: CashflowRow, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    ListRow(
        title = row.title,
        sub = listOfNotNull(row.date?.toMonthDayWeek(), row.repeatLabel).joinToString(" · "),
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
                f == null && s.loading -> YamuCard {
                    repeat(4) {
                        SkeletonBlock(height = 48.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                // 계좌를 아직 안 이었으면 오류가 아니라 빈 상태(F-01과 같은 문장 · 행동)
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 예정된 돈을 보여 드려요", actionText = "계좌 연결", onAction = onLink) }
                f == null && s.entries == null -> YamuCard { ErrorState(s.error ?: "예정된 돈을 불러오지 못했어요", onRetry = viewModel::refreshWithEntries) }
                else -> {
                    // 가계부 회차를 받았으면 그 줄(고칠 수 있음) + 카드대금, 못 받았으면 예보 줄만(읽기 전용)
                    val entries = s.entries
                    val rows = if (entries != null) cashflowRows(entries, f) else {
                        requireNotNull(f)   // 둘 다 없으면 위에서 오류 화면
                        val income = f.incomeDate?.takeIf { (f.incomeAmount ?: 0) > 0 }?.let { CashflowRow(it, f.incomeTitle, f.incomeAmount ?: 0, null, null) }
                        (f.upcoming.map { CashflowRow(it.date, it.title, it.amount, null, null) } + listOfNotNull(income)).sortedBy { it.date }
                    }
                    YamuCard {
                        Text(if (f != null) "월급 전까지 나갈 돈" else "30일 동안 나갈 돈", style = YamuTheme.typography.titleS, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        AmountText(f?.scheduledOutflow ?: rows.filter { it.amount < 0 }.sumOf { -it.amount })
                        Spacer(Modifier.height(4.dp))
                        val outCount = rows.count { it.amount < 0 }
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
                    if (rows.isEmpty()) YamuCard { EmptyState("월급 전까지 예정된 돈이 없어요", actionText = "추가", onAction = onAdd) }
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
