package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 축하 팝 시작부터 리플 · 파티클이 모두 사라질 때까지(ms) */
internal const val BURST_TOTAL_MS = YamuMotion.PARTICLE_START_MS + YamuMotion.PARTICLE_MS

/**
 * 축하 팝 뒤에 퍼지는 고리 두 겹과 파티클 7개. 캐릭터 뒤에 깔고, 팝 시작부터 지난 시간 [elapsedMs]로 그린다.
 * 각도는 매번 같다(녹화끼리 비교할 수 있게). 크기는 이 자리(캐릭터 자리) 폭 기준이고, 고리는 반지름 0.8배 · 파티클은 약 0.86배까지
 * 자리 밖으로 나간다. 조상이 자르면(clip) 잘리므로 화면에 붙일 때 둘레 여백을 본다.
 * [elapsedMs]는 그리기 단계에서만 읽어 매 프레임 컴포지션을 다시 하지 않는다.
 */
@Composable
internal fun CelebrationBurst(
    elapsedMs: () -> Float,
    modifier: Modifier = Modifier,
    ripple: Boolean = true,
    particles: Boolean = true,
) {
    val c = YamuTheme.colors
    val particleColors = listOf(c.primary, c.primaryContainer)
    Canvas(modifier) {
        val elapsedMs = elapsedMs()
        val base = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)

        if (ripple) {
            val t = progress(elapsedMs, YamuMotion.RIPPLE_START_MS, YamuMotion.RIPPLE_MS)
            if (t >= 0f && t < 1f) {
                val e = YamuMotion.out.transform(t)
                val outer = base / 2 * (1f + (YamuMotion.RIPPLE_MAX_SCALE - 1f) * e)
                val stroke = Stroke(width = 2.dp.toPx())
                listOf(outer, outer * YamuMotion.RIPPLE_INNER_RATIO).forEach { r ->
                    drawCircle(c.primary, radius = r, center = center, alpha = 1f - e, style = stroke)
                }
            }
        }

        if (particles) {
            val t = progress(elapsedMs, YamuMotion.PARTICLE_START_MS, YamuMotion.PARTICLE_MS)
            if (t >= 0f && t < 1f) {
                val e = YamuMotion.out.transform(t)
                val distance = base * YamuMotion.PARTICLE_DISTANCE * e
                val radius = base * YamuMotion.PARTICLE_SIZE / 2
                repeat(YamuMotion.PARTICLE_COUNT) { i ->
                    val angle = (-90.0 + i * 360.0 / YamuMotion.PARTICLE_COUNT) * PI / 180.0
                    drawCircle(
                        particleColors[i % particleColors.size],
                        radius = radius,
                        center = center + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat()),
                        alpha = 1f - e,
                    )
                }
            }
        }
    }
}

/** [start]부터 [duration] 동안 0 → 1. 시작 전은 음수, 끝난 뒤는 1 이상 */
private fun progress(elapsed: Float, start: Int, duration: Int) = (elapsed - start) / duration
