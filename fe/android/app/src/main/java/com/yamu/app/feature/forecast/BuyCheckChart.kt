package com.yamu.app.feature.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.SimDay
import com.yamu.app.feature.forecast.data.Simulation
import java.time.LocalDate

// ── 가상 구매 결과(I-02) 그래프 — 손잡이 · 구매일 · 출금일 · 기간 밖 출금(Task 8.2.72) ──

/** 고른 날 칸에 쓰는 그날 일 — 구매(빠지는 돈은 출금 줄에 있어 금액이 없다), 기간 안 출금 회차, 잔고예보의 입출금 */
internal enum class SimEventKind { PURCHASE, WITHDRAWAL, FLOW }

internal data class SimDayEvent(val kind: SimEventKind, val label: String, val amount: Long?)

/** 처음 고른 날 — 구매일. 구매일이 기간 밖이면 사고 나서 가장 낮은 날, 그것도 모르면 마지막 날 */
internal fun simInitialDay(sim: Simulation): Int {
    val days = sim.days
    days.indexOfFirst { it.date == sim.query.purchaseDate }.takeIf { it >= 0 }?.let { return it }
    return days.indexOfFirst { it.date == sim.after?.minBalanceDate }.takeIf { it >= 0 } ?: days.lastIndex.coerceAtLeast(0)
}

/**
 * 출금이 사기 전 · 후 선에 들어갔는지. 출금 계좌가 잔고예보에서 고른 계좌 밖이면 BE가 두 선을 같게 두고 안내(outsideSelectionImpact)만 준다.
 * 그때는 선이 내려가지 않으니 그래프에 출금 표시를 하지 않는다
 */
internal fun withdrawalsOnChart(sim: Simulation): Boolean = sim.outsideNote == null

/** 기간 안 출금 회차가 있는 날(그래프 날짜 번호) */
internal fun simWithdrawDays(sim: Simulation): Set<Int> {
    if (!withdrawalsOnChart(sim)) return emptySet()
    val index = sim.days.withIndex().associate { (i, d) -> d.date to i }
    return sim.schedule.filter { !it.beyondPeriod }.mapNotNull { w -> w.date?.let(index::get) }.toSet()
}

/** 기간 밖 출금 — 날짜 축 끝 글자. 한 번이면 '12/14 출금', 할부로 여러 번이면 '12/14부터 3번 출금' */
internal fun beyondLabel(sim: Simulation): String? {
    if (!withdrawalsOnChart(sim)) return null
    val dates = sim.schedule.filter { it.beyondPeriod }.mapNotNull { it.date }.sorted()
    val first = dates.firstOrNull() ?: return null
    return if (dates.size == 1) "${first.toMonthDay()} 출금" else "${first.toMonthDay()}부터 ${dates.size}번 출금"
}

/**
 * '사기 전' 선과 같은 잔고예보 — 날짜가 모두 같고 날마다 '사기 전' 잔액이 그 예보의 손잡이 선(AI 생활비 반영, 없으면 제외)과 같을 때만.
 * 시뮬레이션 응답에는 날마다의 입출금이 없어 잔고예보에서 받은 것을 빌려 쓴다(BE는 구매 전 결과가 잔고예보 조회와 같다고 정했다).
 * 계좌 · 기간을 바꿨거나 그사이 결제가 생겨 값이 다르면 빌리지 않고 구매 · 출금만 보인다
 */
internal fun matchingForecast(sim: Simulation, cached: Forecast?): Forecast? {
    cached ?: return null
    if (cached.days.size != sim.days.size || sim.days.none { it.before != null }) return null
    val same = cached.days.zip(sim.days).all { (f, s) -> f.date == s.date && (s.before == null || rideValue(f, cached.hasConservative) == s.before) }
    return cached.takeIf { same }
}

/** 날마다 고른 날 칸에 쓸 일 — 구매, 출금 회차, 빌려 온 잔고예보 입출금 순 */
internal fun simDayEvents(sim: Simulation, forecast: Forecast?): List<List<SimDayEvent>> {
    val onChart = withdrawalsOnChart(sim)
    val withdrawals = sim.schedule.filter { !it.beyondPeriod && it.date != null }.groupBy { it.date }
    return sim.days.mapIndexed { i, d ->
        buildList {
            if (d.date == sim.query.purchaseDate) add(SimDayEvent(SimEventKind.PURCHASE, "구매 (${sim.card.name})", null))
            withdrawals[d.date]?.forEach { w ->
                add(SimDayEvent(SimEventKind.WITHDRAWAL, if (onChart) w.label else "${w.label} (고르지 않은 계좌)", w.amount))
            }
            forecast?.days?.getOrNull(i)?.events?.filter { it.amount != 0L }?.forEach { add(SimDayEvent(SimEventKind.FLOW, dayEventLabel(it), it.amount)) }
        }
    }
}

/** 그래프에 동전을 얹는 날 — 돈이 실제로 오가는 날(신용카드 구매일처럼 구매만 있는 날은 점선만) */
internal fun simEventDays(events: List<List<SimDayEvent>>, sim: Simulation): Set<Int> = events.indices.filter { i ->
    events[i].any { e -> e.kind == SimEventKind.FLOW || (e.kind == SimEventKind.WITHDRAWAL && withdrawalsOnChart(sim)) }
}.toSet()

/** 말풍선 윗줄 — '11/20 (금) · 구매 외 1건', 일이 없으면 '11월 20일 (금)' */
internal fun simDayLabel(date: LocalDate, events: List<SimDayEvent>, today: LocalDate = LocalDate.now(KST)): String {
    val first = events.firstOrNull() ?: return date.toKoreanDateWeek(markToday = true, today = today)
    val name = when (first.kind) {
        SimEventKind.PURCHASE -> "구매"
        SimEventKind.WITHDRAWAL -> "출금"
        SimEventKind.FLOW -> first.label
    }
    val more = if (events.size > 1) " 외 ${events.size - 1}건" else ""
    return "${date.toMonthDayWeek()} · $name$more"
}

/** TalkBack이 고른 날을 바꾼 뒤 읽는 문장 — 날짜, 사기 전 · 후 잔액, 그날 일 */
internal fun simDayState(day: SimDay, events: List<SimDayEvent>): String = listOfNotNull(
    day.date.toKoreanDateWeek(),
    day.before?.let { "사기 전 ${it.toWon()}원" },
    day.after?.let { "사기 후 ${it.toWon()}원" },
    events.takeIf { it.isNotEmpty() }?.joinToString(", ") { e -> e.amount?.let { "${e.label} ${it.toWon(withPlusSign = true)}원" } ?: "${e.label} 카드 결제" },
).joinToString(", ")

/** 그래프 읽어 주기 — 가장 낮을 때와 기간 밖 출금 */
internal fun simChartDescription(sim: Simulation): String = listOfNotNull(
    "사기 전과 후 잔액 그래프, 사고 나면 가장 낮을 때 ${(sim.after?.minBalance ?: 0).toWon()}원",
    sim.schedule.filter { it.beyondPeriod }.mapNotNull { it.date }.minOrNull()?.takeIf { withdrawalsOnChart(sim) }?.let { "${it.toKoreanDate()} 출금은 예보 기간 밖이라 선에 넣지 않았어요" },
).joinToString(", ")

/**
 * 사기 전 · 후 그래프 + 범례 + 고른 날 칸. 손잡이는 '사기 후' 선을 탄다.
 * [selected] · [interacted]는 화면이 들고 있어 '빠져나가는 날' 줄을 눌러도 같은 날을 가리킨다
 */
@Composable
internal fun SimGraph(
    sim: Simulation,
    threshold: Long?,
    forecast: Forecast?,
    selected: Int,
    onSelect: (Int) -> Unit,
    interacted: Boolean,
    onInteract: () -> Unit,
    flash: Int,
) {
    val c = YamuTheme.colors
    val days = sim.days
    val matched = remember(sim, forecast) { matchingForecast(sim, forecast) }
    val events = remember(sim, matched) { simDayEvents(sim, matched) }
    val eventDays = remember(sim, events) { simEventDays(events, sim) }
    val marks = ChartMarks(
        purchase = days.indexOfFirst { it.date == sim.query.purchaseDate }.takeIf { it >= 0 },
        withdrawals = remember(sim) { simWithdrawDays(sim) },
        beyond = remember(sim) { beyondLabel(sim) },
        flash = flash,
    )
    // 마지막 선('사기 후')이 면 · 구간 색을 따르고, '사기 전' 회색 점선은 비교용이라 색을 바꾸지 않는다
    val lines = listOf(
        ChartLine("사기 전", days.map { it.before }, c.text3, dashed = true, width = 1.8.dp, zoned = false),
        ChartLine("사기 후", days.map { it.after }, c.primary, width = 2.4.dp),
    )
    val sel = selected.coerceIn(0, days.lastIndex)
    BalanceChart(
        dates = days.map { it.date },
        lines = lines,
        description = simChartDescription(sim),
        threshold = threshold,
        eventDays = eventDays,
        marks = marks,
        selected = sel,
        onSelect = onSelect,
        dayLabel = { simDayLabel(days[it].date, events[it]) },
        dayState = { simDayState(days[it], events[it]) },
        interacted = interacted,
        onInteract = onInteract,
    )
    Spacer(Modifier.height(10.dp))
    ChartLegend(lines)
    Spacer(Modifier.height(14.dp))
    SimDayPanel(days[sel], events[sel], threshold, knowsFlows = matched != null)
}

/**
 * 고른 날 — 사기 전 · 후 끝 잔액, 사고 나서 안전 잔액 · 0원 아래면 태그, 그날 일.
 * 잔고예보 입출금을 빌려 오지 못했으면([knowsFlows] false) 다른 입출금을 모르니 '오가는 돈이 없어요'라고 하지 않는다
 */
@Composable
private fun SimDayPanel(day: SimDay, events: List<SimDayEvent>, threshold: Long?, knowsFlows: Boolean) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.date.toKoreanDateWeek(markToday = true), style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
            when (day.after?.let { zoneOf(it, threshold) }) {
                BalanceZone.BELOW_ZERO -> StatusTag("0원 아래", tone = TagTone.Error)
                BalanceZone.BELOW_SAFE -> StatusTag("안전 잔액 아래", tone = TagTone.Warning)
                else -> Unit
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayValue("사기 전", day.before, c.text2, Modifier.weight(1f))
            DayValue("사기 후", day.after, c.onPrimaryContainer, Modifier.weight(1f))
        }
        if (events.isEmpty()) {
            if (knowsFlows) Text("이날은 오가는 돈이 없어요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 8.dp))
        } else {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = c.outlineVariant)
            Spacer(Modifier.height(6.dp))
            events.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label, style = YamuTheme.typography.bodyM, color = c.text1, modifier = Modifier.weight(1f))
                    Text(
                        e.amount?.let { "${it.toWon(withPlusSign = true)}원" } ?: "카드 결제",
                        style = YamuTheme.typography.bodyM,
                        color = when {
                            e.amount == null -> c.text2
                            e.amount > 0 -> c.primary
                            else -> c.text1
                        },
                    )
                }
            }
        }
    }
}
