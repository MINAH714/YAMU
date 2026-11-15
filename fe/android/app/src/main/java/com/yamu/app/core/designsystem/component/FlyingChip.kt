package com.yamu.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 칩이 날아갈 출발 · 도착 자리(화면 루트 기준). [Modifier.chipFrom] · [Modifier.chipTo]가 잰다 */
@Stable
class FlyingChipAnchors internal constructor() {
    internal var from by mutableStateOf<Rect?>(null)
    internal var to by mutableStateOf<Rect?>(null)
}

@Composable
fun rememberFlyingChipAnchors(): FlyingChipAnchors = remember { FlyingChipAnchors() }

/** 칩이 나오는 자리(결제 완료의 금액 같은 것) */
fun Modifier.chipFrom(anchors: FlyingChipAnchors): Modifier = onGloballyPositioned { anchors.from = it.boundsInRoot() }

/** 칩이 닿는 자리(실적 합계 같은 것) */
fun Modifier.chipTo(anchors: FlyingChipAnchors): Modifier = onGloballyPositioned { anchors.to = it.boundsInRoot() }

/** 날아가는 칩의 자리 계산 — 화면과 떼어 단위 테스트한다 */
internal object ChipFlight {
    /** 비행 진행 [p](0~1)에서 칩 가운데 — 출발 가운데에서 도착 가운데로 곧게 */
    fun center(from: Rect, to: Rect, p: Float): Offset = lerp(from.center, to.center, p.coerceIn(0f, 1f))

    /** 칩을 [layer] 안에 둘 왼쪽 위(칩 가운데가 [center]에 오도록) */
    fun topLeft(center: Offset, chip: IntSize, layer: Offset): IntOffset =
        IntOffset((center.x - chip.width / 2f - layer.x).roundToInt(), (center.y - chip.height / 2f - layer.y).roundToInt())
}

/**
 * 칩 하나가 [anchors]의 출발 자리에서 나와 도착 자리로 날아간다(M-DONE-pay). 처음 그려진 순간을 0으로
 * [YamuMotion.CHIP_APPEAR_AT_MS]에 나타나고 [YamuMotion.CHIP_FLY_AT_MS]부터 [YamuMotion.CHIP_FLY_MS] 동안 날아간 뒤 도착하며 사라진다.
 * 부르는 화면의 맨 바깥 Box 안 맨 끝에 [Modifier.matchParentSize]로 둔다(다른 내용 위에 그려짐). 터치는 받지 않는다.
 * 한 번만 날고 회전으로 다시 만들어져도 다시 날지 않는다. 모션 줄이기면 날지 않고 바로 [onArrive]를 부르고, 자리를 못 쟀으면 칩은 보이지 않은 채 같은 때 부른다.
 * 칩 글자는 읽어 주지 않는다 — 같은 내용을 도착 자리가 읽어 준다
 */
@Composable
fun FlyingChipLayer(
    text: String,
    anchors: FlyingChipAnchors,
    modifier: Modifier = Modifier,
    onArrive: () -> Unit = {},
) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val arrive by rememberUpdatedState(onArrive)
    var flown by rememberSaveable { mutableStateOf(false) }
    var layer by remember { mutableStateOf(Offset.Zero) }
    var chip by remember { mutableStateOf(IntSize.Zero) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(CHIP_FROM_SCALE) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (flown) return@LaunchedEffect
        if (reduce) {
            flown = true
            arrive()
            return@LaunchedEffect
        }
        val appear = tween<Float>(YamuMotion.SHORT_MS, delayMillis = YamuMotion.CHIP_APPEAR_AT_MS, easing = YamuMotion.standard)
        launch { alpha.animateTo(1f, appear) }
        launch { scale.animateTo(1f, appear) }
        progress.animateTo(1f, tween(YamuMotion.CHIP_FLY_MS, delayMillis = YamuMotion.CHIP_FLY_AT_MS, easing = YamuMotion.inOut))
        flown = true
        arrive()
        val fade = tween<Float>(YamuMotion.SHORT_MS, easing = YamuMotion.standard)
        launch { scale.animateTo(CHIP_FROM_SCALE, fade) }
        alpha.animateTo(0f, fade)
    }
    Box(modifier.onGloballyPositioned { layer = it.positionInRoot() }.clearAndSetSemantics {}) {
        val from = anchors.from
        val to = anchors.to
        if (from != null && to != null && alpha.value > 0f) {
            Text(
                text,
                style = YamuTheme.typography.labelL,
                color = c.onPrimary,
                maxLines = 1,
                modifier = Modifier
                    .onSizeChanged { chip = it }
                    .offset { ChipFlight.topLeft(ChipFlight.center(from, to, progress.value), chip, layer) }
                    .graphicsLayer {
                        this.alpha = alpha.value
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .background(c.primary, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 칩이 나타날 때 커지기 시작하고 도착하며 줄어드는 크기(넘침 없음) */
private const val CHIP_FROM_SCALE = 0.92f
