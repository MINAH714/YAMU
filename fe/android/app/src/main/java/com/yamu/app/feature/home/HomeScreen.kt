package com.yamu.app.feature.home

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.NotificationBell
import com.yamu.app.core.designsystem.component.TopBarLogo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuPreviews
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.formatSyncedAt
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.home.data.ForecastSummary
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.HomeTodo
import com.yamu.app.feature.home.data.RecentPayment
import com.yamu.app.feature.home.data.TodoTone
import java.time.LocalDate

/** H-01 홈 (H-01d 가입 첫날 · H-01c 결제 반영 직후는 같은 화면의 상태) */
@Composable
fun HomeRoute(
    onPay: () -> Unit,
    onForecast: () -> Unit,
    onNotifications: () -> Unit,
    onMyCards: () -> Unit,
    onStartLinking: () -> Unit,
    onTodo: (userCardId: Long?) -> Unit,
    onRecentPayment: (paymentId: Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()                 // 돌아올 때마다(결제 뒤 반영)
        onPauseOrDispose { }
    }
    HomeScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::load,
        onPay = onPay,
        onForecast = onForecast,
        onNotifications = onNotifications,
        onMyCards = onMyCards,
        onStartLinking = onStartLinking,
        onTodo = onTodo,
        onRecentPayment = onRecentPayment,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onPay: () -> Unit,
    onForecast: () -> Unit,
    onNotifications: () -> Unit,
    onMyCards: () -> Unit,
    onStartLinking: () -> Unit,
    onTodo: (Long?) -> Unit,
    onRecentPayment: (Long) -> Unit,
) {
    val c = YamuTheme.colors
    Scaffold(
        topBar = {
            YamuTopBar("홈", containerColor = c.background, logo = TopBarLogo.Start) {
                IconButton(onClick = onMyCards) {
                    Icon(YamuIcons.CreditCard, contentDescription = "내 카드", tint = c.text1)
                }
                NotificationBell(unread = state.content?.unreadCount ?: 0, onClick = onNotifications)
            }
        },
        containerColor = c.background,
    ) { inner ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize(),
        ) {
            val content = state.content
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when {
                    content != null -> HomeContent(content, state.userName, state.error, onPay, onForecast, onStartLinking, onTodo, onRecentPayment)
                    state.isLoading -> HomeSkeleton()
                    state.error != null -> YamuCard { ErrorState(state.error.userMessage(), onRetry = onRetry) }
                }
                Spacer(Modifier.height(YamuSpacing.bottomSafe))
            }
        }
    }
}

@Composable
private fun HomeContent(
    home: HomeSummary,
    userName: String?,
    error: ApiError?,
    onPay: () -> Unit,
    onForecast: () -> Unit,
    onStartLinking: () -> Unit,
    onTodo: (Long?) -> Unit,
    onRecentPayment: (Long) -> Unit,
) {
    val c = YamuTheme.colors
    val synced = formatSyncedAt(home.syncedAtIso)

    // 실패해도 마지막 값은 그대로 — 위에 한 줄
    if (error != null) {
        Text(
            listOfNotNull(error.userMessage(), synced?.let { "마지막 $it" }).joinToString(" · "),
            style = YamuTheme.typography.bodyS,
            color = c.onWarningContainer,
            modifier = Modifier
                .padding(horizontal = YamuSpacing.screen)
                .padding(bottom = YamuSpacing.blockGap)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.warningContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }

    // 인사 머리 — 월급까지 남은 날(첫날이면 아래에 '가입 첫날이에요')
    home.forecast?.daysToIncome?.takeIf { !home.needsLinking }?.let { d ->
        Column(Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 8.dp, bottom = 20.dp)) {
            Text("${userName ?: "야무"}님, 월급까지 D-$d", style = YamuTheme.typography.headlineS, color = c.text1)
            if (home.forecast.isProvisional) {
                Spacer(Modifier.height(4.dp))
                Text("가입 첫날이에요", style = YamuTheme.typography.sub, color = c.text3)
            }
        }
    }

    if (home.needsLinking) {
        YamuCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharacterImage(YamuPose.Wave, size = CharacterSize.S)
                Spacer(Modifier.width(12.dp))
                Text(
                    "계좌 · 카드를 연결하면\n월급 전 예상 잔액을 알려 드려요",
                    style = YamuTheme.typography.titleM,
                    color = c.text1,
                )
            }
        }
    }

    home.recentPayment?.let { RecentPaymentRow(it, onClick = { onRecentPayment(it.paymentId) }) }

    when {
        home.forecast != null -> ForecastCard(home.forecast, onForecast)
        "balanceForecast" in home.failedAreas -> YamuCard {
            Text("예상 잔액을 불러오지 못했어요", style = YamuTheme.typography.titleM, color = c.text2)
        }
    }

    Column(Modifier.padding(horizontal = YamuSpacing.screen).padding(bottom = YamuSpacing.blockGap)) {
        if (home.needsLinking) {
            PrimaryButton("계좌 · 카드 연결하기", onClick = onStartLinking)
        } else {
            PrimaryButton("QR로 결제하기", onClick = onPay, enabled = home.cardCount > 0)
            if (home.cardCount == 0) {
                Text(
                    "카드를 연결하면 쓸 수 있어요",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    if (home.todos.isNotEmpty()) {
        YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("야무가 챙겨 봤어요", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
                if (home.forecast?.isProvisional == true) Text("잠정 판단", style = YamuTheme.typography.sub, color = c.text3)
            }
            home.todos.forEachIndexed { i, todo ->
                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                TodoRow(todo, onClick = { onTodo(todo.userCardId) })
            }
        }
    }
}

@Composable
private fun ForecastCard(forecast: ForecastSummary, onClick: () -> Unit) {
    val c = YamuTheme.colors
    YamuCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(forecast.label, style = YamuTheme.typography.titleS, color = c.text2)
            if (forecast.isEstimate) {
                Spacer(Modifier.width(6.dp))
                EstimateTag()
            }
            Spacer(Modifier.weight(1f))
            Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
        }
        Spacer(Modifier.height(4.dp))
        AmountText(forecast.amount, shortage = forecast.amount < 0)
        Spacer(Modifier.height(8.dp))   // 금액 아래 4dp는 AmountText가 둔다
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shortage = forecast.shortageFrom
            if (shortage != null) {
                StatusTag("${shortage.toMonthDay()}부터 부족 예상", tone = TagTone.Error)
            } else if (forecast.incomeDate != null) {
                StatusTag("월급날(${forecast.incomeDate.toMonthDay()})까지 부족 없음", tone = TagTone.Success)
            }
            // 첫날은 판단 근거가 적다는 것만 — D-day는 인사 머리가, 계산 시각은 오류일 때 위 줄이 말한다
            if (forecast.isProvisional) Text("정확도 낮음", style = YamuTheme.typography.bodyS, color = c.text3)
        }
    }
}

@Composable
private fun RecentPaymentRow(payment: RecentPayment, onClick: () -> Unit) {
    val c = YamuTheme.colors
    // '야무카페 역삼점 15,000원' / '방금 · 신한카드 1,500원 할인'(할인만 파랑) — 프로토타입 H-01c
    YamuCard(onClick = onClick, contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${payment.merchantName} ${payment.amount.toWon()}원", style = YamuTheme.typography.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        append(listOfNotNull("방금", payment.cardName).joinToString(" · "))
                        payment.benefitAmount?.takeIf { it > 0 }?.let {
                            append(" ")
                            withStyle(SpanStyle(color = c.primary)) { append("${it.toWon()}원 할인") }
                        }
                    },
                    style = YamuTheme.typography.sub,
                    color = c.text3,
                )
            }
            Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
        }
    }
}

@Composable
private fun TodoRow(todo: HomeTodo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            todo.tag?.let {
                StatusTag(it, tone = todo.tone.toTagTone(), guide = true)
                Spacer(Modifier.height(8.dp))
            }
            Text(todo.title, style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
        }
        Icon(YamuIcons.ChevronRight, contentDescription = null, tint = YamuTheme.colors.text3)
    }
}

private fun TodoTone.toTagTone(): TagTone = when (this) {
    TodoTone.Neutral -> TagTone.Neutral
    TodoTone.Primary -> TagTone.Primary
    TodoTone.Success -> TagTone.Success
    TodoTone.Warning -> TagTone.Warning
    TodoTone.Error -> TagTone.Error
}

@Composable
private fun HomeSkeleton() {
    YamuCard {
        SkeletonBlock(widthFraction = 0.4f)
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(height = 32.dp, widthFraction = 0.6f)
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(widthFraction = 0.5f)
    }
    YamuCard {
        SkeletonBlock(widthFraction = 0.3f)
        Spacer(Modifier.height(12.dp))
        SkeletonBlock()
    }
}

@YamuPreviews
@Composable
private fun HomeScreenPreview() = YamuTheme {
    HomeScreen(
        state = HomeUiState(
            isLoading = false,
            content = HomeSummary(
                forecast = ForecastSummary(
                    label = "월급 전 예상 잔액",
                    amount = 350_000,
                    isEstimate = true,
                    isProvisional = false,
                    incomeDate = LocalDate.of(2026, 11, 10),
                    daysToIncome = 25,
                    shortageFrom = null,
                ),
                recentPayment = RecentPayment(1L, "야무카페 역삼점", 15_000, "신한카드", 1_500),
                todos = listOf(
                    HomeTodo("통신비만 우리카드로 옮기면, 더 안 써도 40만 원이 채워져요", "실적 달성 추천", TodoTone.Primary, 1),
                    HomeTodo("NH농협카드는 이번 달 실적을 채우지 않는 게 유리해요", "추가 소비 비추천", TodoTone.Error, 3),
                ),
                cardCount = 6,
                unreadCount = 3,
                needsLinking = false,
                syncedAtIso = null,
                failedAreas = emptySet(),
            ),
        ),
        onRefresh = {}, onRetry = {}, onPay = {}, onForecast = {}, onNotifications = {}, onMyCards = {},
        onStartLinking = {}, onTodo = {}, onRecentPayment = {},
    )
}
