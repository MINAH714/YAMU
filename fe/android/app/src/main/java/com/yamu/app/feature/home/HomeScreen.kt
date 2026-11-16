package com.yamu.app.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.formatSyncedAt
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.ui.ReloadOnReconnect
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.home.data.BenefitByType
import com.yamu.app.feature.home.data.CardBenefit
import com.yamu.app.feature.home.data.HOME_CARD_ROWS
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeCardRow
import com.yamu.app.feature.home.data.HomeCardsData
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeGreeting
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.ConditionLine
import com.yamu.app.feature.home.data.MissedBenefits
import com.yamu.app.feature.home.data.RemainingLimit
import com.yamu.app.feature.home.data.PaySettings
import com.yamu.app.feature.home.data.PerfChipTone
import com.yamu.app.feature.home.data.RecentPayment
import com.yamu.app.feature.home.data.amountText
import com.yamu.app.feature.home.data.benefitCoins
import com.yamu.app.feature.home.data.benefitTitle
import com.yamu.app.feature.home.data.cardTileDescription
import com.yamu.app.feature.home.data.emptyNote
import com.yamu.app.feature.home.data.homeGreeting
import com.yamu.app.feature.home.data.minLabel
import com.yamu.app.feature.home.data.missed
import com.yamu.app.feature.home.data.paidAtLabel
import com.yamu.app.feature.home.data.pendingOnly
import com.yamu.app.feature.home.data.recentPaymentOutsidePayCard
import com.yamu.app.feature.home.data.rows
import com.yamu.app.feature.home.data.showsTypes
import com.yamu.app.feature.home.data.unconvertedNote
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.CardArtRef
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/**
 * H-01 홈 — v4(11/13 '홈 개편 및 소비 분석 확장 요구사항 명세서'). 위에서부터 인사 → 이번 달 혜택 → 잔고예보 보기 버튼(S15P31E103-520)
 * → 내 카드 혜택 현황 → 놓치기 아까운 혜택 → 잔고예보 요약(S15P31E103-481). 큰 자산 흐름 그래프는 홈에 두지 않는다(HOME-16).
 * 혜택 아래 결제 버튼(-480 · -499)은 11/16에 뺐다(-519) — 결제는 하단 가운데 버튼
 */
@Composable
fun HomeRoute(
    onForecast: () -> Unit,
    onNotifications: () -> Unit,
    onMyCards: () -> Unit,
    onStartLinking: () -> Unit,
    onRecentPayment: (paymentId: Long) -> Unit,
    onPayments: () -> Unit,
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
                            onStartLinking = onStartLinking,
                            onRecentPayment = onRecentPayment,
                            onPayments = onPayments,
                            onBenefitReport = onBenefitReport,
                            onAddCard = onAddCard,
                            onForecast = onForecast,
                        )
                        // 아래쪽(S15P31E103-481) — 카드가 있어야 보이는 두 칸, 그다음 잔고예보 요약
                        if (!content.needsLinking && !content.needsCard) {
                            CardsSection(state.cards, arts, state.cardBenefitsShown(), onCard = onCardPerformance, onAll = onPerfTab, onRetry = onRetryCards)
                            state.cards.dataOrNull?.missed()?.takeUnless { it.isEmpty }?.let { MissedSection(it, arts, onCard = onCardPerformance, onAll = onBenefitLimits) }
                        }
                        ForecastSection(
                            slot = forecastSlot(content),
                            home = content,
                            brief = state.forecast,
                            onForecast = onForecast,
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
    onStartLinking: () -> Unit,
    onRecentPayment: (Long) -> Unit,
    onPayments: () -> Unit,
    onBenefitReport: () -> Unit,
    onAddCard: () -> Unit,
    onForecast: () -> Unit,
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
                body = "결제할 때 혜택이 큰 카드를 골라 드리고 이용 혜택과 잔고를 여기 모아 드려요",
                button = "계좌 · 카드 연결하기",
                onClick = onStartLinking,
            )
            RecentPaymentCard(home.recentPaymentOutsidePayCard(), onRecentPayment, onPayments)
        }
        // 꺼진 결제 버튼 대신 다음 할 일 — 카드 추가(C-11)로(S15P31E103-430)
        home.needsCard -> {
            SetupGuide(
                title = "카드를 등록해 주세요",
                body = "결제할 때 혜택이 큰 카드를 골라 드리고 이용 혜택을 여기 모아 드려요",
                button = "카드 등록하기",
                onClick = onAddCard,
            )
            RecentPaymentCard(home.recentPaymentOutsidePayCard(), onRecentPayment, onPayments)
        }
        // 카드 영역만 실패했으면 몇 장인지 몰라 혜택 · 결제 칸을 그린다 — 카드가 정말 없으면 결제 코드 화면이 알려 준다
        // 혜택 히어로 아래 'YAMU 결제 바코드 열기' 버튼은 11/16 사용자 요청으로 뺐다(S15P31E103-519) — 결제는 하단 가운데 버튼 하나로 연다
        else -> {
            BenefitHero(state.benefit, onClick = onBenefitReport, onRetry = onRetryBenefit)
            // 옛 결제 버튼 자리에 잔고예보로 가는 브랜드 색 버튼(11/16 사용자 요청, S15P31E103-520) — 혜택을 본 다음 이번 달 잔고를 이어서 본다
            PrimaryButton(
                "잔고예보 보기",
                onClick = onForecast,
                modifier = Modifier.padding(horizontal = YamuSpacing.screen).padding(bottom = YamuSpacing.blockGap),
            )
        }
    }
}

/** 인사 두 줄 — '○○님, 11월 혜택을 모아 봤어요' / '11/1~11/13' */
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
 * 큰 숫자와 동전은 카드사가 확정한 혜택만, 아직 확정 전인 금액은 '1,500원 혜택 예정' 칩으로만 떼어 놓는다(HOME-04).
 * 11/13 시안의 점선 동전과 '확정 전 +' 문구는 11/16에 뺐다 — 비어 보이고 캐시백 · 적립은 YAMU에서 확정되지 않아 '확정 전'에서 벗어나지 못한다.
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
            HeroTitle(benefitTitle(null), more = false)
            Spacer(Modifier.height(YamuSpacing.x3))
            Box(Modifier.width(150.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.onHero.copy(alpha = HERO_GLASS_ALPHA)))
            Spacer(Modifier.height(YamuSpacing.x3))
            Box(Modifier.width(110.dp).height(22.dp).clip(RoundedCornerShape(YamuRadius.input)).background(c.onHero.copy(alpha = HERO_GLASS_ALPHA)))
        }
        // 혜택만 실패 — 홈의 다른 칸은 그대로 그리고 여기만 다시 시도(COMMON-08)
        is HomeSection.Failed -> HeroFrame(onClick = null) {
            HeroTitle(benefitTitle(null), more = false)
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
    val coins = benefitCoins(b.confirmed)
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
    val shown = if (coins > 0) rememberRolledAmount(b.confirmed, pile) else b.confirmed
    val described = buildString {
        append("${benefitTitle(b.month)} 확정 ${b.confirmed.toWon()}원")
        if (b.pending > 0) append(", ${b.pending.toWon()}원 혜택 예정")
    }

    HeroFrame(onClick = onClick, onClickLabel = "혜택 리포트 보기", sheen = sheen.value) {
        HeroTitle(benefitTitle(b.month), more = true)
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
                        if (b.pending > 0) HeroChip("${b.pending.toWon()}원 혜택 예정", dashed = true)
                    }
                }
            }
            if (coins > 0) {
                CoinPile(
                    solidCoins = coins,
                    ghostCoins = 0,
                    modifier = Modifier.padding(start = YamuSpacing.x2).width(HERO_PILE_WIDTH).height(HERO_PILE_HEIGHT),
                    state = pile,
                )
            }
        }
        if (b.showsTypes) {
            Spacer(Modifier.height(YamuSpacing.x3))
            BenefitTypeCells(b.byType)
        }
        // 동전 모양 풀이 줄은 11/15에 뺐다 — 확정 · 혜택 예정 칩이 말한다
        b.unconvertedNote()?.let {
            Spacer(Modifier.height(YamuSpacing.x3))
            Text(it, style = YamuTheme.typography.bodyS, color = c.onHeroMuted)
        }
    }
}

/**
 * 종류별 세 칸(할인 · 캐시백 · 적립, BE 순서) — 확정 금액만(11/14 결정: 세 칸 합 = 큰 숫자).
 * 확정 전만 있는 종류는 0원 대신 '확정 전', 원화로 못 바꾼 것만 있으면 '환산 전'
 */
@Composable
private fun BenefitTypeCells(types: List<BenefitByType>) {
    val c = YamuTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        types.forEach { t ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.onHero.copy(alpha = HERO_GLASS_ALPHA))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(t.type.label, style = YamuTheme.typography.bodyS, color = c.onHeroMuted)
                // 칸이 좁아 큰 글자(1.3배)에서 '19,30…'으로 잘렸다 — 한 줄로 두고 글자를 줄이며, 그래도 넘치면 말줄임(-494와 같게)
                val style = YamuTheme.typography.labelL
                Text(
                    t.amountText(),
                    style = style,
                    // 확정 전만 있는 칸은 흐리게 — 큰 숫자(확정)에 더해지지 않는 금액이라(S15P31E103-520)
                    color = if (t.pendingOnly) c.onHeroMuted else c.onHero,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
                )
            }
        }
    }
}

/** 히어로 바탕 — 브랜드 색 깊은 그러데이션, 누르면 0.97로 살짝 눌림(모션 줄이기면 대신 옅어짐), [sheen] 0~1이면 빛이 한 번 훑는다 */
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
    // 모션 줄이기면 줄어드는 대신 옅어져 누른 것을 보인다(물결은 꺼 두어서 이것이 없으면 아무 표시가 없다, S15P31E103-513)
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed && reduce) YamuMotion.REDUCED_PRESS_ALPHA else 1f,
        animationSpec = tween(YamuMotion.SHORT_MS),
        label = "heroPressAlpha",
    )
    Column(
        Modifier
            .padding(horizontal = YamuSpacing.screen)
            .padding(bottom = YamuSpacing.blockGap)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = pressAlpha
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

/** 히어로 위 작은 유리 칩. [dashed]면 바탕 없이 점선 테두리 — 아직 확정 전인 '혜택 예정' 금액 */
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
        if (link != null) DetailLink(link, onLink)
    }
}

/** 칸 머리 오른쪽 조용한 링크 '전체보기 ›' · '자세히 ›' — 머리 줄은 40dp 그대로, 터치 칸(48)만 위아래로 넘친다(S15P31E103-513) */
@Composable
private fun DetailLink(text: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .overflowHeight(40.dp)
            .heightIn(min = YamuSize.touch)
            .clip(RoundedCornerShape(YamuRadius.s))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = YamuTheme.typography.link, color = c.text3)
        Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
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
 * 내 카드 혜택 현황(HOME-10 · 11) — 카드 목록 순서로 3장 + '전체보기 ›'(실적관리 탭).
 * 11/16 사용자 요청으로 세로 줄 대신 놓치기 아까운 혜택과 같은 카드 그림 가로 타일로 바꿨다(S15P31E103-519, 한눈에 보이게).
 * 타일은 카드 이름 → 받은 혜택 → 실적 막대(-520에서 글자를 줄임). 받은 혜택은 새 서버(BE -489)의 카드별 혜택이 있을 때만 —
 * 옛 서버이거나 그 호출만 실패했거나 이번 달 YAMU 결제가 없는 카드면 그 줄을 숨긴다(11/13 결정: 0원으로 지어내지 않음).
 * 누르면 그 카드 R-02(실적 조건 없는 카드는 R-02에 보일 실적이 없어 열지 않는다)
 */
@Composable
private fun CardsSection(
    section: HomeSection<HomeCardsData>,
    arts: Map<Long, CardArtRef>,
    benefits: Map<Long, CardBenefit>?,
    onCard: (Long) -> Unit,
    onAll: () -> Unit,
    onRetry: () -> Unit,
) {
    val rows = section.dataOrNull?.rows()
    if (rows != null && rows.isEmpty()) return   // 카드 영역만 실패해 0장으로 읽힌 홈 — 실적도 0장이면 그릴 것이 없다
    YamuCard(contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) { SectionTitle("내 카드 혜택 현황", link = "전체보기", onLink = onAll) }
        Spacer(Modifier.height(12.dp))
        when {
            rows != null -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rows.forEach { row ->
                    CardTile(row, arts[row.userCardId], benefits?.get(row.userCardId), onClick = if (row.opens) ({ onCard(row.userCardId) }) else null)
                }
            }
            section is HomeSection.Failed -> Box(Modifier.padding(horizontal = 20.dp)) { SectionFailure("카드 현황을 불러오지 못했어요", onRetry) }
            else -> Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(HOME_CARD_ROWS) {
                    Column(Modifier.width(MISSED_TILE_WIDTH)) {
                        SkeletonBlock(height = CARD_TILE_ART_HEIGHT)
                        Spacer(Modifier.height(10.dp))
                        SkeletonBlock(height = 16.dp, widthFraction = 0.7f)
                        Spacer(Modifier.height(6.dp))
                        SkeletonBlock(height = 16.dp, widthFraction = 0.5f)
                    }
                }
            }
        }
    }
}

/** 카드 타일 그림 높이(폭 148dp 카드의 1.586:1) — 불러오는 중 회색 막대 크기 */
private val CARD_TILE_ART_HEIGHT = 93.dp

/** 내 카드 타일 한 장 — 읽을 때는 [cardTileDescription] 한 문장 */
@Composable
private fun CardTile(row: HomeCardRow, art: CardArtRef?, benefit: CardBenefit?, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    CardArtTile(
        art, row.brand, art?.productName ?: row.productName ?: row.name,
        description = cardTileDescription(row, benefit),
        onClickLabel = "카드 실적 보기",
        onClick = onClick,
    ) {
        // 11/16 사용자 요청(S15P31E103-520): 글자는 이름과 받은 혜택만, 나머지는 그림(실적 막대). 혜택 예정 · 남은 한도 · 실적 글자는
        // 카드를 눌러 여는 R-02에서 보고, 화면 읽기는 [cardTileDescription]으로 다 읽는다
        Text(row.name, style = YamuTheme.typography.sub, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        benefit?.let { b -> Text("${b.confirmed.toWon()}원 받음", style = YamuTheme.typography.value, color = c.primary, maxLines = 1) }
        row.progress?.let { p ->
            Spacer(Modifier.height(8.dp))
            val done = row.chip.tone == PerfChipTone.Done
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (done) c.success else c.primary))
            }
        }
    }
}

/**
 * 놓치기 아까운 혜택(HOME-12 · 13) — 확인된 것이 있을 때만. 실물 카드 그림을 크게 둔 카드를 옆으로 넘긴다(11/15 결정, 글 목록 대신).
 * 남은 한도(금액을 아는 이번 달 한도, 많은 순)가 먼저, 그다음 조건 카드(이번 달 실적 → 다음 달 혜택, 남은 금액 적은 순).
 * 더 쓰라는 말은 쓰지 않는다 — 조건 카드는 지금 실적과 목표를 막대로만 보인다. 한도 카드를 누르면 R-06, 조건 카드를 누르면 그 카드 R-02,
 * '남은 혜택 모두 보기 ›'는 R-06. 설명 두 줄('확인할 수 있는 것만 모았어요' · '더 쓰는 게 이득이라는 뜻은 아니에요')은 11/15에 뺐다
 */
@Composable
private fun MissedSection(missed: MissedBenefits, arts: Map<Long, CardArtRef>, onCard: (Long) -> Unit, onAll: () -> Unit) {
    YamuCard(contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) { SectionTitle("놓치기 아까운 혜택") }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            missed.remaining.forEach { r -> LimitTile(r, arts[r.userCardId], onClick = onAll) }
            missed.conditions.forEach { line -> ConditionTile(line, arts[line.userCardId], onClick = { onCard(line.userCardId) }) }
        }
        Box(Modifier.padding(horizontal = 16.dp)) { QuietLink("남은 혜택 모두 보기 ›", onClick = onAll) }
    }
}

/** 놓치기 아까운 혜택 카드 한 장의 폭 — 360dp 화면에서 두 장이 다 보이고 셋째가 조금 보인다 */
private val MISSED_TILE_WIDTH = 148.dp

/**
 * 카드 그림(카드 목록의 상품 그림, 없으면 카드사 색 카드) + 아래 글 — 읽을 때는 [description] 한 문장.
 * 놓치기 아까운 혜택과 내 카드 혜택 현황이 같이 쓴다. [onClick]이 null이면 누를 수 없는 타일(실적 조건 없는 카드)
 */
@Composable
private fun CardArtTile(art: CardArtRef?, brand: Brand, artName: String, description: String, onClickLabel: String, onClick: (() -> Unit)?, body: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(MISSED_TILE_WIDTH)
            .clip(RoundedCornerShape(YamuRadius.s))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = description }
            .padding(bottom = 8.dp),
    ) {
        CardListThumb(art?.brand ?: brand, art?.productName ?: artName, width = MISSED_TILE_WIDTH, dimmed = art?.dimmed == true)
        Spacer(Modifier.height(10.dp))
        body()
    }
}

/** 남은 한도 카드 — 카드 이름 · 한도 이름 · 남은 금액 */
@Composable
private fun LimitTile(r: RemainingLimit, art: CardArtRef?, onClick: () -> Unit) {
    val c = YamuTheme.colors
    CardArtTile(art, r.brand, r.artName, "${r.cardName}, ${r.limitName} ${r.amount.toWon()}원 남음", onClickLabel = "혜택 한도 보기", onClick = onClick) {
        Text(r.cardName, style = YamuTheme.typography.sub, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.limitName, style = YamuTheme.typography.bodyM, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text("${r.amount.toWon()}원 남음", style = YamuTheme.typography.value, color = c.primary, maxLines = 1)
    }
}

/** 조건 카드 — 카드 이름 · '다음 달 혜택 조건' · 지금 실적 / 목표 막대. 목표 금액을 모르면 막대 없이 목표 표시와 지금 실적 */
@Composable
private fun ConditionTile(line: ConditionLine, art: CardArtRef?, onClick: () -> Unit) {
    val c = YamuTheme.colors
    CardArtTile(art, line.brand, line.artName, "${line.cardName}, ${line.text}", onClickLabel = "카드 실적 보기", onClick = onClick) {
        Text(line.cardName, style = YamuTheme.typography.sub, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("다음 달 혜택 조건", style = YamuTheme.typography.bodyM, color = c.text1, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        val progress = line.progress
        if (progress != null && line.target != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.primary))
            }
            Spacer(Modifier.height(4.dp))
            Text("${line.recognized.manShort()} / ${line.target.manShort()}원", style = YamuTheme.typography.sub, color = c.text2, maxLines = 1)
        } else {
            Text(line.targetText, style = YamuTheme.typography.sub, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("지금 ${line.recognized.toWon()}원", style = YamuTheme.typography.sub, color = c.text2, maxLines = 1)
        }
    }
}

/** 막대 아래 짧은 금액 — 25.5만 · 30만(0.1만 아래는 버림), 1만 원 아래는 그대로 8,000 */
internal fun Long.manShort(): String {
    if (this < 10_000) return toWon()
    val tenths = this / 1_000
    return if (tenths % 10 == 0L) "${tenths / 10}만" else "${tenths / 10}.${tenths % 10}만"
}

/**
 * 잔고예보 요약(HOME-14 · 15) — 기준 계좌 하나의 지금 잔고와 30일 안 가장 낮은 잔고(잔고예보 API 결과를 다시 씀, FORECAST-02).
 * 계좌 칩으로 계좌 범위를, 줄 이름으로 기간을 말한다. 제목 옆 '자세히 ›'가 F-01(같은 계좌 · 30일)을 연다.
 * 11/16 사용자 요청(S15P31E103-520): 아래 버튼 둘('잔고예보' · '자산 흐름 보기')을 빼고 줄 뒤 '· 오늘 HH:mm 기준' · '· 최저 날짜'를 지웠다.
 * 계좌가 없으면 연결 안내, 예보만 실패하면 지금 잔고는 남기고 다시 시도. 월급을 정하기 전이면 B-06으로 가는 한 줄(11/13 결정)
 */
@Composable
private fun ForecastSection(
    slot: ForecastSlot,
    home: HomeSummary,
    brief: HomeSection<HomeForecastBrief>,
    onForecast: () -> Unit,
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
    // 지난 기준 계좌의 요약이 남아 있으면 그리지 않는다(새 계좌를 불러오는 중으로) — '잔고예보' 버튼이 여는 계좌와 맞춘다
    val data = brief.forAccount(home.baseAccountId).dataOrNull
    YamuCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("잔고예보", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            // 생활비 자료가 부족해 어림한 예보 — 첫 분석 화면과 같은 '잠정'(확정 결과처럼 보이지 않게)
            if (data?.provisional == true) StatusTag("잠정", tone = TagTone.Neutral, guide = true)
            data?.accountLabel?.let { StatusTag(it, tone = TagTone.Neutral, guide = true) }
            DetailLink("자세히", onForecast)
        }
        when {
            data != null -> {
                ForecastRow("지금 잔고", null, data.currentBalance, emphasize = false)
                ForecastRow(data.minLabel, null, data.minBalance, emphasize = true)
            }
            slot == ForecastSlot.HomeFailed -> SectionFailure("잔고를 불러오지 못했어요", onRetry)
            brief is HomeSection.Failed -> {
                // 예보만 실패 — 홈 요약이 읽은 지금 잔고는 남긴다(사용자 상태별 처리 '예측 실패')
                ForecastRow("지금 잔고", null, home.forecast?.currentBalance, emphasize = false)
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
        // 남은 너비를 채우고 오른쪽에 붙인다 — 다음 줄로 넘어가도 왼쪽으로 가지 않게(SpaceBetween은 줄에 혼자면 왼쪽)
        Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterEnd) {
            // 금액을 모르면 0원이 아니라 '확인할 수 없음'(COMMON-07)
            if (amount != null) {
                AmountText(amount, size = AmountSize.Value, color = if (emphasize) c.primary else c.text1, shortage = amount < 0)
            } else {
                Text("확인할 수 없음", style = YamuTheme.typography.bodyM, color = c.text3)
            }
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
        onRefresh = {}, onRetry = {}, onNotifications = {}, onMyCards = {},
        onStartLinking = {}, onRecentPayment = {},
    )
}
