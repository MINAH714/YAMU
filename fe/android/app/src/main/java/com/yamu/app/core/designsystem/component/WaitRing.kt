package com.yamu.app.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt

object WaitRingDefaults {
    /** 대기 판 원판 둘레 링 */
    val stroke = 4.dp
    /** 임시: 결제 요청 화면 '남은 시간' 옆 작은 링 */
    val miniSize = 20.dp
    val miniStroke = 2.dp
    /** 임시: 결과 불명 점선(선 4 · 간격 4) */
    val dash = 4.dp
}

/**
 * 트랙 위에 12시에서 시계 방향으로 호를 그린다(모션 명세 M-WAIT-HALO wait-ring). 움직임은 부르는 쪽이 [progress]로 준다.
 * [dashed]는 결과 불명이다. 실패(실선 빨강)와 색만 다르면 구분이 어려워 선 모양도 바꾼다.
 * [progress]는 그릴 때 읽으므로 매 프레임 바뀌어도 다시 조합하지 않는다.
 */
@Composable
fun WaitRing(
    progress: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = YamuTheme.colors.surfaceHigh,
    stroke: Dp = WaitRingDefaults.stroke,
    dashed: Boolean = false,
) {
    Canvas(modifier) {
        val w = stroke.toPx()
        val topLeft = Offset(w / 2, w / 2)
        val arc = Size(size.width - w, size.height - w)
        drawArc(trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(w))
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f) return@Canvas
        val line = if (dashed) {
            // 둘레를 선 · 간격 쌍의 정수 배로 맞춰 12시에서 마지막 선과 첫 선이 붙어 보이지 않게 한다
            val circumference = (PI * (size.minDimension - w)).toFloat()
            val pairs = (circumference / (2 * WaitRingDefaults.dash.toPx())).roundToInt().coerceAtLeast(1)
            val d = circumference / (2 * pairs)
            Stroke(w, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(d, d)))
        } else {
            Stroke(w, cap = StrokeCap.Round)
        }
        drawArc(color, startAngle = -90f, sweepAngle = 360f * p, useCenter = false, topLeft = topLeft, size = arc, style = line)
    }
}

/** 남은 시간 링 계산. 화면과 떼어 단위 테스트한다 */
internal object Countdown {
    /** 남은 비율 0~1. 전체 시간이 0 이하면 0 */
    fun ratio(nowMs: Long, deadlineMs: Long, totalMs: Long): Float =
        if (totalMs <= 0L) 0f else ((deadlineMs - nowMs).toFloat() / totalMs).coerceIn(0f, 1f)

    /** 임박: 남은 시간이 0보다 크고 기준 이하 */
    fun urgent(nowMs: Long, deadlineMs: Long, urgentMs: Long): Boolean = deadlineMs - nowMs in 1..urgentMs

    fun expired(nowMs: Long, deadlineMs: Long): Boolean = nowMs >= deadlineMs

    /** 화면에 보일 남은 초. 올림이라 0.4초 남으면 1초, 마감부터 0 */
    fun remainingSec(nowMs: Long, deadlineMs: Long): Long = ((deadlineMs - nowMs).coerceAtLeast(0L) + 999) / 1000

    /**
     * 이번 틱에 같은 속도로 줄여 갈 목표 비율과 길이. 보통은 다음 틱 시각의 비율까지 한 틱 동안,
     * 마감까지 한 틱이 안 남았으면 마감 시각에 0이 되도록 남은 시간만큼. 길이 0이면 이미 마감.
     */
    fun step(nowMs: Long, deadlineMs: Long, totalMs: Long): Step {
        val ms = (deadlineMs - nowMs).coerceIn(0L, YamuMotion.COUNTDOWN_TICK_MS.toLong())
        return Step(ratio(nowMs + ms, deadlineMs, totalMs), ms.toInt())
    }

    data class Step(val target: Float, val durationMs: Int)
}

/**
 * 남은 시간 링과 옆 숫자가 같이 읽는 상태([rememberCountdown]으로 만든다). 둘이 같은 틱에서 읽으니 숫자가 '00:10'이 되는
 * 순간과 임박 색이 바뀌는 순간이 같다. 틱마다 바뀌는 시각 대신 바뀔 때만 바뀌는 값들을 내놓아 1초에 네 번 다시 조합하지 않는다.
 */
@Stable
class CountdownState internal constructor(
    private val deadlineMs: Long,
    internal val totalMs: Long,
    private val urgentMs: Long,
    startMs: Long,
) {
    internal val progress = Animatable(Countdown.ratio(startMs, deadlineMs, totalMs))
    internal var tickAt by mutableLongStateOf(startMs)

    /** 남은 초(올림 — 서버 시각까지 남은 초 secondsUntil과 같은 셈) */
    val remainingSec: Long by derivedStateOf { Countdown.remainingSec(tickAt, deadlineMs) }
    val urgent: Boolean by derivedStateOf { Countdown.urgent(tickAt, deadlineMs, urgentMs) }
    val expired: Boolean by derivedStateOf { Countdown.expired(tickAt, deadlineMs) }

    internal fun ratioAt(nowMs: Long) = Countdown.ratio(nowMs, deadlineMs, totalMs)
    internal fun stepAt(nowMs: Long) = Countdown.step(nowMs, deadlineMs, totalMs)
}

/**
 * 확인 마감까지 남은 시간(모션 명세 M-WAIT-COUNTDOWN). 250ms마다 다시 재고 링은 그 사이를 같은 속도로 줄인다.
 * 전체 시간은 [key](결제 요청)마다 처음 그린 순간 남아 있던 시간으로 한 번만 잡아서 회전해도 다시 잡지 않는다.
 * 백그라운드에서 돌아오면 지난 시간을 따라 그리지 않고 한 번에 맞춘다. 모션 줄이기에서는 틱마다 계단으로 바뀐다(정보라서 남김).
 */
@Composable
fun rememberCountdown(
    deadlineMs: Long,
    key: String,
    urgentMs: Long = 10_000L,
    now: () -> Long = System::currentTimeMillis,
): CountdownState {
    val reduce = YamuTheme.reduceMotion
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val totalMs = rememberSaveable(key) { (deadlineMs - now()).coerceAtLeast(0L) }
    val state = remember(key, deadlineMs, totalMs, urgentMs) { CountdownState(deadlineMs, totalMs, urgentMs, now()) }
    LaunchedEffect(state, reduce) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.progress.snapTo(state.ratioAt(now()))
            while (true) {
                val t = now()
                state.tickAt = t
                val step = state.stepAt(t)
                if (reduce || step.durationMs == 0) {
                    state.progress.snapTo(state.ratioAt(t))
                } else {
                    // 다음 틱 시각에 있어야 할 비율까지 같은 속도로. 그래서 호가 실제 남은 시간보다 한 틱 늦지 않음
                    launch { state.progress.animateTo(step.target, tween(step.durationMs, easing = LinearEasing)) }
                }
                if (state.expired) break
                delay(YamuMotion.COUNTDOWN_TICK_MS.toLong())
            }
        }
    }
    return state
}

/** 남은 시간 색: 평소 브랜드색, 임박하면 주황으로 300ms에 걸쳐(결과 값이 아닌 숫자라 글자는 바로, 색만 보간). 링과 숫자가 같이 쓴다 */
@Composable
fun animateCountdownColor(state: CountdownState): Color {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val color by animateColorAsState(
        targetValue = if (state.urgent) c.warning else c.primary,
        animationSpec = tween(if (reduce) YamuMotion.SHORT_MS else YamuMotion.VALUE_COLOR_MS, easing = YamuMotion.standard),
        label = "countdownColor",
    )
    return color
}

/** 남은 시간 링. 임박하면 주황, 마감이 지나면 회색 트랙만 남는다 */
@Composable
fun CountdownRing(
    state: CountdownState,
    modifier: Modifier = Modifier,
    stroke: Dp = WaitRingDefaults.miniStroke,
) {
    val c = YamuTheme.colors
    WaitRing(
        progress = { state.progress.value },
        color = animateCountdownColor(state),
        modifier = modifier,
        trackColor = if (state.expired) c.text3 else c.surfaceHigh,
        stroke = stroke,
    )
}

/** 숫자 없이 링만 쓸 때 */
@Composable
fun CountdownRing(
    deadlineMs: Long,
    key: String,
    modifier: Modifier = Modifier,
    urgentMs: Long = 10_000L,
    stroke: Dp = WaitRingDefaults.miniStroke,
) = CountdownRing(rememberCountdown(deadlineMs, key, urgentMs), modifier, stroke)
