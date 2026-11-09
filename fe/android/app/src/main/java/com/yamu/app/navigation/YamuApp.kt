package com.yamu.app.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yamu.app.core.designsystem.component.YamuBottomBar
import com.yamu.app.core.designsystem.component.YamuSnackbar
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.security.SessionEnd
import com.yamu.app.core.ui.LocalSnackbar

/**
 * 바깥 틀 — 하단 탭(탭 첫 화면 5개에서만) · 스낵바.
 * 여백(insets)은 화면마다 자기 Scaffold가 처리하고, 여기서는 하단 탭 높이만 넘긴 뒤 소비(consume)한다 — 두 번 겹치지 않게.
 * [onDeviceRevoked]는 서버에서 이 기기가 해제됐을 때 이 폰의 세션 · 기기 키를 지운다(첫 화면으로 가기 전에)
 */
@Composable
fun YamuApp(
    sessionExpired: Flow<SessionEnd> = emptyFlow(),
    autoLockSeconds: Flow<Int> = flowOf(60),
    onDeviceRevoked: suspend () -> Unit = {},
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by navController.currentBackStackEntryAsState()
    val forgetDevice by rememberUpdatedState(onDeviceRevoked)

    // 다시 로그인해야 함 → 이유에 따라 로그인 화면이나 첫 화면으로(07 문서 3-3)
    LaunchedEffect(sessionExpired) {
        sessionExpired.collect { reason ->
            if (reason == SessionEnd.DEVICE_REVOKED) {
                try {
                    forgetDevice()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e   // 지우지 못해도 다음 실행 때 로그인 단계에서 기기 없음으로 다시 지운다
                }
            }
            val move = sessionEndMove(reason, navController.currentDestination.sessionPlace()) ?: return@collect
            if (move.toWelcome) navController.toWelcome()
            else navController.navigate(Login) { popUpTo(navController.graph.id) { inclusive = true } }
            snackbar.showSnackbar(move.message)
        }
    }
    AutoLockEffect(navController, autoLockSeconds)

    val destination = entry?.destination
    val currentTab = TopTab.entries.firstOrNull { tab -> destination?.hasRoute(tab.routeClass) == true }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = YamuTheme.colors.background,
            bottomBar = {
                if (currentTab != null) {
                    YamuBottomBar(
                        items = TopTab.items,
                        selectedIndex = currentTab.ordinal,
                        onSelect = { navController.navigateToTab(TopTab.entries[it]) },
                    )
                }
            },
            snackbarHost = {
                // 탭 밖 흐름 화면은 아래 고정 버튼(BottomCta)이 있어 그 위로 띄운다 — 주 버튼을 가리지 않게
                val lift = if (currentTab == null) {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = YamuSize.button + YamuSpacing.x2 + YamuSpacing.x4)
                } else Modifier
                SnackbarHost(snackbar, modifier = lift) { data -> YamuSnackbar(data) }
            },
        ) { inner ->
            YamuNavHost(
                navController = navController,
                modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            )
        }
    }
}

/** 세션이 끝났을 때 지금 화면이 어디인가 — 갈 곳을 정하는 데만 쓴다 */
internal enum class SessionPlace { LOGIN, WELCOME, WITHDRAW, OTHER }

/** 세션이 끝나 옮겨 갈 곳 — [toWelcome]이 아니면 로그인 화면 */
internal data class SessionEndMove(val toWelcome: Boolean, val message: String)

/**
 * 세션이 끝났을 때 어디로 갈지. null이면 그대로 둔다(같은 화면으로 두 번 가지 않게).
 * - EXPIRED: 로그인 화면. 이미 로그인 · 잠금 해제 · 첫 화면이면 그대로. 탈퇴 화면도 그대로 — 탈퇴 응답을 못 받았을 때
 *   401 UNAUTHORIZED를 탈퇴가 끝난 증거로 보고 스스로 이 폰을 비운 뒤 처음으로 가는데 여기서도 옮기면 화면이 두 번 바뀌고 마무리가 끊긴다
 * - DEVICE_REVOKED: 첫 화면(로그인해도 서버가 기기를 모른다). 이미 첫 화면이면 그대로
 */
internal fun sessionEndMove(reason: SessionEnd, here: SessionPlace): SessionEndMove? = when (reason) {
    SessionEnd.EXPIRED -> when (here) {
        SessionPlace.LOGIN, SessionPlace.WELCOME, SessionPlace.WITHDRAW -> null
        SessionPlace.OTHER -> SessionEndMove(toWelcome = false, message = "다시 로그인해 주세요")
    }
    SessionEnd.DEVICE_REVOKED ->
        if (here == SessionPlace.WELCOME) null
        else SessionEndMove(toWelcome = true, message = "다른 폰에서 등록해서 이 폰은 로그아웃됐어요")
}

private fun NavDestination?.sessionPlace(): SessionPlace = when {
    this == null -> SessionPlace.OTHER
    hasRoute(Login::class) || hasRoute(Relock::class) -> SessionPlace.LOGIN
    hasRoute(Welcome::class) -> SessionPlace.WELCOME
    hasRoute(Withdraw::class) -> SessionPlace.WITHDRAW
    else -> SessionPlace.OTHER
}
