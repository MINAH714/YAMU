package com.yamu.app.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.floor

/**
 * 자리 하나가 어떻게 바뀌는지. [place]는 오른쪽에서 몇 번째 자리(1 = 일의 자리), [order]는 굴러가는 순서(0부터, 아랫자리가 먼저)이고
 * -1이면 굴리지 않고 굴림이 시작될 때 바로 바뀐다. 바퀴는 [from]에서 [end]까지 돈다. 값이 커지면 위로(9 다음 0), 작아지면 아래로 돈다
 */
internal data class DigitRoll(val place: Int, val from: Int, val to: Int, val order: Int, val end: Int)

/** 굴러가는 숫자 계획 — 화면과 떼어 단위 테스트한다 */
internal object RollPlan {
    /**
     * [from]에서 [to]로 바뀐 자리만 고른다. 굴리는 건 바뀐 자리 중 위쪽 [YamuMotion.ROLL_MAX_DIGITS]개까지이고,
     * 새로 생긴 자리(9,800 → 10,200의 만 자리)와 아래로 밀린 자리는 바로 바뀐다. 음수가 끼면 굴리지 않는다
     */
    fun plan(from: Long, to: Long): List<DigitRoll> {
        if (from == to) return emptyList()
        if (from < 0 || to < 0) return emptyList()
        val a = from.toString()
        val b = to.toString()
        val places = maxOf(a.length, b.length)
        fun digit(s: String, place: Int): Int? = if (place > s.length) null else s[s.length - place].digitToInt()
        val changed = (1..places).filter { digit(a, it) != digit(b, it) }
        val rolling = changed.filter { it <= a.length && it <= b.length }.sortedDescending().take(YamuMotion.ROLL_MAX_DIGITS)
        val order = rolling.sorted().withIndex().associate { (i, place) -> place to i }
        val up = to > from
        return changed.filter { it <= b.length }.map { place ->
            val f = digit(a, place) ?: 0
            val t = digit(b, place)!!
            val end = when {
                up && t < f -> t + 10
                !up && t > f -> t - 10
                else -> t
            }
            DigitRoll(place, f, t, order[place] ?: -1, end)
        }
    }

    /** 굴림이 시작되고 마지막 자리가 멈출 때까지(굴리는 자리가 없으면 0) */
    fun totalMs(plan: List<DigitRoll>): Int =
        plan.filter { it.order >= 0 }.maxOfOrNull { YamuMotion.ROLL_DIGIT_MS + YamuMotion.ROLL_STAGGER_MS * it.order } ?: 0
}

/**
 * 바뀐 자리만 굴러가는 금액(M-DONE-pay 실적 합계). [value]가 바뀌면 보이던 값에서 바뀐 자리만 굴린다.
 * 처음 그릴 때 [initial]이 [value]와 다르면 [delayMillis] 뒤 [initial]에서 [value]로 굴린다(결제 완료의 1300ms 같은 것).
 * 글자 배치는 진짜 글줄에 맡기고 굴리는 자리만 투명하게 둔 뒤 그 칸 안에 숫자를 그려, 쉼표 · '원'의 자리가 일반 글자와 같다.
 * 굴린 값은 화면을 다시 만들어도(회전) 다시 굴리지 않는다. 모션 줄이기면 같은 때에 바로 바뀐다. 읽어 주기는 늘 [value] 하나만
 */
@Composable
fun RollingNumber(
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    initial: Long = value,
    delayMillis: Int = 0,
    suffix: String = "원",
) {
    val reduce = YamuTheme.reduceMotion
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    // 다 굴러간 값 — 회전으로 다시 만들어지면 여기서 이어 간다
    var settled by rememberSaveable { mutableLongStateOf(initial) }
    var shown by remember { mutableLongStateOf(settled) }
    val wheels = remember { mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(value, reduce) {
        if (value == settled) {
            wheels.clear()
            shown = value
            return@LaunchedEffect
        }
        val from = settled
        if (delayMillis > 0) delay((delayMillis * (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)).toLong())
        val plan = RollPlan.plan(from, value).filter { it.order >= 0 }
        if (reduce || plan.isEmpty()) {
            wheels.clear()
            shown = value
            settled = value
            return@LaunchedEffect
        }
        // 바퀴를 옛 숫자에 두고 같은 프레임에 새 값의 글줄로 바꾼다(굴리지 않는 자리는 이때 바로 바뀜)
        wheels.clear()
        plan.forEach { wheels[it.place] = Animatable(it.from.toFloat()) }
        shown = value
        coroutineScope {
            plan.forEach { d ->
                launch {
                    wheels.getValue(d.place).animateTo(
                        d.end.toFloat(),
                        tween(YamuMotion.ROLL_DIGIT_MS, delayMillis = YamuMotion.ROLL_STAGGER_MS * d.order, easing = YamuMotion.standard),
                    )
                }
            }
        }
        settled = value
        wheels.clear()
    }
    val text = shown.toWon()
    val measurer = rememberTextMeasurer()
    val glyphs = remember(style, ink) { (0..9).map { measurer.measure("$it", style.copy(color = ink)) } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val rolling = wheels.keys.toSet()
    val annotated = remember(text, rolling, suffix) {
        buildAnnotatedString {
            text.forEachIndexed { i, ch ->
                val place = placeOf(text, i)
                if (ch.isDigit() && place in rolling) withStyle(SpanStyle(color = Color.Transparent)) { append(ch) } else append(ch)
            }
            append(suffix)
        }
    }
    Text(
        annotated,
        style = style,
        color = ink,
        maxLines = 1,
        onTextLayout = { layout = it },
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "${value.toWon()}$suffix" }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                text.forEachIndexed { i, ch ->
                    if (!ch.isDigit() || i >= l.layoutInput.text.length) return@forEachIndexed
                    val v = wheels[placeOf(text, i)]?.value ?: return@forEachIndexed
                    val box = l.getBoundingBox(i)
                    val base = floor(v)
                    val lo = Math.floorMod(base.toInt(), 10)
                    val hi = Math.floorMod(base.toInt() + 1, 10)
                    val frac = v - base
                    clipRect(box.left, box.top, box.right, box.bottom) {
                        val a = glyphs[lo]
                        drawText(a, topLeft = Offset(box.left + (box.width - a.size.width) / 2, box.top - frac * box.height))
                        if (frac > 0.001f) {
                            val b = glyphs[hi]
                            drawText(b, topLeft = Offset(box.left + (box.width - b.size.width) / 2, box.top + (1 - frac) * box.height))
                        }
                    }
                }
            },
    )
}

/** 글줄 [text]의 [index]번째 글자가 오른쪽에서 몇 번째 숫자 자리인지(쉼표는 세지 않음) */
private fun placeOf(text: String, index: Int): Int = text.substring(index).count { it.isDigit() }

