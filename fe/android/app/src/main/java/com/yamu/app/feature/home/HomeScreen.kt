package com.yamu.app.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yamu.app.core.designsystem.component.AmountSize
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.CoinPile
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.NotificationBell
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.QuietLink
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.TopBarLogo
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuPreviews
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.component.rememberCoinPileState
import com.yamu.app.core.designsystem.component.rememberRolledAmount
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.icon.YamuTabIcons
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.formatSyncedAt
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.ui.ReloadOnReconnect
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.home.data.HOME_CARD_ROWS
import com.yamu.app.feature.home.data.HOME_FORECAST_DAYS
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeCardRow
import com.yamu.app.feature.home.data.HomeCardsData
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeGreeting
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.MissedBenefits
import com.yamu.app.feature.home.data.PaySettings
import com.yamu.app.feature.home.data.PerfChipTone
import com.yamu.app.feature.home.data.RecentPayment
import com.yamu.app.feature.home.data.asOfLabel
import com.yamu.app.feature.home.data.benefitCoins
import com.yamu.app.feature.home.data.emptyNote
import com.yamu.app.feature.home.data.homeGreeting
import com.yamu.app.feature.home.data.missed
import com.yamu.app.feature.home.data.paidAtLabel
import com.yamu.app.feature.home.data.payNote
import com.yamu.app.feature.home.data.paySubtitle
import com.yamu.app.feature.home.data.recentPaymentOutsidePayCard
import com.yamu.app.feature.home.data.rows
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.CardArtRef
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/**
 * H-01 홈 — v4(11/13 '홈 개편 및 소비 분석 확장 요구사항 명세서'). 위에서부터 인사 → 이번 달 혜택 → YAMU로 결제하기(S15P31E103-480)
 * → 내 카드 혜택 현황 → 놓치기 아까운 혜택 → 잔고예보 요약(S15P31E103-481). 큰 자산 흐름 그래프는 홈에 두지 않는다(HOME-16)
 */
@Composable
fun HomeRoute(
    onPay: () -> Unit,
    onForecast: () -> Unit,
    onNotifications: () -> Unit,
    onMyCards: () -> Unit,
    onStartLinking: () -> Unit,
    onRecentPayment: (paymentId: Long) -> Unit,
    onPayments: () -> Unit,
    onReport: () -> Unit,
    onRegisterIncome: () -> Unit,
    onAddCard: () -> Unit,
    onBenefitReport: () -> Unit,
    onCardPerformance: (userCardId: Long) -> Unit,
    onPerfTab: () -> Unit,
    onBenefitLimits: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()                 // 돌아올 때마다(결제 · 취소 뒤 혜택 · 카드 현황 반영, QA-03)
        onPauseOrDispose { }
    }
    // 인터넷이 끊겨 마지막 값을 보이는 동안 연결이 돌아오면 저절로 다시 불러온다(S15P31E103-464)
    ReloadOnReconnect(waiting = state.error == ApiError.Network, onReconnect = viewModel::load)
    HomeScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::load,
        onRetryBenefit = viewModel::retryBenefit,
        onRetryCards = viewModel::retryCards,
        onRetryForecast = viewModel::retryForecast,
        arts = arts,
        onPay = onPay,
        onForecast = {
            viewModel.openForecast()   // F-01을 홈 요약과 같은 기준 계좌 · 30일로(고른 기간 · 계좌를 덮어씀)
            onForecast()
        },
        onCardPerformance = onCardPerformance,
        onPerfTab = onPerfTab,
        onBenefitLimits = onBenefitLimits,
        onNotifications = onNotifications,
        onMyCards = onMyCards,
        onStartLinking = onStartLinking,
        onRecentPayment = onRecentPayment,
        onPayments = {
            viewModel.openPayments()   // 결제 내역을 최근 결제가 있는 달로 — 월초엔 지난달 결제일 수 있다
            onPayments()
        },
        onBenefitReport = {
            viewModel.openBenefitReport()   // 혜택 리포트를 히어로와 같은 달로
            onBenefitReport()
        },
        onAssetFlow = {
            viewModel.openAssetFlow()   // 소비분석이 이번 달 · 자산 흐름 칸으로 내려가게 요청해 두고
            onReport()                  // 하단 탭을 누른 것과 같이 소비분석 탭으로 바꾼다(뒤로 = 홈)
        },
        onRegisterIncome = onRegisterIncome,
        onAddCard = onAddCard,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onPay: () -> Unit,
    onNotifications: () -> Unit,
    onMyCards: () -> Unit,
    onStartLinking: () -> Unit,
    onRecentPayment: (Long) -> Unit,
    onRetryBenefit: () -> Unit = {},
    onRetryCards: () -> Unit = {},
    onRetryForecast: () -> Unit = {},
    arts: Map<Long, CardArtRef> = emptyMap(),
    onPayments: () -> Unit = {},
    onBenefitReport: () -> Unit = {},
    onCardPerformance: (Long) -> Unit = {},
    onPerfTab: () -> Unit = {},
    onBenefitLimits: () -> Unit = {},
    onForecast: () -> Unit = {},
    onAssetFlow: () -> Unit = {},
    onRegisterIncome: () -> Unit = {},
    onAddCard: () -> Unit = {},
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
                    content != null -> {
                        HomeContent(
                            home = content,
                            state = state,
                            onRetryBenefit = onRetryBenefit,
                            onPay = onPay,
                            onStartLinking = onStartLinking,
                            onRecentPayment = onRecentPayment,
                            onPayments = onPayments,
                            onBenefitReport = onBenefitReport,
                            onAddCard = onAddCard,
                        )
                        // 아래쪽(S15P31E103-481) — 카드가 있어야 보이는 두 칸, 그다음 잔고예보 요약
                        if (!content.needsLinking && !content.needsCard) {
                            CardsSection(state.cards, arts, onCard = onCardPerformance, onAll = onPerfTab, onRetry = onRetryCards)
                            state.cards.dataOrNull?.missed()?.takeUnless { it.isEmpty }?.let { MissedSection(it, onAll = onBenefitLimits) }
                        }
                        ForecastSection(
                            slot = forecastSlot(content),
                            home = content,
                            brief = state.forecast,
                            onForecast = onForecast,
                            onAssetFlow = onAssetFlow,
                            onRegisterIncome = onRegisterIncome,
                            onStartLinking = onStartLinking,
                            onRetry = onRetryForecast,
                        )
                    }
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
    state: HomeUiState,
    onRetryBenefit: () -> Unit,
    onPay: () -> Unit,
    onStartLinking: () -> Unit,
    onRecentPayment: (Long) -> Unit,
    onPayments: () -> Unit,
    onBenefitReport: () -> Unit,
    onAddCard: () -> Unit,
) {
    val c = YamuTheme.colors
    val error = state.error

    // 실패해도 마지막 값은 그대로 — 위에 한 줄
    if (error != null) {
        val synced = formatSyncedAt(home.syncedAtIso)
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

    // 인사 — 집계 범위는 혜택을 받았으면 서버 기간, 아직이면 오늘까지(한국 날짜)
    val benefit = state.benefit.dataOrNull
    val today = LocalDate.now(KST)
    val month = benefit?.month ?: YearMonth.from(today)
    Greeting(
        homeGreeting(
            name = state.userName,
            needsLinking = home.needsLinking,
            needsCard = home.needsCard,
            month = month,
            from = benefit?.from ?: month.atDay(1),
            to = benefit?.to ?: today,
        ),
    )

    when {
        // 연결 전 · 카드 없음 — 혜택을 지어내지 않고 할 일을 안내한다(사용자 상태별 처리 '카드 미등록')
        home.needsLinking -> {
            SetupGuide(
                title = "계좌 · 카드를 연결해 주세요",
                body = "결제할 때 혜택이 큰 카드를 골라 드리고 받은 혜택과 잔고를 여기 모아 드려요",
                button = "계좌 · 카드 연결하기",
                onClick = onStartLinking,
            )
            RecentPaymentCard(home.recentPaymentOutsidePayCard(), onRecentPayment, onPayments)
        }
        // 꺼진 결제 버튼 대신 다음 할 일 — 카드 추가(C-11)로(S15P31E103-430)
        home.needsCard -> {
            SetupGuide(
                title = "카드를 등록해 주세요",
                body = "결제할 때 혜택이 큰 카드를 골라 드리고 받은 혜택을 여기 모아 드려요",
                button = "카드 등록하기",
                onClick = onAddCard,
            )
            RecentPaymentCard(home.recentPaymentOutsidePayCard(), onRecentPayment, onPayments)
        }
        // 카드 영역만 실패했으면 몇 장인지 몰라 혜택 · 결제 칸을 그린다 — 카드가 정말 없으면 결제 코드 화면이 알려 준다
        else -> {
            BenefitHero(state.benefit, onClick = onBenefitReport, onRetry = onRetryBenefit)
            PayCard(
                settings = state.pay.dataOrNull,
                cardName = { id -> home.cards.firstOrNull { it.userCardId == id }?.name },
                recentPayment = home.recentPayment,
                onPay = onPay,
                onRecentPayment = onRecentPayment,
                onPayments = onPayments,
            )
        }
    }
}

/** 인사 두 줄 — '○○님, 11월 혜택을 모아 봤어요' / 'YAMU로 결제한 것만 셌어요 · 11/1~11/13' */
@Composable
private fun Greeting(greeting: HomeGreeting) {
    val c = YamuTheme.colors
    Column(Modifier.padding(start = YamuSpacing.screen + 4.dp, end = YamuSpacing.screen, top = 8.dp, bottom = 20.dp)) {
        Text(greeting.title, style = YamuTheme.typography.headlineS, color = c.text1)
        Spacer(Modifier.height(4.dp))
        Text(greeting.sub, style = YamuTheme.typography.sub, color = c.text3)
    }
}

/** 연결 전 · 카드 없음 안내 — 손 흔드는 야무 + 할 일 + 버튼 하나 */
@Composable
private fun SetupGuide(title: String, body: String, button: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    YamuCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CharacterImage(YamuPose.Wave, size = CharacterSize.S)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = YamuTheme.typography.titleM, color = c.text1)
                Spacer(Modifier.height(2.dp))
                Text(body, style = YamuTheme.typography.sub, color = c.text2)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(button, onClick = onClick)
    }
}

/**
 * 이번 달 혜택 히어로(HOME-02 ~ 06) — 예전 예상 잔액 히어로의 남색 그러데이션과 금색 동전 무더기를 옮겨 왔다(-406).
 * 큰 숫자는 카드사가 확정한 혜택만, 확정 전 금액은 점선 테두리 칩과 점선 동전으로 떼어 놓는다(HOME-04, 11/13 시안에서 고름).
 * 할인 · 캐시백 · 적립 세 칸(HOME-03)은 BE에 종류별 합계가 없어 그 필드가 올 때까지 그리지 않는다.
 *
 * 움직임: 동전이 떨어지는 동안 금액이 0에서 올라가 마지막 동전과 함께 멈추고, 다 쌓이면 빛이 카드를 한 번 훑는다.
 * 카드를 누르면 살짝 눌렸다가(0.97) 혜택 리포트(S-04, 같은 달)로. 모션 줄이기면 금액은 처음부터 끝값, 눌림 · 빛 없음
 */
@Composable
private fun BenefitHero(section: HomeSection<HomeBenefit>, onClick: () -> Unit, onRetry: () -> Unit) {
    val c = YamuTheme.colors
    when (section) {
        is HomeSection.Ready -> BenefitHeroReady(section.data, onClick)
        HomeSection.Loading -> HeroFrame(onClick = null) {
            HeroTitle("이번 달 받은 혜택", more = false)
            Spacer(Modifier.height(YamuSpacing.x3))
            Box(Modifier.width(150.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.onHero.copy(alpha = HERO_GLASS_ALPHA)))
            Spacer(Modifier.height(YamuSpacing.x3))
            Box(Modifier.width(110.dp).height(22.dp).clip(RoundedCornerShape(YamuRadius.input)).background(c.onHero.copy(alpha = HERO_GLASS_ALPHA)))
        }
        // 혜택만 실패 — 홈의 다른 칸은 그대로 그리고 여기만 다시 시도(COMMON-08)
        is HomeSection.Failed -> HeroFrame(onClick = null) {
            HeroTitle("이번 달 받은 혜택", more = false)
            Spacer(Modifier.height(YamuSpacing.x3))
            Text("혜택을 불러오지 못했어요", style = YamuTheme.typography.titleM, color = c.onHero)
            Spacer(Modifier.height(YamuSpacing.x3))
            HeroButton("다시 시도", onClick = onRetry)
        }
    }
}

@Composable
private fun BenefitHeroReady(b: HomeBenefit, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val pile = rememberCoinPileState()
    val (solid, ghost) = benefitCoins(b.confirmed, b.pending)
    // 다 쌓이는 순간 한 번 지나가는 빛 — 이미 쌓인 채 다시 그려질 때(탭을 오가도)는 없음
    val sheen = remember { Animatable(-1f) }
    val enteredAtStart = remember { pile.entered }
    LaunchedEffect(pile.entered) {
        if (pile.entered && !enteredAtStart && !reduce) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(HERO_SHEEN_MS, easing = YamuMotion.inOut))
        }
    }
    // 동전이 없으면(0원) 금액도 굴리지 않는다 — 무더기가 없어 rolled가 오르지 않으므로
    val shown = if (solid + ghost > 0) rememberRolledAmount(b.confirmed, pile) else b.confirmed
    val described = buildString {
        append("${b.month.monthValue}월에 받은 혜택 확정 ${b.confirmed.toWon()}원")
        if (b.pending > 0) append(", 확정 전 ${b.pending.toWon()}원")
    }

    HeroFrame(onClick = onClick, onClickLabel = "혜택 리포트 보기", sheen = sheen.value) {
        HeroTitle("${b.month.monthValue}월에 받은 혜택", more = true)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Spacer(Modifier.height(YamuSpacing.x2))
                AmountText(
                    shown,
                    size = AmountSize.Hero,
                    color = c.onHero,
                    fitOneLine = true,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = described },
                )
                Spacer(Modifier.height(YamuSpacing.x2))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val empty = b.emptyNote()
                    if (empty != null) {
                        HeroChip(empty)
                    } else {
                        HeroChip("확정")
                        if (b.pending > 0) HeroChip("확정 전 +${b.pending.toWon()}원", dashed = true)
                    }
                }
            }
            if (solid + ghost > 0) {
                CoinPile(
                    solidCoins = solid,
                    ghostCoins = ghost,
                    modifier = Modifier.padding(start = YamuSpacing.x2).width(HERO_PILE_WIDTH).height(HERO_PILE_HEIGHT),
                    state = pile,
                )
            }
        }
        if (ghost > 0) {
            Spacer(Modifier.height(YamuSpacing.x3))
            Text("꽉 찬 동전은 확정, 점선 동전은 확정 전이에요", style = YamuTheme.typography.bodyS, color = c.onHeroMuted)
        }
    }
}

/** 히어로 바탕 — 브랜드 색 깊은 그러데이션, 누르면 0.97로 살짝 눌림(모션 줄이기면 없음), [sheen] 0~1이면 빛이 한 번 훑는다 */
@Composable
private fun HeroFrame(
    onClick: (() -> Unit)?,
    onClickLabel: String? = null,
    sheen: Float = -1f,
    content: @Composable () -> Unit,
) {
    val c = YamuTheme.colors
    val reduce = YamuTheme.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) HERO_PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "heroPress",
    )
    Column(
        Modifier
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
                if (sheen in 0f..1f) {
                    val band = size.width * 0.45f
                    val x = -band + (size.width + 2 * band) * sheen
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, c.onHero.copy(alpha = HERO_SHEEN_ALPHA), Color.Transparent),
                            start = Offset(x - band, 0f),
                            end = Offset(x + band, size.height * 0.5f),
                        ),
                    )
                }
            }
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
                else Modifier,
            )
            .padding(start = YamuSpacing.x5, end = YamuSpacing.x5, top = YamuSpacing.x5, bottom = YamuSpacing.x4),
    ) { content() }
}

/** 히어로 머리 — 이름(왼쪽)과 '자세히 ›'(누를 수 있을 때만, 오른쪽 위) */
@Composable
private fun HeroTitle(text: String, more: Boolean) {
    val c = YamuTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = YamuTheme.typography.cardTitle, color = c.onHero.copy(alpha = 0.88f), modifier = Modifier.weight(1f))
        if (more) {
            Spacer(Modifier.width(8.dp))
            Text("자세히", style = YamuTheme.typography.labelL, color = c.onHeroMuted)
            Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.onHeroMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** 히어로 위 작은 유리 칩. [dashed]면 바탕 없이 점선 테두리 — 확정 전 금액(점선 동전과 같은 모양) */
@Composable
private fun HeroChip(text: String, dashed: Boolean = false) {
    val c = YamuTheme.colors
    val shape = RoundedCornerShape(YamuRadius.input)
    Text(
        text,
        style = YamuTheme.typography.labelM,
        color = c.onHero,
        modifier = Modifier
            .clip(shape)
            .then(if (dashed) Modifier.dashedOutline(c.onHero.copy(alpha = 0.75f)) else Modifier.background(c.onHero.copy(alpha = HERO_GLASS_ALPHA)))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 알약 모양 점선 테두리 */
private fun Modifier.dashedOutline(color: Color) = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(size.height / 2),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.4.dp.toPx()))),
    )
}

/** 히어로 위 누르는 알약(다시 시도) — 유리 바탕 */
@Composable
private fun HeroButton(text: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Text(
        text,
        style = YamuTheme.typography.labelL,
        color = c.onHero,
        modifier = Modifier
            .heightIn(min = YamuSize.touch)
            .clip(RoundedCornerShape(YamuRadius.input))
            .clickable(role = Role.Button, onClick = onClick)
            .background(c.onHero.copy(alpha = HERO_GLASS_ALPHA))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * 'YAMU로 결제하기'(HOME-07 ~ 09) — 혜택 바로 아래의 주된 행동. 바코드를 열기 전에 특정 카드를 최적이라고 하지 않고
 * '결제할 곳과 금액이 정해지면' 고른다고 말한다. 자동 선택을 끈 사람에겐 기본 카드로 결제한다고 바꿔 보인다.
 * 버튼은 하단 가운데 결제 버튼과 같은 결제 코드(P-06) — 인증 · 자동 선택 설정은 그 화면이 지금대로 따른다(QA-04).
 * 최근 결제 한 줄(-428 · -460)은 결제하고 돌아왔을 때 바로 보이게 이 칸 안 버튼 위에 둔다(11/13 결정)
 */
@Composable
private fun PayCard(
    settings: PaySettings?,
    cardName: (Long) -> String?,
    recentPayment: RecentPayment?,
    onPay: () -> Unit,
    onRecentPayment: (Long) -> Unit,
    onPayments: () -> Unit,
) {
    val c = YamuTheme.colors
    val note = payNote(settings, cardName)
    YamuCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("YAMU로 결제하기", style = YamuTheme.typography.cardTitle, color = c.text1)
                paySubtitle(settings)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = YamuTheme.typography.sub, color = c.text3)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(YamuTabIcons.PayCode, contentDescription = null, tint = c.primary, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (note.autoOff) YamuIcons.Info else YamuIcons.CheckCircle,
                contentDescription = null,
                tint = if (note.autoOff) c.warning else c.success,
                modifier = Modifier.padding(top = 1.dp).size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(note.text, style = YamuTheme.typography.bodyM, color = if (note.autoOff) c.onWarningContainer else c.text2)
        }
        RecentPaymentSlot(recentPayment, onRecentPayment, onPayments)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("통합 결제 바코드 열기", onClick = onPay, icon = YamuTabIcons.PayCode)
    }
}

/**
 * 결제하고 돌아오면 이 줄이 새로 생긴다 — 한 번에 튀지 않게 위에서부터 펼친다.
 * 처음부터 있으면 그냥 보인다. 모션 줄이기면 페이드만
 */
@Composable
private fun RecentPaymentSlot(payment: RecentPayment?, onClick: (Long) -> Unit, onAll: () -> Unit) {
    val reduce = YamuTheme.reduceMotion
    val kept = remember { mutableStateOf(payment) }      // 사라지는 동안에도 그릴 마지막 결제
    if (payment != null) kept.value = payment
    val spec = tween<IntSize>(RECENT_ROW_MS, easing = YamuMotion.standard)
    AnimatedVisibility(
        visible = payment != null,
        enter = if (reduce) fadeIn(tween(YamuMotion.SHORT_MS)) else expandVertically(spec, expandFrom = Alignment.Top) + fadeIn(tween(RECENT_ROW_MS)),
        exit = if (reduce) fadeOut(tween(YamuMotion.SHORT_MS)) else shrinkVertically(spec, shrinkTowards = Alignment.Top) + fadeOut(tween(RECENT_ROW_MS)),
    ) {
        kept.value?.let { RecentPaymentRow(it, onClick = { onClick(it.paymentId) }, onAll = onAll) }
    }
}

private const val RECENT_ROW_MS = 300

/**
 * 결제 칸이 없는 홈(연결 전 · 카드 없음)의 최근 결제 — 안내 카드 아래 따로 한 칸. 카드를 모두 지운 사람도
 * 지난 결제와 결제 내역 입구를 잃지 않게(예전 홈은 상태와 상관없이 그렸다). 결제가 없으면 그리지 않는다
 */
@Composable
private fun RecentPaymentCard(payment: RecentPayment?, onClick: (Long) -> Unit, onAll: () -> Unit) {
    payment ?: return
    YamuCard(contentPadding = PaddingValues(start = YamuSpacing.x5, end = YamuSpacing.x5, top = YamuSpacing.x2, bottom = YamuSpacing.x5)) {
        RecentPaymentRow(payment, onClick = { onClick(payment.paymentId) }, onAll = onAll, top = 0.dp)
    }
}

/** 보이는 높이는 [visible]로 두고 터치 칸은 제 크기대로 위아래로 넘친다(잔고예보 화면의 같은 이름 도우미와 같다) */
private fun Modifier.overflowHeight(visible: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = visible.roundToPx()
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

/**
 * 최근 결제 — 머리 '최근 결제 · 결제 내역 ›'(S-05, 2차 QA 39) 아래 회색 칸에 결제 한 줄(누르면 결제 상세).
 * '야무카페 역삼점 15,000원' / '방금 · 신한카드 1,500원 할인'(할인만 파랑). 시각은 paidAt으로(어제 결제면 '어제 17:05')
 */
@Composable
private fun RecentPaymentRow(payment: RecentPayment, onClick: () -> Unit, onAll: () -> Unit, top: Dp = 14.dp) {
    val c = YamuTheme.colors
    val time = paidAtLabel(payment.paidAtIso)
    Column(Modifier.padding(top = top)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("최근 결제", style = YamuTheme.typography.labelM, color = c.text3, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    // 보이는 머리 줄은 32dp 그대로, '결제 내역' 터치 칸(48)만 위아래로 넘친다
                    .overflowHeight(32.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(YamuRadius.s))
                    .clickable(role = Role.Button, onClickLabel = "결제 내역 보기", onClick = onAll)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("결제 내역", style = YamuTheme.typography.link, color = c.text3)
                Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(YamuRadius.input))
                .background(c.surfaceVariant)
                .clickable(role = Role.Button, onClickLabel = "결제 상세 보기", onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${payment.merchantName} ${payment.amount.toWon()}원", style = YamuTheme.typography.titleS, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        append(listOfNotNull(time, payment.cardName).joinToString(" · "))
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

/** 카드 머리 — 제목(왼쪽)과 조용한 글자 버튼 '전체보기 ›'(오른쪽, 터치 칸은 위아래로 넘친다) */
@Composable
private fun SectionTitle(title: String, link: String? = null, onLink: () -> Unit = {}) {
    val c = YamuTheme.colors
    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
        if (link != null) {
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(YamuRadius.s))
                    .clickable(role = Role.Button, onClick = onLink)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(link, style = YamuTheme.typography.link, color = c.text3)
                Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 칸 하나만 실패했을 때 — 문구 + '다시 시도'(COMMON-08) */
@Composable
private fun SectionFailure(text: String, onRetry: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(YamuRadius.input)).background(c.warningContainer)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = YamuTheme.typography.bodyM, color = c.onWarningContainer, modifier = Modifier.weight(1f))
        Text(
            "다시 시도",
            style = YamuTheme.typography.labelL,
            color = c.text1,
            modifier = Modifier
                .heightIn(min = YamuSize.touch)
                .clip(RoundedCornerShape(YamuRadius.s))
                .clickable(role = Role.Button, onClick = onRetry)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 10.dp),
        )
    }
}

/**
 * 내 카드 혜택 현황(HOME-10 · 11) — 카드 목록 순서로 3장 + '전체보기 ›'(실적관리 탭). 줄을 누르면 그 카드 R-02.
 * 줄마다 남은 한도 한 줄과 실적 칩. 카드별 이번 달 받은 혜택은 BE 필드가 오면 넣는다(11/13 결정: 그 전엔 숨김)
 */
@Composable
private fun CardsSection(section: HomeSection<HomeCardsData>, arts: Map<Long, CardArtRef>, onCard: (Long) -> Unit, onAll: () -> Unit, onRetry: () -> Unit) {
    val rows = section.dataOrNull?.rows()
    if (rows != null && rows.isEmpty()) return   // 카드 영역만 실패해 0장으로 읽힌 홈 — 실적도 0장이면 그릴 것이 없다
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = if (rows != null) 6.dp else 16.dp)) {
        SectionTitle("내 카드 혜택 현황", link = "전체보기", onLink = onAll)
        when {
            rows != null -> rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = YamuTheme.colors.outlineVariant)
                CardRowItem(row, arts[row.userCardId], onClick = { onCard(row.userCardId) })
            }
            section is HomeSection.Failed -> SectionFailure("카드 현황을 불러오지 못했어요", onRetry)
            else -> {
                Spacer(Modifier.height(14.dp))
                repeat(HOME_CARD_ROWS) {
                    SkeletonBlock(height = 20.dp, widthFraction = 0.8f)
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun CardRowItem(row: HomeCardRow, art: CardArtRef?, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (row.opens) Modifier.clickable(role = Role.Button, onClickLabel = "카드 실적 보기", onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardListThumb(art?.brand ?: row.brand, art?.productName ?: row.productName ?: row.name, width = 52.dp, dimmed = art?.dimmed == true)
        Spacer(Modifier.width(12.dp))
        // 이름 · 한도 · 실적 칩을 세로로 — 카드 이름이 길어도 남은 한도 금액이 잘리지 않게(칩을 오른쪽에 두면 한도 줄이 '…'로 끊긴다)
        Column(Modifier.weight(1f)) {
            Text(row.name, style = YamuTheme.typography.titleM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.limitLine?.let { Text(it, style = YamuTheme.typography.sub, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(6.dp))
            StatusTag(
                row.chip.text,
                tone = when (row.chip.tone) {
                    PerfChipTone.Done -> TagTone.Success
                    PerfChipTone.Todo, PerfChipTone.None -> TagTone.Neutral
                },
                guide = true,
            )
        }
        if (row.opens) Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
    }
}

/**
 * 놓치기 아까운 혜택(HOME-12 · 13) — 확인된 것이 있을 때만. '남은 한도'(금액을 아는 이번 달 한도)와 '조건을 채우면'(이번 달 실적 → 다음 달 혜택)을 나눈다.
 * 조건을 확인하라고 보여 주는 칸이라 더 쓰라는 말을 쓰지 않고, 아래에 그 뜻을 한 줄로 적는다. '남은 혜택 모두 보기 ›'는 R-06
 */
@Composable
private fun MissedSection(missed: MissedBenefits, onAll: () -> Unit) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        SectionTitle("놓치기 아까운 혜택")
        Spacer(Modifier.height(2.dp))
        Text("이번 달 남은 혜택 중 확인할 수 있는 것만 모았어요", style = YamuTheme.typography.sub, color = c.text3)
        if (missed.remaining.isNotEmpty()) {
            MissedGroup("남은 한도") {
                missed.remaining.forEach { r ->
                    MissedRow("${r.cardName} · ${r.limitName}", amount = "${r.amount.toWon()}원")
                }
            }
        }
        if (missed.conditions.isNotEmpty()) {
            MissedGroup("조건을 채우면") {
                missed.conditions.forEach { line -> MissedRow("${line.cardName} · ${line.text}", amount = null) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("조건을 확인하라고 보여 드리는 거예요. 더 쓰는 게 이득이라는 뜻은 아니에요.", style = YamuTheme.typography.bodyS, color = c.text3)
        QuietLink("남은 혜택 모두 보기 ›", onClick = onAll)
    }
}

@Composable
private fun MissedGroup(title: String, rows: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = YamuTheme.typography.labelM, color = YamuTheme.colors.text3)
        rows()
    }
}

@Composable
private fun MissedRow(text: String, amount: String?) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(YamuRadius.input)).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = YamuTheme.typography.bodyM, color = c.text1, modifier = Modifier.weight(1f))
        if (amount != null) {
            Spacer(Modifier.width(10.dp))
            Text(amount, style = YamuTheme.typography.value, color = c.text1)
        }
    }
}

/**
 * 잔고예보 요약(HOME-14 · 15) — 기준 계좌 하나의 지금 잔고 · 기준 시각과 30일 안 가장 낮은 잔고 · 날짜(잔고예보 API 결과를 다시 씀, FORECAST-02).
 * 계좌 칩으로 계좌 범위를, 줄 이름으로 기준 시점 · 기간을 말한다. 버튼 둘: '잔고예보'(F-01, 같은 계좌 · 30일) · '자산 흐름 보기'(소비분석 탭 자산 흐름 칸).
 * 계좌가 없으면 연결 안내, 예보만 실패하면 지금 잔고는 남기고 다시 시도. 월급을 정하기 전이면 B-06으로 가는 한 줄(11/13 결정)
 */
@Composable
private fun ForecastSection(
    slot: ForecastSlot,
    home: HomeSummary,
    brief: HomeSection<HomeForecastBrief>,
    onForecast: () -> Unit,
    onAssetFlow: () -> Unit,
    onRegisterIncome: () -> Unit,
    onStartLinking: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = YamuTheme.colors
    when (slot) {
        ForecastSlot.Hidden -> return
        ForecastSlot.NoAccount -> {
            YamuCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharacterImage(YamuPose.Wave, size = CharacterSize.S)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("계좌를 연결하면 잔고를 예보해요", style = YamuTheme.typography.titleM, color = c.text1)
                        Spacer(Modifier.height(2.dp))
                        Text("연결 전에는 잔고와 예보를 만들어 보여 드리지 않아요", style = YamuTheme.typography.sub, color = c.text2)
                    }
                }
                Spacer(Modifier.height(16.dp))
                NeutralButton("계좌 연결하기", onClick = onStartLinking)
            }
            return
        }
        else -> Unit
    }
    val data = brief.dataOrNull
    YamuCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("잔고예보", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            data?.accountLabel?.let { StatusTag(it, tone = TagTone.Neutral, guide = true) }
        }
        when {
            data != null -> {
                ForecastRow("지금 잔고", data.calculatedAtIso?.let(::asOfLabel), data.currentBalance, emphasize = false)
                ForecastRow("${HOME_FORECAST_DAYS}일 안 가장 낮을 때", data.minDate?.toMonthDayWeek(), data.minBalance, emphasize = true)
            }
            slot == ForecastSlot.HomeFailed -> SectionFailure("잔고를 불러오지 못했어요", onRetry)
            brief is HomeSection.Failed -> {
                // 예보만 실패 — 홈 요약이 읽은 지금 잔고는 남긴다(사용자 상태별 처리 '예측 실패')
                ForecastRow("지금 잔고", formatSyncedAt(home.syncedAtIso)?.replace(" 반영", " 기준"), home.forecast?.currentBalance, emphasize = false)
                SectionFailure("예보를 불러오지 못했어요", onRetry)
            }
            else -> {
                Spacer(Modifier.height(12.dp))
                SkeletonBlock(height = 22.dp, widthFraction = 0.9f)
                Spacer(Modifier.height(10.dp))
                SkeletonBlock(height = 22.dp, widthFraction = 0.9f)
            }
        }
        if (home.forecast?.needsIncome == true) {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(YamuRadius.input))
                    .background(c.primaryContainer)
                    .clickable(role = Role.Button, onClickLabel = "월급 등록하기", onClick = onRegisterIncome)
                    .heightIn(min = YamuSize.touch)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("월급날을 알려 주면 월급 전 잔고도 볼 수 있어요", style = YamuTheme.typography.bodyM, color = c.onPrimaryContainer, modifier = Modifier.weight(1f))
                Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralButton("잔고예보", onClick = onForecast, modifier = Modifier.weight(1f))
            NeutralButton("자산 흐름 보기", onClick = onAssetFlow, modifier = Modifier.weight(1f))
        }
    }
}

/** '지금 잔고 · 오늘 14:05 기준 ……… 412,000원' — 이름과 금액이 한 줄에 안 들어가면 금액이 다음 줄 오른쪽으로 */
@Composable
private fun ForecastRow(label: String, detail: String?, amount: Long?, emphasize: Boolean) {
    val c = YamuTheme.colors
    FlowRow(
        Modifier.padding(top = 12.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(listOfNotNull(label, detail).joinToString(" · "), style = YamuTheme.typography.bodyM, color = c.text3)
        // 금액을 모르면 0원이 아니라 '확인할 수 없음'(COMMON-07)
        if (amount != null) {
            AmountText(amount, size = AmountSize.Value, color = if (emphasize) c.primary else c.text1, shortage = amount < 0, modifier = Modifier.padding(start = 12.dp))
        } else {
            Text("확인할 수 없음", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

private const val HERO_PRESSED_SCALE = 0.97f
private const val HERO_SHEEN_MS = 900
private const val HERO_SHEEN_ALPHA = 0.16f
private const val HERO_GLASS_ALPHA = 0.16f
private val HERO_PILE_WIDTH = 116.dp
private val HERO_PILE_HEIGHT = 92.dp

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
                forecast = null,
                recentPayment = RecentPayment(1L, "야무카페 역삼점", 15_000, "신한카드", 1_500, OffsetDateTime.now().toString()),
                todos = emptyList(),
                cardCount = 6,
                unreadCount = 3,
                needsLinking = false,
                syncedAtIso = null,
                failedAreas = emptySet(),
            ),
            userName = "김야무",
            benefit = HomeSection.Ready(
                HomeBenefit(YearMonth.of(2026, 11), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 13), confirmed = 12_400, pending = 13_100, hasPayments = true),
            ),
            pay = HomeSection.Ready(PaySettings(autoSelect = true, defaultUserCardId = null)),
        ),
        onRefresh = {}, onRetry = {}, onPay = {}, onNotifications = {}, onMyCards = {},
        onStartLinking = {}, onRecentPayment = {},
    )
}
