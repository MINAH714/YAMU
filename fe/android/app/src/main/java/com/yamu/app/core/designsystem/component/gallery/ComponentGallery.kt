package com.yamu.app.core.designsystem.component.gallery

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.component.AmountSize
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.CardArt
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
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
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuSwitch
import com.yamu.app.core.designsystem.component.YamuTabs
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.BrandColor
import com.yamu.app.core.designsystem.theme.Issuer
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.ui.LocalSnackbar
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
                    GallerySections()
                    Spacer(Modifier.height(56.dp))
                }
            }
        }
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
        CardArt(Issuer.SHINHAN, "신한 생활 신용카드", "1021", Modifier.width(220.dp))
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

