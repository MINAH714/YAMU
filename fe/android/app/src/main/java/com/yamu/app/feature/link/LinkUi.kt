package com.yamu.app.feature.link

import androidx.annotation.DrawableRes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.designsystem.component.CardArt
import com.yamu.app.core.designsystem.component.CardThumb
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.em
import com.yamu.app.core.designsystem.component.ExpandToggle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.feature.link.data.Brand

/** 은행은 브랜드 색 없이 원 안에 짧은 글자('KB') — 프로토타입 .logo-ph. [onSurface] = 회색 타일 위면 흰 원 */
@Composable
fun BankMark(brand: Brand, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp, onSurface: Boolean = true) {
    val c = YamuTheme.colors
    Box(
        modifier.size(size).clip(CircleShape).background(if (onSurface) c.surfaceVariant else c.surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(brand.shortName.take(2), style = YamuTheme.typography.labelS, color = c.text2, maxLines = 1)
    }
}

/** 은행인지 — 이름이 '은행'으로 끝나면(서버 기관 종류가 오면 그걸로 바꾼다) */
fun Brand.isBank(): Boolean = name.endsWith("은행")

/** 카드사 대표 색(바탕 · 글자) — 서버 색, 없으면 회색 */
@Composable
private fun Brand.colors(): Pair<Color, Color> {
    val c = YamuTheme.colors
    val bg = colorArgb?.let { Color(it) } ?: c.surfaceHigh
    val fg = when {
        colorArgb == null -> c.text2
        bg.luminance() > 0.5f -> Color(0xFF2B2B2B)     // KB 노랑 위는 진한 글자
        else -> Color.White
    }
    return bg to fg
}

/** 은행 · 카드사 표시 — 짧은 이름 + 대표 색(서버 색, 없으면 회색). 카드 견본(C-03)과 같은 색 */
@Composable
fun BrandMark(brand: Brand, modifier: Modifier = Modifier, large: Boolean = false) {
    val (bg, fg) = brand.colors()
    Box(
        modifier
            .heightIn(min = if (large) 28.dp else 20.dp)
            .widthIn(min = if (large) 44.dp else 32.dp)
            .clip(RoundedCornerShape(if (large) 6.dp else 4.dp))
            .background(bg)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            brand.shortName,
            style = YamuTheme.typography.labelS.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 11.sp, letterSpacing = (-0.04).em),
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * 큰 카드 — 실물 카드를 손에 든 크기(결제 요청 확인 P-06c · 카드추천 P-01). 폭은 부르는 쪽에서 widthIn(max = 320dp)으로 막는다.
 * 상품 그림(CardImages)이 있으면 그림, 없으면 카드사 색 카드에 짧은 이름 · 상품명 · 끝자리
 */
@Composable
fun BrandCardArt(brand: Brand, name: String, last4: String?, modifier: Modifier = Modifier) {
    val image = CardImages.forProduct(name)
    if (image != null) return CardArt(image, name, modifier)
    val (bg, fg) = brand.colors()
    Column(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(bg)
            .padding(YamuSpacing.x5)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(brand.shortName, style = YamuTheme.typography.labelL.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(name, style = YamuTheme.typography.titleS, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        last4?.let { Text("•••• $it", style = YamuTheme.typography.labelM, color = fg) }
    }
}

/** 카드 목록 줄 앞자리(M-02 · H-03) — 88dp 상품 그림, 없으면 같은 크기의 카드사 색 카드 */
@Composable
fun CardListThumb(brand: Brand, productName: String) {
    CardThumb(CardImages.forProduct(productName), width = 88.dp) {
        Box(Modifier.width(88.dp).aspectRatio(1.586f).clip(RoundedCornerShape(8.dp))) {
            BrandMark(brand, Modifier.fillMaxSize(), large = true)
        }
    }
}

/** 고르는 줄 — 로고 + 이름 ·끝자리 + 오른쪽 체크(C-03 · C-12) */
@Composable
fun SelectRow(
    brand: Brand,
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    suffix: String? = null,              // 끝자리 '·1021' — 이름보다 작고 회색
    chipSlot: Dp = 56.dp,                // 칩 칸 폭 — 목록 고르기(C-03) 56, 새 카드 확인(C-12) 44
    @DrawableRes cardImage: Int? = null, // 카드 상품 그림(CardImages) — 있으면 칩 자리에 작은 카드 그림(C-12)
) {
    val c = YamuTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (sub == null) YamuSize.listRowOne else YamuSize.listRowMin)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = listOfNotNull(title, suffix).joinToString(" ") }
            .padding(vertical = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        // 고르는 목록의 칩은 56dp 칸(프로토타입 .pk > .cw) — 은행 원도 같은 칸 가운데에 두어 이름 시작을 맞춘다
        Box(Modifier.width(chipSlot), contentAlignment = Alignment.Center) {
            if (brand.isBank()) BankMark(brand) else CardThumb(cardImage) { BrandMark(brand, Modifier.fillMaxWidth(), large = true) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (suffix != null) Text(suffix, style = YamuTheme.typography.bodyM, color = c.text3, maxLines = 1)
            }
            if (sub != null) Text(sub, style = YamuTheme.typography.sub, color = c.text3)
        }
        RoundCheckbox(checked = checked, onCheckedChange = null)
    }
}

/** 1원과 함께 온 숫자 — 칸마다 한 자리(서버 codeLength만큼). 문자 자동 입력 · 직접 입력 둘 다 */
@Composable
fun CodeBoxes(
    value: String,
    length: Int,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val c = YamuTheme.colors
    BasicTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter(Char::isDigit).take(length)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
                repeat(length) { i ->
                    val ch = value.getOrNull(i)
                    val shape = RoundedCornerShape(YamuRadius.input)
                    Box(
                        Modifier
                            .size(width = 44.dp, height = 52.dp)   // 프로토타입 .otp > span
                            .clip(shape)
                            .background(c.surfaceVariant)
                            .then(
                                when {
                                    error -> Modifier.border(1.dp, c.error, shape)
                                    i == value.length -> Modifier.border(1.dp, c.primary, shape)
                                    else -> Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(ch?.toString() ?: "", style = YamuTheme.typography.amountM.copy(fontSize = 24.sp), color = c.text1)
                    }
                }
            }
        },
    )
}

/** 찾는 중 — 캐릭터 + 한 문장(찾는 중 컷은 시안 대기라 카드 컷) */
@Composable
fun SearchingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharacterImage(YamuPose.Card, size = CharacterSize.M, animate = true)
        Spacer(Modifier.height(YamuSpacing.x4))
        Text(message, style = YamuTheme.typography.bodyL, color = YamuTheme.colors.text2, textAlign = TextAlign.Center)
    }
}

/** 접었다 펴는 줄 — '다른 은행 · 카드사' · '3장 더 보기'(공통 ExpandToggle, 펼치면 '접기') */
@Composable
fun FoldToggle(text: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    ExpandToggle(if (expanded) "접기" else text, expanded = expanded, onToggle = onToggle, modifier = modifier)
}
