package com.yamu.app.feature.forecast

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
