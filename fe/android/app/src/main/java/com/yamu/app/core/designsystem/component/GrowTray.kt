package com.yamu.app.core.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** 떠 있는 트레이와 화면 가장자리 사이 · 트레이 모서리 · 위 띠 두께 */
object GrowTrayDefaults {
    val margin = 12.dp
    val radius = 24.dp
    val band = 6.dp
    val elevation = 16.dp
}

/** 트레이 자리 계산 — 화면과 떼어 단위 테스트한다. 모두 트레이를 띄우는 판(host) 기준 px */
internal object TrayGeometry {
    /** 버튼 자리([p] 0)와 트레이 자리([p] 1) 사이 */
    fun lerpRect(from: Rect, to: Rect, p: Float): Rect = Rect(
        androidx.compose.ui.util.lerp(from.left, to.left, p),
        androidx.compose.ui.util.lerp(from.top, to.top, p),
        androidx.compose.ui.util.lerp(from.right, to.right, p),
        androidx.compose.ui.util.lerp(from.bottom, to.bottom, p),
    )

    /**
     * 떠 있는 트레이 자리 — 좌우 [margin], 아래는 시스템 바 위로 [margin]. 높이는 내용 높이이고,
     * 화면보다 크면 위도 시스템 바 아래 [margin]까지만 (내용은 그 안에서 스크롤)
     */
    fun trayRect(hostW: Float, hostH: Float, contentH: Float, margin: Float, bottomInset: Float, topInset: Float): Rect {
        val bottom = hostH - margin - bottomInset
        val top = max(topInset + margin, bottom - contentH)
        return Rect(margin, top, hostW - margin, bottom)
    }

    /** 내용이 쓸 수 있는 가장 큰 높이 */
    fun maxContentHeight(hostH: Float, margin: Float, bottomInset: Float, topInset: Float): Float =
        (hostH - margin * 2 - bottomInset - topInset).coerceAtLeast(0f)

    /** 뒤 화면이 [scale]로 줄어든 채 잰 자리를 줄기 전 자리로 되돌린다(판 가운데 기준) */
    fun unscale(r: Rect, hostW: Float, hostH: Float, scale: Float): Rect {
        val cx = hostW / 2
        val cy = hostH / 2
        return Rect(cx + (r.left - cx) / scale, cy + (r.top - cy) / scale, cx + (r.right - cx) / scale, cy + (r.bottom - cy) / scale)
    }
}

/**
 * 버튼에서 커지는 확인 트레이의 상태([rememberGrowTrayState]). [open]하면 [Modifier.growTrayAnchor]를 단 버튼 자리에서
 * 트레이가 커지고, [close]하면 같은 길로 되감긴다. 연 상태는 회전해도 남고, 다시 그려지면 끝 상태로 바로 보인다
 */
@Stable
class GrowTrayState internal constructor(initiallyOpen: Boolean) {
    /** 트레이가 그려지는 중(열리는 중 · 열림 · 닫히는 중) */
    var visible by mutableStateOf(initiallyOpen)
        private set

    /** 열렸거나 열리는 중. 닫히기 시작하면 false */
    var isOpen by mutableStateOf(initiallyOpen)
        private set

    /** 처음 [YamuMotion.TRAY_BLOCK_MS] 동안과 닫히는 동안은 트레이 안을 누를 수 없다(커지는 중 연타가 안쪽 버튼에 닿지 않게) */
    internal var interactive by mutableStateOf(initiallyOpen)

    /** 0 버튼 자리 · 1 트레이 자리 */
    internal val progress = Animatable(if (initiallyOpen) 1f else 0f)
    /** 바탕: 0 버튼색 · 1 surface */
    internal val fill = Animatable(if (initiallyOpen) 1f else 0f)
    /** 위 띠: 0 버튼색 · 1 위험색 */
    internal val band = Animatable(if (initiallyOpen) 1f else 0f)
    internal val contentAlpha = Animatable(if (initiallyOpen) 1f else 0f)
    /** 모션 줄이기에서 트레이 전체 페이드. 보통은 1 */
    internal val alpha = Animatable(if (initiallyOpen) 1f else 0f)

    internal var anchorInRoot: Rect? = null
    internal var hostOrigin = Offset.Zero
    internal var hostSize = IntSize.Zero
    /** 연 순간의 버튼 자리(판 기준). 다시 그려져 모르면 닫을 때 지금 자리에서 되돌려 구한다 */
    private var from: Rect? = null
    internal var reduce = false
    internal var scope: CoroutineScope? = null
    private var job: Job? = null

    internal fun anchorInHost(): Rect? = anchorInRoot?.translate(-hostOrigin)

    /** 그릴 때 쓰는 버튼 자리 — 없으면 트레이 자리에서 그대로 페이드한다 */
    internal fun fromRect(): Rect? = from

    fun open() {
        if (isOpen) return
        val s = scope ?: return
        from = anchorInHost()
        isOpen = true
        visible = true
        interactive = false
        job?.cancel()
        job = s.launch {
            if (reduce || from == null) {
                progress.snapTo(1f); fill.snapTo(1f); band.snapTo(1f); contentAlpha.snapTo(1f)
                alpha.animateTo(1f, tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard))
                interactive = true
                return@launch
            }
            alpha.snapTo(1f)
            launch { fill.animateTo(1f, tween(YamuMotion.COLOR_SHIFT_MS, easing = YamuMotion.standard)) }
            launch { band.animateTo(1f, tween(YamuMotion.COLOR_SHIFT_MS, easing = YamuMotion.standard)) }
            launch { contentAlpha.animateTo(1f, tween(YamuMotion.TRAY_CONTENT_MS, delayMillis = YamuMotion.TRAY_CONTENT_DELAY_MS, easing = YamuMotion.standard)) }
            launch {
                delay(scaledMs(YamuMotion.TRAY_BLOCK_MS))
                interactive = true
            }
            progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = YamuMotion.TRAY_STIFFNESS))
        }
    }

    fun close() {
        if (!isOpen) return
        val s = scope ?: return
        isOpen = false
        interactive = false
        if (from == null && !reduce) {
            // 회전 뒤라 연 순간을 모른다 — 지금 버튼 자리는 뒤 화면과 함께 줄어 있으니 되돌려 쓴다
            from = anchorInHost()?.let { TrayGeometry.unscale(it, hostSize.width.toFloat(), hostSize.height.toFloat(), YamuMotion.BACKDROP_SCALE) }
        }
        job?.cancel()
        job = s.launch {
            if (reduce || from == null) {
                alpha.animateTo(0f, tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard))
                finish()
                return@launch
            }
            launch { contentAlpha.animateTo(0f, tween(YamuMotion.TRAY_CONTENT_OUT_MS, easing = YamuMotion.standard)) }
            launch { band.animateTo(0f, tween(YamuMotion.COLOR_SHIFT_MS, easing = YamuMotion.standard)) }
            launch { fill.animateTo(0f, tween(YamuMotion.COLOR_SHIFT_MS, delayMillis = YamuMotion.TRAY_CLOSE_COLOR_DELAY_MS, easing = YamuMotion.standard)) }
            progress.animateTo(0f, tween(YamuMotion.TRAY_CLOSE_MS, easing = YamuMotion.inOut))
            finish()
        }
    }

    private suspend fun finish() {
        visible = false
        progress.snapTo(0f); fill.snapTo(0f); band.snapTo(0f); contentAlpha.snapTo(0f); alpha.snapTo(0f)
    }

    companion object {
        internal val Saver: Saver<GrowTrayState, Boolean> = Saver(save = { it.isOpen }, restore = { GrowTrayState(it) })
    }
}

@Composable
fun rememberGrowTrayState(): GrowTrayState = rememberSaveable(saver = GrowTrayState.Saver) { GrowTrayState(false) }

/**
 * 트레이가 커져 나올 버튼에 단다. 트레이가 떠 있는 동안 버튼은 숨기고(트레이가 곧 그 버튼), 닫히는 마지막 프레임에 다시 보인다.
 * 모션 줄이기에서는 트레이가 제자리에서 나타나므로 버튼을 숨기지 않는다
 */
fun Modifier.growTrayAnchor(state: GrowTrayState): Modifier = this
    .onGloballyPositioned { state.anchorInRoot = it.boundsInRoot() }
    .graphicsLayer { alpha = if (state.visible && !state.reduce) 0f else 1f }

/**
 * 버튼에서 커지는 확인 트레이를 띄우는 판(모션 명세 M-TRAY-app). [content] 화면 전체를 감싸고, [state]가 열리면
 * 뒤 화면을 0.92배로 물리고 어둡게 덮은 뒤 아래에 떠 있는 네 모서리 트레이([tray])를 띄운다. 끌어 닫기는 없고
 * 바깥 · 뒤로가기는 [onDismiss]로 알린다(진행 중이라 닫으면 안 되면 부르는 쪽이 무시한다).
 *
 * [fromColor]는 버튼 바탕(글자 버튼이면 surface, 채운 버튼이면 그 색)이고, 트레이 바탕은 거기서 surface로 120ms에 바뀐다.
 * [danger]면 위 띠가 같은 시간에 위험색으로 바뀐다. [fromRadius]는 버튼 모서리.
 * 트레이 안 내용은 처음부터 끝 폭으로 놓이고 커지는 틀이 잘라 보여 주므로 커지는 동안 글자가 다시 줄바꿈되지 않는다.
 * 트레이가 떠 있는 동안 뒤 화면은 TalkBack에서 숨기고, 트레이는 [title]을 판 제목으로 읽는다.
 * [insets]는 트레이를 띄울 때 피할 시스템 바(컴포넌트 갤러리처럼 화면 일부에 둘 때는 비운다)
 */
@Composable
fun GrowTrayHost(
    state: GrowTrayState,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    fromColor: Color = YamuTheme.colors.surface,
    fromRadius: Dp = YamuRadius.button,
    danger: Boolean = true,
    bottomInsets: WindowInsets = WindowInsets.navigationBars,
    topInsets: WindowInsets = WindowInsets.statusBars,
    tray: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val scope = rememberCoroutineScope()
    SideEffect {
        state.scope = scope
        state.reduce = reduce
    }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                state.hostOrigin = it.positionInRoot()
                state.hostSize = it.size
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (reduce) 1f else androidx.compose.ui.util.lerp(1f, YamuMotion.BACKDROP_SCALE, state.progress.value)
                    scaleX = s
                    scaleY = s
                }
                .then(if (state.visible) Modifier.clearAndSetSemantics {} else Modifier),
        ) { content() }
        if (state.visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (reduce) state.alpha.value else state.progress.value.coerceIn(0f, 1f) }
                    .background(c.scrim)
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) { detectTapGestures { if (state.interactive) onDismiss() } },
            )
            TrayFrame(state, title, fromColor, fromRadius, danger, bottomInsets, topInsets, tray)
        }
    }
    // 화면 안의 뒤로가기보다 나중에 등록돼야 트레이가 열린 동안 이쪽이 먼저 받는다
    BackHandler(enabled = state.isOpen) { onDismiss() }
}

@Composable
private fun TrayFrame(
    state: GrowTrayState,
    title: String,
    fromColor: Color,
    fromRadius: Dp,
    danger: Boolean,
    bottomInsets: WindowInsets,
    topInsets: WindowInsets,
    tray: @Composable ColumnScope.() -> Unit,
) {
    val c = YamuTheme.colors
    val density = LocalDensity.current
    val surface = c.surface
    val dangerColor = c.error
    val scroll = rememberScrollState()
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            // 틀: 그림자 · 바탕 · 위 띠. 크기와 모서리는 놓을 때 정해진다
            Box(
                Modifier
                    .layoutId(FRAME)
                    .graphicsLayer {
                        val p = state.progress.value
                        alpha = state.alpha.value
                        shadowElevation = GrowTrayDefaults.elevation.toPx() * p.coerceIn(0f, 1f)
                        shape = roundedShape(androidx.compose.ui.util.lerp(fromRadius.toPx(), GrowTrayDefaults.radius.toPx(), p.coerceIn(0f, 1f)))
                        clip = true
                    }
                    .drawBehind {
                        drawRect(lerp(fromColor, surface, state.fill.value))
                        if (danger) drawRect(lerp(fromColor, dangerColor, state.band.value), size = Size(size.width, GrowTrayDefaults.band.toPx()))
                    },
            )
            Column(
                Modifier
                    .layoutId(CONTENT)
                    .semantics { paneTitle = title }
                    .pointerInput(Unit) {
                        // 커지는 동안 · 닫히는 동안 누른 것은 안쪽 버튼에 닿기 전에 버린다
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (!state.interactive) e.changes.forEach { it.consume() }
                            }
                        }
                    }
                    .verticalScroll(scroll)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp + GrowTrayDefaults.band, bottom = 16.dp),
                content = tray,
            )
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val margin = GrowTrayDefaults.margin.toPx()
        val bottomInset = bottomInsets.getBottom(density).toFloat()
        val topInset = topInsets.getTop(density).toFloat()
        val trayW = (w - margin * 2).roundToInt().coerceAtLeast(0)
        val maxH = TrayGeometry.maxContentHeight(h, margin, bottomInset, topInset).roundToInt()
        val content = measurables.first { it.layoutId == CONTENT }.measure(Constraints(minWidth = trayW, maxWidth = trayW, maxHeight = maxH))
        val to = TrayGeometry.trayRect(w, h, content.height.toFloat(), margin, bottomInset, topInset)
        val p = state.progress.value
        val cur = state.fromRect()?.let { TrayGeometry.lerpRect(it, to, p) } ?: to
        val frame = measurables.first { it.layoutId == FRAME }.measure(
            Constraints.fixed(cur.width.roundToInt().coerceAtLeast(0), cur.height.roundToInt().coerceAtLeast(0)),
        )
        val radius = androidx.compose.ui.util.lerp(fromRadius.toPx(), GrowTrayDefaults.radius.toPx(), p.coerceIn(0f, 1f))
        val x = cur.left.roundToInt()
        val y = cur.top.roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            frame.place(x, y)
            content.placeWithLayer(x, y) {
                alpha = state.contentAlpha.value * state.alpha.value
                // 내용은 끝 폭으로 놓고 지금 틀 크기만큼만 보인다
                shape = TrayClip(cur.width, cur.height, radius)
                clip = true
            }
        }
    }
}

private const val FRAME = "frame"
private const val CONTENT = "content"

private fun roundedShape(radiusPx: Float): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radiusPx)))
}

/** 내용 층을 지금 틀 크기([w] × [h])로 자른다 — 층 자체는 끝 크기라 모양을 따로 준다 */
private class TrayClip(private val w: Float, private val h: Float, private val r: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, w, h, CornerRadius(r)))
}

/** 애니메이션 배율(개발자 옵션 · 녹화용 5배)을 따라 기다린다. delay는 배율을 모르므로 직접 곱한다 */
private suspend fun scaledMs(ms: Int): Long = (ms * (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)).toLong()
