package com.yamu.app.core.designsystem.component

import androidx.compose.ui.semantics.heading
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.yamu.app.core.designsystem.theme.LocalYamuReduceMotion
import com.yamu.app.core.designsystem.theme.YamuMotion
import kotlin.math.asin
import kotlin.math.sqrt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.R
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme

/** 앱바 — 56dp · 제목 18 SemiBold 한 줄. 뒤로(←)는 onBack이 있을 때만, 오른쪽 행동은 2개까지 */
@Composable
fun YamuTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    containerColor: Color = YamuTheme.colors.surface,
    closeIcon: Boolean = false,          // 결제처럼 '닫는' 화면은 ✕(P-06 · P-07)
    logo: TopBarLogo? = null,            // 제목 대신 YAMU 로고(홈 · 시작 화면 왼쪽, 잠금 해제 가운데)
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = YamuTheme.colors
    if (logo == TopBarLogo.Center) {
        Box(
            modifier.fillMaxWidth().background(containerColor).windowInsetsPadding(WindowInsets.statusBars).height(YamuSize.appBar),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.yamu_logo), contentDescription = "YAMU", modifier = Modifier.height(16.dp).semantics { heading() })
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(containerColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(YamuSize.appBar)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                if (closeIcon) Icon(YamuIcons.Close, contentDescription = "닫기", tint = c.text1)
                else Icon(YamuIcons.ArrowBack, contentDescription = "뒤로", tint = c.text1)
            }
        } else {
            Spacer(Modifier.width(8.dp))   // 제목이 카드 끝(16dp)과 맞게
        }
        if (logo == TopBarLogo.Start) {
            Image(painterResource(R.drawable.yamu_logo), contentDescription = title, modifier = Modifier.padding(start = 4.dp).height(20.dp))
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                title,
                style = YamuTheme.typography.appBar,
                color = c.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (onBack != null) 8.dp else 4.dp),
            )
        }
        actions()
    }
}

/** 앱바 로고 자리 — 왼쪽(높이 20dp, 홈 · 시작) · 가운데(높이 16dp, 잠금 해제) */
enum class TopBarLogo { Start, Center }

/** 큰 제목 앱바 — 112dp: 위 56dp에 오른쪽 아이콘, 아래에 24sp 제목(탭 첫 화면 · 프로토타입 .appbar.medium) */
@Composable
fun YamuLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    containerColor: Color = YamuTheme.colors.surface,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(containerColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(YamuSize.appBarLarge),
    ) {
        Row(
            Modifier.fillMaxWidth().height(YamuSize.appBar).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            content = actions,
        )
        Text(
            title,
            style = YamuTheme.typography.headlineS.copy(lineHeight = 32.sp),
            color = YamuTheme.colors.text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 아이콘 줄(56) 아래 12 — 프로토타입 .appbar.medium(위 8 + 아이콘 48 + 제목 위 8)과 제목 자리가 같다
            modifier = Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 12.dp),
        )
    }
}

/** 앱바 알림 종 — 안 읽은 알림 수 배지. 수는 서버 값 그대로 */
@Composable
fun NotificationBell(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
            Icon(YamuIcons.Notifications, contentDescription = "알림 ${unread}개", tint = YamuTheme.colors.text1)
        }
    }
}

/** [selectedIcon] = 고른 탭 아이콘(속을 채운 모양) — 없으면 [icon] 그대로 */
data class BottomBarItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** 하단 막대 가운데 버튼 — 탭이 아니라 화면을 여는 행동(결제 코드). 고른 표시를 두지 않는다 */
data class BottomBarCenter(val label: String, val icon: ImageVector, val description: String, val onClick: () -> Unit)

/** 가운데 원이 막대 위로 올라온 높이 — 막대 바로 위에 뜨는 것(스낵바)은 이만큼 더 올린다 */
val BottomBarCenterRise = 25.dp

private val CenterSize = 54.dp
private val NotchRadius = 33.dp
private val NotchCenterY = 2.dp   // 원 중심 = 막대 위 선에서 2dp 아래(CenterSize / 2 - BottomBarCenterRise)

/** 칸 순서 — 탭 번호, 가운데 버튼 자리는 null. 탭 넷이면 [0, 1, null, 2, 3] */
internal fun bottomBarSlots(tabCount: Int, hasCenter: Boolean): List<Int?> {
    if (!hasCenter) return List(tabCount) { it }
    val left = (tabCount + 1) / 2
    return List(left) { it } + listOf(null) + List(tabCount - left) { left + it }
}

/**
 * 하단 탭 — 64dp. 고른 탭은 아이콘을 메인 색 · 채운 모양으로 바꾸고 이름 밑에 물결(~)을 한 번 그린다(색 말고 모양으로도 구분).
 * 이름 글자는 고르면 글자 1단계 Bold, 아니면 3단계(작은 글자에 메인 색을 쓰면 대비가 모자라서).
 * [center]가 있으면 가운데를 반원으로 파고 그 위에 원 버튼을 얹는다(S15P31E103-478, 11/13 시안 '파인 홈 위의 원').
 * 원은 막대 위로 [BottomBarCenterRise]만큼 올라와 화면 내용을 조금 덮는다 — 부모가 자르지 않으니 그 자리도 눌린다.
 */
@Composable
fun YamuBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    center: BottomBarCenter? = null,
) {
    val c = YamuTheme.colors
    val reduceMotion = LocalYamuReduceMotion.current
    Column(
        modifier
            .fillMaxWidth()
            .barBackground(notch = center != null, fill = c.surface, line = c.outlineVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(YamuSize.bottomBar),
        ) {
            bottomBarSlots(items.size, center != null).forEach { slot ->
                if (slot == null) {
                    center?.let { CenterSlot(it, reduceMotion, Modifier.weight(1f)) }
                } else {
                    TabSlot(items[slot], slot == selectedIndex, reduceMotion, onClick = { onSelect(slot) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TabSlot(item: BottomBarItem, selected: Boolean, reduceMotion: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = YamuTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(Modifier.size(width = 48.dp, height = 28.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = if (selected) c.primary else c.text3,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            item.label,
            style = YamuTheme.typography.labelS.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) c.text1 else c.text3,
            maxLines = 1,
        )
        if (selected) TabWave(reduceMotion)
    }
}

/** 고른 탭 이름 밑 물결 — 수달 가방 무늬. 고를 때 한 번 그려지고(280ms), 모션 줄이기면 바로 다 보인다 */
@Composable
private fun TabWave(reduceMotion: Boolean) {
    val color = YamuTheme.colors.primary
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduceMotion) progress.animateTo(1f, tween(280, easing = YamuMotion.standard)) }
    val wave = remember { PathParser().parsePathString(TAB_WAVE).toPath() }
    Canvas(Modifier.padding(top = 1.dp).size(width = 15.dp, height = 6.dp)) {
        val scale = size.width / 15f
        val scaled = Path().apply { addPath(wave); transform(Matrix().apply { scale(scale, scale) }) }
        val measure = PathMeasure().apply { setPath(scaled, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * progress.value, part, true)
        drawPath(part, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

private const val TAB_WAVE = "M1.5 3.6c1.7-2.3 3.3-2.3 4.9 0s3.3 2.3 4.9 0 1.6-1.6 2.2-1.2"

/** 가운데 버튼 칸 — 원은 막대 위로 올라오고 이름은 다른 탭 이름과 같은 줄. 칸 전체가 버튼 하나로 읽힌다 */
@Composable
private fun CenterSlot(center: BottomBarCenter, reduceMotion: Boolean, modifier: Modifier) {
    val c = YamuTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.94f else 1f, tween(YamuMotion.SHORT_MS), label = "payPress")
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = center.description, onClick = center.onClick)
            .semantics(mergeDescendants = true) { contentDescription = center.description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .riseAbove(BottomBarCenterRise)
                .size(CenterSize)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(6.dp, CircleShape, ambientColor = c.primary, spotColor = c.primary)
                .clip(CircleShape)
                .background(c.primary)
                // 막대 위로 올라온 부분은 칸(Column) 밖이라 칸의 클릭이 못 받는다 — 원에도 누르기를 단다.
                // clickable 대신 pointerInput이라 화면 읽기에는 칸 하나만 버튼으로 남는다
                .pointerInput(center.onClick) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            interaction.emit(press)
                            interaction.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        },
                        onTap = { center.onClick() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(center.icon, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(center.label, style = YamuTheme.typography.labelS.copy(fontWeight = FontWeight.Bold), color = c.text1, maxLines = 1)
    }
}

/** 그린 자리는 [rise]만큼 위로, 차지하는 높이는 그만큼 줄인다 — 아래 이름이 다른 탭 이름과 같은 줄에 온다 */
private fun Modifier.riseAbove(rise: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val risePx = rise.roundToPx()
    layout(placeable.width, (placeable.height - risePx).coerceAtLeast(0)) { placeable.place(0, -risePx) }
}

/** 막대 바탕과 위 선. [notch]면 가운데를 반원으로 파고 선도 그 가장자리를 따라 돈다 */
private fun Modifier.barBackground(notch: Boolean, fill: Color, line: Color) = drawBehind {
    val stroke = 1.dp.toPx()
    val top = stroke / 2
    if (!notch) {
        drawRect(fill)
        drawLine(line, Offset(0f, top), Offset(size.width, top), stroke)
        return@drawBehind
    }
    val r = NotchRadius.toPx()
    val cy = NotchCenterY.toPx()
    val cx = size.width / 2
    val bar = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
    val hole = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
    drawPath(Path().apply { op(bar, hole, PathOperation.Difference) }, fill)
    val dx = sqrt(r * r - cy * cy)   // 원이 위 선과 만나는 곳
    drawLine(line, Offset(0f, top), Offset(cx - dx, top), stroke)
    drawLine(line, Offset(cx + dx, top), Offset(size.width, top), stroke)
    val lift = Math.toDegrees(asin((cy / r).toDouble())).toFloat()
    drawArc(line, startAngle = -lift, sweepAngle = 180f + 2 * lift, useCenter = false, topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r), style = Stroke(stroke))
}
