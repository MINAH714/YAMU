package com.yamu.app.feature.forecast

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 잔액 그래프의 계산만 모은 곳 — 그리기([BalanceChart])와 떼어 단위 테스트한다(Task 8.2.65).
 * 금액은 서버가 준 값 그대로 쓰고, 여기서는 화면에 놓을 자리와 표시 여부만 정한다
 */

/** 그래프 세로 범위(원). [bottom]이 바닥, [top]이 꼭대기 */
internal data class ChartRange(val bottom: Double, val top: Double) {
    /** 0 = 바닥, 1 = 꼭대기 */
    fun fraction(v: Double): Float = ((v - bottom) / (top - bottom)).toFloat()

    /** 이 금액의 가로선을 그릴 만큼 범위 안에 있는지(바닥 · 꼭대기에 딱 붙으면 그리지 않는다) */
    fun shows(v: Long): Boolean = v > bottom && v < top
}

/** 거의 평평한 선이 절벽처럼 보이지 않게 두는 최소 폭 — 5만 원과 최고 잔액의 10% 중 큰 값 */
internal const val CHART_MIN_SPAN_WON = 50_000.0
internal const val CHART_MIN_SPAN_RATIO = 0.1

/** 아래 · 위 여백(폭에 대한 비율) */
private const val PAD_BOTTOM = 0.12
private const val PAD_TOP = 0.08

/** 안전 잔액이 선 바닥보다 이만큼(폭의 35%) 안쪽으로만 아래에 있으면 범위에 넣는다. 더 멀면 넣지 않아 선이 납작해지지 않게 */
internal const val CHART_THRESHOLD_REACH = 0.35

/**
 * 세로 범위 — 선이 있는 곳에 여백만 둔다. 전에는 0원과 안전 잔액을 늘 넣어 잔액이 크면 선이 납작했다(QA P2-21).
 * 선이 모두 0원 이상이면 여백 때문에 0원 아래가 생기지 않게 바닥을 0원에서 끊는다
 */
internal fun chartRange(values: List<Long>, threshold: Long?): ChartRange {
    if (values.isEmpty()) return ChartRange(0.0, 1.0)
    val lo = values.min().toDouble()
    val hi = values.max().toDouble()
    val span = maxOf(hi - lo, abs(hi) * CHART_MIN_SPAN_RATIO, CHART_MIN_SPAN_WON)
    val extra = (span - (hi - lo)) / 2
    var bottom = lo - extra - span * PAD_BOTTOM
    val top = hi + extra + span * PAD_TOP
    if (threshold != null && threshold < bottom && bottom - threshold < span * CHART_THRESHOLD_REACH) bottom = threshold - span * 0.06
    if (lo >= 0) bottom = max(bottom, 0.0)
    return ChartRange(bottom, top)
}

/** 금액이 놓인 구간 — 안전 잔액 아래는 주황, 0원 아래는 빨강으로 선 · 면 색을 나눈다 */
internal enum class BalanceZone { OK, BELOW_SAFE, BELOW_ZERO }

internal fun zoneOf(value: Long, threshold: Long?): BalanceZone = when {
    value < 0 -> BalanceZone.BELOW_ZERO
    threshold != null && value < threshold -> BalanceZone.BELOW_SAFE
    else -> BalanceZone.OK
}

/** 급감 기준(임시, MR · Jira에 적어 둠) — 하루에 20만 원 이상 또는 전날 잔액의 30% 이상 */
internal const val SHARP_DROP_WON = 200_000L
internal const val SHARP_DROP_RATIO = 0.3

/** 하루에 크게 빠지는 날의 번호. 전날이 0원 이하면 비율은 뜻이 없어 금액 기준만 본다. 값이 비면 건너뛴다 */
internal fun sharpDropDays(values: List<Long?>): Set<Int> = buildSet {
    for (i in 1 until values.size) {
        val a = values[i - 1] ?: continue
        val b = values[i] ?: continue
        val drop = a - b
        if (drop <= 0) continue
        if (drop >= SHARP_DROP_WON || (a > 0 && drop >= a * SHARP_DROP_RATIO)) add(i)
    }
}

/** 가로 위치(0~1) → 가장 가까운 날 */
internal fun dayAt(fraction: Float, n: Int): Int =
    if (n <= 1) 0 else (fraction.coerceIn(0f, 1f) * (n - 1)).roundToInt().coerceIn(0, n - 1)

/** 날 → 가로 위치(0~1) */
internal fun fractionOf(day: Int, n: Int): Float = if (n <= 1) 0f else day.coerceIn(0, n - 1) / (n - 1f)

/**
 * 가로 위치(0~1)의 선 값 — 손잡이가 날 사이를 지날 때도 선 위에 있게 두 날 사이를 곧게 잇는다(선을 그리는 방식과 같다).
 * 한쪽 날이 비면 있는 쪽 값, 둘 다 비면 null
 */
internal fun valueAt(values: List<Long?>, fraction: Float): Double? {
    if (values.isEmpty()) return null
    if (values.size == 1) return values[0]?.toDouble()
    val f = fraction.coerceIn(0f, 1f) * (values.size - 1)
    val i0 = floor(f).toInt().coerceIn(0, values.size - 1)
    val i1 = (i0 + 1).coerceAtMost(values.size - 1)
    val a = values[i0]
    val b = values[i1]
    if (a == null || b == null) return (a ?: b)?.toDouble()
    return a + (b - a) * (f - i0).toDouble()
}

// ── 한 달 그래프(소비분석 자산 흐름, Task 8.2.99) ──

/**
 * 한 달 그래프의 날 종류 — 색만으로 나누지 않고 선 모양 · 빗금 · 글자로도 나눈다(COMMON-11).
 * 지난 실제 잔고는 아직 BE에 없어 지금은 지난날이 모두 [NO_DATA]다(COMMON-03: 지출액으로 잔고를 만들지 않음)
 */
enum class DayKind {
    /** 지난날 실제 잔고 — 실선 */
    ACTUAL,
    /** 오늘 — 실선과 점선이 만나는 자리, 세로 기준선 */
    TODAY,
    /** 앞날 예상 잔고 — 점선 */
    EXPECTED,
    /** 잔고를 받을 수 없는 날 — 진한 빗금 */
    NO_DATA,
    /** 예보가 닿지 않는 날(오늘부터 30일 뒤, FORECAST-06) — 옅은 빗금 */
    OUT_OF_RANGE,
}

/** 빗금으로 비우는 날인지 */
internal val DayKind.hatched: Boolean get() = this == DayKind.NO_DATA || this == DayKind.OUT_OF_RANGE

/**
 * 한 달([month])의 날 종류 — 오늘([today], 한국 날짜) 앞은 실제 잔고를 받은 날([actualDates])이면 실제, 아니면 데이터 없음.
 * 오늘 뒤는 예보 마지막 날([expectedUntil])까지 예상, 그 뒤는 범위 밖(FORECAST-06: 예보를 늘려 그리지 않음).
 * 예보를 못 받았으면(null) 오늘 뒤도 데이터 없음이다
 */
internal fun monthDayKinds(month: YearMonth, today: LocalDate, actualDates: Set<LocalDate>, expectedUntil: LocalDate?): List<DayKind> =
    (1..month.lengthOfMonth()).map { d ->
        val date = month.atDay(d)
        when {
            date.isBefore(today) -> if (date in actualDates) DayKind.ACTUAL else DayKind.NO_DATA
            date == today -> DayKind.TODAY
            expectedUntil == null -> DayKind.NO_DATA
            !date.isAfter(expectedUntil) -> DayKind.EXPECTED
            else -> DayKind.OUT_OF_RANGE
        }
    }

/**
 * 한 달 그래프 표시 — [kinds]는 날짜와 같은 길이. 주면 [BalanceChart]가 실선 · 점선 · 빗금 · 오늘 기준선 · 바닥 날짜를 그린다.
 * [emptyValue]는 값이 없는 날 말풍선 아랫줄('데이터 없음' · '범위 밖')
 */
data class MonthChart(val kinds: List<DayKind>, val emptyValue: (DayKind) -> String = ::kindShortLabel)

/** 날 종류 짧은 이름 — 날짜 칸 꼬리표 · 말풍선 · 읽어 주기가 같이 쓴다 */
fun kindShortLabel(kind: DayKind): String = when (kind) {
    DayKind.ACTUAL -> "실제"
    DayKind.TODAY -> "지금"
    DayKind.EXPECTED -> "예상"
    DayKind.NO_DATA -> "데이터 없음"
    DayKind.OUT_OF_RANGE -> "범위 밖"
}

/** 같은 빗금 종류가 이어지는 묶음 — [from]~[to]는 날 번호(둘 다 포함) */
internal data class KindRun(val kind: DayKind, val from: Int, val to: Int)

/** 빗금으로 칠할 묶음들 — 데이터 없음 · 범위 밖이 이어지는 곳마다 하나. 종류가 바뀌면 나눈다 */
internal fun hatchRuns(kinds: List<DayKind>): List<KindRun> = buildList {
    var start = -1
    for (i in kinds.indices) {
        val k = kinds[i]
        if (start >= 0 && kinds[start] != k) { add(KindRun(kinds[start], start, i - 1)); start = -1 }
        if (start < 0 && k.hatched) start = i
    }
    if (start >= 0) add(KindRun(kinds[start], start, kinds.lastIndex))
}

/**
 * 실선(실제)과 점선(예상)으로 나눈 값 — 오늘은 두 선에 다 넣어 이어지게 한다. 빗금 날은 둘 다 비운다.
 * 날짜와 길이가 다르면 짧은 쪽에 맞춘다
 */
internal fun splitByKind(values: List<Long?>, kinds: List<DayKind>): Pair<List<Long?>, List<Long?>> {
    val n = minOf(values.size, kinds.size)
    val actual = List(n) { i -> values[i].takeIf { kinds[i] == DayKind.ACTUAL || kinds[i] == DayKind.TODAY } }
    val expected = List(n) { i -> values[i].takeIf { kinds[i] == DayKind.TODAY || kinds[i] == DayKind.EXPECTED } }
    return actual to expected
}

/** 바닥에 적는 날 — 1 · 8 · 15 · 22 · 29일 중 그달에 있는 날(0부터 센 번호) */
internal fun monthAxisIndices(days: Int): List<Int> = listOf(1, 8, 15, 22, 29).filter { it <= days }.map { it - 1 }

/**
 * 한 달 그래프 손잡이 값 — 두 날 모두 값이 있을 때만 곧게 잇고, 한쪽이 비면 가까운 날 값(그날도 비면 null).
 * [valueAt]은 빈 날 위에서 옆 날 값을 끌어와 빗금 위에 동전이 뜬다
 */
internal fun valueAtStrict(values: List<Long?>, fraction: Float): Double? {
    if (values.isEmpty()) return null
    if (values.size == 1) return values[0]?.toDouble()
    val f = fraction.coerceIn(0f, 1f) * (values.size - 1)
    val i0 = floor(f).toInt().coerceIn(0, values.size - 1)
    val i1 = (i0 + 1).coerceAtMost(values.size - 1)
    val a = values[i0]
    val b = values[i1]
    if (a != null && b != null) return a + (b - a) * (f - i0).toDouble()
    return values[if (f - i0 < 0.5f) i0 else i1]?.toDouble()
}

/** 날마다 처음으로 값이 있는 선의 값 — 한 달 그래프는 실선 · 점선이 날을 나눠 가져 손잡이가 이 값을 탄다 */
internal fun firstValues(lines: List<List<Long?>>): List<Long?> {
    val n = lines.maxOfOrNull { it.size } ?: 0
    return List(n) { i -> lines.firstNotNullOfOrNull { it.getOrNull(i) } }
}
