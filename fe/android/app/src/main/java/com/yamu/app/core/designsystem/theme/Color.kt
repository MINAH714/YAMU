package com.yamu.app.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 화면에서 쓰는 색은 전부 여기서 — 화면 코드에 Color(0xFF…)를 직접 쓰지 않는다. 값은 android-proto/src/tokens.css */
@Immutable
data class YamuColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val background: Color,          // 탭 화면 바탕(회색)
    val surface: Color,             // 카드 · 흐름 화면 바탕(흰색)
    val surfaceVariant: Color,      // 회색 칸 · 칩
    val surfaceHigh: Color,
    val text1: Color,               // 글자 1단계
    val text2: Color,               // 글자 2단계
    val text3: Color,               // 글자 3단계(보조)
    val outline: Color,
    val outlineVariant: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val inverseSurface: Color,      // 스낵바
    val inverseOnSurface: Color,
    val scrim: Color,
    val qrInk: Color,               // 결제 QR · 바코드 — 테마와 무관하게 흰 바탕 위 검정
    val qrBackground: Color,
    val coinFace: Color,            // 동전 그림(홈 예상 잔액) 윗면 — 캐릭터처럼 그림 색이라 '브랜드 색 하나' 규칙 밖
    val coinSide: Color,            // 동전 옆면
    val coinLine: Color,            // 동전 사이 홈 · 윗면 안쪽 고리
    val coinGlow: Color,            // 동전 무더기 뒤 은은한 빛(히어로 카드 위)
    val heroStart: Color,           // 홈 히어로 카드(예상 잔액) 바탕 — 왼쪽 위에서 오른쪽 아래로. 브랜드 색에서 만든다
    val heroEnd: Color,
    val onHero: Color,              // 히어로 카드 위 글자
    val onHeroMuted: Color,         // 히어로 카드 위 보조 글자 · 칩 바탕
    val isDark: Boolean,
)

/** 히어로 카드 바탕 — 브랜드 색을 깊은 남색 쪽으로 눌러 금색 동전이 잘 보이게. 메인 색을 바꾸면 같이 바뀐다 */
private val HeroDeep = Color(0xFF0A1433)

val LightYamuColors = YamuColors(
    primary = BrandColor.Light,
    onPrimary = Color.White,
    primaryContainer = BrandColor.LightContainer,
    onPrimaryContainer = BrandColor.OnLightContainer,
    inversePrimary = BrandColor.LightInverse,
    background = Color(0xFFF2F4F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F4F6),
    surfaceHigh = Color(0xFFE5E8EB),
    text1 = Color(0xFF191F28),
    text2 = Color(0xFF4E5968),
    text3 = Color(0xFF8B95A1),
    outline = Color(0xFFD1D6DB),
    outlineVariant = Color(0xFFE5E8EB),
    success = Color(0xFF03B26C),
    successContainer = Color(0xFFEBF9F2),
    onSuccessContainer = Color(0xFF028A55),
    warning = Color(0xFFE57A00),
    warningContainer = Color(0xFFFFF4E5),
    onWarningContainer = Color(0xFFB35F00),
    error = Color(0xFFF04452),
    errorContainer = Color(0xFFFFEEEF),
    onErrorContainer = Color(0xFFD22030),
    inverseSurface = Color(0xFF333D4B),
    inverseOnSurface = Color(0xFFFFFFFF),
    scrim = Color(0x66000000),
    qrInk = Color(0xFF000000),
    qrBackground = Color(0xFFFFFFFF),
    coinFace = Color(0xFFFFD43B),
    coinSide = Color(0xFFF5A800),
    coinLine = Color(0xFFD98C00),
    coinGlow = Color(0xFFFFF1C9),
    heroStart = lerp(BrandColor.Light, HeroDeep, 0.15f),
    heroEnd = lerp(BrandColor.Light, HeroDeep, 0.7f),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xB3FFFFFF),
    isDark = false,
)

val DarkYamuColors = YamuColors(
    primary = BrandColor.Dark,
    onPrimary = Color.White,
    primaryContainer = BrandColor.DarkContainer,
    onPrimaryContainer = BrandColor.OnDarkContainer,
    inversePrimary = BrandColor.DarkInverse,
    background = Color(0xFF17171C),
    surface = Color(0xFF202027),
    surfaceVariant = Color(0xFF2C2C35),
    surfaceHigh = Color(0xFF3A3A45),
    text1 = Color(0xFFEDEDF0),
    text2 = Color(0xFFB4B4BD),
    text3 = Color(0xFF80808B),
    outline = Color(0xFF4D4D59),
    outlineVariant = Color(0xFF33333D),
    success = Color(0xFF2FCA8B),
    successContainer = Color(0xFF11392A),
    onSuccessContainer = Color(0xFFA8EDCB),
    warning = Color(0xFFFFAE42),
    warningContainer = Color(0xFF3F2C12),
    onWarningContainer = Color(0xFFFFD9A6),
    error = Color(0xFFFF6B76),
    errorContainer = Color(0xFF4A1A20),
    onErrorContainer = Color(0xFFFFD3D7),
    inverseSurface = Color(0xFFEDEDF0),
    inverseOnSurface = Color(0xFF202027),
    scrim = Color(0x99000000),
    qrInk = Color(0xFF000000),
    qrBackground = Color(0xFFFFFFFF),
    coinFace = Color(0xFFF2C94C),
    coinSide = Color(0xFFD99A0B),
    coinLine = Color(0xFFA87400),
    coinGlow = Color(0xFFFFEBB5),
    heroStart = lerp(BrandColor.Dark, HeroDeep, 0.35f),
    heroEnd = lerp(BrandColor.Dark, HeroDeep, 0.78f),
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xB3FFFFFF),
    isDark = true,
)

/** Material 컴포넌트(시트 · 다이얼로그 · 스위치 등)도 같은 색을 쓰게 */
fun YamuColors.toMaterial(): ColorScheme =
    (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        background = background,
        onBackground = text1,
        surface = surface,
        onSurface = text1,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = text2,
        outline = outline,
        outlineVariant = outlineVariant,
        tertiary = success,
        error = error,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        scrim = scrim,
    )
