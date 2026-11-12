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
 * 시연 모드 화면 캡처 허용 — release에서도 듣는다(S15P31E103-434). 발표 녹화 · 미러링에서 간편 비밀번호 · 결제 코드 ·
 * 카드 번호 화면이 검게 나오지 않게. 결제 · 금융망 값이 모두 모의 · 가상이라 보여도 된다고 정했다.
 * 마이 버전 글자 7번 → 간편 비밀번호 본인 확인 뒤에만 켤 수 있고, 앱을 다시 켜도 남되 [ALLOW_MS] 뒤 저절로 꺼진다.
 * 켜졌다는 표시는 일부러 띄우지 않는다(녹화 화면에 나오지 않게)
 */
object DemoCapture {
    const val ALLOW_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "yamu_demo"
    private const val KEY_UNTIL = "captureAllowedUntil"

    /** 허용이 끝나는 시각(epoch ms). 없으면 꺼짐 */
    var allowedUntil by mutableStateOf<Long?>(null)
        private set

    /** 앱 시작 때 한 번 — 저장해 둔 끝 시각을 읽는다. 이미 지났으면 지운다 */
    fun init(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = prefs.getLong(KEY_UNTIL, 0L).takeIf { it > 0L }
        allowedUntil = until?.takeIf { isAllowed(it, now) }
        if (until != null && allowedUntil == null) prefs.edit().remove(KEY_UNTIL).apply()
    }

    fun set(context: Context, on: Boolean, now: Long = System.currentTimeMillis()) {
        val until = untilFor(on, now)
        allowedUntil = until
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (until == null) remove(KEY_UNTIL) else putLong(KEY_UNTIL, until)
        }.apply()
    }

    fun allowedAt(now: Long = System.currentTimeMillis()): Boolean = isAllowed(allowedUntil, now)

    internal fun untilFor(on: Boolean, now: Long): Long? = if (on) now + ALLOW_MS else null

    internal fun isAllowed(until: Long?, now: Long): Boolean = until != null && now < until
}

/**
 * 이 화면이 보이는 동안 캡처 · 최근 앱 미리보기를 막는다 — 간편 비밀번호 · 결제 코드 · 카드 번호 입력.
 * 수를 세는 까닭: 화면 전환 때 새 화면이 먼저 켜고 앞 화면이 뒤늦게 꺼서(로그인 → 카드 번호 입력) 막아 둔 것이 풀리지 않게
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity()
    // 시연 모드는 release에서도, 디버그 스위치는 debug에서만. 화면에 들어올 때마다 다시 보므로 24시간이 지나면 다음 화면부터 다시 막힌다
    val allowCapture = DemoCapture.allowedAt() || (BuildConfig.DEBUG && SecureScreenDebug.allowCapture)
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
