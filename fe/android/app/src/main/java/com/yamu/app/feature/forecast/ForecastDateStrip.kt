package com.yamu.app.feature.forecast

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import kotlin.math.abs

/**
 * 그래프 밑 날짜 띠(Task 8.2.70) — 날마다 알약 하나, 가운데 칸에 맞춰 멈추고 양 끝 알약이 잘려 보여 옆으로 더 있다는 걸 알린다.
 * 손으로 밀면 가운데 알약이 고른 날이 되고([DaySource.STRIP]), 다른 곳에서 날을 바꾸면 그 알약을 가운데로 데려온다.
 * 알약에는 그날 오가는 돈(만 원 단위)을 붙이고, 안전 잔액 아래는 주황 테 · 0원 아래는 빨간 테. 끝에는 월급날 알약을 둔다.
 * 처음 열었을 때 아무도 만지지 않았으면 띠를 한 번 살짝 밀었다 돌려놓는다(모션 줄이기면 끔).
 * 카드 안쪽 여백을 넘어 카드 끝까지 닿게 그린다([bleed]).
 * 한 달 띠(알약에 [StripDay.kind]가 있을 때, Task 8.2.99)는 예상은 테두리만, 데이터 없음 · 범위 밖은 빗금, 오늘은 굵은 테두리로 보이고
 * 양 끝에 [leading] · [trailing] 칸('‹ 10월 보기')을 둔다. 띠를 끝까지 밀어도 달은 넘어가지 않는다
 */
@Composable
internal fun DateStrip(
    days: List<StripDay>,
    selected: Int,
    source: DaySource,
    onSelect: (Int, DaySource) -> Unit,
    modifier: Modifier = Modifier,
    income: LocalDate? = null,
    hint: Boolean = false,
    bleed: Dp = 0.dp,
    onScrollingChange: (Boolean) -> Unit = {},
    leading: StripJump? = null,
    trailing: StripJump? = null,
) {
    if (days.isEmpty()) return
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    // 앞 칸이 있으면 날 알약의 목록 번호가 하나씩 밀린다 — 띠 안에서 날 번호와 목록 번호를 바꿀 때 이만큼 더하고 뺀다
    val lead = if (leading != null) 1 else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, days.lastIndex) + lead)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnScrolling by rememberUpdatedState(onScrollingChange)

    // 손으로 민 스크롤만 고른 날을 바꾼다 — 다른 곳을 따라 굴러가는 스크롤(코드가 부른 것)은 끌기가 없어 걸러진다
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolling = true }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { moving ->
            if (!moving) userScrolling = false
        }
    }
    LaunchedEffect(userScrolling) { latestOnScrolling(userScrolling) }

    // 가운데에 가장 가까운 날 알약(월급날 · 달 넘기기 칸은 빼고) — 날 번호로 돌려준다
    val centered by remember(days.size, lead) {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.filter { it.key is Long }.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.let { it.index - lead }
        }
    }
    LaunchedEffect(state, days.size) {
        snapshotFlow { centered }.distinctUntilChanged().collect { i -> if (userScrolling && i != null) latestOnSelect(i, DaySource.STRIP) }
    }
    // 다른 곳(그래프 · 칸 · 목록 · 알약 누르기)에서 바꾼 날을 가운데로. 그래프를 끄는 동안은 손가락에 늦지 않게 바로 옮긴다
    LaunchedEffect(selected, source) {
        if (!stripFollows(source) || userScrolling) return@LaunchedEffect
        if (centered == selected) return@LaunchedEffect
        if (stripGlides(source) && !reduce) state.animateScrollToItem(selected + lead) else state.scrollToItem(selected + lead)
    }

    // 첫 안내 — 띠를 왼쪽으로 살짝 밀었다가 돌려놓는다
    val peek = remember { Animatable(0f) }
    LaunchedEffect(hint) {
        if (!hint || reduce) { peek.snapTo(0f); return@LaunchedEffect }
        delay(PEEK_DELAY_MS)
        peek.animateTo(0f, keyframes {
            durationMillis = PEEK_MS
            -1f at PEEK_MS * 35 / 100
            0.2f at PEEK_MS * 70 / 100
        })
    }

    BoxWithConstraints(
        modifier
            .layout { m, cons ->
                // 카드 안쪽 여백을 넘어 카드 끝까지 — 양 끝 알약이 카드 가장자리에서 잘려 보이게
                val extra = bleed.roundToPx()
                val p = m.measure(cons.copy(minWidth = cons.maxWidth + 2 * extra, maxWidth = cons.maxWidth + 2 * extra))
                layout(cons.maxWidth, p.height) { p.place(-extra, 0) }
            }
            // 양 끝을 흐리게 — 잘린 알약이 '더 있다'는 단서가 되게
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val fade = (EDGE_FADE.toPx() / size.width).coerceIn(0f, 0.5f)
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val side = ((maxWidth - PILL_W) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center),
            contentPadding = PaddingValues(horizontal = side, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(PILL_GAP),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = peek.value * PEEK_DX.toPx() }
                .semantics { contentDescription = "날짜 띠, 옆으로 밀어 날짜 바꾸기" },
        ) {
            leading?.let { j -> item(key = "lead") { JumpPill(j) } }
            itemsIndexed(days, key = { _, d -> d.date.toEpochDay() }) { i, d ->
                DatePill(d, index = i, selected = i == selected, onClick = { latestOnSelect(i, DaySource.PILL) })
            }
            if (income != null) item(key = "income") { IncomePill(income) }
            trailing?.let { j -> item(key = "trail") { JumpPill(j) } }
        }
    }
}

@Composable
private fun DatePill(day: StripDay, index: Int, selected: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val kind = day.kind
    // 한 달 띠의 오늘은 굵은 검은 테(0원 아래 빨간 테가 먼저)
    val ring = when (day.zone) {
        BalanceZone.BELOW_ZERO -> c.error
        BalanceZone.BELOW_SAFE -> c.warning
        BalanceZone.OK -> null
    } ?: c.text1.takeIf { kind == DayKind.TODAY && !selected }
    val shape = RoundedCornerShape(16.dp)
    val hatched = kind?.hatched == true && !selected
    // 예상은 바탕 없이 테두리만, 빗금 날은 바탕 대신 빗금(아래 drawBehind)
    val bg = when {
        selected -> c.primary
        kind == DayKind.EXPECTED || hatched -> Color.Transparent
        else -> c.surfaceVariant
    }
    val main = when {
        selected -> c.onPrimary
        hatched -> c.text3
        else -> c.text1
    }
    val sub = when {
        selected -> c.onPrimary.copy(alpha = 0.82f)
        kind == null && index == 0 -> c.primary
        kind == DayKind.TODAY -> c.text1
        else -> c.text3
    }
    val hatchInk = c.text3.copy(alpha = if (kind == DayKind.NO_DATA) HATCH_STRONG else HATCH_LIGHT)
    Box(
        Modifier
            .size(PILL_W, PILL_H)
            .then(
                when {
                    ring != null -> Modifier.border(if (selected) 2.5.dp else if (kind == DayKind.TODAY) 2.dp else 1.5.dp, ring, shape)
                    kind == DayKind.EXPECTED && !selected -> Modifier.border(1.dp, c.outline, shape)
                    else -> Modifier
                },
            )
            .padding(if (ring != null && selected) 4.dp else 0.dp)
            .clip(shape)
            .background(bg)
            .then(if (hatched) Modifier.drawBehind { drawHatch(Offset.Zero, size, hatchInk) } else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = pillDescription(day)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (kind != null) monthPillTopLabel(day) else pillTopLabel(day.date, index), style = YamuTheme.typography.labelS.copy(fontSize = 10.5.sp, lineHeight = 13.sp), color = sub)
            Text("${day.date.dayOfMonth}", style = YamuTheme.typography.value.copy(fontSize = 17.sp, lineHeight = 20.sp), color = main)
            Row(Modifier.height(13.dp), verticalAlignment = Alignment.CenterVertically) {
                pillAmount(day.net)?.let { amount ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c.coinFace).border(1.dp, c.coinLine, CircleShape))
                    Spacer(Modifier.width(2.dp))
                    Text(amount, style = YamuTheme.typography.labelS.copy(fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = (-0.2).sp), color = if (selected) c.onPrimary else if (day.net > 0) c.primary else c.text1, maxLines = 1)
                }
            }
        }
    }
}

/** 한 달 띠 끝 '‹ 10월 보기' 칸 — 점선 테, 누르면 그 달로 */
@Composable
private fun JumpPill(jump: StripJump) {
    val c = YamuTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val line = c.outline
    Box(
        Modifier
            .height(PILL_H)
            .widthIn(min = PILL_W)
            .clip(shape)
            .drawBehind {
                drawRoundRect(
                    line, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClickLabel = jump.description, onClick = jump.onClick)
            .semantics { contentDescription = jump.description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(jump.label, style = YamuTheme.typography.labelM, color = c.text2, maxLines = 1)
    }
}

/** 끝에 붙는 월급날 알약 — 고를 수 없고(예보 기간 밖) 어디까지 버티면 되는지만 보여 준다 */
@Composable
private fun IncomePill(date: LocalDate) {
    val c = YamuTheme.colors
    Box(
        Modifier
            .size(PILL_W, PILL_H)
            .clip(RoundedCornerShape(16.dp))
            .background(c.primaryContainer)
            .semantics(mergeDescendants = true) { contentDescription = "${date.toKoreanDateWeek()}, 월급날" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("월급", style = YamuTheme.typography.labelS.copy(fontSize = 10.5.sp, lineHeight = 13.sp), color = c.onPrimaryContainer)
            Text("${date.dayOfMonth}", style = YamuTheme.typography.value.copy(fontSize = 17.sp, lineHeight = 20.sp), color = c.onPrimaryContainer)
            Text("입금", style = YamuTheme.typography.labelS.copy(fontSize = 9.5.sp, lineHeight = 13.sp), color = c.primary, textAlign = TextAlign.Center)
        }
    }
}

private val PILL_W = 50.dp
private val PILL_H = 68.dp
private val PILL_GAP = 6.dp
private val EDGE_FADE = 30.dp
private val PEEK_DX = 26.dp
private const val PEEK_DELAY_MS = 1_300L   // 그래프 들어오기 · 손잡이 흔들기와 겹치지 않게 조금 늦게
private const val PEEK_MS = 1_150
