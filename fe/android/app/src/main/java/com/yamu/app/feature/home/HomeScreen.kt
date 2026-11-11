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
import com.yamu.app.core.designsystem.component.CoinPile
import com.yamu.app.core.designsystem.component.CoinPileDefaults
import com.yamu.app.core.designsystem.component.coinCountFor
import com.yamu.app.core.designsystem.component.rememberCoinPileState
import com.yamu.app.core.designsystem.component.AmountSize
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuRadius
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import com.yamu.app.core.designsystem.component.rememberRolledAmount
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
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
    onCashflow: () -> Unit,
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
        onCashflow = onCashflow,
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
    onCashflow: () -> Unit,
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
            FillRestColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when {
                    content != null -> HomeContent(content, state.userName, state.error, onPay, onForecast, onStartLinking, onTodo, onRecentPayment)
                    state.isLoading -> HomeSkeleton()
                    state.error != null -> YamuCard { ErrorState(state.error.userMessage(), onRetry = onRetry) }
                }
                if (state.showsEntries) HomeEntries(onPay.takeIf { content == null }, onForecast, onCashflow)
                // 예상 잔액 카드가 남는 높이를 받으면 아래 빈자리가 없으니 맨 아래 여백도 카드 사이 간격(카드가 둔 12dp)만
                if (content?.forecast?.hasCoinStage() != true) Spacer(Modifier.height(YamuSpacing.bottomSafe))
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

    RecentPaymentSlot(home.recentPayment, onRecentPayment)

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

/** 홈 요약 없이도 열 수 있는 화면 — 요약을 받았으면 결제 버튼은 위에 이미 있다 */
@Composable
private fun HomeEntries(onPay: (() -> Unit)?, onForecast: () -> Unit, onCashflow: () -> Unit) {
    onPay?.let {
        Column(Modifier.padding(horizontal = YamuSpacing.screen).padding(bottom = YamuSpacing.blockGap)) {
            PrimaryButton("QR로 결제하기", onClick = it)
        }
    }
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        ListRow(title = "잔고예보", chevron = true, onClick = onForecast)
        ListRow(title = "예정 수입·지출", chevron = true, onClick = onCashflow)
    }
}

/** 예상 잔액이 0원보다 많으면 카드 아래에 동전 무대를 두고, 그 카드가 홈의 남는 높이를 받는다 */
private fun ForecastSummary.hasCoinStage(): Boolean = coinCountFor(amount) > 0

/**
 * 예상 잔액 카드 — 0원보다 많으면 [ForecastHero](홈의 남는 높이를 받는 큰 카드 + 동전 무더기),
 * 0원 이하(부족)면 예전 흰 카드. 빨간 금액은 남색 바탕에서 잘 안 읽혀 부족은 흰 카드로 둔다
 */
@Composable
private fun ForecastCard(forecast: ForecastSummary, onClick: () -> Unit) {
    if (forecast.hasCoinStage()) {
        ForecastHero(forecast, onClick)
        return
    }
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

/**
 * 홈 히어로 — 예상 잔액(월급 전이면 가장 낮을 때)을 이 화면의 주인공으로. 브랜드 색 깊은 그러데이션 바탕에 흰 큰 금액(40sp),
 * 아래 무대에 금색 동전이 금액만큼 떨어져 쌓인다(오른쪽 아래). 홈의 남는 높이를 이 카드가 받아 화면 아래 빈자리가 없다.
 *
 * 움직임: 금액 글자는 동전이 떨어지는 동안 0에서 올라가 마지막 동전과 함께 멈추고, 다 쌓이면 빛이 카드를 한 번 훑는다.
 * 그 뒤 금액이 바뀌면(결제하고 돌아옴) 보이던 값에서 새 값으로 굴러가고, 동전은 늘어난 만큼 떨어지거나 줄어든 만큼 떠난다.
 * 카드를 누르면 살짝 눌렸다가(0.97) 잔고예보로, 동전을 누르면 동전이 튀어 오르고 반짝인다(이동 없음).
 * 모션 줄이기면 금액은 처음부터 끝값, 눌림 · 빛 없음. 읽어 주기는 늘 끝 금액
 */
@Composable
private fun ForecastHero(forecast: ForecastSummary, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val pile = rememberCoinPileState()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) HERO_PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "heroPress",
    )
    // 다 쌓이는 순간 한 번 지나가는 빛 — 이미 쌓인 채 다시 그려질 때(탭을 오가도)는 없음
    val sheen = remember { Animatable(-1f) }
    val enteredAtStart = remember { pile.entered }
    LaunchedEffect(pile.entered) {
        if (pile.entered && !enteredAtStart && !reduce) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(HERO_SHEEN_MS, easing = YamuMotion.inOut))
        }
    }
    val shown = rememberRolledAmount(forecast.amount, pile)

    Column(
        Modifier
            .layoutId(FILL_REST)
            .padding(horizontal = YamuSpacing.screen)
            .padding(bottom = YamuSpacing.blockGap)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.sheet))
            .background(Brush.linearGradient(listOf(c.heroStart, c.heroEnd)))
            .drawWithContent {
                drawContent()
                val p = sheen.value
                if (p in 0f..1f) {
                    val band = size.width * 0.45f
                    val x = -band + (size.width + 2 * band) * p
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, c.onHero.copy(alpha = HERO_SHEEN_ALPHA), Color.Transparent),
                            start = Offset(x - band, 0f),
                            end = Offset(x + band, size.height * 0.5f),
                        ),
                    )
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "잔고예보 보기", onClick = onClick)
            .padding(start = YamuSpacing.x6, end = YamuSpacing.x6, top = YamuSpacing.x6, bottom = YamuSpacing.x5),
    ) {
        // 글씨가 커서 한 줄에 다 안 들어가면 '예측' 칩이 다음 줄로 — '자세히 ›'는 늘 오른쪽 위
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(forecast.label, style = YamuTheme.typography.cardTitle, color = c.onHero.copy(alpha = 0.88f))
                if (forecast.isEstimate) HeroChip("예측")
            }
            Spacer(Modifier.width(8.dp))
            Text("자세히", style = YamuTheme.typography.labelL, color = c.onHeroMuted)
            Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.onHeroMuted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(YamuSpacing.x2))
        AmountText(
            shown,
            size = AmountSize.Hero,
            color = c.onHero,
            fitOneLine = true,
            modifier = Modifier.clearAndSetSemantics { contentDescription = "${forecast.amount.toWon()}원" },
        )
        Spacer(Modifier.height(YamuSpacing.x3))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val shortage = forecast.shortageFrom
            if (shortage != null) {
                HeroStatus("${shortage.toMonthDay()}부터 부족 예상", dot = c.error)
            } else if (forecast.incomeDate != null) {
                HeroStatus("월급날(${forecast.incomeDate.toMonthDay()})까지 부족 없음", dot = c.success)
            }
            if (forecast.isProvisional) Text("정확도 낮음", style = YamuTheme.typography.bodyS, color = c.onHeroMuted)
        }
        Spacer(Modifier.height(YamuSpacing.x3))
        CoinPile(
            forecast.amount,
            Modifier.fillMaxWidth().weight(1f).heightIn(min = CoinPileDefaults.minHeight),
            state = pile,
        )
    }
}

/** 히어로 위 작은 유리 칩('예측') */
@Composable
private fun HeroChip(text: String) {
    val c = YamuTheme.colors
    Text(
        text,
        style = YamuTheme.typography.labelM,
        color = c.onHero,
        modifier = Modifier
            .clip(RoundedCornerShape(YamuRadius.tag))
            .background(c.onHero.copy(alpha = HERO_GLASS_ALPHA))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 히어로 위 상태 알약 — 반투명 바탕 + 상태 색 점(부족 없음 초록 · 부족 예상 빨강) */
@Composable
private fun HeroStatus(text: String, dot: Color) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.onHero.copy(alpha = HERO_GLASS_ALPHA))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, style = YamuTheme.typography.labelL, color = c.onHero)
    }
}

private const val HERO_PRESSED_SCALE = 0.97f
private const val HERO_SHEEN_MS = 900
private const val HERO_SHEEN_ALPHA = 0.16f
private const val HERO_GLASS_ALPHA = 0.16f

/**
 * 세로로 쌓되, 화면보다 짧으면 [FILL_REST] 표시한 자식 하나(예상 잔액 카드)가 남는 높이를 모두 받는다.
 * 화면보다 길면 그 자식도 제 높이(최소 크기)로 쌓이고 스크롤된다 — Column의 weight는 이때 0이 되어 카드가 사라진다.
 * 화면 높이는 fillMaxSize() 뒤 verticalScroll()이 넘기는 최소 높이다.
 */
@Composable
private fun FillRestColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val restIndex = measurables.indexOfFirst { it.layoutId == FILL_REST }
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { i, m -> if (i != restIndex) placeables[i] = m.measure(loose) }
        if (restIndex >= 0) {
            val rest = measurables[restIndex]
            val others = placeables.sumOf { it?.height ?: 0 }
            val height = maxOf(rest.minIntrinsicHeight(width), constraints.minHeight - others)
            placeables[restIndex] = rest.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))
        }
        val total = placeables.sumOf { it!!.height }
        layout(width, maxOf(total, constraints.minHeight)) {
            var y = 0
            placeables.forEach {
                it!!.place(0, y)
                y += it.height
            }
        }
    }
}

private const val FILL_REST = "fillRest"

/**
 * 결제하고 돌아오면 이 줄이 새로 생기고 아래 예상 잔액 카드가 그만큼 줄어든다 — 한 번에 튀지 않게 위에서부터 펼친다.
 * 처음부터 있으면 그냥 보인다. 모션 줄이기면 페이드만
 */
@Composable
private fun RecentPaymentSlot(payment: RecentPayment?, onClick: (Long) -> Unit) {
    val reduce = YamuTheme.reduceMotion
    val kept = remember { mutableStateOf(payment) }      // 사라지는 동안에도 그릴 마지막 결제
    if (payment != null) kept.value = payment
    val spec = tween<IntSize>(RECENT_ROW_MS, easing = YamuMotion.standard)
    AnimatedVisibility(
        visible = payment != null,
        enter = if (reduce) fadeIn(tween(YamuMotion.SHORT_MS)) else expandVertically(spec, expandFrom = Alignment.Top) + fadeIn(tween(RECENT_ROW_MS)),
        exit = if (reduce) fadeOut(tween(YamuMotion.SHORT_MS)) else shrinkVertically(spec, shrinkTowards = Alignment.Top) + fadeOut(tween(RECENT_ROW_MS)),
    ) {
        kept.value?.let { RecentPaymentRow(it, onClick = { onClick(it.paymentId) }) }
    }
}

private const val RECENT_ROW_MS = 300

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
                    label = "월급 전 가장 낮을 때",
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
        onRefresh = {}, onRetry = {}, onPay = {}, onForecast = {}, onCashflow = {}, onNotifications = {}, onMyCards = {},
        onStartLinking = {}, onTodo = {}, onRecentPayment = {},
    )
}
