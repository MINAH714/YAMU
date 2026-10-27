package com.yamu.app.core.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

/** Compose 안에서 지금 Activity 찾기 — 지문 창(BiometricPrompt)에 필요 */
fun Context.findActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 이 화면이 보이는 동안 캡처 · 최근 앱 미리보기를 막는다 — 간편 비밀번호 · 결제 코드 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
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
