package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 축하 팝 시작부터 리플 · 파티클이 모두 사라질 때까지(ms) */
internal const val BURST_TOTAL_MS = YamuMotion.PARTICLE_START_MS + YamuMotion.PARTICLE_MS

/**
 * 축하 팝 뒤에 퍼지는 고리 두 겹과 파티클 7개. 캐릭터 뒤에 깔고, 팝 시작부터 지난 시간 [elapsedMs]로 그린다.
 * 각도는 매번 같다(녹화끼리 비교할 수 있게). 크기는 이 자리(캐릭터 자리) 폭 기준이고, 고리는 반지름 0.8배 · 파티클은 약 0.86배까지
 * 자리 밖으로 나간다. 조상이 자르면(clip) 잘리므로 화면에 붙일 때 둘레 여백을 보고, 스크롤 안이면 [CelebrationLayer]에 그린다.
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
    Canvas(modifier) {
        drawCelebrationBurst(elapsedMs(), Offset(size.width / 2, size.height / 2), size.minDimension, ripple, particles, c.primary, c.primaryContainer)
    }
}

/**
 * 고리 · 파티클을 스크롤 · 상단 바보다 위에 그리는 층. 캐릭터가 스크롤 안에 있어 위로 가는 파티클이 잘릴 때
 * 화면 전체를 이것으로 감싸고, 같은 [state]를 CharacterImage(burstLayer = …)에 넘긴다. 배치는 바뀌지 않는다.
 * 터지는 동안만 Canvas 한 장을 얹고, 매 프레임 캐릭터의 지금 자리를 읽어 스크롤해도 따라간다.
 */
@Composable
fun CelebrationLayer(state: CelebrationLayerState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = YamuTheme.colors
    Box(modifier.onGloballyPositioned { state.host = it }) {
        content()
        if (state.spots.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                val host = state.host?.takeIf { it.isAttached } ?: return@Canvas
                state.spots.forEach { spot ->
                    val target = spot.coords()?.takeIf { it.isAttached } ?: return@forEach
                    val topLeft = host.localPositionOf(target, Offset.Zero)
                    val w = target.size.width.toFloat()
                    val h = target.size.height.toFloat()
                    drawCelebrationBurst(
                        spot.elapsedMs(), topLeft + Offset(w / 2, h / 2), min(w, h),
                        spot.parts.ripple, spot.parts.particles, c.primary, c.primaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberCelebrationLayer(): CelebrationLayerState = remember { CelebrationLayerState() }

@Stable
class CelebrationLayerState internal constructor() {
    internal val spots = mutableStateListOf<BurstSpot>()
    internal var host: LayoutCoordinates? = null   // 그리기 때만 읽어서 상태로 두지 않는다
}

/** 층에 맡긴 한 번의 버스트. [coords]는 캐릭터 자리(크기 · 회전 전) */
internal class BurstSpot(
    val elapsedMs: () -> Float,
    val parts: BurstParts,
    val coords: () -> LayoutCoordinates?,
)

/** [center]를 가운데로, [base](캐릭터 자리 폭) 기준 크기로 그린다 */
private fun DrawScope.drawCelebrationBurst(
    elapsedMs: Float,
    center: Offset,
    base: Float,
    ripple: Boolean,
    particles: Boolean,
    primary: Color,
    primaryContainer: Color,
) {
    if (ripple) {
        val t = progress(elapsedMs, YamuMotion.RIPPLE_START_MS, YamuMotion.RIPPLE_MS)
        if (t >= 0f && t < 1f) {
            val e = YamuMotion.out.transform(t)
            val outer = base / 2 * (1f + (YamuMotion.RIPPLE_MAX_SCALE - 1f) * e)
            val stroke = Stroke(width = 2.dp.toPx())
            listOf(outer, outer * YamuMotion.RIPPLE_INNER_RATIO).forEach { r ->
                drawCircle(primary, radius = r, center = center, alpha = 1f - e, style = stroke)
            }
        }
    }

    if (particles) {
        val t = progress(elapsedMs, YamuMotion.PARTICLE_START_MS, YamuMotion.PARTICLE_MS)
        if (t >= 0f && t < 1f) {
            val e = YamuMotion.out.transform(t)
            val distance = base * YamuMotion.PARTICLE_DISTANCE * e
            val radius = base * YamuMotion.PARTICLE_SIZE / 2
            val colors = listOf(primary, primaryContainer)
            repeat(YamuMotion.PARTICLE_COUNT) { i ->
                val angle = (-90.0 + i * 360.0 / YamuMotion.PARTICLE_COUNT) * PI / 180.0
                drawCircle(
                    colors[i % colors.size],
                    radius = radius,
                    center = center + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat()),
                    alpha = 1f - e,
                )
            }
        }
    }
}

/** [start]부터 [duration] 동안 0 → 1. 시작 전은 음수, 끝난 뒤는 1 이상 */
private fun progress(elapsed: Float, start: Int, duration: Int) = (elapsed - start) / duration
