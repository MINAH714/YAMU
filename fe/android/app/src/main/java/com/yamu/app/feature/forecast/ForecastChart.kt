package com.yamu.app.feature.forecast

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import java.time.LocalDate

/**
 * 예상 잔액 그래프 — 서버 예상 선(series.expected)을 오늘부터 월급 전날까지 그대로 잇는다(앱은 값을 만들지 않음).
 * 프로토타입 F-01: 파란 점선 + 아래 연한 채움, 0원 바닥선(실선), 시작 · 끝 점(부족이면 끝 점만 빨강),
 * 오른쪽 끝 '월급' 화살표, 계획은 그날 회색 점(월급 뒤 계획은 화살표 위). 축 글자는 '오늘' · 월급 전날 · '월급'.
 */
@Composable
fun ForecastChart(
    points: List<Pair<LocalDate, Long>>,
    incomeDate: LocalDate?,
    modifier: Modifier = Modifier,
    planDates: List<LocalDate> = emptyList(),
) {
    if (points.size < 2) return
    val c = YamuTheme.colors
    val minV = minOf(points.minOf { it.second }, 0L)
    val maxV = maxOf(points.maxOf { it.second }, 1L)
    val lowest = points.minOf { it.second }
    val endShort = points.last().second < 0
    val lineColor = c.primary
    val fillColor = c.primaryContainer.copy(alpha = 0.7f)
    val surface = c.surface
    Column(modifier.semantics { contentDescription = "예상 잔액 그래프, 가장 낮을 때 ${lowest.toWon()}원" }) {
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp)) {
            val arrowX = size.width - 4.dp.toPx()
            val lineRight = arrowX - 14.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height
            val span = (maxV - minV).toFloat()
            fun y(v: Long) = bottom - (v - minV) / span * (bottom - top)
            val stepX = lineRight / (points.size - 1)
            fun x(i: Int) = i * stepX
            val baseY = y(0)
            // 채움
            val fill = Path().apply {
                moveTo(x(0), baseY)
                points.forEachIndexed { i, p -> lineTo(x(i), y(p.second)) }
                lineTo(x(points.lastIndex), baseY)
                close()
            }
            drawPath(fill, fillColor)
            // 0원 바닥선
            drawLine(c.outline, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx())
            // 예상 선(점선)
            val line = Path().apply {
                moveTo(x(0), y(points.first().second))
                points.forEachIndexed { i, p -> if (i > 0) lineTo(x(i), y(p.second)) }
            }
            drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            // 계획 점 — 그날(회색 5dp + 흰 테두리 2dp), 월급 뒤면 화살표 끝에서 25dp 아래(프로토타입 F-01d)
            planDates.forEach { d ->
                val i = points.indexOfFirst { it.first == d }
                val center = if (i >= 0) Offset(x(i), y(points[i].second)) else Offset(arrowX, top + 25.dp.toPx())
                drawCircle(surface, radius = 7.dp.toPx(), center = center)
                drawCircle(c.text2, radius = 5.dp.toPx(), center = center)
            }
            // 시작 · 끝 점
            drawCircle(lineColor, radius = 4.5.dp.toPx(), center = Offset(x(0), y(points.first().second)))
            drawCircle(if (endShort) c.error else lineColor, radius = 4.5.dp.toPx(), center = Offset(x(points.lastIndex), y(points.last().second)))
            // 월급 화살표
            if (incomeDate != null) {
                drawLine(lineColor, Offset(arrowX, bottom), Offset(arrowX, top + 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                val head = Path().apply {
                    moveTo(arrowX, top)
                    lineTo(arrowX - 5.dp.toPx(), top + 9.dp.toPx())
                    lineTo(arrowX + 5.dp.toPx(), top + 9.dp.toPx())
                    close()
                }
                drawPath(head, lineColor)
            }
        }
        Spacer(Modifier.height(6.dp))
        val axis = YamuTheme.typography.labelS
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("오늘", style = axis, color = c.text1, modifier = Modifier.weight(1f))
            points.last().first.let { Text(it.toMonthDay(), style = axis, color = c.text1) }
            if (incomeDate != null) {
                Spacer(Modifier.width(8.dp))
                Text("월급", style = axis, color = c.primary)
            }
        }
    }
}
