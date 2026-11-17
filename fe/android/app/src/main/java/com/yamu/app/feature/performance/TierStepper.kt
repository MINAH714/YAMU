package com.yamu.app.feature.performance

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.LocalYamuReduceMotion
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import java.util.Locale

/**
 * 여러 단계 실적 게이지(20만 · 30만 · 40만 …) — 단계마다 같은 폭, 단계 끝에 점.
 * 넘긴 단계의 점은 채우고, 지금 단계는 그 칸 안에서 채운 비율만큼. 모든 단계를 넘기면 초록.
 * 막대는 [animatedFill]로 차오르고, 점은 막대가 지나갈 때 채워진다(모든 단계를 넘긴 카드는 끝에 닿을 때 초록).
 * [showLabels]면 점 아래에 단계 금액('20만')과 그 단계의 한 달 최대 혜택('월 1.5만', [amounts])을 둔다
 */
@Composable
internal fun TierStepper(
    current: Long,
    thresholds: List<Long>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
    amounts: List<Long?> = emptyList(),
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val n = thresholds.size
    if (n == 0) return
    val reached = thresholds.count { it <= current }
    val done = reached >= n
    // 단계 칸 단위 진행 — 넘긴 칸 수 + 지금 칸 안의 비율
    val progress = if (done) n.toFloat() else {
        val lo = if (reached == 0) 0L else thresholds[reached - 1]
        val hi = thresholds[reached]
        reached + ((current - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    val shown = animatedFill(progress)
    val fill = if (done && shown >= n) c.success else c.primary
    val nodeRadius = 5.dp
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(nodeRadius * 2 + 2.dp)) {
            val r = nodeRadius.toPx()
            val y = size.height / 2
            val x0 = r
            val w = size.width - 2 * r
            val bar = 6.dp.toPx()
            drawLine(c.outlineVariant, Offset(x0, y), Offset(x0 + w, y), strokeWidth = bar, cap = StrokeCap.Round)
            if (shown > 0f) drawLine(fill, Offset(x0, y), Offset(x0 + w * shown / n, y), strokeWidth = bar, cap = StrokeCap.Round)
            for (i in 0 until n) {
                val center = Offset(x0 + w * (i + 1) / n, y)
                if (i < reached && shown >= i + 1) {
                    drawCircle(fill, r, center)
                } else {
                    drawCircle(c.surface, r, center)
                    drawCircle(c.outlineVariant, r - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                }
            }
        }
        if (showLabels) {
            // 라벨은 점 바로 아래 가운데(양 끝은 화면 안으로)
            Layout(
                content = {
                    thresholds.forEachIndexed { i, th ->
                        val on = i < reached
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(shortMan(th), style = t.bodyS.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.text1 else c.text3)
                            amounts.getOrNull(i)?.let { Text("월 ${shortMan(it)}", style = t.bodyS, color = if (on) c.text2 else c.text3) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
                val width = constraints.maxWidth
                val r = nodeRadius.roundToPx()
                val w = width - 2 * r
                layout(width, placeables.maxOf { it.height }) {
                    placeables.forEachIndexed { i, p ->
                        val x = r + w * (i + 1) / n - p.width / 2
                        p.place(x.coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
                    }
                }
            }
        }
    }
}

/** 게이지가 차오르는 시간 — 홈 예상 잔액이 굴러가는 시간(YamuMotion.AMOUNT_ROLL_MS)과 같게 */
internal const val GAUGE_FILL_MS = 700

/**
 * 게이지가 차오르는 값(S15P31E103-614). 화면에 처음 나올 땐 0에서 화면 교체 페이드(SCREEN_SWAP_MS)가 끝난 뒤 출발하고,
 * 값이 바뀌면(결제하고 돌아옴) 보이던 값에서 새 값으로 [GAUGE_FILL_MS] 동안 간다. 모션 줄이기면 바로 새 값.
 * 다 찬 뒤에는 [target]을 그대로 돌려준다(Float 반올림 없이)
 */
@Composable
internal fun animatedFill(target: Float): Float {
    val reduceMotion = LocalYamuReduceMotion.current
    val anim = remember { Animatable(if (reduceMotion) target else 0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(target, reduceMotion) {
        val delay = if (first[0]) YamuMotion.SCREEN_SWAP_MS else 0
        first[0] = false
        if (reduceMotion) anim.snapTo(target)
        else anim.animateTo(target, tween(GAUGE_FILL_MS, delayMillis = delay, easing = YamuMotion.standard))
    }
    return if (!anim.isRunning && anim.targetValue == target) target else anim.value
}

/** 200000 → '20만', 15000 → '1.5만', 4500 → '4,500' */
internal fun shortMan(v: Long): String {
    if (v < 10_000 || v % 1_000 != 0L) return v.toWon()
    val man = v / 10_000.0
    val text = if (man % 1.0 == 0.0) man.toLong().toString() else String.format(Locale.ROOT, "%.1f", man).trimEnd('0').trimEnd('.')
    return "${text}만"
}
