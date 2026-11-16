package com.yamu.app.feature.forecast

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastDay
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastSelection
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 고른 날을 바꾼 곳(Task 8.2.70) — 바꾼 곳은 다시 움직이지 않고 나머지만 따라온다 */
enum class DaySource {
    /** 처음 열었을 때 · 기간이나 계좌가 바뀌어 다시 고른 것(사람이 바꾼 게 아니다) */
    INIT,
    /** 그래프 손잡이를 끌거나 누름 · 읽어 주기 동작 */
    CHART,
    /** 날짜 띠를 손으로 밀어 가운데 칸이 바뀜 */
    STRIP,
    /** 날짜 띠의 알약을 누름 — 띠가 그 알약을 가운데로 데려와야 해서 [STRIP]과 나눈다 */
    PILL,
    /** 고른 날 칸을 옆으로 밀거나 ‹ ›를 누름 */
    PANEL,
    /** '잔액이 낮아지는 때' · '예정된 돈' 줄을 누름 */
    LIST,
    /** 달력 면의 칸을 누름(Task 8.2.71) — 달력은 자기가 고른 날로 달을 넘기지 않는다 */
    CALENDAR,
}

/** 그래프 · 달력 면(Task 8.2.71) — 바꾸기는 카드 오른쪽 위 알약으로만(그래프 가로 끌기와 달력 넘기기가 부딪히지 않게) */
enum class ChartView { GRAPH, CALENDAR }

/**
 * 잔고예보에서 고른 날 — 그래프 · 날짜 띠 · 고른 날 칸 · 목록이 함께 보는 하나뿐인 상태.
 * 서로 상대를 직접 부르지 않고 여기만 바꾸며, [source]로 누가 바꿨는지 남겨 바꾼 곳이 자기 변화를 다시 받아 움직이지 않게 한다.
 * [touched]는 사람이 한 번이라도 만졌는지 — 첫 안내(손잡이 흔들기 · 띠 살짝 밀기 · '밀어서 날짜 보기')를 끈다.
 * [version]은 바뀔 때마다 오른다 — 같은 날을 다시 골라도 위 금액이 그날로 다시 머문다
 */
@Stable
class DaySelection(initial: Int, val count: Int, touched: Boolean = false) {
    var day by mutableIntStateOf(clamp(initial)); private set
    var source by mutableStateOf(DaySource.INIT); private set
    var touched by mutableStateOf(touched); private set
    var version by mutableIntStateOf(0); private set

    /** 날을 바꾼다. 실제로 다른 날이 됐으면 true. 사람이 바꾼 것([DaySource.INIT] 말고)이면 같은 날이어도 [version]을 올린다 */
    fun select(index: Int, from: DaySource): Boolean {
        val d = clamp(index)
        if (from != DaySource.INIT) { touched = true; version++ }
        if (d == day) return false
        day = d
        source = from
        return true
    }

    fun touch() { touched = true }

    private fun clamp(i: Int) = if (count <= 0) 0 else i.coerceIn(0, count - 1)

    companion object {
        /** 화면을 돌려도 고른 날과 첫 안내 여부를 지킨다(날 수가 바뀌면 화면이 새로 만든다) */
        fun saver(count: Int): Saver<DaySelection, List<Int>> = Saver(
            save = { listOf(it.day, if (it.touched) 1 else 0) },
            restore = { DaySelection(it[0], count, touched = it[1] == 1) },
        )
    }
}

/** 띠가 고른 날을 따라 움직여야 하는지 — 띠를 손으로 미는 중이면 띠가 주인이다 */
internal fun stripFollows(source: DaySource): Boolean = source != DaySource.STRIP

/** 띠가 따라갈 때 부드럽게 굴러갈지 — 그래프를 끄는 동안은 손가락과 같이 바로 옮겨야 늦지 않는다 */
internal fun stripGlides(source: DaySource): Boolean = source == DaySource.PILL || source == DaySource.PANEL || source == DaySource.LIST

/** 고른 날 칸이 옆으로 밀려 바뀔지 — 칸을 밀었을 때만. 그래프를 빠르게 끌 때 칸이 쌓여 밀리지 않게 나머지는 바로 바꾼다 */
internal fun panelSlides(source: DaySource): Boolean = source == DaySource.PANEL

/** 그래프를 끄는 동안 띠에서 온 변화는 받지 않는다 — 띠가 아직 굴러가는 중에 그래프를 잡으면 두 곳이 서로 끌어당긴다 */
internal fun accepts(from: DaySource, chartDragging: Boolean): Boolean = !(chartDragging && from == DaySource.STRIP)

// ── 날짜 띠 ──

/**
 * 띠 알약 하나 — 그날 오가는 돈 합과 구간(손잡이가 타는 선 기준).
 * [kind]가 있으면 한 달 띠(소비분석 자산 흐름, Task 8.2.99)라 색 말고 테두리 · 빗금으로 날 종류를 보인다
 */
internal data class StripDay(val date: LocalDate, val net: Long, val zone: BalanceZone, val kind: DayKind? = null)

/** 한 달 띠 끝 '‹ 10월 보기' · '12월 보기 ›' 칸 — 띠를 끝까지 밀어도 달은 저절로 넘어가지 않고 이 칸을 눌러야 넘어간다 */
internal data class StripJump(val label: String, val description: String, val onClick: () -> Unit)

/** 한 달 띠 알약 윗줄 — 오늘은 '오늘', 나머지는 요일(한 달 안이라 '12월' 같은 달 표시는 없다) */
internal fun monthPillTopLabel(day: StripDay): String =
    if (day.kind == DayKind.TODAY) "오늘" else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/** 알약 읽어 주기의 날 종류 — 오늘은 '오늘'로 읽는다(꼬리표의 '지금'은 잔고 이야기라서) */
internal fun pillKindLabel(kind: DayKind): String = when (kind) {
    DayKind.ACTUAL -> "실제 잔고"
    DayKind.SIMULATED -> "QA 합성 잔고"
    DayKind.TODAY -> "오늘"
    DayKind.EXPECTED -> "예상 잔고"
    DayKind.NO_DATA -> "잔고 데이터 없음"
    DayKind.OUT_OF_RANGE -> "예보 범위 밖"
    DayKind.ERROR -> "잔고 조회 실패"
    DayKind.UNKNOWN -> "표시할 수 없음"
}

/** 띠 알약들 — 오가는 돈은 0원이 아닌 입출금 합, 구간은 'AI 생활비 반영' 선(없으면 '제외' 선)으로 정한다(그래프 손잡이 · 면과 같은 선) */
internal fun stripDays(days: List<ForecastDay>, hasConservative: Boolean, threshold: Long?): List<StripDay> = days.map { d ->
    val ride = (if (hasConservative) d.conservative else null) ?: d.expected
    StripDay(d.date, d.events.filter { it.amount != 0L }.sumOf { it.amount }, ride?.let { zoneOf(it, threshold) } ?: BalanceZone.OK)
}

/** 알약 윗줄 — 첫 칸은 '오늘', 달이 바뀌는 1일은 '12월', 나머지는 요일 */
internal fun pillTopLabel(date: LocalDate, index: Int): String = when {
    index == 0 -> "오늘"
    date.dayOfMonth == 1 -> "${date.monthValue}월"
    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
}

/**
 * 알약 아랫줄 금액 — 좁은 칸(50dp)이라 만 원 단위로 줄인다. '−2.5만' · '−15만' · '+280만', 만 원 아래는 '−6천', 0원이면 null.
 * 100만 원 이상은 소수점 없이, 그 아래는 소수 한 자리(.0은 뗀다)
 */
internal fun pillAmount(net: Long): String? {
    if (net == 0L) return null
    val sign = if (net > 0) "+" else "−"
    val a = abs(net)
    if (a < 10_000) return "$sign${(a / 1000.0).roundToLong().coerceAtLeast(1)}천"
    val man = a / 10_000.0
    val text = if (man >= 100) man.roundToLong().toString() else ((man * 10).roundToLong() / 10.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    return "$sign${text}만"
}

/** 알약 읽어 주기 — '11월 21일 (토), −250,000원, 안전 잔액 아래' */
internal fun pillDescription(day: StripDay): String = listOfNotNull(
    day.date.toKoreanDateWeek(),
    day.kind?.let(::pillKindLabel),
    day.net.takeIf { it != 0L }?.let { "${it.toWon(withPlusSign = true)}원" },
    when (day.zone) { BalanceZone.BELOW_ZERO -> "0원 아래"; BalanceZone.BELOW_SAFE -> "안전 잔액 아래"; BalanceZone.OK -> null },
).joinToString(", ")

// ── 위 금액 · 안내 문장 ──

/** 미는 동안 위 제목 — '11월 27일 (금) 잔액' */
internal fun heroLiveTitle(date: LocalDate): String = "${date.toKoreanDateWeek()} 잔액"

/** 위 금액이 따르는 값 — 그래프 손잡이가 타는 선과 같다('AI 생활비 반영', 없으면 '제외') */
internal fun rideValue(day: ForecastDay, hasConservative: Boolean): Long? = (if (hasConservative) day.conservative else null) ?: day.expected

/**
 * 월급을 못 찾아 N일로 계산했을 때 위에 적는 이유(P1-08). 앱은 잔고예보 설정의 기간 기준을 늘 '월급날까지'로만 저장하므로
 * (온보딩 B-06) 기간을 고르지 않았거나 '월급날까지'를 골랐는데 N일이 왔으면 월급을 못 찾은 것이다. N일을 직접 골랐으면 null
 */
internal fun incomeMissingNote(f: Forecast, selection: ForecastSelection): String? {
    if (f.isNextIncome) return null
    if (selection.period != null && selection.period != ForecastPeriod.NEXT_INCOME) return null
    return "월급을 찾지 못해 오늘부터 ${f.horizonDays ?: 30}일로 계산했어요. 월급을 등록하면 월급날까지로 바꿔 보여 드려요."
}

/** 월급 전날처럼 예보할 날이 하루뿐일 때 그래프 대신 보이는 문장(P2-24) — 전에는 그래프가 말없이 사라졌다 */
internal fun singleDayNote(f: Forecast): String =
    f.incomeDate?.let { "내일(${it.monthValue}월 ${it.dayOfMonth}일)이 월급날이에요. 월급이 들어오면 다음 월급날까지 다시 예보해요." }
        ?: "예보할 날이 오늘 하루뿐이라 그래프 없이 오늘 끝 잔액만 보여 드려요."
