package com.yamu.app.core.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.yamu.app.BuildConfig

/** Compose 안에서 지금 Activity 찾기 — 지문 창(BiometricPrompt)에 필요 */
fun Context.findActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private var secureScreens = 0   // 메인 스레드(Compose 효과)에서만 바꾼다

/** 촬영 · 점검용(디버그 메뉴, debug 빌드에서만 듣는다). 켜면 SecureScreen이 캡처를 막지 않아 화면 녹화로 전환을 잴 수 있다. 앱을 다시 켜면 꺼진다 */
object SecureScreenDebug {
    var allowCapture by mutableStateOf(false)
}

/**
 * 이 화면이 보이는 동안 캡처 · 최근 앱 미리보기를 막는다 — 간편 비밀번호 · 결제 코드 · 카드 번호 입력.
 * 수를 세는 까닭: 화면 전환 때 새 화면이 먼저 켜고 앞 화면이 뒤늦게 꺼서(로그인 → 카드 번호 입력) 막아 둔 것이 풀리지 않게
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity()
    val allowCapture = BuildConfig.DEBUG && SecureScreenDebug.allowCapture
    DisposableEffect(activity, allowCapture) {
        if (allowCapture) return@DisposableEffect onDispose {}
        secureScreens++
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secureScreens--
            if (secureScreens == 0) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** 결제 코드가 보이는 동안 화면 밝기 최대(가맹점 단말이 잘 찍게) — 화면을 떠나면 원래대로 */
@Composable
fun MaxBrightness() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        onDispose {
            if (window != null && before != null) window.attributes = window.attributes.apply { screenBrightness = before }
        }
    }
}
