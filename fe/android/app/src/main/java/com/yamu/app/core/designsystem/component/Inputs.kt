package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuTheme

/** 토스형 스위치 — 켜짐 브랜드 색 · 꺼짐 회색 · 흰 원 · 아이콘 없음 */
@Composable
fun YamuSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = YamuTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {},   // 빈 내용이라도 있으면 꺼짐 상태 손잡이도 24dp(M3 기본 16dp) — 프로토타입 .switch::after 24dp
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = c.outline,
            uncheckedBorderColor = Color.Transparent,
            // 못 켜는 스위치(지문 없는 기기 등)도 같은 모양을 흐리게 — M3 기본(흰 트랙 · 테두리)을 쓰지 않는다
            disabledCheckedThumbColor = Color.White,
            disabledCheckedTrackColor = c.primary.copy(alpha = 0.38f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = Color.White,
            disabledUncheckedTrackColor = c.outline.copy(alpha = 0.38f),
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** 둥근 체크 — 약관 · 선택 목록. onCheckedChange를 null로 두고 줄 전체를 누르게 해도 된다 */
@Composable
fun RoundCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    partial: Boolean = false,          // 일부만 골랐을 때(모두 동의) — 파란 원 + 가로줄
) {
    val c = YamuTheme.colors
    Box(
        modifier
            .padding(1.dp)   // 둘레 1dp — 자리 24dp(프로토타입 .checkbox margin 1)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked || partial) c.primary else c.outlineVariant)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (partial && !checked) Box(Modifier.size(width = 10.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White))
        else Icon(YamuIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** 분할 버튼 — 2~3개 */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = YamuTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(YamuSize.segmented)
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.surfaceVariant)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) c.surface else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = YamuTheme.typography.labelL, color = if (selected) c.text1 else c.text3, maxLines = 1)
            }
        }
    }
}

/** 화면 안 탭 — 선택 = 1단계 글자 Bold + 2dp 밑줄 */
@Composable
fun YamuTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = YamuTheme.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(YamuSize.tabs)) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        label,
                        style = YamuTheme.typography.titleS.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = if (selected) c.text1 else c.text3,
                        modifier = Modifier.padding(bottom = 12.dp),
                        maxLines = 1,
                    )
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp).background(if (selected) c.text1 else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
    }
}

enum class KeypadMode { Pin, Amount }

sealed interface KeypadKey {
    data class Digit(val value: Int) : KeypadKey
    data object DoubleZero : KeypadKey
    data object Delete : KeypadKey
}

/**
 * 숫자 키패드 — 시스템 키보드 대신.
 * [KeypadMode.Pin]은 숫자 배열을 [shuffleKey]가 바뀔 때마다 섞는다(보안 키패드). [KeypadMode.Amount]는 00 · 지우기.
 */
@Composable
fun NumberKeypad(
    mode: KeypadMode,
    onKey: (KeypadKey) -> Unit,
    modifier: Modifier = Modifier,
    shuffleKey: Int = 0,
    background: Color = YamuTheme.colors.surface,
    topDivider: Boolean = false,     // 회색 키패드(A-06)는 위에 1dp 선
) {
    var reshuffle by remember { mutableIntStateOf(0) }   // 왼쪽 아래 재배열(보안 키패드만)
    val digits = remember(mode, shuffleKey, reshuffle) {
        if (mode == KeypadMode.Pin) (0..9).shuffled() else listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
    }
    val rows: List<List<KeypadKey?>> = listOf(
        digits.subList(0, 3).map { KeypadKey.Digit(it) },
        digits.subList(3, 6).map { KeypadKey.Digit(it) },
        digits.subList(6, 9).map { KeypadKey.Digit(it) },
        listOf(if (mode == KeypadMode.Amount) KeypadKey.DoubleZero else null, KeypadKey.Digit(digits[9]), KeypadKey.Delete),
    )
    val c = YamuTheme.colors
    val digitStyle = YamuTheme.typography.amountM.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    Column(modifier.fillMaxWidth().background(background).windowInsetsPadding(WindowInsets.navigationBars)) {
        if (topDivider) HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    val shuffleSlot = key == null && mode == KeypadMode.Pin
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .then(
                                when {
                                    key != null -> Modifier.clickable { onKey(key) }
                                    shuffleSlot -> Modifier.clickable(onClickLabel = "숫자 다시 섞기") { reshuffle++ }
                                    else -> Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            is KeypadKey.Digit -> Text("${key.value}", style = digitStyle, color = c.text1)
                            KeypadKey.DoubleZero -> Text("00", style = digitStyle, color = c.text1)
                            KeypadKey.Delete -> Icon(YamuIcons.Backspace, contentDescription = "지우기", tint = c.text1, modifier = Modifier.size(24.dp))
                            null -> if (shuffleSlot) Text("재배열", style = YamuTheme.typography.labelL, color = c.text2)
                        }
                    }
                }
            }
        }
    }
}
