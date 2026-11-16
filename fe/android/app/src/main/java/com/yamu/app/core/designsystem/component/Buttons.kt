package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme

/** 주 버튼 — 화면에 1개, 글자는 누르면 일어날 일("신한카드로 15,000원 결제") */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,           // 지문으로 서명 · 연결할 때 fingerprint(C-02c · C-12)
) {
    val c = YamuTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(YamuSize.button),
        shape = RoundedCornerShape(YamuRadius.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary,
            contentColor = c.onPrimary,
            // 불러오는 중에는 누를 수 없지만 색은 그대로(회색이면 진행 표시가 안 보임)
            disabledContainerColor = if (loading) c.primary else c.surfaceHigh,
            disabledContentColor = if (loading) c.onPrimary else c.text3,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.onPrimary, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = YamuTheme.typography.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 보조 버튼 — 회색 바탕 */
@Composable
fun NeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = YamuTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(YamuSize.button),
        shape = RoundedCornerShape(YamuRadius.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.surfaceVariant,
            contentColor = c.text2,
            disabledContainerColor = c.surfaceVariant,
            disabledContentColor = c.text3,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text, style = YamuTheme.typography.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 회색 글자 버튼 — '나중에 할게요' · '닫기' */
@Composable
fun TextButtonGrey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    small: Boolean = false,   // 14sp(프로토타입 .btn.text.md — 가입 완료 '나중에 할게요' · 계획 지우기)
) {
    val c = YamuTheme.colors
    TextButton(onClick = onClick, modifier = modifier.height(YamuSize.touch)) {
        Text(text, style = if (small) YamuTheme.typography.labelL else YamuTheme.typography.button, color = if (muted) c.text3 else c.text2)
    }
}

/** 파란 글자 버튼 — '자세히 비교' 같은 보조 이동 */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.height(YamuSize.touch)) {
        Text(text, style = YamuTheme.typography.labelL, color = YamuTheme.colors.primary)
    }
}

/**
 * 글자 크기만큼의 회색 버튼 — 48dp(반경 12) · 36dp(반경 10). 앞 아이콘 선택(프로토타입 .btn.md · .btn.sm).
 * 작은 버튼도 터치 칸은 48dp — 보이는 36dp 위아래로 6dp씩 넘친다(S15P31E103-513)
 */
@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    small: Boolean = false,
    horizontalPadding: Dp = if (small) 14.dp else 16.dp,
) {
    val c = YamuTheme.colors
    val height = if (small) 36.dp else YamuSize.touch
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .overflowTouch((YamuSize.touch - height) / 2, Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick))
            .height(height)
            .clip(RoundedCornerShape(if (small) 10.dp else 12.dp))
            .background(c.surfaceVariant)
            .indication(interaction, LocalIndication.current)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
        Text(text, style = YamuTheme.typography.labelL, color = c.text2, maxLines = 1)
    }
}

/** 조용한 글자 버튼 — 13sp Medium 회색, 터치 48dp(프로토타입 .btn.link: 비밀번호 찾기 · 자세히 보기) */
@Composable
fun QuietLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = YamuTheme.typography.link,
        color = YamuTheme.colors.text3,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(YamuRadius.s))
            .clickable(role = Role.Button, onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 4.dp),
    )
}

/** 흐름 화면 아래 고정 버튼 자리 — Scaffold(bottomBar = { BottomCta { PrimaryButton(…) } }) */
@Composable
fun BottomCta(
    modifier: Modifier = Modifier,
    insets: Boolean = true,          // 아래에 키패드가 붙으면 false(키패드가 제스처 여백을 맡음)
    secondary: (@Composable () -> Unit)? = null,
    primary: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(YamuTheme.colors.surface)
            .then(if (insets) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
            .padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x3, bottom = YamuSpacing.x2),   // 프로토타입 .bottom 12 · 8
    ) {
        primary()
        if (secondary != null) {
            Spacer(Modifier.height(YamuSpacing.x2))
            secondary()
        }
    }
}

@YamuPreviews
@Composable
private fun ButtonsPreview() = YamuTheme {
    Column(Modifier.background(YamuTheme.colors.surface).padding(16.dp)) {
        PrimaryButton("신한카드로 15,000원 결제", onClick = {})
        Spacer(Modifier.height(8.dp))
        NeutralButton("다른 카드로", onClick = {})
        TextButtonGrey("나중에 할게요", onClick = {})
        LinkButton("3개월 할부 · 다른 날과 비교", onClick = {})
    }
}
