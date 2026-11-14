package com.yamu.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 동전 무더기 — 금액이 '이만큼 쌓였다'를 보여 주는 그림(홈 히어로 카드 아래 무대). 금액이 클수록 동전이 많다([coinCountFor], 최대 32개).
 * 줄 자리 · 높이를 조금씩 어긋나게 정하고 옆에 한 개가 떨어져 있어, 계단 · 막대가 아니라 손으로 부은 더미처럼 보인다.
 * 받은 자리 오른쪽 아래에 놓는다(무더기 오른쪽 끝 = 자리 오른쪽 끝). 동전 크기는 자리에 맞춘다(28~60dp).
 *
 * 움직임: 처음 보일 때 한 번 — 위쪽 여러 높이에서 고르지 않은 간격으로 떨어지다 끝으로 갈수록 드문드문, 닿을 때 살짝 튄다.
 * [state]의 rolled가 같은 동안 0 → 1로 올라 금액 글자를 함께 올릴 수 있다([rememberRolledAmount]). 다 쌓인 뒤 금액이 늘면
 * 늘어난 만큼만 떨어지고, 줄면 나중에 얹힌 동전부터 떠올라 사라진다. 자리 크기가 바뀌면 같은 동전을 새 크기로 이어 그린다.
 * 동전을 누르면 누른 자리부터 물결처럼 동전이 튀어 오르고 반짝이가 흩어지며 짧은 진동이 온다. 동전 밖을 누르면 그대로
 * 부모(카드)의 누르기로 간다. 무한 반복 없음. 모션 줄이기면 떨어지지 않고 쌓인 모양이 나타나고 누르기 반응도 없다.
 * 장식이라 읽어 주기 없음 — 금액은 위 글자가 말한다.
 */
@Composable
fun CoinPile(amount: Long, modifier: Modifier = Modifier, state: CoinPileState = rememberCoinPileState()) =
    CoinPile(solidCoins = coinCountFor(amount), ghostCoins = 0, modifier = modifier, state = state)

/**
 * 꽉 찬 동전 [solidCoins]개 위에 점선 동전 [ghostCoins]개를 얹은 무더기 — 홈 혜택 히어로(S15P31E103-480)에서
 * 꽉 찬 동전은 카드사가 확정한 혜택, 점선 동전은 확정 전 금액이다(HOME-04). 쌓는 순서가 같아 점선 동전은 늘 나중 자리(뒷줄 · 위)에 놓이고,
 * 확정돼서 점선이 줄고 꽉 찬 동전이 늘면 같은 자리의 동전이 그대로 채워진다. 개수는 부르는 쪽이 정한다(둘 합 최대 32개)
 */
@Composable
fun CoinPile(solidCoins: Int, ghostCoins: Int, modifier: Modifier = Modifier, state: CoinPileState = rememberCoinPileState()) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val solid = solidCoins.coerceIn(0, CoinPileSpec.FILL_ORDER.size)
    val coins = (solid + ghostCoins.coerceAtLeast(0)).coerceAtMost(CoinPileSpec.FILL_ORDER.size)
    val motion = remember { PileMotion(state) }
    SideEffect { motion.solid = solid }   // 그리기 때 읽는다 — 개수가 같고 확정만 바뀌어도 다시 그린다
    val path = remember { Path() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val colors = CoinColors(face = c.coinFace, side = c.coinSide, line = c.coinLine, glow = c.coinGlow, sparkle = c.onHero, shadow = c.scrim, ghost = c.onHero)

    // 크기 · 개수가 정해지거나 바뀔 때만 다시 정한다. 들어오는 모션은 끝까지 한 번 — 도는 중에 바뀌면 같은 시계로 이어서 돈다
    LaunchedEffect(size, coins) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val layout = CoinLayout(size.width.toFloat(), size.height.toFloat(), density, coins)
        when {
            !state.entered -> motion.enter(layout, coins, reduce)
            motion.layout == null -> motion.rest(layout, coins)     // 이미 쌓인 채로 다시 그릴 때(탭을 오가도)
            else -> motion.change(layout, coins, reduce)
        }
    }
    LaunchedEffect(motion.runs) {
        val end = motion.endMs(reduce)
        while (motion.clockMs < end) withFrameNanos(motion::tick)
        if (motion.layout != null) motion.finish()
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(reduce) {
                // 동전 위를 눌렀을 때만 받는다 — 밖은 부모(카드) 누르기로
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (reduce || !motion.hits(down.position)) return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    motion.hop(up.position)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
    ) {
        val layout = motion.layout ?: return@Canvas
        val now = motion.clockMs
        translate(left = motion.slidePx(layout, now)) {
            drawGlow(layout, motion, now, colors)
            // 뒷줄부터 — 앞줄이 뒷줄 아래쪽을 가린다. 같은 줄은 아래 동전부터(위 동전이 아래 동전 윗면을 덮음)
            val solid = motion.solid
            CoinPileSpec.DRAW_ORDER.forEach { stack ->
                val hop = motion.hopOffset(layout, stack, now)
                // 쌓는 순서(drops 순서)로 [solid]번째부터는 점선 동전
                motion.drops.forEachIndexed { i, d ->
                    if (d.stack == stack && now >= d.landMs) {
                        val alpha = if (reduce) ((now - d.startMs) / YamuMotion.SHORT_MS).coerceIn(0f, 1f) else 1f
                        drawRestingCoin(layout, d, hop + motion.rebound(layout, d, now), colors, path, alpha, ghost = i >= solid)
                    }
                }
                // 줄어든 만큼 떠나는 동전은 그 줄 맨 위에 있었으므로 남은 동전 다음에
                motion.leaving.forEach { g -> if (g.drop.stack == stack) drawLeavingCoin(layout, g, now, colors, path, reduce) }
                if (!reduce) {
                    motion.drops.forEachIndexed { i, d ->
                        if (d.stack == stack && now >= d.startMs && now < d.landMs) drawFallingCoin(layout, d, now, colors, path, ghost = i >= solid)
                    }
                }
            }
        }
        drawSparkles(layout, motion, now, colors)
    }
}

/**
 * 무더기 바깥에서 보는 상태 — [entered]는 들어오는 모션을 끝냈는지(저장돼 탭을 오가도 다시 떨어지지 않는다),
 * [rolled]는 금액 글자를 올릴 비율(0 → 1, 동전이 다 닿으면 1). 금액을 동전과 함께 올리려면 같은 상태를 넘긴다
 */
@Stable
class CoinPileState internal constructor(entered: Boolean) {
    var entered by mutableStateOf(entered)
        internal set
    var rolled by mutableFloatStateOf(if (entered) 1f else 0f)
        internal set

    companion object {
        val Saver = Saver<CoinPileState, Boolean>(save = { it.entered }, restore = { CoinPileState(it) })
    }
}

@Composable
fun rememberCoinPileState(): CoinPileState = rememberSaveable(saver = CoinPileState.Saver) { CoinPileState(false) }

/**
 * 무더기와 함께 보여 줄 금액 글자. 들어오는 동안은 동전이 닿는 만큼 0에서 [amount]로 오르고, 다 쌓인 뒤 금액이 바뀌면
 * (결제하고 돌아옴 · 새로고침) 보이던 값에서 새 값으로 AMOUNT_ROLL_MS 동안 굴러간다. 모션 줄이기면 늘 [amount]
 */
@Composable
fun rememberRolledAmount(amount: Long, state: CoinPileState): Long {
    val reduce = YamuTheme.reduceMotion
    val roll = remember { AmountRoll(amount) }
    LaunchedEffect(amount) { roll.to(amount, animate = state.entered && !reduce) }
    return when {
        reduce -> amount
        !state.entered -> (amount * state.rolled.toDouble()).roundToLong()
        else -> roll.value
    }
}

/** 보이던 값([from])에서 새 값([target])으로 — 굴러가는 중에 또 바뀌면 그 순간 값에서 다시 출발한다 */
@Stable
internal class AmountRoll(start: Long) {
    private var from by mutableLongStateOf(start)
    private var target by mutableLongStateOf(start)
    private val progress = Animatable(1f)
    val value: Long get() = from + ((target - from) * progress.value.toDouble()).roundToLong()

    suspend fun to(next: Long, animate: Boolean) {
        if (next == target) return
        from = value
        target = next
        if (!animate) {
            progress.snapTo(1f)
            return
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(YamuMotion.AMOUNT_ROLL_MS, easing = YamuMotion.standard))
    }
}

object CoinPileDefaults {
    /** 동전 무대 최소 높이 — 글씨가 아주 커 카드가 넘칠 때 이 높이로 쌓인다(동전 약 47dp, 떨어지는 길이가 가장 짧다) */
    val minHeight = 112.dp
}

/** 금액 → 동전 수. 대략 '이만큼'만 보이면 되므로 구간으로 나눈다(0 이하 0개 · 35만 원 14개 · 500만 원 이상 32개) */
fun coinCountFor(amount: Long): Int = COUNT_STEPS.lastOrNull { amount >= it.first }?.second ?: 0

private val COUNT_STEPS = listOf(
    1L to 1,
    10_000L to 2,
    30_000L to 3,
    50_000L to 5,
    100_000L to 8,
    200_000L to 11,
    300_000L to 14,
    500_000L to 17,
    700_000L to 20,
    1_000_000L to 23,
    2_000_000L to 26,
    3_000_000L to 29,
    5_000_000L to 32,
)

/**
 * 동전 한 줄 — 가로 자리 [x](무더기 기준, 지름 배수), 앞에서부터 [row]번째 줄, 최대 [cap]개.
 * [lift]는 줄 바닥을 조금 올리는 정도(지름 배수), [bias]는 채우는 순서를 앞당기는(−) 정도
 */
internal class CoinStack(val x: Float, val row: Int, val cap: Int, val lift: Float = 0f, val bias: Float = 0f)

/** 무더기 모양 값(dp · 동전 지름 배수) */
internal object CoinPileSpec {
    /**
     * 동전 지름은 자리 폭 ÷ WIDTH_IN_COINS와 자리 높이 × HEIGHT_SHARE 중 작은 값(MIN ~ MAX dp).
     * 높이 몫을 넉넉히 둬 보통은 폭이 크기를 정한다 — 결제 줄이 생겨 카드가 낮아져도 무더기가 작아 보이지 않게
     * (금액은 거의 그대로인데 동전이 줄어 보이면 틀린 말이 된다). 가장 많을 때 무더기 높이가 지름의 약 1.24배라 0.42면 위로 48%가 남는다
     */
    const val WIDTH_IN_COINS = 5.6f
    const val HEIGHT_SHARE = 0.42f
    const val MIN_DIAMETER = 28f
    const val MAX_DIAMETER = 60f
    /** 동전 한 개 두께 = 쌓일 때 올라가는 높이(지름 배수). 윗면 세로 반지름이 이보다 크면 아래 동전 윗면이 옆으로 삐져나온다 */
    const val STEP_RATIO = 0.13f
    /** 누운 동전 윗면의 세로 반지름(지름 배수, 조금 위에서 내려다본 모양) */
    const val FACE_RATIO = 0.13f
    /** 자리 아래 끝에서 띄우는 거리(dp) */
    const val FLOOR_INSET = 0.5f
    /** 한 줄 뒤로 갈 때마다 이만큼(지름 배수) 위에 놓여 멀리 있는 것처럼 보인다 */
    const val ROW_RAISE = 0.24f
    /** 무더기 오른쪽 끝과 자리 오른쪽 끝 사이(dp) — 0이면 카드의 › 아이콘과 같은 세로줄 */
    const val RIGHT_INSET = 0f
    /**
     * 손으로 부은 더미 — 줄 자리 · 높이를 조금씩 어긋나게 하고(앞 6 · 4 · 3 · 2줄, 모두 32개), 왼쪽에 한 개가 떨어져 있다.
     * 가장 높은 곳이 가운데에서 살짝 오른쪽이라 오른쪽 끝에 붙인 무더기가 기울어 보이지 않는다
     */
    val STACKS = listOf(
        CoinStack(0.04f, 0, 3), CoinStack(-1.0f, 0, 3), CoinStack(0.97f, 0, 2), CoinStack(-1.93f, 0, 2, lift = 0.02f),
        CoinStack(1.95f, 0, 1), CoinStack(-2.45f, 0, 1, bias = -0.5f),
        CoinStack(-0.47f, 1, 3), CoinStack(0.52f, 1, 4, lift = 0.02f), CoinStack(-1.46f, 1, 2), CoinStack(1.44f, 1, 2, lift = 0.03f),
        CoinStack(0.03f, 2, 3), CoinStack(-0.96f, 2, 1), CoinStack(0.99f, 2, 2),
        CoinStack(0.55f, 3, 2), CoinStack(-0.4f, 3, 1),
    )
    /** 뒷줄부터, 같은 줄은 바깥부터 — 앞 · 안쪽 줄이 뒤 · 바깥 줄을 덮는다 */
    val DRAW_ORDER = STACKS.indices.sortedWith(compareByDescending<Int> { STACKS[it].row }.thenByDescending { abs(STACKS[it].x) })
    /** 가운데에서 멀수록 늦게 쌓는 정도와 순서를 조금 섞는 정도 */
    private const val SPREAD = 0.4f
    private const val ORDER_JITTER = 0.1f
    /** 몇 번째 동전이 어느 줄에 얹히는지. 낮고 가운데에 가까운 자리부터, 조금씩 섞어서 채운다 */
    val FILL_ORDER: List<Int> = STACKS.flatMapIndexed { i, s -> (0 until s.cap).map { level -> i to level } }
        .sortedWith(
            compareBy<Pair<Int, Int>> { (i, level) ->
                val s = STACKS[i]
                s.row * ROW_RAISE + s.lift + level * STEP_RATIO + abs(s.x) * SPREAD + s.bias + wobble(i * 37 + level * 11) * ORDER_JITTER
            }.thenBy { it.first },
        )
        .map { it.first }
    /** 쌓인 동전이 줄에서 비켜나는 최대 거리 · 맨 위 동전이 비스듬히 놓이는 최대 각도 · 떨어진 한 개의 각도 */
    const val COIN_DX = 0.045f
    const val TOP_TILT_DEG = 6f
    const val STRAY_TILT_DEG = 10f
    /** 떨어지는 시간의 기준 거리(dp) — 이만큼 떨어지는 데 COIN_FALL_MS */
    const val FALL_REF = 180f
    /** 출발 높이를 고르지 않게 — 자리 위끝에서 착지점까지 거리의 이만큼 아래까지 흩어서 출발 */
    const val START_SPREAD = 0.3f
    /** 흐리게 나타나 또렷해지는 구간(떨어지는 시간 비율) */
    const val FADE_IN = 0.25f
    /**
     * 떨어지기 시작하는 때: k번째(n개 중) = 창 × (LINEAR·u + (1 − LINEAR)·u^TAPER_POWER), u = k/(n−1).
     * 앞은 촘촘하고 끝으로 갈수록 간격이 벌어져(마지막 간격이 처음의 약 16배) 마지막엔 하나씩 떨어진다.
     * 간격마다 그 자리 평균 간격의 ±TIME_JITTER만큼 흔들어 박자가 고르지 않게 한다
     */
    const val TAPER_LINEAR = 0.25f
    const val TAPER_POWER = 5f
    const val TIME_JITTER = 0.45f
    /** 떨어지며 옆으로 밀리는 거리(dp) · 기우는 각도의 최대 */
    const val DRIFT = 14f
    const val TILT_DEG = 18f
    /** 닿을 때 튀는 높이(지름 배수) — 위에 다음 동전이 그 사이 닿으면 튀지 않는다 */
    const val REBOUND = 0.07f
    /** 금액이 줄어 떠나는 동전이 떠오르는 높이(지름 배수) */
    const val LEAVE_RISE = 0.7f
    /** 누르면 튀어 오르는 높이(지름 배수, 누른 자리에서 가장 높고 멀수록 낮다)와 물결이 퍼지는 속도(dp/ms) */
    const val HOP = 0.35f
    const val HOP_MIN_SHARE = 0.35f
    const val HOP_SPEED = 0.9f
    /** 누른 자리를 받는 무더기 둘레 여유(dp) */
    const val HIT_SLOP = 16f
    /** 반짝이 수 · 퍼지는 거리(dp) · 크기(dp) */
    const val SPARKLES = 7
    const val SPARKLE_DISTANCE = 56f
    const val SPARKLE_SIZE = 6f
    /** 무더기 뒤 빛 — 무더기 폭의 이만큼 반지름, 이 진하기. 누르면 잠깐 더 밝게 */
    const val GLOW_RADIUS = 0.8f
    const val GLOW_ALPHA = 0.2f
    const val GLOW_TAP_BOOST = 0.2f
    /** 바닥 그림자 — 무더기 폭의 이만큼, 세로로 이만큼 납작하게, 이 진하기(scrim 기준) */
    const val SHADOW_WIDTH = 1.15f
    const val SHADOW_FLAT = 0.16f
    const val SHADOW_ALPHA = 0.9f
    const val RING_ALPHA = 0.55f
    const val RING_SCALE = 0.64f
    const val RING_WIDTH = 1f
    const val GROOVE_WIDTH = 1f
    /** 점선 동전 — 선 굵기 · 점 · 틈(dp), 속 · 옆면 · 윗면 진하기 */
    const val GHOST_LINE = 1.3f
    const val GHOST_DASH = 3f
    const val GHOST_GAP = 2.4f
    const val GHOST_FILL_ALPHA = 0.08f
    const val GHOST_SIDE_ALPHA = 0.62f
    const val GHOST_FACE_ALPHA = 0.75f
}

/**
 * 무더기 모양 — 받은 자리 크기와 동전 수로 정한다. 길이는 px, [dp]는 1dp의 px.
 * 무더기는 [coins]개일 때의 오른쪽 끝을 자리 오른쪽 끝에 맞춘다(동전이 적으면 오른쪽에 작게)
 */
internal class CoinLayout(val width: Float, val height: Float, val dp: Float, val coins: Int) {
    val diameter = minOf(width / CoinPileSpec.WIDTH_IN_COINS, height * CoinPileSpec.HEIGHT_SHARE)
        .coerceIn(CoinPileSpec.MIN_DIAMETER * dp, CoinPileSpec.MAX_DIAMETER * dp)
    val radius = diameter / 2
    val step = diameter * CoinPileSpec.STEP_RATIO
    val faceRy = diameter * CoinPileSpec.FACE_RATIO
    val floorY = height - CoinPileSpec.FLOOR_INSET * dp
    /** 자리 위끝 — 동전이 정면을 보고 있어도 자리 안에서 출발한다 */
    val startFaceY = radius
    private val anchorX = width - CoinPileSpec.RIGHT_INSET * dp - pileRightEdge(coins) * diameter

    fun centerX(stack: Int): Float = anchorX + CoinPileSpec.STACKS[stack].x * diameter

    /** 쌓인 동전 하나가 줄에서 비켜난 거리 — 매번 같다 */
    fun coinDx(stack: Int, level: Int): Float = wobble(stack * 31 + level * 17 + 5) * CoinPileSpec.COIN_DX * diameter

    /** [stack] 줄의 [level]번째(바닥 0) 동전 윗면 가운데 y */
    fun restFaceY(stack: Int, level: Int): Float {
        val s = CoinPileSpec.STACKS[stack]
        val floor = floorY - (s.row * CoinPileSpec.ROW_RAISE + s.lift) * diameter
        return floor - level * step - step - faceRy
    }

    /** 멈춘 채 출발해 중력처럼 빨라지므로 걸리는 시간은 거리의 제곱근에 비례. FALL_REF만큼 떨어지는 데 COIN_FALL_MS */
    fun fallMs(stack: Int, level: Int, fromY: Float = startFaceY): Float =
        YamuMotion.COIN_FALL_MS * sqrt((restFaceY(stack, level) - fromY).coerceAtLeast(0f) / (CoinPileSpec.FALL_REF * dp))
}

/** [coins]개 무더기의 기준에서 오른쪽 끝까지(지름 배수). 개수에 따라 왼쪽만 먼저 차기도 하므로 오른쪽 줄만 본다 */
internal fun pileRightEdge(coins: Int): Float =
    CoinPileSpec.FILL_ORDER.take(coins).maxOfOrNull { CoinPileSpec.STACKS[it].x + 0.5f } ?: 0.5f

/**
 * 동전 하나. 시각은 처음 그린 프레임부터(ms). [startY] · [drift]는 px, [tilt]는 떨어지기 시작할 때 · [restTilt]는 놓인 뒤 각도(도),
 * [turns]는 떨어지며 뒤집히는 반 바퀴 수
 */
internal data class CoinDrop(
    val stack: Int,
    val level: Int,
    val startMs: Float,
    val fallMs: Float,
    val startY: Float,
    val drift: Float,
    val tilt: Float,
    val turns: Float,
    val restTilt: Float,
) {
    val landMs: Float get() = startMs + fallMs
}

/** 들어오는 모션 — 화면 교체 페이드 뒤 [CoinPileSpec.FILL_ORDER] 순서대로, 처음엔 촘촘하고 끝으로 갈수록 드문드문 떨어진다 */
internal fun entranceDrops(layout: CoinLayout, coins: Int, reduce: Boolean): List<CoinDrop> =
    appendDrops(layout, emptyList(), coins, if (reduce) 0f else YamuMotion.COIN_START_DELAY_MS.toFloat(), reduce)

/** 이미 쌓인 모양(들어오는 모션 없이) */
internal fun restingDrops(layout: CoinLayout, coins: Int): List<CoinDrop> =
    appendDrops(layout, emptyList(), coins, RESTED_MS, reduce = true)

/** 다 쌓인 뒤 금액이 늘었을 때 — 늘어난 동전만 [nowMs]부터 떨어진다 */
internal fun addedDrops(layout: CoinLayout, existing: List<CoinDrop>, coins: Int, nowMs: Float, reduce: Boolean): List<CoinDrop> =
    appendDrops(layout, existing, coins, nowMs, reduce)

/**
 * [n]개 중 [k]번째(0부터)가 떨어지기 시작하는 때(첫 동전부터 ms). 전체 창은 개수 × COIN_STAGGER_MS(최대 COIN_WINDOW_MAX_MS).
 * 첫 동전은 0, 나머지는 그 자리 평균 간격의 ±TIME_JITTER만큼 흔든다
 */
internal fun dropDelay(k: Int, n: Int): Float {
    if (n <= 1 || k == 0) return 0f
    val window = (n * YamuMotion.COIN_STAGGER_MS).coerceAtMost(YamuMotion.COIN_WINDOW_MAX_MS).toFloat()
    val u = k / (n - 1f)
    val a = CoinPileSpec.TAPER_LINEAR
    val p = CoinPileSpec.TAPER_POWER
    val base = window * (a * u + (1 - a) * u.pow(p))
    val localGap = window / (n - 1) * (a + (1 - a) * p * u.pow(p - 1))
    return (base + wobble(k * 13 + 5) * CoinPileSpec.TIME_JITTER * localGap).coerceAtLeast(0f)
}

/** 금액 글자를 올리는 곡선 — 처음 빠르고 끝에서 천천히 멈춘다(동전도 끝으로 갈수록 드문드문) */
internal fun rollEase(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

private const val RESTED_MS = -1_000_000f

private fun appendDrops(layout: CoinLayout, existing: List<CoinDrop>, coins: Int, fromMs: Float, reduce: Boolean): List<CoinDrop> {
    val target = coins.coerceAtMost(CoinPileSpec.FILL_ORDER.size)
    val counts = IntArray(CoinPileSpec.STACKS.size)
    val tops = FloatArray(CoinPileSpec.STACKS.size) { Float.NEGATIVE_INFINITY }
    existing.forEach {
        counts[it.stack]++
        tops[it.stack] = maxOf(tops[it.stack], it.landMs)
    }
    // 다 쌓였을 때 줄마다 몇 개인지 — 맨 위 동전만 비스듬히 놓는다
    val finalCounts = IntArray(CoinPileSpec.STACKS.size)
    CoinPileSpec.FILL_ORDER.take(target).forEach { finalCounts[it]++ }
    val added = ArrayList<CoinDrop>()
    val n = target - existing.size
    for (i in existing.size until target) {
        val stack = CoinPileSpec.FILL_ORDER[i]
        val earliest = if (reduce) fromMs else fromMs + dropDelay(added.size, n)
        val drop = layout.drop(stack, counts[stack], earliest, tops[stack], reduce, top = counts[stack] == finalCounts[stack] - 1)
        added += drop
        counts[stack]++
        tops[stack] = drop.landMs
    }
    return added
}

private fun CoinLayout.drop(stack: Int, level: Int, earliest: Float, belowLandMs: Float, reduce: Boolean, top: Boolean): CoinDrop {
    val seed = stack * 31 + level * 17
    val restTilt = when {
        CoinPileSpec.STACKS[stack].cap == 1 && CoinPileSpec.STACKS[stack].row == 0 -> wobble(seed + 6) * CoinPileSpec.STRAY_TILT_DEG
        top -> wobble(seed + 6) * CoinPileSpec.TOP_TILT_DEG
        else -> 0f
    }
    val restY = restFaceY(stack, level)
    if (reduce) return CoinDrop(stack, level, earliest, 0f, restY, 0f, 0f, 0f, restTilt)
    // 어떤 건 위끝에서, 어떤 건 조금 아래에서 나타난다
    val startY = startFaceY + (wobble(seed + 4) + 1f) / 2f * CoinPileSpec.START_SPREAD * (restY - startFaceY).coerceAtLeast(0f)
    val fall = fallMs(stack, level, startY)
    return CoinDrop(
        stack = stack,
        level = level,
        startMs = maxOf(earliest, belowLandMs + YamuMotion.COIN_STACK_GAP_MS - fall),
        fallMs = fall,
        startY = startY,
        drift = wobble(seed + 1) * CoinPileSpec.DRIFT * dp,
        tilt = wobble(seed + 2) * CoinPileSpec.TILT_DEG,
        turns = 1.75f + wobble(seed + 3) * 0.75f,
        restTilt = restTilt,
    )
}

/** 매번 같은 -1 ~ 1(녹화끼리 비교할 수 있게) */
private fun wobble(seed: Int): Float = Math.floorMod(seed * 7919 + 104_729, 997) / 498f - 1f

/** 줄어든 만큼 떠나는 동전 — [startMs]부터 떠올라 사라진다. [ghost]는 떠날 때 점선 동전이었는지 */
internal class LeavingCoin(val drop: CoinDrop, val startMs: Float, val ghost: Boolean = false)

/** 그리기 상태 — 시계 · 동전 목록 · 누르기 물결. 바깥에 보이는 값은 [state]에 쓴다 */
@Stable
internal class PileMotion(private val state: CoinPileState) {
    var layout: CoinLayout? by mutableStateOf(null)
        private set
    /** 꽉 찬 동전 수 — 쌓는 순서로 이 앞까지는 꽉 찬 동전, 뒤는 점선 동전 */
    var solid by mutableIntStateOf(Int.MAX_VALUE)
    val drops = mutableStateListOf<CoinDrop>()
    val leaving = mutableStateListOf<LeavingCoin>()
    var clockMs by mutableFloatStateOf(0f)
        private set
    /** 바뀌면 프레임을 다시 돈다 */
    var runs by mutableIntStateOf(0)
        private set
    private var originNanos = -1L
    private var rollFrom = 0f
    private var rollTo = 0f
    private var hopAt = Float.NEGATIVE_INFINITY
    private var hopX = 0f
    private var hopY = 0f
    /** 개수가 바뀌어 오른쪽 끝을 다시 맞출 때, 옛 자리에서 새 자리로 미끄러지는 거리(지름 배수)와 시작 시각 */
    private var slideFrom = 0f
    private var slideAt = Float.NEGATIVE_INFINITY

    /** 들어오는 모션. 이미 돌고 있으면 시계는 그대로 두고 순서만 다시 정한다(닿은 것은 닿은 채) */
    fun enter(next: CoinLayout, coins: Int, reduce: Boolean) {
        replace(next, entranceDrops(next, coins, reduce))
        if (reduce || drops.isEmpty()) {
            state.rolled = 1f
        } else {
            rollFrom = drops.minOf { it.startMs }
            rollTo = drops.maxOf { it.landMs }
        }
    }

    fun rest(next: CoinLayout, coins: Int) {
        replace(next, restingDrops(next, coins))
        state.rolled = 1f
    }

    /**
     * 다 쌓인 뒤 자리 크기나 동전 수가 바뀔 때. 크기만 바뀌면 같은 동전을 새 크기로 그린다(결제 줄이 생겨 카드가 줄어도
     * 떨어지거나 떠나던 동전은 그대로 이어서). 늘면 늘어난 만큼 떨어지고, 줄면 나중에 얹힌 동전부터 떠올라 사라진다.
     * 오른쪽 끝을 다시 맞추느라 무더기 자리가 바뀌면 옛 자리에서 미끄러져 간다
     */
    fun change(next: CoinLayout, coins: Int, reduce: Boolean) {
        val prev = layout
        val now = nowMs()
        if (prev != null && coins != prev.coins && !reduce) {
            slideFrom = slideDiameters(now) + pileRightEdge(coins) - pileRightEdge(prev.coins)
            slideAt = now
        }
        layout = next
        val before = drops.size
        when {
            coins > before -> drops += addedDrops(next, drops.toList(), coins, now, reduce)
            coins < before -> {
                val gone = drops.subList(coins, before).toList()
                drops.removeRange(coins, before)
                // 떠나는 동전의 원래 자리(coins + k)가 점선 자리였는지 — 거꾸로 떠나므로 뒤에서부터 센다
                leaving += gone.asReversed().mapIndexed { i, d ->
                    LeavingCoin(d, now + if (reduce) 0f else i * LEAVE_STAGGER_MS, ghost = before - 1 - i >= solid)
                }
            }
        }
        runs++
    }

    fun finish() {
        state.rolled = 1f
        state.entered = true
        leaving.clear()
    }

    private fun replace(next: CoinLayout, list: List<CoinDrop>) {
        layout = next
        drops.clear()
        drops += list
        leaving.clear()
        slideFrom = 0f
        runs++
    }

    /** 프레임 시각과 같은 기준(System.nanoTime)이라 쉬고 있다가 눌러도 지금 시각에서 시작한다 */
    private fun nowMs(): Float = if (originNanos < 0) 0f else (System.nanoTime() - originNanos) / 1_000_000f

    /** 무더기를 옛 자리 쪽으로 옮겨 그릴 거리(px) — 개수가 바뀐 뒤 SLIDE_MS 동안 0으로 줄어든다 */
    fun slidePx(l: CoinLayout, now: Float): Float = slideDiameters(now) * l.diameter

    private fun slideDiameters(now: Float): Float {
        val q = ((now - slideAt) / SLIDE_MS).coerceIn(0f, 1f)
        return slideFrom * (1f - YamuMotion.standard.transform(q))
    }

    fun tick(frameNanos: Long) {
        if (originNanos < 0) originNanos = frameNanos
        clockMs = (frameNanos - originNanos) / 1_000_000f
        if (!state.entered && rollTo > rollFrom) state.rolled = rollEase((clockMs - rollFrom) / (rollTo - rollFrom))
    }

    fun endMs(reduce: Boolean): Float {
        val landed = drops.maxOfOrNull { it.landMs + if (reduce) YamuMotion.SHORT_MS.toFloat() else REBOUND_MS } ?: 0f
        val left = leaving.maxOfOrNull { it.startMs + leaveMs(reduce) } ?: 0f
        return maxOf(landed, left, slideAt + SLIDE_MS, hopAt + HOP_TOTAL_MS)
    }

    /** 누른 자리가 무더기(둘레 여유 포함) 안인지 */
    fun hits(at: Offset): Boolean {
        val l = layout ?: return false
        if (drops.isEmpty()) return false
        val slop = CoinPileSpec.HIT_SLOP * l.dp
        val left = drops.minOf { l.centerX(it.stack) } - l.radius - slop
        val right = drops.maxOf { l.centerX(it.stack) } + l.radius + slop
        val top = drops.minOf { l.restFaceY(it.stack, it.level) } - l.faceRy - slop * 2
        return at.x in left..right && at.y >= top
    }

    fun hop(at: Offset) {
        hopAt = nowMs()
        hopX = at.x
        hopY = at.y
        runs++
    }

    /** 누르기 물결: 누른 자리에서 가까운 줄부터 차례로 튀어 오른다. 줄은 통째로 움직여 동전끼리 벌어지지 않는다 */
    fun hopOffset(l: CoinLayout, stack: Int, now: Float): Float {
        val distance = abs(l.centerX(stack) - hopX)
        val q = (now - hopAt - distance / (CoinPileSpec.HOP_SPEED * l.dp)) / HOP_MS
        if (q < 0f || q >= 1f) return 0f
        val near = 1f - (distance / (3f * l.diameter)).coerceAtMost(1f)
        val height = l.diameter * CoinPileSpec.HOP * (CoinPileSpec.HOP_MIN_SHARE + (1 - CoinPileSpec.HOP_MIN_SHARE) * near)
        return -height * sin(PI.toFloat() * q)
    }

    /** 닿는 순간 살짝 튐 — 그 사이 위에 다음 동전이 닿으면 겹치지 않게 튀지 않는다 */
    fun rebound(l: CoinLayout, d: CoinDrop, now: Float): Float {
        val q = (now - d.landMs) / REBOUND_MS
        if (q < 0f || q >= 1f || d.fallMs == 0f) return 0f
        val above = drops.firstOrNull { it.stack == d.stack && it.level == d.level + 1 }
        if (above != null && above.landMs < d.landMs + REBOUND_MS) return 0f
        return -l.diameter * CoinPileSpec.REBOUND * sin(PI.toFloat() * q) * (1f - q)
    }

    /** 누른 뒤 지난 시간(ms) — 반짝이 · 빛 */
    fun sinceHop(now: Float): Float = now - hopAt
    val hopPoint: Offset get() = Offset(hopX, hopY)

    companion object {
        const val REBOUND_MS = 160f
        const val HOP_MS = 340f
        const val SPARKLE_MS = 450f
        /** 가장 먼 줄까지 물결이 닿고 튀는 시간까지 넉넉히 */
        const val HOP_TOTAL_MS = 900f
        /** 떠나는 동전 하나가 떠올라 사라지는 시간 · 다음 동전이 따라 떠나는 간격. 모션 줄이기면 제자리에서 SHORT_MS 동안 흐려진다 */
        const val LEAVE_MS = 380f
        const val LEAVE_STAGGER_MS = 45f
        /** 무더기가 새 자리로 미끄러지는 시간 */
        const val SLIDE_MS = 420f

        fun leaveMs(reduce: Boolean): Float = if (reduce) YamuMotion.SHORT_MS.toFloat() else LEAVE_MS
    }
}

/** [ghost]는 점선 동전의 선 색(히어로 위 흰 글자 색) */
private class CoinColors(val face: Color, val side: Color, val line: Color, val glow: Color, val sparkle: Color, val shadow: Color, val ghost: Color)

/**
 * 무더기 뒤 은은한 빛과 바닥 그림자 — 쌓이는 만큼 짙어지고, 누르면 빛이 잠깐 더 밝다.
 * 빛은 노랑이 아니라 따뜻한 흰빛이어야 파란 바탕과 섞여 탁해지지 않는다. 그림자는 바닥에 납작한 타원
 */
private fun DrawScope.drawGlow(l: CoinLayout, motion: PileMotion, now: Float, colors: CoinColors) {
    val landed = motion.drops.filter { now >= it.landMs }
    if (landed.isEmpty()) return
    val share = landed.size / motion.drops.size.coerceAtLeast(1).toFloat()
    val left = landed.minOf { l.centerX(it.stack) } - l.radius
    val right = landed.maxOf { l.centerX(it.stack) } + l.radius
    val top = landed.minOf { l.restFaceY(it.stack, it.level) }
    val center = Offset((left + right) / 2, (top + l.floorY) / 2)
    val radius = maxOf((right - left) * CoinPileSpec.GLOW_RADIUS, l.diameter * 1.2f)
    val tap = motion.sinceHop(now).let { if (it in 0f..PileMotion.HOP_TOTAL_MS) sin(PI.toFloat() * it / PileMotion.HOP_TOTAL_MS) else 0f }
    val alpha = (CoinPileSpec.GLOW_ALPHA * share + CoinPileSpec.GLOW_TAP_BOOST * tap).coerceAtMost(1f)
    drawCircle(
        brush = Brush.radialGradient(listOf(colors.glow.copy(alpha = alpha), Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
    val floor = Offset(center.x, l.floorY - l.faceRy)
    val shadowRadius = (right - left) * CoinPileSpec.SHADOW_WIDTH / 2
    scale(scaleX = 1f, scaleY = CoinPileSpec.SHADOW_FLAT, pivot = floor) {
        drawCircle(
            brush = Brush.radialGradient(listOf(colors.shadow.copy(alpha = CoinPileSpec.SHADOW_ALPHA * share), Color.Transparent), center = floor, radius = shadowRadius),
            radius = shadowRadius,
            center = floor,
        )
    }
}

/** 누른 자리에서 위쪽으로 흩어지는 반짝이(십자 별) — 금색과 흰색 */
private fun DrawScope.drawSparkles(l: CoinLayout, motion: PileMotion, now: Float, colors: CoinColors) {
    val t = motion.sinceHop(now) / PileMotion.SPARKLE_MS
    if (t < 0f || t >= 1f) return
    val e = YamuMotion.out.transform(t)
    repeat(CoinPileSpec.SPARKLES) { i ->
        val angle = (-160f + i * 140f / (CoinPileSpec.SPARKLES - 1) + wobble(i * 7 + 3) * 8f) * PI.toFloat() / 180f
        val distance = CoinPileSpec.SPARKLE_DISTANCE * l.dp * (0.7f + 0.3f * (wobble(i * 5 + 1) + 1f) / 2f) * e
        val center = motion.hopPoint + Offset(cos(angle) * distance, sin(angle) * distance)
        val arm = CoinPileSpec.SPARKLE_SIZE * l.dp * (1f - t)
        val color = if (i % 2 == 0) colors.face else colors.sparkle
        val stroke = 1.6f * l.dp
        drawLine(color, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke, StrokeCap.Round, alpha = 1f - t)
        drawLine(color, center - Offset(0f, arm), center + Offset(0f, arm), stroke, StrokeCap.Round, alpha = 1f - t)
    }
}

private fun DrawScope.drawRestingCoin(l: CoinLayout, d: CoinDrop, offsetY: Float, colors: CoinColors, path: Path, alpha: Float, ghost: Boolean = false) {
    val x = l.centerX(d.stack) + l.coinDx(d.stack, d.level)
    val y = l.restFaceY(d.stack, d.level) + offsetY
    rotate(d.restTilt, pivot = Offset(x, y)) {
        if (ghost) {
            drawGhostCoin(x, y, l.radius, l.faceRy, l.step, colors, path, alpha = alpha, dp = l.dp)
        } else {
            drawCoin(x, y, l.radius, l.faceRy, l.step, colors, path, groove = d.level > 0, alpha = alpha, dp = l.dp)
        }
    }
}

/** 떠나는 동전 — 놓인 자리에서 빠르게 떠올랐다 느려지며 흐려진다(쓴 만큼 빠져나감). 모션 줄이기면 제자리에서 흐려지기만 */
private fun DrawScope.drawLeavingCoin(l: CoinLayout, g: LeavingCoin, now: Float, colors: CoinColors, path: Path, reduce: Boolean) {
    val q = ((now - g.startMs) / PileMotion.leaveMs(reduce)).coerceIn(0f, 1f)
    if (q >= 1f) return
    val rise = if (reduce) 0f else -l.diameter * CoinPileSpec.LEAVE_RISE * YamuMotion.out.transform(q)
    drawRestingCoin(l, g.drop, rise, colors, path, alpha = 1f - q, ghost = g.ghost)
}

/** 뒤집히며 떨어지다 닿는 순간 놓일 모양(윗면 세로 반지름 faceRy · 두께 step · 놓일 각도)이 된다. 처음에는 흐리게 */
private fun DrawScope.drawFallingCoin(l: CoinLayout, d: CoinDrop, now: Float, colors: CoinColors, path: Path, ghost: Boolean = false) {
    val p = ((now - d.startMs) / d.fallMs).coerceIn(0f, 1f)
    val restY = l.restFaceY(d.stack, d.level)
    val y = d.startY + (restY - d.startY) * p * p
    val x = l.centerX(d.stack) + l.coinDx(d.stack, d.level) + d.drift * (1f - p)
    val flat = acos(l.faceRy / l.radius)
    val angle = flat + d.turns * PI.toFloat() * (1f - p)
    val ry = l.radius * abs(cos(angle))
    val thick = l.step * abs(sin(angle)) / sin(flat)
    val alpha = (p / CoinPileSpec.FADE_IN).coerceIn(0f, 1f)
    rotate(d.restTilt + (d.tilt - d.restTilt) * (1f - p), pivot = Offset(x, y)) {
        if (ghost) {
            drawGhostCoin(x, y, l.radius, ry, thick, colors, path, alpha = alpha, dp = l.dp)
        } else {
            drawCoin(x, y, l.radius, ry, thick, colors, path, groove = false, alpha = alpha, dp = l.dp)
        }
    }
}

/**
 * 점선 동전 — 확정 전 금액. 속은 거의 비우고(흰빛 6%) 옆면 · 윗면 테두리만 흰 점선으로 그린다(시안 .pile 점선 동전).
 * 색이 아니라 선 모양으로도 꽉 찬 동전과 구분된다(COMMON-11)
 */
private fun DrawScope.drawGhostCoin(
    cx: Float,
    faceY: Float,
    r: Float,
    ry: Float,
    thick: Float,
    colors: CoinColors,
    path: Path,
    alpha: Float,
    dp: Float,
) {
    val oy = ry.coerceAtLeast(0.5f)
    val dash = Stroke(
        width = CoinPileSpec.GHOST_LINE * dp,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(CoinPileSpec.GHOST_DASH * dp, CoinPileSpec.GHOST_GAP * dp)),
    )
    if (thick > 0.5f) {
        // 옆면 — 아래쪽 반원과 양옆 세로선만(윗면 테두리와 겹치지 않게)
        path.reset()
        path.moveTo(cx - r, faceY)
        path.lineTo(cx - r, faceY + thick)
        path.arcTo(Rect(cx - r, faceY + thick - oy, cx + r, faceY + thick + oy), 180f, -180f, false)
        path.lineTo(cx + r, faceY)
        drawPath(path, colors.ghost, alpha = alpha * CoinPileSpec.GHOST_SIDE_ALPHA, style = dash)
    }
    drawOval(colors.ghost, topLeft = Offset(cx - r, faceY - oy), size = Size(2 * r, 2 * oy), alpha = alpha * CoinPileSpec.GHOST_FILL_ALPHA)
    drawOval(colors.ghost, topLeft = Offset(cx - r, faceY - oy), size = Size(2 * r, 2 * oy), alpha = alpha * CoinPileSpec.GHOST_FACE_ALPHA, style = dash)
}

/** 옆면 → 윗면 → 윗면 안쪽 고리. 쌓인 동전은 아래 동전과 닿는 곳에 진한 홈 */
private fun DrawScope.drawCoin(
    cx: Float,
    faceY: Float,
    r: Float,
    ry: Float,
    thick: Float,
    colors: CoinColors,
    path: Path,
    groove: Boolean,
    alpha: Float,
    dp: Float,
) {
    val oy = ry.coerceAtLeast(0.5f)
    if (thick > 0.5f) {
        path.reset()
        path.moveTo(cx - r, faceY)
        path.lineTo(cx - r, faceY + thick)
        path.arcTo(Rect(cx - r, faceY + thick - oy, cx + r, faceY + thick + oy), 180f, -180f, false)
        path.lineTo(cx + r, faceY)
        path.close()
        drawPath(path, colors.side, alpha = alpha)
        if (groove) {
            drawArc(
                color = colors.line,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - r, faceY + thick - oy),
                size = Size(2 * r, 2 * oy),
                alpha = alpha,
                style = Stroke(CoinPileSpec.GROOVE_WIDTH * dp),
            )
        }
    }
    drawOval(colors.face, topLeft = Offset(cx - r, faceY - oy), size = Size(2 * r, 2 * oy), alpha = alpha)
    if (oy > 2 * dp) {
        val k = CoinPileSpec.RING_SCALE
        drawOval(
            colors.line,
            topLeft = Offset(cx - r * k, faceY - oy * k),
            size = Size(2 * r * k, 2 * oy * k),
            alpha = alpha * CoinPileSpec.RING_ALPHA,
            style = Stroke(CoinPileSpec.RING_WIDTH * dp),
        )
    }
}
