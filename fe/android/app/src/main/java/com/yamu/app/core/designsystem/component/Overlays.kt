package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme

/** 바텀시트 — 위 반경 24 · 손잡이. 화면에 들어오자마자 띄우지 않기 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YamuBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = YamuTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),   // 반만 열려 아래 버튼이 가려지지 않게
        shape = RoundedCornerShape(topStart = YamuRadius.sheet, topEnd = YamuRadius.sheet),
        containerColor = c.surface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(c.outline),
            )
        },
    ) {
        Column(Modifier.padding(horizontal = YamuSpacing.screen).padding(bottom = YamuSpacing.x6), content = content)
    }
}

/** 확인 다이얼로그 — 왼쪽(닫는) 버튼은 '취소' 대신 '닫기' */
@Composable
fun YamuDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    dismissText: String = "닫기",
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText, style = t.labelL, color = c.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText, style = t.labelL, color = c.text2) } },
        title = { Text(title, style = t.titleL, color = c.text1) },
        text = if (text != null) {
            { Text(text, style = t.bodyM, color = c.text2) }
        } else null,
        shape = RoundedCornerShape(YamuRadius.card),
        containerColor = c.surface,
    )
}

/** 스낵바 — 반경 12 · 오른쪽 '실행 취소'. SnackbarHost(state) { YamuSnackbar(it) } */
@Composable
fun YamuSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val c = YamuTheme.colors
    Snackbar(
        snackbarData = data,
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(YamuRadius.input),
        containerColor = c.inverseSurface,
        contentColor = c.inverseOnSurface,
        actionColor = c.inversePrimary,
    )
}
