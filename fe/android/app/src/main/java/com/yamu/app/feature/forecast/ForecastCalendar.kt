package com.yamu.app.feature.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.forecast.data.Forecast
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// ───────── 계산(단위 테스트) ─────────

/** 달력에 보일 달 — 예보 첫날이 든 달부터 마지막 날(월급날이 있으면 월급날)이 든 달까지 */
internal fun calendarMonths(first: LocalDate, last: LocalDate): List<YearMonth> {
    val end = YearMonth.from(maxOf(first, last))
    return generateSequence(YearMonth.from(first)) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }.toList()
}

/** 한 달의 칸 — 일요일부터 시작하는 주마다 7칸, 앞뒤 빈칸은 null */
internal fun monthGrid(month: YearMonth): List<LocalDate?> {
    val lead = month.atDay(1).dayOfWeek.value % 7   // 일요일 0 · 월요일 1 … 토요일 6
    val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val cells = List(lead) { null } + days
    return cells + List((7 - cells.size % 7) % 7) { null }
}

/** '2026년 11월' */
internal fun monthTitle(month: YearMonth): String = "${month.year}년 ${month.monthValue}월"

/** 달력 칸 색 단계 — 안전 잔액 위는 여유가 많을수록 진한 파랑(4단계), 그 아래는 주황, 0원 아래는 빨강 */
internal enum class CellTone { OK1, OK2, OK3, OK4, BELOW_SAFE, BELOW_ZERO }

/** [max]는 기간 중 가장 큰 끝 잔액 — 안전 잔액(없으면 0원)에서 [max]까지를 넷으로 나눈다 */
internal fun cellTone(value: Long, threshold: Long?, max: Long): CellTone = when (zoneOf(value, threshold)) {
    BalanceZone.BELOW_ZERO -> CellTone.BELOW_ZERO
    BalanceZone.BELOW_SAFE -> CellTone.BELOW_SAFE
    BalanceZone.OK -> {
        val base = threshold ?: 0L
        val r = if (max <= base) 0.0 else (value - base).toDouble() / (max - base)
        when {
            r > 0.75 -> CellTone.OK4
            r > 0.5 -> CellTone.OK3
            r > 0.25 -> CellTone.OK2
            else -> CellTone.OK1
        }
    }
}

/** 칸 읽어 주기 — '11월 21일 (토), 끝 잔액 165,000원, −250,000원, 안전 잔액 아래' */
internal fun cellDescription(date: LocalDate, end: Long?, net: Long, zone: BalanceZone): String = listOfNotNull(
    date.toKoreanDateWeek(),
    end?.let { "끝 잔액 ${it.toWon()}원" },
    net.takeIf { it != 0L }?.let { "${it.toWon(withPlusSign = true)}원" },
    when (zone) { BalanceZone.BELOW_ZERO -> "0원 아래"; BalanceZone.BELOW_SAFE -> "안전 잔액 아래"; BalanceZone.OK -> null },
).joinToString(", ")

// ───────── 화면 ─────────

/**
 * 달력 면(Task 8.2.71) — 칸을 그날 끝 잔액('AI 생활비 반영' 선, 없으면 '제외')으로 칠하고 돈이 오가는 날엔 금액을 얹는다.
 * 달은 손가락을 따라 옆으로 넘기고(HorizontalPager) 머리의 ‹ ›도 둔다. 칸을 누르면 고른 날이 바뀌고([DaySource.CALENDAR])
 * 다른 곳에서 다른 달 날짜를 고르면 그 달로 넘어간다. 모션 줄이기면 넘김을 바로 한다(손으로 미는 건 그대로 따라온다)
 */
@Composable
internal fun ForecastCalendar(
    f: Forecast,
    strip: List<StripDay>,
    selected: Int,
    source: DaySource,
    onSelect: (Int, DaySource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = f.days
    if (days.isEmpty()) return
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val months = remember(days.first().date, f.incomeDate, days.last().date) { calendarMonths(days.first().date, f.incomeDate ?: days.last().date) }
    val indexOf = remember(days) { days.withIndex().associate { it.value.date to it.index } }
    val ends = remember(days, f.hasConservative) { days.map { rideValue(it, f.hasConservative) } }
    val max = remember(ends) { ends.filterNotNull().maxOrNull() ?: 0L }
    val monthOf = { i: Int -> months.indexOf(YearMonth.from(days[i.coerceIn(0, days.lastIndex)].date)).coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = monthOf(selected)) { months.size }
    // 다른 곳에서 고른 날이 다른 달이면 그 달로
    LaunchedEffect(selected, source) {
        if (source == DaySource.CALENDAR) return@LaunchedEffect
        val target = monthOf(selected)
        if (target != pager.currentPage) if (reduce) pager.scrollToPage(target) else pager.animateScrollToPage(target)
    }
    fun flip(to: Int) {
        if (to !in months.indices) return
        scope.launch { if (reduce) pager.scrollToPage(to) else pager.animateScrollToPage(to) }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalendarArrow(back = true, enabled = pager.currentPage > 0, onClick = { flip(pager.currentPage - 1) })
            Text(
                monthTitle(months[pager.currentPage.coerceIn(0, months.lastIndex)]),
                style = YamuTheme.typography.titleS,
                color = c.text1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CalendarArrow(back = false, enabled = pager.currentPage < months.lastIndex, onClick = { flip(pager.currentPage + 1) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
            WEEK.forEach { Text(it, style = YamuTheme.typography.labelS, color = c.text3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        HorizontalPager(pager, verticalAlignment = Alignment.Top, pageSpacing = 12.dp) { page ->
            Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                monthGrid(months[page]).chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                        week.forEach { date ->
                            val i = date?.let { indexOf[it] }
                            Box(Modifier.weight(1f).height(CELL_H)) {
                                when {
                                    date == null -> Unit
                                    i != null -> DayCell(date, i, strip[i], ends[i], max, f.alertThreshold, selected = i == selected, onClick = { onSelect(i, DaySource.CALENDAR) })
                                    date == f.incomeDate -> IncomeCell(date)
                                    else -> Text(
                                        "${date.dayOfMonth}",
                                        style = YamuTheme.typography.labelM,
                                        color = c.text3,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 색 풀이 — 칸 색이 무엇을 뜻하는지
        FlowRow(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(c.primary.copy(alpha = 0.24f), "여유가 많을수록 진하게")
            Swatch(c.warningContainer, "안전 잔액 아래")
            Swatch(c.errorContainer, "0원 아래")
            Text("칸 색은 $LINE_WITH_LIVING 선 기준", style = YamuTheme.typography.bodyS, color = c.text3)
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, index: Int, day: StripDay, end: Long?, max: Long, threshold: Long?, selected: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val tone = end?.let { cellTone(it, threshold, max) } ?: CellTone.OK1
    val bg = when (tone) {
        CellTone.OK1 -> c.primary.copy(alpha = 0.07f)
        CellTone.OK2 -> c.primary.copy(alpha = 0.13f)
        CellTone.OK3 -> c.primary.copy(alpha = 0.20f)
        CellTone.OK4 -> c.primary.copy(alpha = 0.28f)
        CellTone.BELOW_SAFE -> c.warningContainer
        CellTone.BELOW_ZERO -> c.errorContainer
    }
    val amountColor = when {
        tone == CellTone.BELOW_ZERO -> c.onErrorContainer
        tone == CellTone.BELOW_SAFE -> c.onWarningContainer
        day.net > 0 -> c.primary
        else -> c.text1
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(CELL_H)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(2.dp, c.primary, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = cellDescription(date, end, day.net, day.zone)
            }
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${date.dayOfMonth}", style = YamuTheme.typography.labelM.copy(fontSize = 12.5.sp), color = c.text1)
        when {
            day.net != 0L -> Text(pillAmount(day.net) ?: "", style = YamuTheme.typography.labelS.copy(fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = (-0.3).sp), color = amountColor, maxLines = 1)
            index == 0 -> Text("오늘", style = YamuTheme.typography.labelS.copy(fontSize = 9.sp, lineHeight = 12.sp), color = c.primary)
        }
    }
}

/** 월급날 칸 — 예보 기간 밖이라 누를 수 없고, 어디까지 버티면 되는지만 */
@Composable
private fun IncomeCell(date: LocalDate) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .height(CELL_H)
            .clip(RoundedCornerShape(10.dp))
            .background(c.primaryContainer)
            .semantics(mergeDescendants = true) { contentDescription = "${date.toKoreanDateWeek()}, 월급날" }
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${date.dayOfMonth}", style = YamuTheme.typography.labelM.copy(fontSize = 12.5.sp), color = c.onPrimaryContainer)
        Text("월급", style = YamuTheme.typography.labelS.copy(fontSize = 9.sp, lineHeight = 12.sp), color = c.primary)
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    val c = YamuTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = YamuTheme.typography.bodyS, color = c.text3)
    }
}

@Composable
private fun CalendarArrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(YamuRadius.s))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (back) "이전 달" else "다음 달", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(YamuRadius.s)).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                YamuIcons.ChevronRight,
                contentDescription = if (back) "이전 달" else "다음 달",
                tint = if (enabled) c.text1 else c.text3.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp).graphicsLayer { if (back) scaleX = -1f },
            )
        }
    }
}

private val WEEK = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    .map { it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN) }
private val CELL_H = 48.dp
private val CELL_GAP = 3.dp
