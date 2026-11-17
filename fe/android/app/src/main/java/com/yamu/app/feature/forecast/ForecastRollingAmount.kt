package com.yamu.app.feature.forecast

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * 위 큰 금액(Task 8.2.70) — 값이 바뀌면 자리마다 숫자가 굴러간다(오른쪽 자리부터 조금씩 늦게 멈춰 빠르게 감기는 느낌).
 * 생김새는 AmountText(L)와 같다: 숫자 30sp · 작은 '원'(0.6em), 아래 4dp. 글자 배치는 진짜 글줄에 맡기고 숫자 자리만 투명하게 둔 뒤
 * 그 칸 안에 굴러가는 숫자를 그려, '원'과 쉼표 · 빼기 표시의 높이가 AmountText와 어긋나지 않는다.
 * 모션 줄이기면 바로 바뀐다. 읽어 주기는 늘 바뀐 뒤 금액 하나만
 */
@Composable
internal fun RollingAmountText(won: Long, color: Color, modifier: Modifier = Modifier) {
    val base = YamuTheme.typography.amountL
    val reduce = YamuTheme.reduceMotion
    val text = won.toWon()
    val measurer = rememberTextMeasurer()
    // 자리(오른쪽에서 몇 번째인지)마다 지금 보이는 숫자 — 자릿수가 바뀌어도 같은 자리끼리 이어 굴린다
    val wheelCache = remember { HashMap<Int, Animatable<Float, AnimationVector1D>>() }
    val targets = text.mapIndexedNotNull { i, ch -> if (ch.isDigit()) (text.length - i) to ch.digitToInt() else null }
    val wheels = remember(text) {
        targets.forEach { (place, d) -> wheelCache.getOrPut(place) { Animatable(d.toFloat()) } }
        wheelCache
    }
    LaunchedEffect(text, reduce) {
        targets.forEach { (place, d) ->
            val wheel = wheels.getValue(place)
            launch {
                if (reduce) wheel.snapTo(d.toFloat())
                else wheel.animateTo(d.toFloat(), tween(ROLL_BASE_MS + place * ROLL_STEP_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(text) {
        buildAnnotatedString {
            text.forEach { ch -> if (ch.isDigit()) withStyle(SpanStyle(color = Color.Transparent)) { append(ch) } else append(ch) }
            withStyle(SpanStyle(fontSize = 0.6.em, fontWeight = FontWeight.SemiBold)) { append(" 원") }
        }
    }
    // 칸보다 길면(1,000억 · 조 단위, 큰 글씨) 글자를 절반까지 줄인다. 굴러가는 숫자도 같은 크기로 그려야 칸과 맞는다(큰 금액 QA)
    BoxWithConstraints(modifier) {
        val natural = remember(annotated, base) { measurer.measure(annotated, base, softWrap = false, maxLines = 1).size.width }
        val scale = rollingFitScale(natural, constraints.maxWidth)
        val style = if (scale < 1f) base.copy(fontSize = base.fontSize * scale) else base
        val glyphs = remember(style, color) { (0..9).map { measurer.measure("$it", style.copy(color = color)) } }
        Text(
            annotated,
            style = style,
            color = color,
            maxLines = 1,
            onTextLayout = { layout = it },
            modifier = Modifier
                .padding(bottom = 4.dp)
                .clearAndSetSemantics { contentDescription = "${text}원" }
                .drawWithContent {
                    drawContent()
                    val l = layout ?: return@drawWithContent
                    text.forEachIndexed { i, ch ->
                        if (!ch.isDigit() || i >= l.layoutInput.text.length) return@forEachIndexed
                        val v = wheels[text.length - i]?.value ?: return@forEachIndexed
                        val box = l.getBoundingBox(i)
                        val lo = floor(v).toInt().coerceIn(0, 9)
                        val hi = (lo + 1).coerceAtMost(9)
                        val frac = v - lo
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
}

/** 금액 글줄이 칸보다 길 때 줄일 배율 — 0.01 단위로 내려 칸을 넘지 않게, 절반 아래로는 안 간다 */
internal fun rollingFitScale(naturalWidth: Int, maxWidth: Int): Float {
    if (maxWidth == Constraints.Infinity || naturalWidth <= maxWidth || naturalWidth <= 0) return 1f
    return (floor(maxWidth * 100f / naturalWidth) / 100f).coerceAtLeast(ROLL_MIN_SCALE)
}

private const val ROLL_MIN_SCALE = 0.5f
private const val ROLL_BASE_MS = 240
private const val ROLL_STEP_MS = 22
