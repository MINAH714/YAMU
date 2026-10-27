package com.yamu.app.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.yamu.app.R

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),          // 13sp 글자 버튼 · 읽은 알림(프로토타입 500)
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),    // YAMU 로고(800)
)

/** 한국어는 어절 단위로 줄을 바꾼다(프로토타입 word-break: keep-all) — 남았어/요처럼 끊기지 않게 */
private val KoreanLineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

/** 어절 줄바꿈은 글자 언어가 한국어일 때만 먹는다 — 기기 언어가 영어여도 한국어 규칙으로 그린다 */
private val Korean = LocaleList("ko-KR")

/** 줄 높이 위아래 여백을 자르지 않는다(Compose 기본은 첫 줄 위 · 끝 줄 아래를 잘라 머리–보조 사이가 4~7dp 좁아짐) — 프로토타입(CSS)처럼 글자를 줄 가운데에 */
private val CssLineHeight = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/** 자간은 프로토타입과 같게 — 기본 −0.01em · 머리 −0.02em · 금액 −0.03em(tokens.css) */
private fun style(size: Int, line: Int, weight: FontWeight, tabularNumbers: Boolean = false, tracking: Double = -0.01) = TextStyle(
    fontFamily = Pretendard,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = if (tabularNumbers) "tnum" else null,   // 금액 숫자 폭 고정
    lineBreak = KoreanLineBreak,
    localeList = Korean,
    lineHeightStyle = CssLineHeight,
)

/** 글자 단계 — 색은 넣지 않는다(화면에서 color = YamuTheme.colors.text3 처럼). 11sp보다 작은 글자 금지 */
@Immutable
data class YamuTypography(
    val amountL: TextStyle = style(30, 38, FontWeight.Bold, tabularNumbers = true, tracking = -0.03),
    val amountM: TextStyle = style(22, 30, FontWeight.Bold, tabularNumbers = true, tracking = -0.02),
    val headlineS: TextStyle = style(24, 34, FontWeight.Bold, tracking = -0.02),
    val titleL: TextStyle = style(20, 28, FontWeight.Bold),
    val appBar: TextStyle = style(18, 24, FontWeight.SemiBold),
    val button: TextStyle = style(17, 24, FontWeight.SemiBold),
    val cardTitle: TextStyle = style(17, 24, FontWeight.Bold),     // 카드 머리(프로토타입 .card-hd .ttl — 많이 쓴 곳 · 놓친 혜택)
    val titleM: TextStyle = style(16, 22, FontWeight.SemiBold),
    val value: TextStyle = style(16, 22, FontWeight.Bold, tabularNumbers = true),
    val titleS: TextStyle = style(14, 20, FontWeight.SemiBold),
    val bodyL: TextStyle = style(16, 24, FontWeight.Normal),
    val bodyM: TextStyle = style(14, 20, FontWeight.Normal),
    val sub: TextStyle = style(13, 18, FontWeight.Normal),
    val bodyS: TextStyle = style(12, 16, FontWeight.Normal),
    val labelL: TextStyle = style(14, 20, FontWeight.SemiBold),
    val labelM: TextStyle = style(12, 16, FontWeight.SemiBold),
    val labelS: TextStyle = style(11, 16, FontWeight.SemiBold),
    val link: TextStyle = style(13, 18, FontWeight.Medium),        // 조용한 글자 버튼 · 펼치기(자세히 · 모두 보기 · 전체 보기)
    val logo: TextStyle = style(22, 22, FontWeight.ExtraBold, tracking = -0.02),   // 앱바 YAMU
)
