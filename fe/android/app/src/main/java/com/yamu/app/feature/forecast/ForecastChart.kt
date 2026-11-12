package com.yamu.app.feature.forecast

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toManWon
import com.yamu.app.core.format.toMonthDay
import java.time.LocalDate

/** 선 하나 — [values]는 날짜와 같은 길이. null인 날은 비워 선이 끊긴다(서버가 준 값 그대로, 앱은 값을 만들지 않는다) */
data class ChartLine(val label: String, val values: List<Long?>, val color: Color, val dashed: Boolean = false, val width: Dp = 2.dp)

/**
 * 잔액 그래프 — 잔고예보(AI 생활비 제외 · 반영)와 가상 구매(사기 전 · 후)가 같이 쓴다.
 * 0원 바닥선, 안전 잔액 점선, 위험 구간 음영, 고른 날 세로줄과 점. 누르거나 옆으로 끌면 [onSelect]로 그날을 알린다.
 * 고른 날이 옮겨 갈 때는 YamuMotion 짧은 곡선으로, 모션 줄이기면 바로 옮긴다
 */
@Composable
fun BalanceChart(
    dates: List<LocalDate>,
    lines: List<ChartLine>,
    description: String,
    modifier: Modifier = Modifier,
    threshold: Long? = null,
    riskRanges: List<IntRange> = emptyList(),
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    incomeArrow: Boolean = false,
    height: Dp = 140.dp,
) {
    val n = dates.size
    if (n < 2 || lines.isEmpty()) return
    val c = YamuTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = YamuTheme.typography.labelS.copy(color = c.onWarningContainer)
    val values = lines.flatMap { it.values.filterNotNull() }
    val minV = minOf(values.minOrNull() ?: 0L, threshold ?: 0L, 0L)
    val maxV = maxOf(values.maxOrNull() ?: 1L, threshold ?: 1L, 1L)
    val reduce = YamuTheme.reduceMotion
    val selectedIndex = selected?.coerceIn(0, n - 1)
    // 손가락 처리(pointerInput)는 날짜 수(n)가 바뀔 때만 다시 시작한다. 그 안에서 처음 받은 onSelect를 그대로 쓰면, 기간은 두고 계좌만 바꿨을 때
    // 화면이 새로 만든 '고른 날' 상태가 아니라 버린 상태를 바꿔 그래프를 눌러도 날짜가 그대로였다 — 늘 마지막 값을 읽는다
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestIncomeArrow by rememberUpdatedState(incomeArrow)
    val fraction by animateFloatAsState(
        targetValue = (selectedIndex ?: 0) / (n - 1f),
        animationSpec = if (reduce) snap() else tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard),
        label = "selectedDay",
    )
    val surface = c.surface
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    contentDescription = description
                    if (onSelect != null && selectedIndex != null) {
                        customActions = listOf(
                            CustomAccessibilityAction("하루 앞") { if (selectedIndex > 0) onSelect(selectedIndex - 1); true },
                            CustomAccessibilityAction("하루 뒤") { if (selectedIndex < n - 1) onSelect(selectedIndex + 1); true },
                        )
                    }
                }
                .then(
                    if (onSelect == null) Modifier else Modifier
                        .pointerInput(n) { detectTapGestures { o -> latestOnSelect?.invoke(indexAt(o.x, size.width.toFloat(), n, latestIncomeArrow, density)) } }
                        .pointerInput(n) { detectHorizontalDragGestures { change, _ -> latestOnSelect?.invoke(indexAt(change.position.x, size.width.toFloat(), n, latestIncomeArrow, density)) } },
                ),
        ) {
            val left = 6.dp.toPx()
            val right = size.width - (if (incomeArrow) 18.dp else 6.dp).toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 2.dp.toPx()
            val span = (maxV - minV).toFloat()
            fun y(v: Long) = bottom - (v - minV) / span * (bottom - top)
            val step = (right - left) / (n - 1)
            fun x(i: Int) = left + i * step

            // 위험 구간 — 그날 칸의 양 끝까지
            riskRanges.forEach { r ->
                val from = (x(r.first.coerceIn(0, n - 1)) - step / 2).coerceAtLeast(0f)
                val to = (x(r.last.coerceIn(0, n - 1)) + step / 2).coerceAtMost(right + 4.dp.toPx())
                drawRect(c.warningContainer, topLeft = Offset(from, 0f), size = Size(to - from, size.height))
            }
            drawLine(c.outline, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
            threshold?.let { t ->
                val ty = y(t)
                drawLine(c.warning.copy(alpha = 0.7f), Offset(0f, ty), Offset(right, ty), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                val label = measurer.measure("안전 잔액 ${t.toManWon()}", labelStyle)
                if (ty - label.size.height - 2.dp.toPx() > 0) drawText(label, topLeft = Offset(left, ty - label.size.height - 2.dp.toPx()))
            }
            lines.forEach { line ->
                val path = Path()
                var drawing = false
                line.values.forEachIndexed { i, v ->
                    if (v == null) {
                        drawing = false
                    } else if (!drawing) {
                        path.moveTo(x(i), y(v)); drawing = true
                    } else {
                        path.lineTo(x(i), y(v))
                    }
                }
                drawPath(
                    path,
                    line.color,
                    style = Stroke(
                        width = line.width.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
                    ),
                )
            }
            // 시작 점 — 오늘. 실선 중 마지막(잔고예보의 AI 생활비 제외 선 · 가상 구매의 사기 후 선) 색으로
            val dotLine = lines.lastOrNull { !it.dashed } ?: lines.first()
            dotLine.values.firstOrNull()?.let { drawCircle(dotLine.color, radius = 4.5.dp.toPx(), center = Offset(x(0), y(it))) }
            if (selectedIndex != null) {
                val sx = left + fraction * (right - left)
                drawLine(c.text2, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1.dp.toPx())
                lines.forEach { line ->
                    val v = line.values.getOrNull(selectedIndex) ?: return@forEach
                    val center = Offset(sx, y(v))
                    if (line.dashed) {
                        drawCircle(surface, radius = 4.5.dp.toPx(), center = center)
                        drawCircle(line.color, radius = 4.5.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                    } else {
                        drawCircle(surface, radius = 6.dp.toPx(), center = center)
                        drawCircle(line.color, radius = 4.5.dp.toPx(), center = center)
                    }
                }
            }
            if (incomeArrow) {
                val ax = size.width - 4.dp.toPx()
                drawLine(c.primary, Offset(ax, bottom), Offset(ax, top + 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                val head = Path().apply {
                    moveTo(ax, top - 2.dp.toPx())
                    lineTo(ax - 5.dp.toPx(), top + 7.dp.toPx())
                    lineTo(ax + 5.dp.toPx(), top + 7.dp.toPx())
                    close()
                }
                drawPath(head, c.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        val axis = YamuTheme.typography.labelS
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("오늘", style = axis, color = c.text1, modifier = Modifier.weight(1f))
            Text(dates.last().toMonthDay(), style = axis, color = c.text1)
            if (incomeArrow) {
                Spacer(Modifier.width(8.dp))
                Text("월급", style = axis, color = c.primary)
            }
        }
    }
}

/** 손가락 위치 → 가장 가까운 날(그래프 안쪽 여백과 월급 화살표 자리를 뺀다) */
private fun indexAt(px: Float, width: Float, n: Int, incomeArrow: Boolean, density: Float): Int {
    val left = 6 * density
    val right = width - (if (incomeArrow) 18 else 6) * density
    val f = ((px - left) / (right - left)).coerceIn(0f, 1f)
    return Math.round(f * (n - 1)).coerceIn(0, n - 1)
}

/**
 * 범례 — 선 모양 + 이름. [missing]은 이번에 그리지 못한 선(취소선).
 * 이름이 'AI 생활비 제외'처럼 길어져 큰 글씨에서는 한 줄에 다 들어가지 않는다 — 넘치면 다음 줄로 내려 이름이 글자 단위로 꺾이지 않게 한다
 */
@Composable
fun ChartLegend(lines: List<ChartLine>, modifier: Modifier = Modifier, missing: List<String> = emptyList()) {
    val c = YamuTheme.colors
    val style = YamuTheme.typography.bodyS
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 18.dp, height = 6.dp)) {
                    drawLine(
                        line.color,
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        strokeWidth = (line.width + 0.4.dp).toPx(),
                        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(line.label, style = style, color = c.text2)
            }
        }
        missing.forEach { Text(it, style = style.copy(textDecoration = TextDecoration.LineThrough), color = c.text3, modifier = Modifier.padding(start = 0.dp)) }
    }
}
