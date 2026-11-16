package com.yamu.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import kotlin.reflect.KClass

/**
 * 하단바를 숨기는 화면(S15P31E103-521) — 무언가 입력 · 확정하는 흐름만. 메인 그래프 안의 보는 화면은 모두 하단바를 보인다.
 * 입력 도중 탭을 눌러 쓴 내용을 잃지 않게, 결제처럼 한 번에 끝내야 하는 흐름에서 다른 데로 새지 않게 한다.
 * 가입 · 연결 · 잠금 해제 · 본인 확인 · 디버그는 메인 그래프 밖이라 이 목록 없이도 숨는다
 */
internal val BAR_HIDDEN_ROUTES: Set<KClass<*>> = setOf(
    PayCode::class, PayDone::class, CancelCode::class,
    BuyCheckInput::class, BuyCheckConditions::class, BuyCheckResult::class, BuyCheckSaved::class,
    CashflowCreate::class, CashflowEdit::class,
    AddCard::class, CardOrder::class, Withdraw::class, PinChange::class,
)

/** 하단바를 보일지 — 메인 그래프 안이고 숨기는 화면이 아닐 때 */
internal fun NavDestination?.showsBottomBar(): Boolean {
    val d = this ?: return false
    if (d.hierarchy.none { it.hasRoute(MainGraph::class) }) return false
    return BAR_HIDDEN_ROUTES.none { d.hasRoute(it) }
}

/**
 * 고른 탭 — 하위 화면이면 그 화면이 쌓인 탭. 홈 위에 쌓인 결제 내역은 홈, 마이에서 연 잔고예보는 마이.
 * 뒤 스택 순서를 보는 공개 API가 없어(currentBackStack은 라이브러리 내부용) 탭 첫 화면이 스택에 있는지만 본다.
 * 홈은 늘 맨 밑에 남고 다른 탭 첫 화면은 [navigateToTab]의 popUpTo<Home>으로 홈 바로 위에 하나만 쌓이므로 그걸로 충분하다
 */
internal fun NavController.selectedTab(): TopTab? = selectedTab { tab ->
    runCatching { getBackStackEntry(tab.routeClass) }.isSuccess
}

/** [inStack] = 그 탭 첫 화면이 뒤 스택에 있는지. 홈이 아닌 탭 첫 화면이 있으면 그 탭, 없고 홈이 있으면 홈 */
internal fun selectedTab(inStack: (TopTab) -> Boolean): TopTab? =
    TopTab.entries.firstOrNull { it != TopTab.HOME && inStack(it) } ?: TopTab.HOME.takeIf(inStack)

/**
 * 하단바 탭을 눌렀을 때. 하위 화면이면 지금 탭의 하위 화면을 저장 없이 닫고 탭 첫 화면에서 바꾼다 —
 * 하위 화면째 저장하면 다른 탭 그래프 화면(마이 위 잔고예보 등)이 엉뚱한 탭에 되살아난다. 지금 탭을 누르면 그 탭 첫 화면
 */
internal fun NavController.selectTab(target: TopTab, current: TopTab?) {
    if (current != null) popBackStack(current.route, inclusive = false)
    if (target != current) navigateToTab(target)
}
