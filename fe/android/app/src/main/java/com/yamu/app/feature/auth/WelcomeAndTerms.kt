package com.yamu.app.feature.auth

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.StartStepper
import com.yamu.app.core.designsystem.component.TopBarLogo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuPreviews
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme

/** A-01 첫 화면. [onLogin]이 null이면 로그인 버튼을 숨긴다(기기 재등록은 Task 8.2.9) */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onLogin: (() -> Unit)?, onDebugMenu: (() -> Unit)?) {
    val c = YamuTheme.colors
    Scaffold(
        topBar = { YamuTopBar("YAMU", logo = TopBarLogo.Start) },
        containerColor = c.surface,
        bottomBar = {
            BottomCta(secondary = onLogin?.let { { NeutralButton("로그인", onClick = it) } }) {
                PrimaryButton("시작하기", onClick = onStart)
            }
        },
    ) { inner ->
        // 위에서부터 쌓는다 — 앱바 아래 64dp에 캐릭터(프로토타입 A-01)
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().padding(horizontal = YamuSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            CharacterImage(YamuPose.Happy, size = CharacterSize.L, animate = true)
            Spacer(Modifier.height(36.dp))
            Text("결제는 간편하게,\n소비는 야무지게", style = YamuTheme.typography.headlineS, color = c.text1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("카드 혜택·실적·잔고를 한 번에 챙겨요", style = YamuTheme.typography.bodyM, color = c.text2, textAlign = TextAlign.Center)
            if (onDebugMenu != null) {
                Spacer(Modifier.height(24.dp))
                TextButtonGrey("디버그 메뉴", onClick = onDebugMenu, muted = true)
            }
        }
    }
}

/** [detail] = 전문을 볼 수 있는 약관(›). 금융 정보 조회 · 카드대금 자동이체는 계좌 · 카드 연결(C-02)에서 받는다 */
private data class Term(val title: String, val required: Boolean, val detail: Boolean = true)

private val TERMS = listOf(
    Term("만 14세 이상이에요", true, detail = false),
    Term("서비스 이용약관", true),
    Term("개인정보 수집·이용", true),
    Term("전자금융거래 이용약관", true),
    Term("마케팅 정보 수신", false),
    Term("위치기반서비스 이용약관", false),
)

/** A-04 약관 동의 — 필수 4개를 모두 켜야 다음으로. (동의 저장 API는 아직 없음 — 08 문서 4-6) */
@Composable
fun TermsScreen(onBack: () -> Unit, onAgree: () -> Unit, onOpenTerm: (String) -> Unit = {}) {
    val c = YamuTheme.colors
    // 본인인증 화면에 갔다 돌아와도 남게 rememberSaveable(화면 기록에 저장) — remember면 다시 그릴 때 다 풀린다(QA 05 · P2-37)
    var checked by rememberSaveable { mutableStateOf(List(TERMS.size) { false }) }
    val allRequired = TERMS.indices.filter { TERMS[it].required }.all { checked[it] }
    Scaffold(
        topBar = {
            Column(Modifier.background(c.surface)) {
                YamuTopBar("회원가입", onBack = onBack)
                StartStepper(step = 1, total = SIGNUP_STEPS, label = "약관 동의")
            }
        },
        bottomBar = { BottomCta { PrimaryButton("동의하고 계속", onClick = onAgree, enabled = allRequired) } },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .consumeWindowInsets(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            // 머리 위 20 · 머리–보조 8 · 보조–'모두 동의' 12 · 선 위 4(프로토타입 A-04 .hd · .all · .divider)
            Spacer(Modifier.height(20.dp))
            Text("약관에 동의해 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(8.dp))
            Text("꼭 필요한 것만 받아요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(12.dp))
            val all = checked.all { it }
            CheckRow("모두 동의", all, bold = true, partial = !all && checked.any { it }) { v -> checked = List(TERMS.size) { v } }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = c.outlineVariant)
            // 필수 · 선택 구역 — 줄마다 '[필수]'를 붙이지 않는다
            listOf(true to "필수", false to "선택").forEach { (required, name) ->
                Text(name, style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold), color = c.text3, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                TERMS.forEachIndexed { i, term ->
                    if (term.required == required) {
                        CheckRow(term.title, checked[i], onDetail = if (term.detail) ({ onOpenTerm(term.title) }) else null) { v ->
                            checked = checked.toMutableList().also { it[i] = v }
                        }
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

@Composable
private fun CheckRow(
    text: String,
    checked: Boolean,
    bold: Boolean = false,
    partial: Boolean = false,
    onDetail: (() -> Unit)? = null,     // 전문 보기 ›
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (bold) 64.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundCheckbox(checked = checked, onCheckedChange = null, partial = partial)
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = if (bold) YamuTheme.typography.titleM else YamuTheme.typography.bodyL,
                color = YamuTheme.colors.text1,
            )
        }
        if (onDetail != null) {
            // › 는 내용 끝선 너머로 12dp(프로토타입 .ag .icon-btn margin-right −12) — 아이콘이 오른쪽 여백선에 붙는다
            IconButton(onClick = onDetail, modifier = Modifier.offset(x = 12.dp)) {
                Icon(YamuIcons.ChevronRight, contentDescription = "$text 전문 보기", tint = YamuTheme.colors.text3)
            }
        }
    }
}

@YamuPreviews
@Composable
private fun WelcomePreview() = YamuTheme { WelcomeScreen(onStart = {}, onLogin = {}, onDebugMenu = null) }

@YamuPreviews
@Composable
private fun TermsPreview() = YamuTheme {
    var unused by rememberSaveable { mutableStateOf(0) }
    TermsScreen(onBack = { unused++ }, onAgree = {})
}
