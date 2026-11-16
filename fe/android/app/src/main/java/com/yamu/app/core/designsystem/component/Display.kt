package com.yamu.app.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.theme.Issuer
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon

enum class TagTone { Neutral, Primary, Success, Warning, Error }

/** 상태 태그 — 상태를 말할 때만, 한 줄에 1개, 아이콘 없음 */
@Composable
fun StatusTag(text: String, modifier: Modifier = Modifier, tone: TagTone = TagTone.Neutral, guide: Boolean = false, large: Boolean = false) {
    val c = YamuTheme.colors
    val (bg, fg) = when (tone) {
        TagTone.Neutral -> c.surfaceVariant to c.text2
        TagTone.Primary -> c.primaryContainer to (if (guide) c.primary else c.onPrimaryContainer)
        TagTone.Success -> c.successContainer to c.onSuccessContainer
        TagTone.Warning -> c.warningContainer to c.onWarningContainer
        TagTone.Error -> c.errorContainer to c.onErrorContainer
    }
    Box(
        modifier
            .heightIn(min = if (large) 28.dp else if (guide) 24.dp else YamuSize.tag)   // 판정 배지(.guide) 24dp · 분석 화면 판정 28dp
            .clip(RoundedCornerShape(YamuRadius.tag))
            .background(bg)
            .padding(horizontal = if (large) 10.dp else if (guide) 8.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (large) YamuTheme.typography.labelL else YamuTheme.typography.labelM, color = fg, maxLines = 1)
    }
}

/** '예측' — 상자 없이 작은 회색 글자. 서버가 isEstimate: true를 주면 금액 옆에 */
@Composable
fun EstimateTag(modifier: Modifier = Modifier, text: String = "예측") {
    Text(
        text,
        modifier = modifier,
        style = YamuTheme.typography.labelS.copy(fontWeight = FontWeight.Medium),
        color = YamuTheme.colors.text3,
    )
}

/**
 * 회색 알약 칩 — 흰 바탕(surface) 위에서만(회색 바탕 위에선 안 보임).
 * 누르는 칩은 보이는 알약(34dp)은 그대로 두고 터치 칸만 위아래로 넘쳐 48dp(S15P31E103-513). 칩 줄 사이는 8dp 넘게 띄운다
 */
@Composable
fun GreyChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = YamuTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(
                if (onClick != null) Modifier.overflowTouch(ChipTouchExtra, Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick))
                else Modifier,
            )
            .heightIn(min = YamuSize.chip)
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primaryContainer else c.surfaceVariant)
            .then(if (onClick != null) Modifier.indication(interaction, LocalIndication.current) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = YamuTheme.typography.labelL, color = if (selected) c.onPrimaryContainer else c.text2)
    }
}

/** 칩 터치 칸을 48dp로 — (48 − 34) / 2 */
private val ChipTouchExtra = (YamuSize.touch - YamuSize.chip) / 2

enum class AmountSize { Hero, L, M, Value }

/** 금액 — 숫자 + '원'(작게). 마이너스는 −, 부족일 때만 빨강, 추정이면 '예측' */
@Composable
fun AmountText(
    won: Long,
    modifier: Modifier = Modifier,
    size: AmountSize = AmountSize.L,
    estimate: Boolean = false,
    shortage: Boolean = false,
    withPlusSign: Boolean = false,
    color: Color? = null,                // 잔고예보 큰 숫자는 파랑(F-01) — 없으면 검정 · 부족이면 빨강
    unit: String = "원",
    fitOneLine: Boolean = false,         // 홈 히어로처럼 글자가 커서 넘칠 수 있는 곳 — 한 줄에 맞게 줄인다(큰 글씨 · 천만 원대)
) {
    val c = YamuTheme.colors
    val style = when (size) {
        AmountSize.Hero -> YamuTheme.typography.amountHero
        AmountSize.L -> YamuTheme.typography.amountL
        AmountSize.M -> YamuTheme.typography.amountM
        AmountSize.Value -> YamuTheme.typography.value
    }
    // 프로토타입(CSS)은 작은 '원'이 큰 숫자의 줄 높이(38px)를 그대로 물려받아 줄 아래로 4dp(중간 크기 3dp) 더 내려간다 —
    // 그래서 큰 금액 덩어리가 42dp. 아래 글줄 간격을 화면마다 맞추지 않도록 여기서 같은 만큼 띄운다
    val hang = when (size) {
        AmountSize.Hero -> 5.dp
        AmountSize.L -> 4.dp
        AmountSize.M -> 3.dp
        AmountSize.Value -> 0.dp
    }
    Row(modifier.padding(bottom = hang), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append(won.toWon(withPlusSign))
                // 단위는 0.6배 · SemiBold(프로토타입 .amount-l .u). 목록 값 크기(16sp)는 같은 크기로 — 11sp 아래로 내려가지 않게
                val small = size != AmountSize.Value
                // 0.6em — 한 줄에 맞게 줄일 때 숫자와 같이 줄도록 숫자 크기 기준으로
                withStyle(SpanStyle(fontSize = if (small) 0.6.em else style.fontSize, fontWeight = FontWeight.SemiBold)) {
                    append(if (small) "\u2009$unit" else unit)
                }
            },
            style = style,
            color = when {
                shortage -> c.error
                color != null -> color
                else -> c.text1
            },
            maxLines = if (fitOneLine) 1 else Int.MAX_VALUE,
            autoSize = if (fitOneLine) TextAutoSize.StepBased(minFontSize = style.fontSize * 0.5f, maxFontSize = style.fontSize, stepSize = 1.sp) else null,
        )
        if (estimate) {
            Spacer(Modifier.width(6.dp))
            EstimateTag()
        }
    }
}

/** 카드사 칩 — 짧은 이름 + 대표 색. 글자는 11sp 이상(프로토타입 9px에서 키움) */
@Composable
fun IssuerChip(issuer: Issuer, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        modifier
            .heightIn(min = if (large) 28.dp else 22.dp)
            .widthIn(min = if (large) 44.dp else 36.dp)
            .clip(RoundedCornerShape(if (large) 6.dp else 4.dp))
            .background(issuer.background)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            issuer.shortName,
            style = YamuTheme.typography.labelS.copy(fontWeight = FontWeight.Bold, fontSize = if (large) 13.sp else 11.sp),
            color = issuer.content,
            maxLines = 1,
        )
    }
}

/** 카드 모양 — 카드 추가 · 내 카드. 상품 그림(CardImages)이 있으면 그림을, 없으면 카드사 색 카드를 보여 준다 */
@Composable
fun CardArt(issuer: Issuer, name: String, last4: String, modifier: Modifier = Modifier, @DrawableRes image: Int? = null) {
    if (image != null) return CardArt(image, name, modifier)
    Column(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(issuer.background)
            .padding(16.dp),
    ) {
        Text(name, style = YamuTheme.typography.labelL, color = issuer.content)
        Spacer(Modifier.weight(1f))
        Text("•••• $last4", style = YamuTheme.typography.labelM, color = issuer.content)
    }
}

/**
 * 카드 상품 그림만 — 카드사 enum 없이 서버 Brand만 있는 화면(C-12)에서. 그림 모서리는 크기마다 달라 같은 모양으로 자른다.
 * 흰 카드가 흰 바탕에 묻히지 않게 1dp 테두리(테마 색). 그림은 테마와 무관하다
 */
@Composable
fun CardArt(@DrawableRes image: Int, name: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(YamuRadius.input)
    Image(
        painterResource(image),
        contentDescription = name,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(shape)
            .border(1.dp, YamuTheme.colors.outlineVariant, shape),
        contentScale = ContentScale.Crop,     // 그림 비율 1.583~1.590 — 잘리는 건 1px 안팎
    )
}

/**
 * 작은 카드 그림 — 목록 줄 앞자리. 기본 폭 44dp는 카드사 칩(large 44 × 28dp)과 같아 줄 높이가 그대로고, 카드 목록(M-02 · H-03)은 88dp.
 * 그림이 없으면 [fallback](카드사 칩). 줄 제목이 상품명을 읽으므로 그림은 꾸밈(설명 없음)
 */
@Composable
fun CardThumb(@DrawableRes image: Int?, modifier: Modifier = Modifier, width: Dp = 44.dp, fallback: @Composable () -> Unit) {
    if (image == null) return fallback()
    val shape = RoundedCornerShape(width / 11)     // 44 → 4dp · 88 → 8dp
    Image(
        painterResource(image),
        contentDescription = null,
        modifier = modifier
            .width(width)
            .aspectRatio(1.586f)
            .clip(shape)
            .border(1.dp, YamuTheme.colors.outlineVariant, shape),
        contentScale = ContentScale.Crop,
    )
}

@YamuPreviews
@Composable
private fun DisplayPreview() = YamuTheme {
    Column(Modifier.background(YamuTheme.colors.surface).padding(16.dp)) {
        AmountText(350_000, estimate = true)
        AmountText(-50_000, size = AmountSize.M, shortage = true)
        Row { StatusTag("실적 달성 추천", tone = TagTone.Primary); Spacer(Modifier.width(8.dp)); IssuerChip(Issuer.SHINHAN) }
        GreyChip("전체 보기", onClick = {})
    }
}
