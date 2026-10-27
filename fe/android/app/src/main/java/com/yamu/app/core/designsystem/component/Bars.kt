package com.yamu.app.core.designsystem.component

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            Text("YAMU", style = YamuTheme.typography.logo.copy(fontSize = 18.sp, lineHeight = 18.sp), color = c.primary, modifier = Modifier.semantics { heading() })
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
            Text("YAMU", style = YamuTheme.typography.logo, color = c.primary, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { contentDescription = title })
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

/** 앱바 로고 자리 — 왼쪽(22sp, 홈 · 시작) · 가운데(18sp, 잠금 해제) */
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

/** 앱바 알림 종 — 안 읽은 알림 수 배지(홈 · 카드추천). 수는 서버 값 그대로 */
@Composable
fun NotificationBell(unread: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
            Icon(YamuIcons.Notifications, contentDescription = "알림 ${unread}개", tint = YamuTheme.colors.text1)
        }
    }
}

/** [selectedIcon] = 고른 탭 아이콘(채운 모양, 프로토타입 .nav-item.on) — 없으면 [icon] 그대로 */
data class BottomBarItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** 하단 탭 — 64dp · 5칸 · 선택 = 글자 1단계 Bold, 나머지 = 3단계. 알약 표시 없음 */
@Composable
fun YamuBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = YamuTheme.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(YamuSize.bottomBar),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint = if (selected) c.text1 else c.text3
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = selected }
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Box(Modifier.size(width = 48.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                        Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        style = YamuTheme.typography.labelS.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
