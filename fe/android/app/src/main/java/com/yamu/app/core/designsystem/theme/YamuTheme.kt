package com.yamu.app.core.designsystem.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LocalYamuColors = staticCompositionLocalOf { LightYamuColors }
private val LocalYamuTypography = staticCompositionLocalOf { YamuTypography() }

/** 모션 줄이기. 켜지면 이동 · 크기 · 회전을 끄고 투명도만 남긴다(YamuMotion 참고) */
val LocalYamuReduceMotion = staticCompositionLocalOf { false }

/** 촬영 · 점검용(디버그 메뉴). 시스템 설정은 그대로 두고 앱만 모션 줄이기로 본다 */
object YamuMotionDebug {
    var forceReduceMotion by mutableStateOf(false)
}

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
    val reduceMotion = rememberSystemReduceMotion() || YamuMotionDebug.forceReduceMotion
    CompositionLocalProvider(
        LocalYamuColors provides colors,
        LocalYamuTypography provides typography,
        LocalYamuReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = colors.toMaterial()) {
            CompositionLocalProvider(LocalContentColor provides colors.text1) {
                ProvideTextStyle(typography.bodyM, content)
            }
        }
    }
}

/**
 * 시스템 설정 > 접근성 '애니메이션 삭제'(애니메이터 배율 0).
 * Compose는 이때 애니메이션을 끝값으로 보내기만 해서, 앱이 직접 읽어 '투명도만 남기기'로 바꾼다. 설정을 바꾸면 바로 따라간다.
 */
@Composable
private fun rememberSystemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var off by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return off
}

/** 화면에서는 YamuTheme.colors.primary · YamuTheme.typography.amountL 처럼 쓴다 */
object YamuTheme {
    val colors: YamuColors
        @Composable @ReadOnlyComposable get() = LocalYamuColors.current
    val typography: YamuTypography
        @Composable @ReadOnlyComposable get() = LocalYamuTypography.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalYamuReduceMotion.current
}
