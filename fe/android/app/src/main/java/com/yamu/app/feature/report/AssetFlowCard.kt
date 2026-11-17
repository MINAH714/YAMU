package com.yamu.app.feature.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.forecast.BalanceChart
import com.yamu.app.feature.forecast.BalanceZone
import com.yamu.app.feature.forecast.ChartLine
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.DaySelection
import com.yamu.app.feature.forecast.DaySource
import com.yamu.app.feature.forecast.DateStrip
import com.yamu.app.feature.forecast.hatchInk
import com.yamu.app.feature.forecast.hatchLabel
import com.yamu.app.feature.forecast.LINE_WITHOUT_LIVING
import com.yamu.app.feature.forecast.LINE_WITH_LIVING
import com.yamu.app.feature.forecast.MonthChart
import com.yamu.app.feature.forecast.StripDay
import com.yamu.app.feature.forecast.StripJump
import com.yamu.app.feature.forecast.accepts
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.drawHatch
import com.yamu.app.feature.forecast.kindShortLabel
import com.yamu.app.feature.forecast.splitByKind
import com.yamu.app.feature.forecast.toKoreanDateWeek
import com.yamu.app.feature.forecast.zoneOf
import com.yamu.app.feature.report.data.ASSET_FLOW_AFTER
import com.yamu.app.feature.report.data.AssetDay
import com.yamu.app.feature.report.data.AssetFlow
import com.yamu.app.feature.report.data.HistoryGap
import com.yamu.app.feature.report.data.MonthFlow
import com.yamu.app.feature.report.data.currentBalanceOf
import com.yamu.app.feature.report.data.gapReason
import com.yamu.app.feature.report.data.isFetchFailure
import com.yamu.app.feature.report.data.ShownMonth
import com.yamu.app.feature.report.data.accountTag
import com.yamu.app.feature.report.data.assetDays
import com.yamu.app.feature.report.data.beyondInMonth
import com.yamu.app.feature.report.data.forecastAfter
import com.yamu.app.feature.report.data.monthTitle
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * S-01 자산 흐름 칸(v4 ANALYSIS-01~15, S15P31E103-483) — 사용금액 바로 아래.
 * 머리(계좌 이름표 · 오늘로) → 지금 잔고(늘 오늘 기준) → 한 달 그래프 · 범례 · 날짜 띠 → 고른 날 칸 → 범위 밖 출금 → 7 · 14 · 30일 뒤 → 잔고예보로.
 * 그래프 끌기 · 띠 밀기 · 알약 누르기가 같은 날을 고르고(잔고예보와 같은 [DaySelection]), 가로로 움직일 때만 날을 바꾼다(세로는 페이지 스크롤, ANALYSIS-15)
 */
@Composable
internal fun AssetFlowCard(
    s: ReportState,
    onSelectDate: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onToday: () -> Unit,
    onRetry: () -> Unit,
    onForecast: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryMonth: () -> Unit = onRetry,
) {
    val c = YamuTheme.colors
    val a = s.asset
    val flow = a.flow
    val atToday = s.shown.month == YearMonth.from(s.today) && s.assetDate == s.today
    YamuCard(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("자산 흐름", style = YamuTheme.typography.cardTitle, color = c.text1)
            flow?.let {
                Spacer(Modifier.width(8.dp))
                AccountTag(accountTag(it.accounts))
            }
            Spacer(Modifier.weight(1f))
            if (flow != null && !atToday) LinkButton("오늘로", onClick = onToday)
        }
        when {
            a.noAccount -> EmptyState("계좌를 연결하면 자산 흐름을 보여 드려요")
            flow == null && a.loading -> {
                SkeletonBlock(height = 20.dp, widthFraction = 0.6f)
                Spacer(Modifier.height(YamuSpacing.x3))
                SkeletonBlock(height = 184.dp)
            }
            flow == null -> ErrorState(a.error ?: "자산 흐름을 불러오지 못했어요", onRetry = onRetry)
            else -> AssetFlowBody(s, flow, onSelectDate, onMonth, onRetry, onRetryMonth, onForecast)
        }
    }
}

@Composable
private fun AssetFlowBody(
    s: ReportState,
    flow: AssetFlow,
    onSelectDate: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onRetry: () -> Unit,
    onRetryMonth: () -> Unit,
    onForecast: () -> Unit,
) {
    val c = YamuTheme.colors
    val f = flow.forecast
    // 한 달 응답(asset-flows)이 같은 계좌일 때만 쓴다 — 아니면 예보만으로(옛 서버 · 실패 · 계좌가 바뀐 사이)
    val monthFlow = s.asset.month?.takeIf { it.month == s.shown.month && flow.matches(it) }
    val (current, asOf) = currentBalanceOf(flow, monthFlow)
    // 지금 잔고 — 지난달을 봐도 '오늘 기준'이라고 적는다(ANALYSIS-03). 기준 시각은 은행에서 잔액을 읽은 때(asset-flows balanceAsOf)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Text(nowLine(asOf, s.today), style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.weight(1f))
        Text(current?.let { "${it.toWon()}원" } ?: "확인할 수 없음", style = YamuTheme.typography.value, color = c.text1)
    }
    s.asset.error?.let { Text("$it · 마지막 값을 보여 드려요", style = YamuTheme.typography.bodyS, color = c.warning) }
    if (f == null) {
        // 계좌는 받았는데 예보만 실패 — 지금 잔고는 두고 예보 자리에만 다시 시도(명세 '예측 실패')
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("예보를 불러오지 못했어요", style = YamuTheme.typography.bodyS, color = c.warning, modifier = Modifier.weight(1f))
            LinkButton("다시 시도", onClick = onRetry)
        }
    }
    Spacer(Modifier.height(YamuSpacing.x3))
    val month = s.shown.month
    val days = remember(month, s.today, flow, monthFlow) { assetDays(month, s.today, flow, monthFlow) }
    // 달마다 그래프 · 띠를 새로 — 띠 스크롤 · 손잡이 위치 · 첫 안내가 앞 달 것을 이어받지 않게
    key(month) {
        val target = days.indexOfFirst { it.date == s.assetDate }.coerceAtLeast(0)
        val day = rememberSaveable(month.toString(), days.size, saver = DaySelection.saver(days.size)) { DaySelection(target, days.size) }
        // 바깥(오늘로 · 홈에서 내려옴)에서 날을 바꿨을 때만 따라간다 — 여기서 고른 날이 ViewModel을 돌아 늦게 와도 손잡이를 되돌리지 않게
        LaunchedEffect(s.assetReset) { if (target != day.day) day.select(target, DaySource.LIST) }
        var chartDragging by remember { mutableStateOf(false) }
        val select: (Int, DaySource) -> Unit = { i, from ->
            if (accepts(from, chartDragging)) {
                day.select(i, from)
                days.getOrNull(day.day)?.let { onSelectDate(it.date) }
            }
        }
        val kinds = remember(days) { days.map { it.kind } }
        val (actual, expected) = remember(days) { splitByKind(days.map { it.value }, kinds) }
        val lines = listOf(
            ChartLine("실제", actual, c.primary, width = 2.6.dp),
            ChartLine("예상", expected, c.primary, dashed = true, width = 2.2.dp),
        )
        BalanceChart(
            dates = days.map { it.date },
            lines = lines,
            description = assetChartDescription(month, kinds, lineWithLiving(flow, monthFlow)),
            eventDays = remember(days) { days.indices.filter { days[it].events.isNotEmpty() }.toSet() },
            selected = day.day,
            onSelect = { select(it, DaySource.CHART) },
            dayLabel = { assetDayLabel(days[it]) },
            dayState = { assetDayState(days[it]) },
            interacted = day.touched,
            onInteract = day::touch,
            onDragChange = { chartDragging = it },
            month = MonthChart(kinds),
        )
        Spacer(Modifier.height(10.dp))
        AssetLegend(kinds, hasConservative = lineWithLiving(flow, monthFlow))
        Spacer(Modifier.height(6.dp))
        val strip = remember(days) { days.map { StripDay(it.date, it.events.sumOf { e -> e.amount }, it.value?.let { v -> zoneOf(v, null) } ?: BalanceZone.OK, it.kind) } }
        DateStrip(
            days = strip,
            selected = day.day,
            source = day.source,
            onSelect = select,
            hint = !day.touched,
            bleed = 20.dp,   // 카드 안쪽 여백만큼 카드 끝까지
            leading = s.shown.takeIf { it.canPrev }?.let { sh -> monthTitle(sh.prev, sh.thisMonth).let { t -> StripJump("‹ $t 보기", "$t 보기", onClick = { onMonth(sh.prev) }) } },
            trailing = s.shown.takeIf { it.canNext }?.let { sh -> monthTitle(sh.next, sh.thisMonth).let { t -> StripJump("$t 보기 ›", "$t 보기", onClick = { onMonth(sh.next) }) } },
        )
        Spacer(Modifier.height(10.dp))
        days.getOrNull(day.day)?.let { AssetDayPanel(it, flow, monthFlow, s.today, current, asOf) }
    }
    // 지난 잔고를 못 받은 달 — 다시 시도(옛 서버 404는 오류가 아니라 여기 없다)
    s.asset.monthError?.let { err ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("지난 잔고를 불러오지 못했어요 · $err", style = YamuTheme.typography.bodyS, color = c.warning, modifier = Modifier.weight(1f))
            LinkButton("다시 시도", onClick = onRetryMonth)
        }
    }
    // 지난 잔고를 확인하지 못한 구간과 까닭(asset-flows partialErrors) — 같은 까닭은 한 줄로
    monthFlow?.let { m -> gapLines(m) }?.take(GAP_LINES)?.forEach { line ->
        Text(line, style = YamuTheme.typography.bodyS, color = c.text2, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp))
    }
    // 은행 · 시간 초과로 못 받은 구간은 다시 하면 될 수 있다(자료 문제인 까닭은 다시 해도 같아 버튼을 두지 않는다)
    if (s.asset.monthError == null && monthFlow?.gaps?.any { it.isFetchFailure() } == true) {
        LinkButton("지난 잔고 다시 불러오기", onClick = onRetryMonth)
    }
    // 범위 밖 큰 출금 — 그래프에 없다는 걸 한 줄로(FORECAST-06, 11/12 신용카드 계획이 안 보이던 자리)
    val beyond = remember(f, month) { beyondInMonth(f, month) }
    beyond.take(BEYOND_LINES).forEach { e ->
        Text(
            "${e.date.toMonthDay()} ${e.label} ${(-e.amount).toWon()}원은 예보 범위 밖이라 그래프에 없어요",
            style = YamuTheme.typography.bodyS,
            color = c.onWarningContainer,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.warningContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    if (beyond.size > BEYOND_LINES) Text("범위 밖 출금 ${beyond.size - BEYOND_LINES}건 더", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
    if (f != null) {
        Spacer(Modifier.height(12.dp))
        // 7 · 14 · 30일 뒤 — 보는 달과 상관없이 오늘부터(ANALYSIS-12)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ASSET_FLOW_AFTER.forEach { n ->
                val (date, value) = forecastAfter(f, s.today, n)
                AfterBox(n, date, value, Modifier.weight(1f))
            }
        }
        // 아래 '예보는 보는 달과 상관없이 오늘부터 세요 · 30일 안 가장 낮을 때' 안내 줄은 11/16 사용자 요청으로 뺐다(S15P31E103-519).
        // 상자마다 날짜가 붙어 있고 30일 최저는 홈 잔고예보 요약 · 잔고예보 화면이 말한다
    }
    Spacer(Modifier.height(12.dp))
    // 회색이라 눈에 안 띄던 자리를 브랜드 색으로 — 홈 잔고예보 칸의 '잔고예보 보기'와 같은 버튼(소비분석 탭에 다른 주 버튼은 없다)
    PrimaryButton("AI 잔고예보 자세히 보기", onClick = onForecast)
}

/** 계좌 이름표 — 회색 알약 '신한 ··5678'(COMMON-04: 어느 계좌 잔고인지) */
@Composable
private fun AccountTag(text: String) {
    val c = YamuTheme.colors
    Text(
        text,
        style = YamuTheme.typography.labelM,
        color = c.text2,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "계좌 $text" },
    )
}

/** 범례 — 선 모양 · 빗금 견본 + 이름. 빗금은 그달에 그 구간이 있을 때만 */
@Composable
private fun AssetLegend(kinds: List<DayKind>, hasConservative: Boolean) {
    val style = YamuTheme.typography.bodyS
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        // 실선은 '과거 잔고' 하나로 — '실제'와 '과거 잔고'가 같은 실선을 두 번 말하던 것을 11/17 사용자 요청으로 '실제'를 뺐다
        if (DayKind.ACTUAL in kinds || DayKind.SIMULATED in kinds) LegendItem("과거 잔고", style) { LineSwatch(dashed = false) }
        LegendItem("예상(${if (hasConservative) LINE_WITH_LIVING else LINE_WITHOUT_LIVING})", style) { LineSwatch(dashed = true) }
        // 빗금은 그달에 그 구간이 있을 때만 — 이름은 그래프 빗금 위 글자와 같다
        listOf(DayKind.NO_DATA, DayKind.ERROR, DayKind.UNKNOWN, DayKind.OUT_OF_RANGE).filter { it in kinds }.forEach { k ->
            LegendItem(hatchLabel(k), style) { HatchSwatch(k) }
        }
    }
}

@Composable
private fun LegendItem(label: String, style: androidx.compose.ui.text.TextStyle, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(5.dp))
        Text(label, style = style, color = YamuTheme.colors.text2)
    }
}

@Composable
private fun LineSwatch(dashed: Boolean) {
    val color = YamuTheme.colors.primary
    Canvas(Modifier.size(width = 18.dp, height = 6.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.4.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null,
        )
    }
}

@Composable
private fun HatchSwatch(kind: DayKind) {
    val c = YamuTheme.colors
    val ink = hatchInk(kind, c.text3, c.warning)
    Canvas(Modifier.size(width = 18.dp, height = 10.dp).clip(RoundedCornerShape(2.dp))) { drawHatch(Offset.Zero, size, ink) }
}

/** 고른 날 칸(ANALYSIS-09) — 날짜 · 종류 꼬리표 · 잔고 · 주요 입출금. 그래프 · 띠 · 알약 어느 쪽으로 골라도 같은 칸 */
@Composable
private fun AssetDayPanel(d: AssetDay, flow: AssetFlow, monthFlow: MonthFlow?, today: LocalDate, current: Long?, asOf: ZonedDateTime) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceVariant)
            .padding(14.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.toKoreanDateWeek(), style = YamuTheme.typography.titleS, color = c.text1)
            Spacer(Modifier.width(8.dp))
            if (!d.simulated) StatusTag(kindShortLabel(d.kind), tone = kindTone(d.kind))
        }
        val (main, key) = panelValue(d, flow, monthFlow, today, current, asOf)
        main?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = YamuTheme.typography.amountM, color = if ((panelAmount(d, current) ?: 0) < 0) c.error else c.text1)
        }
        Text(key, style = YamuTheme.typography.bodyS, color = if (d.kind == DayKind.ERROR) c.warning else c.text3, modifier = Modifier.padding(top = if (main == null) 6.dp else 0.dp))
        panelNote(d, current)?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text2, modifier = Modifier.padding(top = 2.dp)) }
        if (showsDayEvents(d.kind)) {
            Spacer(Modifier.height(8.dp))
            if (d.events.isEmpty()) Text("이날 주요 입출금은 없어요", style = YamuTheme.typography.bodyS, color = c.text3)
            d.events.forEach { e -> EventRow(e, today = d.kind == DayKind.TODAY) }
        }
    }
}

@Composable
private fun EventRow(e: ForecastEvent, today: Boolean) {
    val c = YamuTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.label, style = YamuTheme.typography.bodyM, color = c.text1, maxLines = 1)
            Text(eventSub(e, today), style = YamuTheme.typography.bodyS, color = c.text3, maxLines = 1)
        }
        Text("${e.amount.toWon(withPlusSign = true)}원", style = YamuTheme.typography.value, color = if (e.amount > 0) c.primary else c.text1, textAlign = TextAlign.End)
    }
}

/** 7 · 14 · 30일 뒤 칸 — 예보에 그날이 없으면 '—' */
@Composable
private fun AfterBox(days: Int, date: LocalDate, value: Long?, modifier: Modifier) {
    val c = YamuTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${days}일 뒤", style = YamuTheme.typography.bodyS, color = c.text3)
        Text(date.toMonthDayWeek(), style = YamuTheme.typography.bodyS, color = c.text3)
        Box(Modifier.height(2.dp))
        // 상자가 좁아 큰 글자(1.3배)에서 끝의 '원'이 잘렸다 — 잔고예보 DayValue처럼 한 줄로 두고 글자를 줄이며, 그래도 넘치면 말줄임
        val style = YamuTheme.typography.titleS
        Text(
            value?.let { "${it.toWon()}원" } ?: "—",
            style = style,
            color = if ((value ?: 0) < 0) c.error else c.text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
    }
}

private const val BEYOND_LINES = 2
private const val GAP_LINES = 2
private val HM = DateTimeFormatter.ofPattern("HH:mm")

// ── 글자(단위 테스트) ──

/**
 * '오늘 14:05 기준' — 자정을 넘겨 다시 받기 전이면 그날 날짜로. 금액 옆에 붙어 무엇의 기준인지 보여서
 * '지금 잔고' 이름은 11/16 사용자 요청으로 뺐다(S15P31E103-519, 홈 잔고예보 요약의 '지금 잔고' 줄은 그대로)
 */
internal fun nowLine(loadedAt: ZonedDateTime, today: LocalDate): String =
    "${if (loadedAt.toLocalDate() == today) "오늘" else loadedAt.toLocalDate().toMonthDay()} ${loadedAt.format(HM)} 기준"

/** 종류 꼬리표 색 — 글자가 먼저고 색은 거든다(COMMON-11). 조회 실패만 주황 */
internal fun kindTone(kind: DayKind): TagTone = when (kind) {
    DayKind.TODAY -> TagTone.Primary
    DayKind.ERROR -> TagTone.Warning
    else -> TagTone.Neutral
}

/**
 * 고른 날 칸의 큰 금액과 그 밑 설명. 오늘은 칸 머리와 같은 지금 잔고([current], [asOf]).
 * 빈 날은 까닭을 나눠 적는다 — 조회 실패(다시 시도) · 자료 없음(그 구간의 partialErrors 까닭) · 범위 밖 · 표시 불가
 */
internal fun panelValue(d: AssetDay, flow: AssetFlow, monthFlow: MonthFlow?, today: LocalDate, current: Long?, asOf: ZonedDateTime): Pair<String?, String> = when (d.kind) {
    DayKind.ACTUAL, DayKind.SIMULATED -> d.value?.let { "${it.toWon()}원" } to (if (d.simulated) "그날 끝 잔고" else "그날 끝 실제 잔고")
    DayKind.TODAY -> current?.let { "${it.toWon()}원" } to (if (current == null) "오늘 잔고를 확인하지 못했어요" else nowLine(asOf, today))
    DayKind.EXPECTED -> d.value?.let { "${it.toWon()}원" } to "예상 잔고 · ${if (lineWithLiving(flow, monthFlow)) LINE_WITH_LIVING else LINE_WITHOUT_LIVING}"
    DayKind.NO_DATA -> null to when {
        d.date.isAfter(today) -> "예보를 불러오지 못해 비워 뒀어요."
        monthFlow == null -> "이날 잔고는 아직 받을 수 없어요. 쓴 돈으로 잔고를 만들어 보여 드리지 않아요."
        else -> (gapOn(monthFlow, d.date)?.let { gapReason(it.reason) } ?: "이날 잔고를 확인할 자료가 없어요") + ". 쓴 돈으로 잔고를 만들어 보여 드리지 않아요."
    }
    DayKind.ERROR -> null to (gapOn(monthFlow, d.date)?.let { gapReason(it.reason) } ?: "이날 잔고를 은행에서 불러오지 못했어요") + ". 잠시 뒤 다시 시도해 주세요."
    DayKind.UNKNOWN -> null to "이날 잔고는 지금 앱에서 표시할 수 없어요. 앱을 업데이트하면 보일 수 있어요."
    DayKind.OUT_OF_RANGE -> null to "예보는 오늘부터 30일(${(monthFlow?.forecastTo ?: flow.forecastEnd)?.toMonthDay() ?: "30일 뒤"})까지만 해요."
}

/**
 * 날짜 칸에 그날 입출금 줄('없어요' 포함)을 둘지. 오늘 · 앞날은 예보의 입출금이 있다. 지난날(ACTUAL)은 asset-flows가 지난 거래 목록을
 * 주지 않아(events는 늘 빈 배열) 잔고가 줄어든 날에도 '입출금이 없다'고 단정하게 되므로 빼고 그날 끝 잔고만 보인다
 */
internal fun showsDayEvents(kind: DayKind): Boolean = kind == DayKind.TODAY || kind == DayKind.EXPECTED

/**
 * 앞날 점선 이름('AI 생활비 반영' · '제외') — 점선을 그린 응답으로 정한다. 그달 자산 흐름([monthFlow], 같은 계좌일 때만 넘어온다)에
 * 예보 점이 있으면 그 conservative로, 없으면 30일 예보로. 30일 예보만 실패하고 asset-flows는 받은 경우 '제외'로 붙던 것(!320 리뷰)
 */
internal fun lineWithLiving(flow: AssetFlow, monthFlow: MonthFlow?): Boolean = monthFlow?.hasConservative ?: (flow.forecast?.hasConservative == true)

/**
 * 날짜 칸 큰 금액에 보이는 값 — 오늘은 지금 잔고([current]), 다른 날은 그날 값. 빨간 글자(0원 아래)는 이 값의 부호로 정한다
 * (오늘 칸이 지금 잔고를 보이면서 오늘 끝 예상의 부호로 색을 정하던 것, !312 리뷰)
 */
internal fun panelAmount(d: AssetDay, current: Long?): Long? = if (d.kind == DayKind.TODAY) current else d.value

/** 오늘 칸 덧줄 — 오늘 입출금이 있어 하루 끝 예상([AssetDay.endOfDay])이 지금 잔고와 다르면 */
internal fun panelNote(d: AssetDay, current: Long?): String? {
    if (d.kind != DayKind.TODAY) return null
    val end = d.endOfDay ?: return null
    if (end == current) return null
    return "오늘 끝 예상 ${end.toWon()}원"
}

/** 그날을 덮는 '확인하지 못한 구간' — 날짜를 모르는 구간은 그달 전체로 본다 */
internal fun gapOn(monthFlow: MonthFlow?, date: LocalDate): HistoryGap? =
    monthFlow?.gaps?.firstOrNull { g -> (g.from == null || !date.isBefore(g.from)) && (g.to == null || !date.isAfter(g.to)) }

/** 그래프 밑 안내 — '11/1~11/12 · 은행 연결이 안 돼 지난 잔고를 불러오지 못했어요'. 같은 까닭은 한 줄 */
internal fun gapLines(monthFlow: MonthFlow): List<String> =
    monthFlow.gaps.distinctBy { gapReason(it.reason) to (it.from to it.to) }.map { g ->
        val range = listOfNotNull(g.from?.toMonthDay(), g.to?.toMonthDay()).distinct().joinToString("~")
        listOf(range, gapReason(g.reason)).filter { it.isNotEmpty() }.joinToString(" · ")
    }.distinct()

/** 입출금 보조 줄 — '예정 · 카드 청구' · '오늘 · 수입' · '예정 · 추정' */
internal fun eventSub(e: ForecastEvent, today: Boolean): String = listOf(
    if (today) "오늘" else "예정",
    when {
        e.isCardBill -> if (e.isExpected) "카드 청구 · 금액 확정 전" else "카드 청구"
        e.isEstimate -> "추정"
        e.type == FlowType.SCHEDULED_INCOME || e.amount > 0 -> "수입"
        else -> "지출"
    },
).joinToString(" · ")

/**
 * 말풍선 윗줄 — 입출금이 있으면 '11/21 (토) · 월세'(여러 건이면 '외 N건'), 아니면 '11월 21일 (토) · 예상'.
 * 빈 날은 아랫줄이 '데이터 없음' · '범위 밖'이라 날짜만
 */
internal fun assetDayLabel(d: AssetDay): String {
    if (d.value == null) return d.date.toKoreanDateWeek()
    if (d.events.isEmpty()) return "${d.date.toKoreanDateWeek()} · ${kindShortLabel(d.kind)}"
    val more = if (d.events.size > 1) " 외 ${d.events.size - 1}건" else ""
    return "${d.date.toMonthDayWeek()} · ${d.events.first().label}$more"
}

/** TalkBack이 고른 날을 바꾼 뒤 읽는 문장 — 날짜, 종류, 잔고, 입출금 */
internal fun assetDayState(d: AssetDay): String = listOfNotNull(
    d.date.toKoreanDateWeek(),
    kindShortLabel(d.kind),
    d.value?.let { "${it.toWon()}원" },
    d.events.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.label} ${it.amount.toWon(withPlusSign = true)}원" },
).joinToString(", ")

/** 그래프 읽어 주기 — 어느 달, 어떤 구간이 있는지 */
internal fun assetChartDescription(month: YearMonth, kinds: List<DayKind>, hasConservative: Boolean): String = listOfNotNull(
    "${month.monthValue}월 잔고 그래프, 가로로 밀어 날짜 고르기",
    "지난날 실선".takeIf { DayKind.ACTUAL in kinds },
    "과거 잔고 실선".takeIf { DayKind.SIMULATED in kinds },
    "오늘 세로선".takeIf { DayKind.TODAY in kinds },
    "앞날 점선, ${if (hasConservative) LINE_WITH_LIVING else LINE_WITHOUT_LIVING}".takeIf { DayKind.EXPECTED in kinds },
    "실제 잔고 없는 날은 빗금".takeIf { DayKind.NO_DATA in kinds },
    "잔고 조회에 실패한 날은 주황 빗금".takeIf { DayKind.ERROR in kinds },
    "표시할 수 없는 날은 빗금".takeIf { DayKind.UNKNOWN in kinds },
    "예보 범위 밖은 옅은 빗금".takeIf { DayKind.OUT_OF_RANGE in kinds },
).joinToString(", ")

/** 다음 달 사용금액 자리 — 아직 쓴 돈이 없는 달(월 API는 부르지 않는다) */
@Composable
internal fun NextMonthSpend(shown: ShownMonth) {
    val c = YamuTheme.colors
    Text(monthTitle(shown.month, shown.thisMonth), style = YamuTheme.typography.bodyM, color = c.text2)
    Spacer(Modifier.height(4.dp))
    Text("아직 쓴 돈이 없는 달이에요", style = YamuTheme.typography.cardTitle, color = c.text1)
    Text("아래 자산 흐름에서 예보만 볼 수 있어요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
}
