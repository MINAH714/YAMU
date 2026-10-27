package com.yamu.app.core.designsystem.component

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme

/**
 * 목록 한 줄 — 제목 · 보조 · 값 · › (한 줄에 하나).
 * [valuePlain]은 설정 줄의 회색 값('오전 8:30'), [value]는 굵은 금액.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    lead: (@Composable () -> Unit)? = null,
    value: String? = null,
    valueSub: String? = null,
    valuePlain: String? = null,
    valueColor: Color = Color.Unspecified,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    titleTag: (@Composable () -> Unit)? = null,   // 제목 바로 뒤 태그('기본' · '임박' · '이 기기')
    minHeight: Dp? = null,
    dense: Boolean = false,              // 촘촘한 두 줄 56dp · 보조 12sp · 값 600(F-01 예정된 돈 · 계획)
    onClick: (() -> Unit)? = null,
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight ?: if (dense || (sub == null && valueSub == null)) YamuSize.listRowOne else YamuSize.listRowMin)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x4),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f)) {
            if (titleTag != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = t.titleM, color = c.text1, modifier = Modifier.weight(1f, fill = false))
                    titleTag()
                }
            } else {
                Text(title, style = t.titleM, color = c.text1)
            }
            if (sub != null) Text(sub, style = if (dense) t.bodyS else t.sub, color = c.text3)
        }
        // 값 · 태그 · ›는 가깝게(4dp) — 프로토타입 .trail
        if (value != null || valuePlain != null || trailing != null || chevron) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (value != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(value, style = if (dense) t.titleM else t.value, color = if (valueColor == Color.Unspecified) c.text1 else valueColor)
                        if (valueSub != null) Text(valueSub, style = t.bodyS, color = c.text3)
                    }
                }
                if (valuePlain != null) Text(valuePlain, style = t.bodyM, color = c.text3)
                trailing?.invoke()
                if (chevron) Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
            }
        }
    }
}

/**
 * 키–값 줄 — '결제일 · 매월 14일', '실적 기준 · 400,000원'. 키는 회색 보통, 값은 검정 굵게(프로토타입 .kv).
 * [large] 16sp · 44dp(분석 근거 · 결제 완료) / 14sp · 40dp(거래 상세 · 계획 저장), [minHeight]로 바꿀 수 있다.
 * [keySub] = 키 아래 작은 회색 줄(결제 완료의 실적 줄). [keyWidth]를 주면 14sp 키 열 + 16dp 뒤에 16sp 값이 왼쪽에 붙고 [trailing]은 줄 끝(회원 정보).
 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    minHeight: Dp = if (large) 44.dp else 40.dp,
    keyWidth: Dp? = null,
    keyTag: (@Composable () -> Unit)? = null,
    keySub: String? = null,
    valueLead: (@Composable () -> Unit)? = null,
    valueColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val fixedKey = keyWidth != null
    val keyStyle = if (large && !fixedKey) t.bodyL else t.bodyM
    val valueStyle = when {
        fixedKey -> t.bodyL.copy(fontWeight = FontWeight.Medium)
        large -> t.titleM
        else -> t.titleS
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (fixedKey) 16.dp else 6.dp),
    ) {
        Column(if (keyWidth != null) Modifier.width(keyWidth) else Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(key, style = keyStyle, color = c.text2)
                keyTag?.invoke()
            }
            keySub?.let { Text(it, style = t.sub, color = c.text3) }
        }
        Row(
            if (fixedKey) Modifier.weight(1f) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            valueLead?.invoke()
            Text(
                value,
                style = valueStyle,
                color = if (valueColor == Color.Unspecified) c.text1 else valueColor,
                textAlign = if (fixedKey) TextAlign.Start else TextAlign.End,
                // 값 열이 남은 폭을 다 써야 trailing(자물쇠 · 로그아웃)이 줄 끝에 선다
                modifier = if (fixedKey) Modifier.weight(1f) else Modifier,
            )
            trailing?.invoke()
        }
    }
}

/** 목록 앞 아이콘 — 40dp 회색 원. 색은 상태를 말할 때만 */
@Composable
fun ListLeadIcon(icon: ImageVector, tint: Color = YamuTheme.colors.text3) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(YamuTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@YamuPreviews
@Composable
private fun ListRowPreview() = YamuTheme {
    Column(Modifier.background(YamuTheme.colors.surface).padding(horizontal = 16.dp)) {
        ListRow("하나카드 대금", sub = "10/20 (화)", value = "−150,000원")
        ListRow("받는 시각", valuePlain = "오전 8:30", chevron = true, onClick = {})
    }
}
