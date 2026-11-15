package com.yamu.app.feature.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.YamuBottomSheet
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
 * 끝 달에서는 화살표를 끄고 왜 못 가는지 읽어 준다(이번 달까지 · 다음 달까지 · 최근 12개월까지).
 * [onPick]이 있으면 달 이름을 눌러 연 · 월 시트를 연다(S-01, ANALYSIS-06). 다음 달은 ' · 예보만'을 붙인다
 */
@Composable
internal fun MonthSwitcher(shown: ShownMonth, onSelect: (YearMonth) -> Unit, modifier: Modifier = Modifier, onPick: (() -> Unit)? = null) {
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
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(YamuRadius.s))
                    .then(if (onPick != null) Modifier.clickable(role = Role.Button, onClickLabel = "볼 달 고르기", onClick = onPick) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        contentDescription = "${shown.month.year}년 ${shown.month.monthValue}월" + when {
                            shown.isThisMonth -> ", 이번 달"
                            shown.isNextMonth -> ", 예보만"
                            else -> ""
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    monthTitle(shown.month, shown.thisMonth) + if (shown.isNextMonth) " · 예보만" else "",
                    style = YamuTheme.typography.titleM,
                    color = c.text1,
                    textAlign = TextAlign.Center,
                )
                if (onPick != null) Icon(YamuIcons.ExpandMore, contentDescription = null, tint = c.text3, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
        }
        MonthArrow(
            back = false,
            enabled = shown.canNext,
            description = when {
                shown.canNext -> "다음 달, ${monthTitle(shown.next, shown.thisMonth)}"
                shown.allowNext -> "다음 달 없음, 예보가 닿는 다음 달까지 볼 수 있어요"
                else -> "다음 달 없음, 이번 달까지 볼 수 있어요"
            },
            onClick = { onSelect(shown.next) },
        )
    }
}

/** 연 · 월 고르기 시트(ANALYSIS-06) — 해마다 묶고, 이번 달 · 다음 달(예보만)에 꼬리표. 고르면 닫고 그 달로 */
@Composable
internal fun MonthSheet(shown: ShownMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("볼 달 고르기", style = YamuTheme.typography.titleL, color = c.text1)
        shown.choices.groupBy { it.year }.forEach { (year, months) ->
            Text("${year}년", style = YamuTheme.typography.titleS, color = c.text2, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            months.chunked(MONTHS_PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        MonthChoice(
                            m,
                            selected = m == shown.month,
                            tag = when {
                                m == shown.thisMonth -> "이번 달"
                                m > shown.thisMonth -> "예보만"
                                else -> null
                            },
                            onClick = { onPick(m) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(MONTHS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NeutralButton("닫기", onClick = onDismiss)
    }
}

@Composable
private fun MonthChoice(month: YearMonth, selected: Boolean, tag: String?, onClick: () -> Unit, modifier: Modifier) {
    val c = YamuTheme.colors
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.primary else c.surfaceVariant)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${month.year}년 ${month.monthValue}월" + (tag?.let { ", $it" } ?: "") }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${month.monthValue}월", style = YamuTheme.typography.titleS, color = if (selected) c.onPrimary else c.text1)
        tag?.let { Text(it, style = YamuTheme.typography.labelS, color = if (selected) c.onPrimary.copy(alpha = 0.85f) else c.text3) }
    }
}

private const val MONTHS_PER_ROW = 4

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
