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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yamu.app.core.designsystem.component.YamuBottomBar
import com.yamu.app.core.designsystem.component.YamuSnackbar
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.ui.LocalSnackbar

/**
 * 바깥 틀 — 하단 탭(탭 첫 화면 5개에서만) · 스낵바.
 * 여백(insets)은 화면마다 자기 Scaffold가 처리하고, 여기서는 하단 탭 높이만 넘긴 뒤 소비(consume)한다 — 두 번 겹치지 않게.
 */
@Composable
fun YamuApp(sessionExpired: Flow<Unit> = emptyFlow(), autoLockSeconds: Flow<Int> = flowOf(60)) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val entry by navController.currentBackStackEntryAsState()

    // 토큰 재발급까지 실패 → 로그인 화면으로(07 문서 3-3)
    LaunchedEffect(sessionExpired) {
        sessionExpired.collect {
            navController.navigate(Login) { popUpTo(navController.graph.id) { inclusive = true } }
            snackbar.showSnackbar("다시 로그인해 주세요")
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
