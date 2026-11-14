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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yamu.app.core.designsystem.component.BottomBarCenter
import com.yamu.app.core.designsystem.component.BottomBarCenterRise
import com.yamu.app.core.designsystem.component.YamuBottomBar
import com.yamu.app.core.designsystem.icon.YamuTabIcons
import com.yamu.app.core.designsystem.component.YamuSnackbar
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.security.SessionEnd
import com.yamu.app.core.security.SessionEndEvent
import com.yamu.app.core.ui.LocalSnackbar

/**
 * 바깥 틀 — 하단 탭(탭 첫 화면 4개에서만, 가운데 결제 버튼 포함) · 스낵바.
 * 여백(insets)은 화면마다 자기 Scaffold가 처리하고, 여기서는 하단 탭 높이만 넘긴 뒤 소비(consume)한다 — 두 번 겹치지 않게.
 * [sessionEnd]는 아직 처리하지 않은 '세션 끝남'이라 화면을 다시 만드는 중에 생겨도 남아 있다. 처리하기 전에 [settleSessionEnd]로
 * 지난 알림(그 뒤 새로 로그인)인지 보고 기기 해제면 거기서 세션 · 기기 키를 지운다. 처리한 뒤에는 [onSessionEndHandled]로 지운다
 */
@Composable
fun YamuApp(
    sessionEnd: Flow<SessionEndEvent?> = emptyFlow(),
    autoLockSeconds: Flow<Int> = flowOf(60),
    settleSessionEnd: suspend (SessionEndEvent) -> Boolean = { true },
    onSessionEndHandled: (SessionEndEvent) -> Unit = {},
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by navController.currentBackStackEntryAsState()
    val settle by rememberUpdatedState(settleSessionEnd)
    val handled by rememberUpdatedState(onSessionEndHandled)

    // 다시 로그인해야 함 → 이유에 따라 로그인 화면이나 첫 화면으로(07 문서 3-3)
    LaunchedEffect(sessionEnd) {
        sessionEnd.filterNotNull().collect { event ->
            val stillCurrent = try {
                settle(event)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                true   // 기기 정보를 지우지 못해도 옮긴다 — 다음 실행 때 로그인 단계에서 기기 없음으로 다시 지운다
            }
            val move = if (stillCurrent) sessionEndMove(event.reason, navController.currentDestination.sessionPlace()) else null
            if (move != null) {
                if (move.toWelcome) navController.toWelcome()
                else navController.navigate(Login) { popUpTo(navController.graph.id) { inclusive = true } }
                launch { snackbar.showSnackbar(move.message) }   // 스낵바가 떠 있는 동안에도 다음 알림을 받게
            }
            handled(event)   // 처리하다 화면이 다시 만들어지면 남아 있어 다음 수집기가 다시 본다(이미 옮겼으면 그대로 둔다)
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
                        // 가운데 결제 — 탭이 아니라 결제 코드(P-06)를 연다. 카드가 0장이면 결제 코드 화면이 카드 추가로 보낸다(-450)
                        center = BottomBarCenter("결제", YamuTabIcons.PayCode, "결제 코드 열기") {
                            navController.navigate(PayCode) { launchSingleTop = true }
                        },
                    )
                }
            },
            snackbarHost = {
                // 탭 밖 흐름 화면은 아래 고정 버튼(BottomCta)이 있어 그 위로 띄운다 — 주 버튼을 가리지 않게.
                // 탭 화면은 막대 가운데 원이 위로 올라와 있어 그만큼 올린다
                val lift = if (currentTab == null) {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = YamuSize.button + YamuSpacing.x2 + YamuSpacing.x4)
                } else Modifier.padding(bottom = BottomBarCenterRise)
                SnackbarHost(snackbar, modifier = lift) { data -> YamuSnackbar(data) }
            },
        ) { inner ->
            YamuNavHost(
                navController = navController,
                // 탭 화면은 막대 가운데 원이 올라온 만큼 더 띄운다 — 맨 아래 글자(마이 버전 등)가 원에 가리지 않게
                modifier = Modifier
                    .padding(inner)
                    .consumeWindowInsets(inner)
                    .padding(bottom = if (currentTab != null) BottomBarCenterRise else 0.dp),
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
