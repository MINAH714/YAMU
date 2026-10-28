package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme

/** 탭 화면용 흰 카드 — 반경 20 · 안쪽 20 · 그림자 없음 */
@Composable
fun YamuCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(YamuSpacing.x5),   // 줄을 카드 끝까지 긋는 목록 카드는 좌우 0(H-03)
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = YamuSpacing.screen)
            .padding(bottom = YamuSpacing.blockGap)
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.card))
            .background(YamuTheme.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 흰 화면의 덩어리 — 위에 화면 폭 전체 1dp 선 + 안쪽 위 24 · 아래 20, 제목은 16sp SemiBold 검정(프로토타입 .section).
 * 첫 덩어리는 [divider] = false.
 */
@Composable
fun SectionBlock(
    modifier: Modifier = Modifier,
    title: String? = null,
    divider: Boolean = true,
    top: Dp = YamuSpacing.x6,      // 기본 24/20(.section) — '이거 사도 될까?' 결과는 16/16(.sec)
    bottom: Dp = YamuSpacing.x5,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(thickness = 1.dp, color = YamuTheme.colors.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = top, bottom = bottom)) {
            if (title != null) {
                Text(title, style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
                Spacer(Modifier.height(YamuSpacing.x2))
            }
            content()
        }
    }
}

/** 흐름(흰) 화면용 섹션 */
@Composable
fun YamuSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(YamuTheme.colors.surface)
            .padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x6, bottom = YamuSpacing.x5),
        content = content,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = if (large) YamuTheme.typography.titleL else YamuTheme.typography.titleM,
            color = YamuTheme.colors.text1,
            modifier = Modifier.weight(1f),
        )
        action?.invoke(this)
    }
}

/** 앱바 아래 단계 막대 — 가입 3단계는 이름까지('1/3 약관 동의'), 연결 2단계는 막대만 */
data class FlowStep(val current: Int, val total: Int, val label: String? = null)

/**
 * 등록 · 결제 · 설정 · 입력 같은 흐름 화면 — 흰 바탕.
 * [cards] = 회색 바탕 위 흰 카드(YamuCard)로 묶는 상세 화면(프로토타입 F-02 · H-03 · S-04 · S-05처럼 .scr에 .card).
 */
@Composable
fun FlowScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    closeIcon: Boolean = false,
    cards: Boolean = false,
    step: FlowStep? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = if (cards) YamuTheme.colors.background else YamuTheme.colors.surface
    Scaffold(
        modifier = modifier,
        topBar = {
            Column(Modifier.background(background)) {
                YamuTopBar(title, onBack = onBack, closeIcon = closeIcon, containerColor = background, actions = actions)
                step?.let { StartStepper(it.current, it.total, label = it.label) }
            }
        },
        bottomBar = bottomBar,
        containerColor = background,
        content = content,
    )
}

/** 탭 첫 화면 — 회색 바탕 위 흰 카드. [largeTitle] = 큰 제목 앱바(112dp, 프로토타입 M-01 .appbar.medium) */
@Composable
fun TabScreen(
    title: String,
    modifier: Modifier = Modifier,
    largeTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            if (largeTitle) YamuLargeTopBar(title, containerColor = YamuTheme.colors.background, actions = actions)
            else YamuTopBar(title, containerColor = YamuTheme.colors.background, actions = actions)
        },
        containerColor = YamuTheme.colors.background,
        content = content,
    )
}
