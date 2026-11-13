package com.yamu.app.feature.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.feature.report.data.REPORT_MONTHS
import com.yamu.app.feature.report.data.ShownMonth
import com.yamu.app.feature.report.data.monthTitle
import java.time.YearMonth

/**
 * 앱바 아래 달 이동 '‹ 11월 ›' — S-01 · S-04 · S-05가 같이 쓴다. 화살표 크기 · 모양은 잔고예보 달력(ForecastCalendar)과 같다.
 * 끝 달에서는 화살표를 끄고 왜 못 가는지 읽어 준다(이번 달까지 · 최근 12개월까지).
 */
@Composable
internal fun MonthSwitcher(shown: ShownMonth, onSelect: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    val c = YamuTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = YamuSpacing.x2).padding(bottom = YamuSpacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonthArrow(
            back = true,
            enabled = shown.canPrev,
            description = if (shown.canPrev) "이전 달, ${monthTitle(shown.prev, shown.thisMonth)}" else "이전 달 없음, 최근 ${REPORT_MONTHS}개월까지 볼 수 있어요",
            onClick = { onSelect(shown.prev) },
        )
        Text(
            monthTitle(shown.month, shown.thisMonth),
            style = YamuTheme.typography.titleM,
            color = c.text1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics {
                heading()
                contentDescription = "${shown.month.year}년 ${shown.month.monthValue}월" + if (shown.isThisMonth) ", 이번 달" else ""
            },
        )
        MonthArrow(
            back = false,
            enabled = shown.canNext,
            description = if (shown.canNext) "다음 달, ${monthTitle(shown.next, shown.thisMonth)}" else "다음 달 없음, 이번 달까지 볼 수 있어요",
            onClick = { onSelect(shown.next) },
        )
    }
}

/**
 * 그달 결제가 없을 때. 이번 달이면(매월 1일 등) '지난달 보기'로 바로 앞 달을 연다.
 * 지난 달이면 문장만 — 머리의 화살표로 옮겨 간다. [thisMessage] · [pastMessage]는 화면마다 다르게(혜택 리포트는 '혜택이 없어요')
 */
@Composable
internal fun MonthEmpty(
    shown: ShownMonth,
    onSelect: (YearMonth) -> Unit,
    thisMessage: String = "이번 달 결제가 아직 없어요",
    pastMessage: String = "${monthTitle(shown.month, shown.thisMonth)}에는 결제가 없어요",
) {
    if (shown.isThisMonth && shown.canPrev) {
        EmptyState(thisMessage, actionText = "지난달 보기", onAction = { onSelect(shown.prev) })
    } else {
        EmptyState(pastMessage)
    }
}

@Composable
private fun MonthArrow(back: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(YamuRadius.s))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // 회색 바탕(카드 화면) 위라 흰 칸 — 달력은 흰 카드 위라 회색 칸이다
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(YamuRadius.s)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(
                YamuIcons.ChevronRight,
                contentDescription = null,
                tint = if (enabled) c.text1 else c.text3.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp).graphicsLayer { if (back) scaleX = -1f },
            )
        }
    }
}
