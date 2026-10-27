package com.yamu.app.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalYamuColors = staticCompositionLocalOf { LightYamuColors }
private val LocalYamuTypography = staticCompositionLocalOf { YamuTypography() }

/**
 * 앱 테마. 다크 모드는 시스템 설정을 따른다(앱 안 토글 없음).
 * [primaryOverride]는 컴포넌트 갤러리의 '브랜드 색 미리보기'용.
 */
@Composable
fun YamuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    primaryOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkYamuColors else LightYamuColors
    val colors = if (primaryOverride != null) base.copy(primary = primaryOverride) else base
    val typography = remember { YamuTypography() }
    CompositionLocalProvider(
        LocalYamuColors provides colors,
        LocalYamuTypography provides typography,
    ) {
        MaterialTheme(colorScheme = colors.toMaterial()) {
            CompositionLocalProvider(LocalContentColor provides colors.text1) {
                ProvideTextStyle(typography.bodyM, content)
            }
        }
    }
}

/** 화면에서는 YamuTheme.colors.primary · YamuTheme.typography.amountL 처럼 쓴다 */
object YamuTheme {
    val colors: YamuColors
        @Composable @ReadOnlyComposable get() = LocalYamuColors.current
    val typography: YamuTypography
        @Composable @ReadOnlyComposable get() = LocalYamuTypography.current
}
