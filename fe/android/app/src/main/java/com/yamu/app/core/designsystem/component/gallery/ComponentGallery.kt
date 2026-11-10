package com.yamu.app.core.designsystem.component.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.component.AmountSize
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.designsystem.component.CardArt
import com.yamu.app.core.designsystem.component.CardThumb
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterImageImpl
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.CoinPile
import com.yamu.app.core.designsystem.component.CoinPileDefaults
import com.yamu.app.core.designsystem.component.rememberCoinPileState
import com.yamu.app.core.designsystem.component.rememberRolledAmount
import com.yamu.app.core.designsystem.component.CountdownRing
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.GreyChip
import com.yamu.app.core.designsystem.component.IssuerChip
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.ListLeadIcon
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.MoreDetails
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PerformanceGauge
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StartStepper
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.WaitRingDefaults
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuSwitch
import com.yamu.app.core.designsystem.component.YamuTabs
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.BrandColor
import com.yamu.app.core.designsystem.theme.Issuer
import com.yamu.app.core.designsystem.theme.YamuMotionDebug
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.feature.payment.WaitPhase
import com.yamu.app.feature.payment.WaitingSurface
import kotlinx.coroutines.launch

/**
 * 컴포넌트 갤러리(debug 전용) — 1단계 끝 기준: 여기서 전부 라이트 · 다크 · 글자 1.3배로 깨지지 않는다.
 * '브랜드 색 미리보기'로 메인 색 후보(청록)를 바로 비교한다.
 */
@Composable
fun ComponentGallery(onBack: (() -> Unit)? = null) {
    var dark by rememberSaveable { mutableStateOf(false) }
    var bigFont by rememberSaveable { mutableStateOf(false) }
    var teal by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    YamuTheme(darkTheme = dark, primaryOverride = if (teal) BrandColor.CandidateTeal else null) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = if (bigFont) 1.3f else density.fontScale),
        ) {
            Scaffold(
                topBar = { YamuTopBar("컴포넌트 갤러리", onBack = onBack) },
                containerColor = YamuTheme.colors.background,
            ) { inner ->
                Column(
                    Modifier
                        .padding(inner)
                        .consumeWindowInsets(inner)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    YamuCard {
                        ToggleRow("다크 모드", dark) { dark = it }
                        ToggleRow("글자 1.3배", bigFont) { bigFont = it }
                        ToggleRow("브랜드 색 미리보기(청록 후보)", teal) { teal = it }
                    }
                    CharacterMotionSamples()
                    WaitMotionSamples()
                    CoinPileSamples()
                    GallerySections()
                    Spacer(Modifier.height(56.dp))
                }
            }
        }
    }
}

/**
 * 캐릭터 등장 견본 — 녹화로 박자를 재는 자리(모션 명세 M-DONE-character).
 * 다시 재생은 견본을 새로 그리거나(조용한 등장) 새 축하 키를 넘긴다(팝). '견본 다시 넣기'는 키를 비운 뒤
 * 견본을 새로 그려, 이미 쓴 축하가 다시 돌지 않는지 본다. 팝 크기를 잴 때는 회전을 끈다.
 */
@Composable
private fun CharacterMotionSamples() {
    var enterRun by rememberSaveable { mutableIntStateOf(0) }
    var popCount by rememberSaveable { mutableIntStateOf(0) }
    var popKey by rememberSaveable { mutableStateOf<Int?>(null) }
    var popMount by rememberSaveable { mutableIntStateOf(0) }
    var tilt by rememberSaveable { mutableStateOf(true) }
    YamuCard {
        Text("캐릭터 등장", style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
        ToggleRow("모션 줄이기 강제", YamuMotionDebug.forceReduceMotion) { YamuMotionDebug.forceReduceMotion = it }
        ToggleRow("팝 회전", tilt) { tilt = it }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            key(enterRun) { CharacterImage(YamuPose.Pay, size = CharacterSize.M, animate = true) }
            key(popMount) {
                CharacterImageImpl(
                    YamuPose.Card,
                    modifier = Modifier,
                    size = CharacterSize.M,
                    animate = false,
                    circle = false,
                    celebrateKey = popKey,
                    onCelebrated = { popKey = null },
                    tilt = tilt,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralButton("조용한 등장", onClick = { enterRun++ }, modifier = Modifier.weight(1f))
            NeutralButton("축하 팝", onClick = { popKey = ++popCount }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        TextButtonGrey("견본 다시 넣기(팝이 다시 돌면 안 됨)", onClick = { popMount++ })
    }
}

/**
 * 결제 승인 대기 견본 — 녹화로 헤일로 숨과 남은 시간 링을 재는 자리(모션 명세 M-WAIT-HALO · M-WAIT-COUNTDOWN).
 * 판은 실제 화면처럼 아래 터치를 막으니 견본 밖의 스위치 · 버튼으로 다룬다. 남은 시간 링은 실제 시계로 15초를 돌고
 * 10초부터 임박 색, 0초에 회색 트랙만 남는다. 작은 링이 실제 크기, 큰 링은 재기 쉬운 크기다.
 */
@Composable
private fun WaitMotionSamples() {
    var unknown by rememberSaveable { mutableStateOf(false) }
    var surfaceMount by rememberSaveable { mutableIntStateOf(0) }
    var countdownRun by rememberSaveable { mutableIntStateOf(0) }
    var deadline by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + COUNTDOWN_SAMPLE_MS) }
    YamuCard {
        Text("결제 승인 대기", style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
        ToggleRow("모션 줄이기 강제", YamuMotionDebug.forceReduceMotion) { YamuMotionDebug.forceReduceMotion = it }
        ToggleRow("결과 불명", unknown) { unknown = it }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(580.dp).clip(RoundedCornerShape(YamuRadius.card))) {
            key(surfaceMount) {
                WaitingSurface(
                    phase = if (unknown) WaitPhase.Unknown else WaitPhase.Processing,
                    title = if (unknown) "결제됐는지 확인하고 있어요" else "결제하고 있어요",
                    pill = if (unknown) "확인 중 · 응답을 받지 못했어요" else "신한카드 · 15,000원",
                    entering = true,          // '판 다시 넣기'로 들어오는 모션을 다시 본다
                    lightStatusBar = false,   // 갤러리는 위가 흰 화면이라 상태 표시줄을 바꾸지 않음
                    // 아래 버튼이 생겨도 원판이 그 자리에 있는지 보는 견본. 버튼 모양은 결과 불명 작업에서 정함
                    bottom = if (unknown) {
                        { NeutralButton("결과 다시 확인", onClick = {}) }
                    } else {
                        null
                    },
                )
            }
        }
        TextButtonGrey("판 다시 넣기", onClick = { surfaceMount++ })
        Spacer(Modifier.height(8.dp))
        Text("남은 시간 링", style = YamuTheme.typography.titleS, color = YamuTheme.colors.text2)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountdownRing(deadline, key = "gallery-mini-$countdownRun", modifier = Modifier.size(WaitRingDefaults.miniSize))
            CountdownRing(
                deadline,
                key = "gallery-big-$countdownRun",
                modifier = Modifier.size(96.dp),
                stroke = WaitRingDefaults.stroke,
            )
        }
        NeutralButton("15초부터 다시", onClick = {
            deadline = System.currentTimeMillis() + COUNTDOWN_SAMPLE_MS
            countdownRun++
        })
    }
}

private const val COUNTDOWN_SAMPLE_MS = 15_000L

/**
 * 동전 무더기 견본 — 홈 히어로 카드 아래 무대(같은 그러데이션 바탕). 금액 세 가지(3만 · 35만 · 500만 원 → 3 · 14 · 32개)를
 * 가장 낮은 무대(112dp)와 지금 홈쯤 높이(220dp)에서 보고, '다시 떨어뜨리기'로 들어오는 모션을 다시 본다. 동전을 누르면 튀어 오른다.
 */
@Composable
private fun CoinPileSamples() {
    var run by rememberSaveable { mutableIntStateOf(0) }
    val c = YamuTheme.colors
    YamuCard {
        Text("동전 무더기", style = YamuTheme.typography.titleM, color = c.text1)
        ToggleRow("모션 줄이기 강제", YamuMotionDebug.forceReduceMotion) { YamuMotionDebug.forceReduceMotion = it }
        listOf(30_000L to CoinPileDefaults.minHeight, 350_000L to 220.dp, 5_000_000L to 220.dp).forEach { (won, h) ->
            Spacer(Modifier.height(8.dp))
            Text("${won / 10_000}만 원 · 무대 ${h.value.toInt()}dp", style = YamuTheme.typography.bodyS, color = c.text3)
            key(run) {
                CoinPile(
                    won,
                    Modifier
                        .fillMaxWidth()
                        .height(h)
                        .clip(RoundedCornerShape(YamuRadius.input))
                        .background(Brush.linearGradient(listOf(c.heroStart, c.heroEnd)))
                        .padding(12.dp),
                )
            }
        }
        // 다 쌓인 뒤 금액이 바뀔 때 — 글자는 보이던 값에서 굴러가고, 동전은 늘어난 만큼 떨어지거나 줄어든 만큼 떠난다
        var won by rememberSaveable { mutableLongStateOf(350_000L) }
        Spacer(Modifier.height(8.dp))
        Text("금액 바꾸기 · 지금 ${won / 10_000}만 원", style = YamuTheme.typography.bodyS, color = c.text3)
        key(run) {
            val pile = rememberCoinPileState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(YamuRadius.input))
                    .background(Brush.linearGradient(listOf(c.heroStart, c.heroEnd)))
                    .padding(16.dp),
            ) {
                AmountText(rememberRolledAmount(won, pile), size = AmountSize.Hero, color = c.onHero, fitOneLine = true)
                CoinPile(won, Modifier.fillMaxWidth().height(180.dp), state = pile)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralButton("−10만 원", onClick = { won = (won - 100_000).coerceAtLeast(10_000) }, modifier = Modifier.weight(1f))
            NeutralButton("+30만 원", onClick = { won += 300_000 }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        NeutralButton("다시 떨어뜨리기", onClick = { run++ })
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1, modifier = Modifier.weight(1f))
        YamuSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = YamuTheme.typography.labelM,
        color = YamuTheme.colors.text3,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun GallerySections() {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var checked by rememberSaveable { mutableStateOf(true) }

    SectionTitle("A. 버튼")
    YamuCard {
        PrimaryButton("신한카드로 15,000원 결제", onClick = {})
        Spacer(Modifier.height(8.dp))
        PrimaryButton("불러오는 중", onClick = {}, loading = true)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("비활성", onClick = {}, enabled = false)
        Spacer(Modifier.height(8.dp))
        NeutralButton("다른 카드로", onClick = {})
        Row {
            TextButtonGrey("나중에 할게요", onClick = {})
            LinkButton("3개월 할부 · 다른 날과 비교", onClick = {})
        }
    }

    SectionTitle("B. 화면 틀")
    YamuCard { YamuTopBar("잔고예보", onBack = {}, actions = {}) }

    SectionTitle("C · D. 묶음 · 목록")
    YamuCard {
        ListRow("하나카드 대금", sub = "10/20 (화)", value = "−150,000원")
        ListRow("월세", sub = "10/25 (일)", value = "−250,000원", chevron = true, onClick = {})
        ListRow("받는 시각", valuePlain = "오전 8:30", chevron = true, onClick = {})
        ListRow("알림", lead = { ListLeadIcon(Icons.Rounded.Notifications) }, trailing = { YamuSwitch(checked, { checked = it }) })
    }

    SectionTitle("E. 표시")
    YamuCard {
        Text("월급 전 예상 잔액", style = YamuTheme.typography.titleS, color = YamuTheme.colors.text2)
        AmountText(350_000, estimate = true)
        AmountText(-50_000, size = AmountSize.M, shortage = true)
        AmountText(15_000, size = AmountSize.Value, withPlusSign = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusTag("실적 달성 추천", tone = TagTone.Primary)
            StatusTag("달성", tone = TagTone.Success)
            StatusTag("주의", tone = TagTone.Warning)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusTag("추가 소비 비추천", tone = TagTone.Error)
            StatusTag("자연 소비 유지")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Issuer.entries.forEach { IssuerChip(it) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GreyChip("전체 보기", onClick = {})
            GreyChip("선택됨", selected = true, onClick = {})
        }
        Spacer(Modifier.height(12.dp))
        // 카드 모양 — 그림 없음(카드사 색) · 상품 그림 있음
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardArt(Issuer.SHINHAN, "신한 생활 신용카드", "1021", Modifier.weight(1f))
            CardArt(Issuer.KB, "KB국민 청춘대로 톡톡카드", "1021", Modifier.weight(1f), image = CardImages.forProduct("KB국민 청춘대로 톡톡카드"))
        }
        Spacer(Modifier.height(8.dp))
        // 작은 카드 그림 — 그림 7장 + 그림이 없을 때(카드사 칩)
        (CardImages.all + null).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { image -> CardThumb(image) { IssuerChip(Issuer.WOORI, large = true) } }
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    SectionTitle("F. 입력")
    YamuCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundCheckbox(checked, { checked = it })
            Spacer(Modifier.width(8.dp))
            Text("필수 약관 모두 동의", style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
        }
        Spacer(Modifier.height(12.dp))
        Segmented(listOf("이번 달", "지난달"), segment, { segment = it })
        Spacer(Modifier.height(12.dp))
        YamuTabs(listOf("우리카드", "신한카드", "NH농협카드"), tab, { tab = it })
        Spacer(Modifier.height(12.dp))
        NumberKeypad(KeypadMode.Pin, onKey = {})
    }

    SectionTitle("G. 겹침")
    YamuCard {
        NeutralButton("바텀시트 열기", onClick = { showSheet = true })
        Spacer(Modifier.height(8.dp))
        NeutralButton("다이얼로그 열기", onClick = { showDialog = true })
        Spacer(Modifier.height(8.dp))
        NeutralButton(
            "스낵바 띄우기",
            onClick = {
                scope.launch {
                    snackbar.showSnackbar("통신비를 우리카드 결제로 계산했어요", actionLabel = "실행 취소", duration = SnackbarDuration.Short)
                }
            },
        )
    }

    SectionTitle("H. 그 밖")
    YamuCard {
        MoreDetails {
            Text("화면에서 뺀 설명은 지우지 않고 여기로 옮겨요.", style = YamuTheme.typography.bodyM, color = YamuTheme.colors.text2)
        }
        Spacer(Modifier.height(8.dp))
        StartStepper(step = 1, total = 3, label = "연결 완료")
        Spacer(Modifier.height(16.dp))
        Text("우리카드", style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
        PerformanceGauge(current = 320_000, target = 400_000)
        Spacer(Modifier.height(8.dp))
        Text("하나카드", style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
        PerformanceGauge(current = 300_000, target = 300_000)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CharacterImage(YamuPose.Happy, size = CharacterSize.S, animate = true)
            CharacterImage(YamuPose.Done, size = CharacterSize.S)
            CharacterImage(YamuPose.Coins, size = CharacterSize.S)
        }
        Spacer(Modifier.height(16.dp))
        SkeletonBlock(widthFraction = 0.6f)
        Spacer(Modifier.height(6.dp))
        SkeletonBlock(height = 28.dp)
        EmptyState("아직 결제가 없어요", actionText = "QR 결제하기", onAction = {})
        ErrorState("은행 연결이 늦어요", onRetry = {}, syncedAt = "오늘 09:38")
    }

    if (showSheet) {
        YamuBottomSheet(onDismiss = { showSheet = false }) {
            Text("사는 방법 비교", style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
            ListRow("11월 10일에 사기", sub = "월급 뒤", value = "350,000원")
            PrimaryButton("11월 10일로 계획 저장", onClick = { showSheet = false })
        }
    }
    if (showDialog) {
        YamuDialog(
            title = "로그아웃할까요?",
            text = "다시 들어올 땐 지문으로 열 수 있어요.",
            confirmText = "로그아웃",
            onConfirm = { showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
}

