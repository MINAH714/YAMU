package com.yamu.app.feature.forecast

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toManWon
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 선 하나 — [values]는 날짜와 같은 길이. null인 날은 비워 선이 끊긴다(서버가 준 값 그대로, 앱은 값을 만들지 않는다).
 * [zoned]면 안전 잔액 아래는 주황, 0원 아래는 빨강으로 바꿔 그린다(가상 구매의 '사기 전' 회색 선은 그대로)
 */
data class ChartLine(val label: String, val values: List<Long?>, val color: Color, val dashed: Boolean = false, val width: Dp = 2.dp, val zoned: Boolean = true)

/**
 * 가상 구매(I-02) 표시(Task 8.2.72) — [purchase] 구매일은 회색 점선과 바닥 '구매', [withdrawals] 기간 안 출금일은 큰 동전과 '출금'.
 * [beyond]가 있으면 출금이 예보 기간 밖이라 그래프 밑 날짜 줄 끝에 그 글자('12/14 출금 →')를 붙인다.
 * 전에는 그래프 오른쪽 끝에 주황 점선 · 동전도 두느라 오른쪽을 34dp 비워 선이 일찍 끝나서 뺐다(S15P31E103-528, QA: 그래프가 늘 끝까지).
 * [flash]가 오를 때마다 그 글자를 두 번 깜빡인다('빠져나가는 날'의 기간 밖 줄을 눌렀을 때)
 */
data class ChartMarks(val purchase: Int? = null, val withdrawals: Set<Int> = emptySet(), val beyond: String? = null, val flash: Int = 0)

/**
 * 잔액 그래프 — 잔고예보(AI 생활비 제외 · 반영)와 가상 구매(사기 전 · 후)가 같이 쓴다(Task 8.2.65에서 다시 그림).
 * 마지막 선이 손잡이가 타는 선이라 동전 · 선 아래 면 · 말풍선 금액이 그 선을 따른다(잔고예보는 'AI 생활비 반영', 가상 구매는 '사기 후').
 *
 * - 세로 범위는 선이 있는 곳에 여백만([chartRange]). 안전 잔액 아래는 주황, 0원 아래는 빨강으로 선과 면 색을 나눈다
 * - 돈이 오가는 날([eventDays])엔 동전, 하루에 크게 빠지는 날엔 빨간 ↓([sharpDropDays], 기준은 임시)
 * - [onSelect]가 있으면 동전 손잡이가 손가락을 바로 따라오고 놓으면 가까운 날에 붙는다. 동전을 지나면 그 동전이 한 바퀴 돌며 진동하고,
 *   손잡이가 0원 아래로 들어가면 동전 · 말풍선이 빨갛게 바뀌며 말풍선이 흔들린다
 * - 처음 열 때 선과 면이 왼쪽부터 그려지고 동전이 떨어져 앉는다. 모션 줄이기면 모두 끄고 바로 옮긴다
 * - 가상 구매는 [marks]로 구매일 · 출금일 · 기간 밖 출금을 더 그린다
 * - [month]를 주면 한 달 그래프다(소비분석 자산 흐름, Task 8.2.99). [lines]는 실선(실제) · 점선(예상) 순으로 날을 나눠 갖고,
 *   손잡이는 날마다 값이 있는 선을 탄다. 데이터 없음 · 범위 밖은 빗금, 오늘은 세로 점선, 바닥에 1 · 8 · 15 · 22 · 29일과 '오늘'(겹치는 날짜는 뺌).
 *   값이 없는 날을 고르면 동전 대신 바닥에 빈 점선 동그라미를 둔다. 주지 않으면(잔고예보 · 가상 구매) 지금 모습 그대로
 */
@Composable
fun BalanceChart(
    dates: List<LocalDate>,
    lines: List<ChartLine>,
    description: String,
    modifier: Modifier = Modifier,
    threshold: Long? = null,
    eventDays: Set<Int> = emptySet(),
    marks: ChartMarks? = null,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    dayLabel: (Int) -> String = { dates[it].toKoreanDateWeek() },
    dayState: (Int) -> String = dayLabel,
    incomeArrow: Boolean = false,
    height: Dp? = null,
    interacted: Boolean = false,
    onInteract: (() -> Unit)? = null,
    onDragChange: ((Boolean) -> Unit)? = null,
    month: MonthChart? = null,
) {
    val n = dates.size
    if (n < 2 || lines.isEmpty()) return
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val ride = lines.last()
    // 한 달 그래프면 날 종류(날짜와 길이가 다르면 쓰지 않는다). 손잡이 · 동전 · 말풍선이 타는 값은 그날 값이 있는 선
    val kinds = month?.kinds?.takeIf { it.size == n }
    val rideValues = remember(lines, kinds) { if (kinds != null) firstValues(lines.map { it.values }) else ride.values }
    val range = remember(lines, threshold) { chartRange(lines.flatMap { it.values.filterNotNull() }, threshold) }
    val drops = remember(rideValues) { sharpDropDays(rideValues) }
    val sel = selected?.coerceIn(0, n - 1)
    val interactive = onSelect != null && sel != null
    val chartKey = dates.first() to dates.last()
    val topPad = if (interactive) BUBBLE_ZONE else 12.dp
    // 월급 화살표가 있으면 x축 끝이 월급날(마지막 날 다음 날)이라 하루 칸 하나를 비운다([chartRightPad]).
    // 그 밖에는 양옆 같은 여백이라 마지막 날이 오른쪽 끝에 닿는다(가상 구매 기간 밖 출금도 밑 날짜 줄에만 적는다)
    fun rightPad(width: Float): Float = with(density) { chartRightPad(width, SIDE_PAD.toPx(), n, incomeArrow, SIDE_PAD.toPx()) }

    // 들어오기 — 화면을 돌리거나 돌아와도 같은 기간이면 다시 하지 않는다
    var introDone by rememberSaveable(chartKey) { mutableStateOf(false) }
    val reveal = remember(chartKey) { Animatable(if (introDone || reduce) 1f else 0f) }
    LaunchedEffect(chartKey) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(INTRO_MS, easing = YamuMotion.standard))
        introDone = true
    }

    // 한 번이라도 사람이 만졌는지 — 그래프를 직접 만졌거나([touchedHere]) 화면의 다른 곳(띠 · 칸 · 목록)에서 날을 바꿨거나([interacted])
    var touchedHere by rememberSaveable(chartKey) { mutableStateOf(false) }
    val touched = touchedHere || interacted
    val latestOnInteract by rememberUpdatedState(onInteract)
    val latestOnDragChange by rememberUpdatedState(onDragChange)
    fun markTouched() { if (!touchedHere) { touchedHere = true; latestOnInteract?.invoke() } }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { latestOnDragChange?.invoke(dragging) }

    // 첫 안내 — 아무도 만지지 않았으면 들어오기가 끝난 뒤 손잡이를 좌우로 두 번 흔든다(모션 줄이기면 끔)
    val nudge = remember(chartKey) { Animatable(0f) }
    LaunchedEffect(chartKey, touched, interactive) {
        if (touched || !interactive || reduce) { nudge.snapTo(0f); return@LaunchedEffect }
        snapshotFlow { reveal.value }.first { it >= 1f }   // 들어오기가 끝날 때까지 기다린다(이미 끝났으면 바로)
        delay(NUDGE_DELAY_MS)
        nudge.animateTo(0f, keyframes {
            durationMillis = NUDGE_MS
            1f at NUDGE_MS / 8
            -1f at NUDGE_MS * 3 / 8
            0.6f at NUDGE_MS * 5 / 8
            -0.4f at NUDGE_MS * 7 / 8
        })
    }
    val handle = remember(chartKey) { Animatable(fractionOf(sel ?: 0, n)) }
    // 밖에서(누르기 · 읽어 주기 동작 · 띠) 고른 날로 손잡이를 옮긴다. 끄는 중에는 손가락이 주인이라 건드리지 않는다
    LaunchedEffect(sel, n) {
        if (sel == null || dragging) return@LaunchedEffect
        val target = fractionOf(sel, n)
        if (reduce || !touched) handle.snapTo(target) else handle.animateTo(target, tween(GLIDE_MS, easing = YamuMotion.standard))
    }

    // 동전을 지나면 그 동전이 제자리에서 한 바퀴 — 빠르게 끌어 여러 개를 지나면 같이 돈다
    val spins = remember(chartKey, n) { List(n) { Animatable(0f) } }
    var lastSel by remember(chartKey) { mutableIntStateOf(sel ?: -1) }
    LaunchedEffect(sel) {
        val s = sel ?: return@LaunchedEffect
        if (s == lastSel) return@LaunchedEffect
        lastSel = s
        if (!touched || s !in eventDays) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        if (!reduce) scope.launch {
            spins[s].snapTo(0f)
            spins[s].animateTo(1f, tween(SPIN_MS, easing = LinearOutSlowInEasing))
        }
    }

    // 0원 아래로 들어가는 순간 — 진동 한 번 더 세게, 말풍선 흔들기
    val rideAt = sel?.let { rideValues.getOrNull(it) }
    val neg = (rideAt ?: 0L) < 0
    val shake = remember { Animatable(0f) }
    var wasNeg by remember(chartKey) { mutableStateOf(neg) }
    LaunchedEffect(neg) {
        if (neg && !wasNeg && touched) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            if (!reduce) {
                shake.snapTo(0f)
                shake.animateTo(0f, keyframes {
                    durationMillis = SHAKE_MS
                    -4f at 60
                    4f at 150
                    -2f at 240
                })
            }
        }
        wasNeg = neg
    }
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1.2f else 1f,
        animationSpec = if (reduce) snap() else tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard),
        label = "handleLift",
    )

    // 손가락 처리는 기간 · 날짜 수가 바뀔 때만 다시 시작한다. 그 안에서는 늘 마지막 고른 날 · onSelect를 읽는다
    // (계좌만 바꿨을 때 버린 상태를 바꿔 그래프를 눌러도 날짜가 그대로였던 문제, 앞 버전에서 고친 것)
    val latestSel by rememberUpdatedState(sel)
    val latestOnSelect by rememberUpdatedState(onSelect)
    fun fractionAt(x: Float, width: Float): Float = with(density) {
        val left = SIDE_PAD.toPx()
        ((x - left) / (width - left - rightPad(width))).coerceIn(0f, 1f)
    }
    fun pick(i: Int) { if (i != latestSel) latestOnSelect?.invoke(i) }
    fun follow(x: Float, width: Float) {
        val f = fractionAt(x, width)
        scope.launch { handle.snapTo(f) }
        pick(dayAt(f, n))
    }
    fun settle() {
        val s = latestSel ?: return
        scope.launch { if (reduce) handle.snapTo(fractionOf(s, n)) else handle.animateTo(fractionOf(s, n), tween(SETTLE_MS, easing = YamuMotion.standard)) }
    }

    var boxWidth by remember { mutableIntStateOf(0) }
    var bubbleWidth by remember { mutableIntStateOf(0) }
    val handleAlpha = { ((reveal.value - 0.85f) / 0.15f).coerceIn(0f, 1f) }
    val labelStyle = YamuTheme.typography.labelS
    val coin = CoinPaint(c.coinFace, c.coinSide, c.coinLine)
    val negCoin = CoinPaint(c.errorContainer, c.error, c.error)

    // 가상 구매 기간 밖 출금 글자(밑 날짜 줄) 깜빡이기 — 그 줄을 누를 때마다 두 번. 모션 줄이기면 깜빡이지 않고 잠깐 켜 둔다
    val flash = remember { Animatable(0f) }
    LaunchedEffect(marks?.flash) {
        if ((marks?.flash ?: 0) <= 0 || marks?.beyond == null) return@LaunchedEffect
        if (reduce) {
            flash.snapTo(1f)
            delay(FLASH_MS * 2L)
            flash.snapTo(0f)
        } else repeat(2) {
            flash.animateTo(1f, tween(FLASH_MS / 2, easing = YamuMotion.standard))
            flash.animateTo(0f, tween(FLASH_MS / 2, easing = YamuMotion.standard))
        }
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height ?: if (interactive) 184.dp else 140.dp)
                .onSizeChanged { boxWidth = it.width }
                .semantics {
                    contentDescription = description
                    if (interactive && sel != null) {
                        stateDescription = dayState(sel)
                        customActions = listOf(
                            CustomAccessibilityAction("하루 앞") { if (sel > 0) { markTouched(); onSelect?.invoke(sel - 1); true } else false },
                            CustomAccessibilityAction("하루 뒤") { if (sel < n - 1) { markTouched(); onSelect?.invoke(sel + 1); true } else false },
                        )
                    }
                }
                .then(
                    if (!interactive) Modifier else Modifier
                        .pointerInput(chartKey, n, incomeArrow) {
                            detectTapGestures { o ->
                                markTouched()
                                pick(dayAt(fractionAt(o.x, size.width.toFloat()), n))
                            }
                        }
                        .pointerInput(chartKey, n, incomeArrow) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> markTouched(); dragging = true; follow(o.x, size.width.toFloat()) },
                                onDragEnd = { dragging = false; settle() },
                                onDragCancel = { dragging = false; settle() },
                            ) { change, _ ->
                                change.consume()
                                follow(change.position.x, size.width.toFloat())
                            }
                        },
                ),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val left = SIDE_PAD.toPx()
                val right = size.width - rightPad(size.width)
                val top = topPad.toPx()
                // 한 달 그래프는 바닥에 날짜 글자 자리를 둔다 — 글자 크기를 키워도 선과 겹치지 않게 잰 높이로
                val axisLabels = kinds?.let { monthAxisIndices(n).map { i -> i to measurer.measure("${i + 1}일", labelStyle.copy(color = c.text3)) } }.orEmpty()
                // '오늘'은 기준선 발치(바닥 날짜 줄)에 — 위에 두면 처음 열 때 오늘을 고른 말풍선이 덮는다(!311 리뷰)
                val todayAt = kinds?.indexOf(DayKind.TODAY)?.takeIf { it >= 0 }
                val todayLabel = todayAt?.let { measurer.measure("오늘", labelStyle.copy(color = c.text2, fontWeight = FontWeight.Bold)) }
                val axisHeight = (axisLabels.map { it.second.size.height } + listOfNotNull(todayLabel?.size?.height)).maxOrNull()
                val bottom = size.height - (axisHeight?.let { it + 4.dp.toPx() } ?: BOTTOM_PAD.toPx())
                fun x(i: Int) = left + (right - left) * fractionOf(i, n)
                fun y(v: Double) = bottom - range.fraction(v) * (bottom - top)
                val ySafe = threshold?.let { y(it.toDouble()).coerceIn(0f, size.height) } ?: size.height
                val zeroIn = range.shows(0)
                val yZero = y(0.0).coerceIn(0f, size.height)
                val bandTop = top - 8.dp.toPx()

                // 바탕 띠 — 안전 잔액 아래(연한 주황) · 0원 아래(연한 빨강)
                if (threshold != null && threshold > range.bottom) {
                    val t = max(ySafe, bandTop)
                    val b = min(if (zeroIn) yZero else size.height, size.height)
                    if (b > t) drawRect(c.warningContainer, Offset(0f, t), Size(size.width, b - t), alpha = 0.7f)
                }
                if (zeroIn) {
                    val t = max(yZero, bandTop)
                    drawRect(c.errorContainer, Offset(0f, t), Size(size.width, size.height - t), alpha = 0.7f)
                }
                // 기준선 — 안전 잔액(범위 안이면 점선, 한참 아래면 바닥에 글자만) · 0원. 점선 이름은 선을 그린 뒤 얹는다
                var safeLabel: (() -> Unit)? = null
                var safeBox: Rect? = null   // 안전 잔액 이름이 차지한 칸 — '구매' 글자가 피한다
                threshold?.let { t ->
                    if (range.shows(t)) {
                        drawLine(c.warning.copy(alpha = 0.8f), Offset(0f, ySafe), Offset(right, ySafe), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                        val label = measurer.measure("안전 잔액 ${t.toManWon()}", labelStyle.copy(color = c.onWarningContainer))
                        val w = label.size.width.toFloat()
                        val h = label.size.height.toFloat()
                        val gap = 2.dp.toPx()
                        // 선이 지나가지 않는 자리에 둔다 — 왼쪽 위 · 오른쪽 위 · 왼쪽 아래 · 오른쪽 아래 순. 모두 겹치면 왼쪽 위에 바탕을 깔아 읽히게
                        fun crossed(x0: Float, y0: Float): Boolean {
                            var px = x0
                            while (px <= x0 + w) {
                                val f = ((px - left) / (right - left)).coerceIn(0f, 1f)
                                if (lines.any { l -> valueAt(l.values, f)?.let { v -> y(v) in (y0 - gap)..(y0 + h + gap) } == true }) return true
                                px += 3.dp.toPx()
                            }
                            return false
                        }
                        val spots = listOf(
                            Offset(left, ySafe - h - gap), Offset(right - w, ySafe - h - gap),
                            Offset(left, ySafe + gap), Offset(right - w, ySafe + gap),
                        ).filter { it.y > bandTop && it.y + h < bottom }
                        val spot = spots.firstOrNull { !crossed(it.x, it.y) }
                        val at = spot ?: spots.firstOrNull() ?: Offset(left, ySafe - h - gap)
                        safeBox = Rect(at.x - 3.dp.toPx(), at.y - 1.dp.toPx(), at.x + w + 3.dp.toPx(), at.y + h + 1.dp.toPx())
                        safeLabel = {
                            if (spot == null) drawRoundRect(c.surface.copy(alpha = 0.9f), topLeft = Offset(at.x - 3.dp.toPx(), at.y - 1.dp.toPx()), size = Size(w + 6.dp.toPx(), h + 2.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()))
                            drawText(label, topLeft = at)
                        }
                    } else if (t <= range.bottom) {
                        val label = measurer.measure("안전 잔액 ${t.toManWon()}은 이 아래", labelStyle.copy(color = c.text3))
                        drawText(label, topLeft = Offset(left, size.height - label.size.height))
                    }
                }
                if (zeroIn) {
                    drawLine(c.error.copy(alpha = 0.55f), Offset(0f, yZero), Offset(right, yZero), strokeWidth = 1.dp.toPx())
                    val label = measurer.measure("0원", labelStyle.copy(color = c.onErrorContainer))
                    if (yZero + 2.dp.toPx() + label.size.height < size.height) drawText(label, topLeft = Offset(right - label.size.width, yZero + 2.dp.toPx()))
                }

                // 한 달 그래프 빗금 — 데이터 없음은 진하게, 범위 밖은 옅게. 넓으면 무엇인지 글자로(색만으로 나누지 않게, COMMON-11).
                // 들어오기와 상관없이 처음부터 보인다(값이 아니라 '여기는 없다'는 자리라서)
                if (kinds != null) {
                    val half = (right - left) / (n - 1) / 2f
                    hatchRuns(kinds).forEach { run ->
                        val x0 = max(0f, x(run.from) - half)
                        val x1 = min(size.width, x(run.to) + half)
                        if (x1 <= x0) return@forEach
                        val y0 = top - 4.dp.toPx()
                        drawHatch(Offset(x0, y0), Size(x1 - x0, bottom - y0), hatchInk(run.kind, c.text3, c.warning))
                        val label = measurer.measure(hatchLabel(run.kind), labelStyle.copy(color = if (run.kind == DayKind.ERROR) c.onWarningContainer else c.text2, fontWeight = FontWeight.Bold))
                        if (x1 - x0 >= label.size.width + 16.dp.toPx()) {
                            val at = Offset((x0 + x1 - label.size.width) / 2f, y0 + 6.dp.toPx())
                            drawRoundRect(c.surface.copy(alpha = 0.92f), topLeft = Offset(at.x - 5.dp.toPx(), at.y - 2.dp.toPx()), size = Size(label.size.width + 10.dp.toPx(), label.size.height + 4.dp.toPx()), cornerRadius = CornerRadius(6.dp.toPx()))
                            drawText(label, topLeft = at)
                        }
                    }
                    fun axisLeft(cx: Float, w: Int) = (cx - w / 2f).coerceIn(0f, max(0f, size.width - w))
                    val todayLeft = todayAt?.let { t -> todayLabel?.let { axisLeft(x(t), it.size.width) } }
                    val clear = if (todayLeft == null || todayLabel == null) axisLabels.map { true } else axisLabelsClearOf(
                        axisLabels.map { (i, label) -> axisLeft(x(i), label.size.width).let { l -> l..(l + label.size.width) } },
                        todayLeft..(todayLeft + todayLabel.size.width),
                        gap = 4.dp.toPx(),
                    )
                    axisLabels.forEachIndexed { k, (i, label) ->
                        if (clear[k]) drawText(label, topLeft = Offset(axisLeft(x(i), label.size.width), size.height - label.size.height))
                    }
                    if (todayLeft != null && todayLabel != null) drawText(todayLabel, topLeft = Offset(todayLeft, size.height - todayLabel.size.height))
                }

                // 가상 구매 구매일 — 회색 점선은 선보다 먼저 그려 선 · 동전을 가리지 않는다. '구매' 글자는 선을 그린 뒤 얹는다
                var purchaseLabel: (() -> Unit)? = null
                marks?.purchase?.takeIf { it in 0 until n }?.let { p ->
                    val px = x(p)
                    drawLine(
                        c.text2.copy(alpha = 0.7f), Offset(px, top - 6.dp.toPx()), Offset(px, bottom + 4.dp.toPx()),
                        strokeWidth = 1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                    )
                    val label = measurer.measure("구매", labelStyle.copy(color = c.text2, fontWeight = FontWeight.Bold))
                    val w = label.size.width.toFloat()
                    val h = label.size.height.toFloat()
                    val gap = 2.dp.toPx()
                    val lx = if (px + 5.dp.toPx() + w <= right) px + 5.dp.toPx() else px - 5.dp.toPx() - w
                    // 선 · 동전 · 빨간 ↓ · 안전 잔액 이름이 지나가지 않는 자리 — 바닥, 안 되면 그래프 맨 위. 둘 다 겹치면 바닥에 바탕을 깔아 읽히게
                    // (안전 잔액 이름이 왼쪽 위로 밀리면 구매일이 첫날일 때 바닥의 '구매'와 겹쳤다, S15P31E103-528)
                    fun crossed(ly: Float): Boolean {
                        val band = (ly - gap)..(ly + h + gap)
                        if (safeBox?.overlaps(Rect(lx, band.start, lx + w, band.endInclusive)) == true) return true
                        var sx = lx
                        while (sx <= lx + w) {
                            val f = ((sx - left) / (right - left)).coerceIn(0f, 1f)
                            if (lines.any { l -> valueAt(l.values, f)?.let { v -> y(v) in band } == true }) return true
                            sx += 3.dp.toPx()
                        }
                        return (drops + eventDays).any { d ->
                            val v = rideValues.getOrNull(d) ?: return@any false
                            val cx = x(d)
                            val cy = y(v.toDouble())
                            cx + 8.dp.toPx() >= lx && cx - 8.dp.toPx() <= lx + w && cy - 8.dp.toPx() <= band.endInclusive && cy + 18.dp.toPx() >= band.start
                        }
                    }
                    // 바닥 왼쪽 '안전 잔액 … 이 아래' 글자가 있으면 바닥 자리를 그 위로 한 줄 올린다
                    val belowNote = threshold != null && !range.shows(threshold) && threshold <= range.bottom
                    val floor = if (belowNote) size.height - 2f * h - gap else bottom - h - gap
                    val spot = listOf(floor, top + gap).firstOrNull { !crossed(it) }
                    purchaseLabel = {
                        val at = Offset(lx, spot ?: floor)
                        if (spot == null) drawRoundRect(c.surface.copy(alpha = 0.9f), topLeft = Offset(at.x - 3.dp.toPx(), at.y - 1.dp.toPx()), size = Size(w + 6.dp.toPx(), h + 2.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()))
                        drawText(label, topLeft = at)
                    }
                }

                // 선 · 면 · 동전 — 들어오기 동안은 왼쪽부터 드러난다
                clipRect(right = (size.width + 40.dp.toPx()) * reveal.value - 20.dp.toPx()) {
                    // 높이로 나눈 세 구간 — 위: 안전 잔액 위, 가운데: 안전 잔액과 0원 사이, 아래: 0원 아래
                    val zoneOk = 0f to ySafe
                    val zoneSafe = ySafe to max(ySafe, if (zeroIn) yZero else size.height)
                    val zoneZero = (if (zeroIn) yZero else size.height) to size.height

                    // 손잡이가 타는 선 아래 면 — 0원이 범위 안이면 0원까지(0원 아래는 선과 0원 사이), 아니면 바닥까지.
                    // 한 달 그래프는 실선(실제) 아래만 칠하고, 빈 날로 끊긴 곳마다 따로 닫는다(빗금 위를 건너 칠하지 않게)
                    val baseY = if (zeroIn) yZero else bottom
                    val area = Path()
                    var firstX = Float.NaN
                    var lastX = Float.NaN
                    val fillLine = if (kinds != null) lines.first() else ride
                    var filled = false
                    if (kinds == null) {
                        ride.values.forEachIndexed { i, v ->
                            if (v == null) return@forEachIndexed
                            if (firstX.isNaN()) { area.moveTo(x(i), y(v.toDouble())); firstX = x(i) } else area.lineTo(x(i), y(v.toDouble()))
                            lastX = x(i)
                        }
                        if (!firstX.isNaN()) { area.lineTo(lastX, baseY); area.lineTo(firstX, baseY); area.close(); filled = true }
                    } else {
                        var count = 0
                        fun closeRun() {
                            if (count >= 2) { area.lineTo(lastX, baseY); area.lineTo(firstX, baseY); area.close(); filled = true }
                            firstX = Float.NaN
                            count = 0
                        }
                        fillLine.values.forEachIndexed { i, v ->
                            if (v == null) { closeRun(); return@forEachIndexed }
                            if (firstX.isNaN()) { area.moveTo(x(i), y(v.toDouble())); firstX = x(i) } else area.lineTo(x(i), y(v.toDouble()))
                            lastX = x(i)
                            count++
                        }
                        closeRun()
                    }
                    if (filled) {
                        val okBrush = Brush.verticalGradient(listOf(fillLine.color.copy(alpha = 0.30f), fillLine.color.copy(alpha = 0.03f)), startY = top, endY = max(top + 1f, min(baseY, ySafe)))
                        val safeBrush = Brush.verticalGradient(listOf(c.warning.copy(alpha = 0.28f), c.warning.copy(alpha = 0.05f)), startY = ySafe, endY = max(ySafe + 1f, baseY))
                        val zeroBrush = Brush.verticalGradient(listOf(c.error.copy(alpha = 0.10f), c.error.copy(alpha = 0.40f)), startY = yZero, endY = max(yZero + 1f, bottom))
                        listOf(zoneOk to okBrush, zoneSafe to safeBrush, zoneZero to zeroBrush).forEach { (z, brush) ->
                            if (z.second > z.first) clipRect(top = z.first, bottom = z.second) { drawPath(area, brush) }
                        }
                    }

                    lines.forEach { line ->
                        val path = Path()
                        var drawing = false
                        line.values.forEachIndexed { i, v ->
                            if (v == null) drawing = false
                            else if (!drawing) { path.moveTo(x(i), y(v.toDouble())); drawing = true }
                            else path.lineTo(x(i), y(v.toDouble()))
                        }
                        val stroke = Stroke(
                            width = line.width.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
                        )
                        if (!line.zoned) drawPath(path, line.color, style = stroke)
                        else listOf(zoneOk to line.color, zoneSafe to c.warning, zoneZero to c.error).forEach { (z, color) ->
                            if (z.second > z.first) clipRect(top = z.first, bottom = z.second) { drawPath(path, color, style = stroke) }
                        }
                    }

                    // 돈이 오가는 날 동전 — 들어올 때 선이 닿는 순서대로 떨어져 앉는다
                    eventDays.forEach { i ->
                        val v = rideValues.getOrNull(i) ?: return@forEach
                        val p = ((reveal.value * 1.15f - fractionOf(i, n)) / 0.22f).coerceIn(0f, 1f)
                        if (p <= 0f) return@forEach
                        val dropY = (1f - easeOutBack(p)) * 26.dp.toPx()
                        // 가상 구매 출금일은 조금 큰 동전에 '출금'(오른쪽 끝이면 왼쪽에)
                        val withdrawal = marks?.withdrawals?.contains(i) == true
                        val center = Offset(x(i), y(v.toDouble()) - dropY)
                        val r = (if (withdrawal) WITHDRAW_R else COIN_R).toPx()
                        drawChartCoin(center, r, spins.getOrNull(i)?.value ?: 0f, if (v < 0) negCoin else coin, alpha = p)
                        if (withdrawal) {
                            val label = measurer.measure("출금", labelStyle.copy(color = c.onWarningContainer, fontWeight = FontWeight.Bold))
                            val lx = if (center.x + r + 3.dp.toPx() + label.size.width <= size.width) center.x + r + 3.dp.toPx() else center.x - r - 3.dp.toPx() - label.size.width
                            drawText(label, topLeft = Offset(lx, center.y - label.size.height / 2f), alpha = p)
                        }
                    }
                    // 급하게 빠지는 날 — 선 밑 빨간 ↓
                    drops.forEach { i ->
                        val v = rideValues.getOrNull(i) ?: return@forEach
                        val cx = x(i)
                        val ty = y(v.toDouble()) + 11.dp.toPx()
                        val w = 4.5.dp.toPx()
                        drawPath(Path().apply { moveTo(cx - w, ty); lineTo(cx + w, ty); lineTo(cx, ty + 6.5.dp.toPx()); close() }, c.error)
                    }
                }

                safeLabel?.invoke()
                purchaseLabel?.invoke()

                if (incomeArrow) {
                    val ax = size.width - SIDE_PAD.toPx()   // 월급날 자리 — 마지막 날에서 하루 칸 오른쪽
                    drawLine(c.primary, Offset(ax, bottom), Offset(ax, top + 6.dp.toPx()), strokeWidth = 2.dp.toPx())
                    val head = Path().apply {
                        moveTo(ax, top - 2.dp.toPx())
                        lineTo(ax - 5.dp.toPx(), top + 7.dp.toPx())
                        lineTo(ax + 5.dp.toPx(), top + 7.dp.toPx())
                        close()
                    }
                    drawPath(head, c.primary)
                }

                // 한 달 그래프 오늘 기준선 — 세로 점선(실제 · 예상이 갈리는 자리, ANALYSIS-05)과 바닥의 '오늘'(위 날짜 줄에서 그림).
                // 오늘 점은 선이 닿은 뒤 보인다
                todayAt?.let { t ->
                    val tx = x(t)
                    drawLine(
                        c.text2.copy(alpha = 0.7f), Offset(tx, top - 4.dp.toPx()), Offset(tx, bottom),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                    )
                    rideValues.getOrNull(t)?.takeIf { reveal.value * (size.width + 40.dp.toPx()) - 20.dp.toPx() >= tx }?.let { v ->
                        drawCircle(lines.first().color, radius = 3.4.dp.toPx(), center = Offset(tx, y(v.toDouble())))
                    }
                }

                // 고른 날 — 세로줄, 다른 선의 점, 동전 손잡이
                if (interactive) {
                    val a = handleAlpha()
                    if (a > 0f) {
                        val hf = (handle.value + nudge.value * NUDGE_AMP.toPx() / (right - left)).coerceIn(0f, 1f)   // 첫 안내 흔들기는 보이는 자리만 옮긴다
                        val hx = left + (right - left) * hf
                        drawLine(c.text2.copy(alpha = 0.35f * a), Offset(hx, top - 6.dp.toPx()), Offset(hx, bottom + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                        // 한 달 그래프는 실선 · 점선이 같은 값을 날로 나눠 가져 따로 점을 찍지 않는다
                        if (kinds == null) lines.dropLast(1).forEach { line ->
                            val v = valueAt(line.values, hf) ?: return@forEach
                            val center = Offset(hx, y(v))
                            if (line.dashed || !line.zoned) {
                                drawCircle(c.surface, radius = 3.6.dp.toPx(), center = center, alpha = a)
                                drawCircle(line.color, radius = 3.6.dp.toPx(), center = center, style = Stroke(1.6.dp.toPx()), alpha = a)
                            } else {
                                drawCircle(c.surface, radius = 5.dp.toPx(), center = center, alpha = a)
                                drawCircle(line.color, radius = 3.6.dp.toPx(), center = center, alpha = a)
                            }
                        }
                        val hv = if (kinds != null) valueAtStrict(rideValues, hf) else valueAt(rideValues, hf)
                        if (hv != null) {
                            val center = Offset(hx, y(hv))
                            drawCircle(if (neg) c.error else c.primary, radius = 15.dp.toPx() * lift, center = center, alpha = 0.14f * a)
                            drawChartCoin(center, HANDLE_R.toPx() * lift, 0f, if (neg) negCoin else coin, alpha = a)
                        } else if (kinds != null) {
                            // 값이 없는 날 — 손잡이 대신 바닥에 빈 점선 동그라미(지어낸 값에 동전을 얹지 않는다)
                            val center = Offset(hx, bottom - 7.dp.toPx())
                            drawCircle(c.surface, radius = 6.dp.toPx() * lift, center = center, alpha = a)
                            drawCircle(
                                c.text3, radius = 6.dp.toPx() * lift, center = center, alpha = a,
                                style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                            )
                        }
                    }
                }
            }

            if (interactive && sel != null) {
                // 0원 아래 말풍선 — 밝은 화면은 짙은 빨강에 흰 글자, 어두운 화면은 어두운 빨강에 연분홍 글자(작은 날짜 줄도 읽히게, 4.5:1 넘김)
                val tip = when {
                    !neg -> c.inverseSurface
                    c.isDark -> c.errorContainer
                    else -> c.onErrorContainer
                }
                val onTip = when {
                    !neg -> c.inverseOnSurface
                    c.isDark -> c.onErrorContainer
                    else -> c.onPrimary
                }
                Column(
                    Modifier
                        .offset {
                            val left = SIDE_PAD.roundToPx()
                            val right = boxWidth - rightPad(boxWidth.toFloat()).roundToInt()
                            val hx = (left + (right - left) * handle.value + nudge.value * NUDGE_AMP.toPx()).coerceIn(left.toFloat(), right.toFloat())
                            IntOffset((hx - bubbleWidth / 2f).roundToInt().coerceIn(0, max(0, boxWidth - bubbleWidth)), 0)
                        }
                        .onSizeChanged { bubbleWidth = it.width }
                        .graphicsLayer {
                            alpha = handleAlpha()
                            translationX = shake.value * density.density
                        }
                        .clearAndSetSemantics { }
                        .background(tip, RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(if (touched) dayLabel(sel) else "‹ 밀어서 날짜 보기 ›", style = labelStyle, color = onTip.copy(alpha = 0.82f), maxLines = 1)
                    Text(
                        rideAt?.let { "${it.toWon()}원" } ?: kinds?.getOrNull(sel)?.let { k -> month?.emptyValue?.invoke(k) } ?: "—",
                        style = YamuTheme.typography.value.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = onTip,
                        maxLines = 1,
                    )
                }
            }
        }
        // 한 달 그래프는 바닥 날짜를 그래프 안에 그렸다
        if (kinds == null) AxisRow(dates.last(), incomeArrow, marks?.beyond, { flash.value })
    }
}

/**
 * 잔고예보 · 가상 구매 그래프 밑 '오늘 … 11/25 · 월급 · 12/14 출금 →' 한 줄.
 * 월급 화살표가 있으면 오른쪽 끝이 월급날 칸이라 끝 날짜도 월급날(마지막 날 다음 날)이다.
 * 가상 구매 기간 밖 출금은 그래프 끝 너머라 그래프 안이 아니라 여기에만 화살표로 적는다
 */
@Composable
private fun AxisRow(last: LocalDate, incomeArrow: Boolean, beyond: String?, flash: () -> Float) {
    val c = YamuTheme.colors
    Column {
        Spacer(Modifier.height(6.dp))
        val axis = YamuTheme.typography.labelS
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("오늘", style = axis, color = c.text1, modifier = Modifier.weight(1f))
            Text((if (incomeArrow) last.plusDays(1) else last).toMonthDay(), style = axis, color = c.text1)
            if (incomeArrow) {
                Spacer(Modifier.width(8.dp))
                Text("월급", style = axis, color = c.primary)
            }
            beyond?.let { label ->
                Spacer(Modifier.width(6.dp))
                // 깜빡일 때만 연한 주황 바탕(그리는 단계에서 읽어 깜빡이는 동안 다시 짜지 않는다)
                Text(
                    "$label →",
                    style = axis,
                    color = c.onWarningContainer,
                    modifier = Modifier
                        .drawBehind { val a = flash(); if (a > 0f) drawRoundRect(c.warningContainer, cornerRadius = CornerRadius(4.dp.toPx()), alpha = a) }
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/** 그래프 동전 색 — 평소는 금색, 0원 아래는 빨강 */
private data class CoinPaint(val face: Color, val side: Color, val line: Color)

/**
 * 앞에서 본 동전. [spin] 0→1이면 제자리에서 세로축으로 한 바퀴 돌며 살짝 떠오른다(옆면이 보이는 동안은 옆면 색).
 * 홈 동전 무더기와 같은 금색 토큰을 쓴다
 */
private fun DrawScope.drawChartCoin(center: Offset, r: Float, spin: Float, paint: CoinPaint, alpha: Float = 1f) {
    val turn = cos(spin * 2 * PI).toFloat()
    val sx = abs(turn).coerceAtLeast(0.1f)
    val cy = center.y - sin(spin * PI).toFloat() * 7.dp.toPx()
    val face = if (turn >= 0f) paint.face else paint.side
    drawOval(face, topLeft = Offset(center.x - r * sx, cy - r), size = Size(2 * r * sx, 2 * r), alpha = alpha)
    drawOval(paint.side, topLeft = Offset(center.x - r * sx, cy - r), size = Size(2 * r * sx, 2 * r), alpha = alpha, style = Stroke(1.4.dp.toPx()))
    if (sx > 0.35f) {
        val k = 0.55f
        drawOval(paint.line, topLeft = Offset(center.x - r * k * sx, cy - r * k), size = Size(2 * r * k * sx, 2 * r * k), alpha = alpha, style = Stroke(1.dp.toPx()))
    }
}

/** 살짝 넘쳤다 돌아오는 곡선 — 동전이 떨어져 앉을 때 */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * 빗금 — 오른쪽 위로 기운 줄을 [HATCH_GAP]마다. 그래프의 빈 구간과 날짜 띠의 빈 날 알약이 같이 쓴다(색이 아니라 무늬로 '없음'을 보인다)
 */
internal fun DrawScope.drawHatch(topLeft: Offset, size: Size, color: Color) {
    val gap = HATCH_GAP.toPx()
    val stroke = HATCH_STROKE.toPx()
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        var d = -size.height
        while (d < size.width) {
            drawLine(color, Offset(topLeft.x + d, topLeft.y + size.height), Offset(topLeft.x + d + size.height, topLeft.y), strokeWidth = stroke)
            d += gap
        }
    }
}

internal val HATCH_GAP = 6.dp
internal val HATCH_STROKE = 1.5.dp
internal const val HATCH_STRONG = 0.34f   // 데이터 없음
internal const val HATCH_LIGHT = 0.17f    // 예보 범위 밖

/** 빗금 색 — 조회 실패는 주황(글자도 '잔고 조회 실패'), 범위 밖은 옅게, 나머지는 회색 */
internal fun hatchInk(kind: DayKind, gray: Color, warning: Color): Color = when {
    kind == DayKind.ERROR -> warning.copy(alpha = 0.45f)
    hatchStrong(kind) -> gray.copy(alpha = HATCH_STRONG)
    else -> gray.copy(alpha = HATCH_LIGHT)
}

private val BUBBLE_ZONE = 44.dp      // 말풍선 자리(손잡이가 있을 때만)
private val SIDE_PAD = 8.dp          // 양옆 같은 여백 — 월급날 칸이 없으면 오른쪽을 더 비우지 않는다(QA: 그래프가 늘 끝까지)
private val BOTTOM_PAD = 10.dp
private val COIN_R = 5.dp
private val WITHDRAW_R = 6.5.dp      // 가상 구매 출금일 동전
private val HANDLE_R = 9.5.dp
private const val INTRO_MS = 820
private const val GLIDE_MS = 260     // 밖에서 고른 날로 손잡이가 옮겨 가는 시간
private const val SETTLE_MS = 140    // 손을 떼면 가까운 날에 붙는 시간
private const val SPIN_MS = 560
private const val SHAKE_MS = 320
private const val NUDGE_DELAY_MS = 380L
private const val NUDGE_MS = 1500
private val NUDGE_AMP = 13.dp
private const val FLASH_MS = 900     // 기간 밖 출금 표시 한 번 깜빡이는 시간

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
