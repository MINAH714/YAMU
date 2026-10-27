package com.yamu.app.navigation

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import com.yamu.app.core.ui.findActivity
import kotlinx.coroutines.flow.Flow

/**
 * 앱 자동 잠금(13 문서 9·10-2) — 화면이 안 보이게 된(ON_STOP) 시각을 기억했다가,
 * 설정한 시간 이상 지나 다시 보이면(ON_START) 잠금 해제 화면(Relock)을 위에 얹는다. 풀면 보던 화면 그대로.
 * 지문 · 권한 · 문자 동의 창은 앱 화면이 계속 보여(ON_PAUSE만) '떠남'이 아니다. 화면 회전도 떠남이 아니다.
 */
@Composable
fun AutoLockEffect(navController: NavController, autoLockSeconds: Flow<Int>) {
    val seconds by autoLockSeconds.collectAsStateWithLifecycle(initialValue = 60)
    val currentSeconds by rememberUpdatedState(seconds)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(lifecycleOwner, navController) {
        var stoppedAt: Long? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val at = stoppedAt
                    stoppedAt = null
                    if (at != null && SystemClock.elapsedRealtime() - at >= currentSeconds * 1_000L && navController.shouldRelock()) {
                        navController.navigate(Relock) { launchSingleTop = true }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** 로그인 뒤 화면(메인 · 연결 흐름)에서만 — 가입 · 로그인 · 잠금 해제 · 간편 비밀번호 · 디버그 화면은 걸지 않는다 */
private fun NavController.shouldRelock(): Boolean {
    val destination = currentDestination ?: return false
    if (destination.hasRoute(Relock::class)) return false
    return destination.hierarchy.any { it.hasRoute(MainGraph::class) || it.hasRoute(LinkGraph::class) }
}
