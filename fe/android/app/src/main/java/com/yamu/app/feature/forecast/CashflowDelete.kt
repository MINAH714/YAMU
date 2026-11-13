package com.yamu.app.feature.forecast

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CashflowEntry

// 예정 수입·지출 지우기 — 수정 화면(F-02b) 맨 아래 '삭제'와 F-02 줄 끝 휴지통이 같은 문장 · 규칙을 쓴다(Task 8.2.73)

internal const val DELETE_SCOPE_TITLE = "어디까지 지울까요?"

/** 반복 항목 — 이번 회차만(그 회차 날짜를 보낸다) · 앞으로 전부(서버 ALL은 오늘부터 앞으로 전부) */
@Composable
internal fun DeleteScopeRows(entry: CashflowEntry, onPick: (CashflowDeleteScope) -> Unit) {
    ListRow("이번 회차만", sub = "${entry.date.toKoreanDate()} 한 번만 지워요", chevron = true, onClick = { onPick(CashflowDeleteScope.THIS_ONLY) })
    ListRow("앞으로 전부", sub = "오늘부터 이 반복을 모두 지워요", chevron = true, onClick = { onPick(CashflowDeleteScope.ALL) })
}

/** F-02 휴지통 — 반복 항목은 범위 시트, 1회 항목은 [DeleteOnceDialog] */
@Composable
internal fun DeleteScopeSheet(entry: CashflowEntry, onPick: (CashflowDeleteScope) -> Unit, onDismiss: () -> Unit) {
    YamuBottomSheet(onDismiss = onDismiss) {
        Text(DELETE_SCOPE_TITLE, style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
        Spacer(Modifier.height(YamuSpacing.x3))
        DeleteScopeRows(entry, onPick)
        Spacer(Modifier.height(YamuSpacing.x4))
    }
}

/** 1회 항목 — 지울지 한 번 더. 이름 끝 받침에 따라 '을 · 를' */
@Composable
internal fun DeleteOnceDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    YamuDialog(
        title = "'$title'${title.objectParticle()} 지울까요?",
        text = "예정 목록과 잔고예보에서 빠져요",
        confirmText = "지우기",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
