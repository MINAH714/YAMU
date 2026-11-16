package com.yamu.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import com.yamu.app.feature.auth.WelcomeLogin
import kotlin.reflect.KClass

// 이동 도우미 — '뒤로 가면 어디로 가나'를 여기서 정한다(06 문서 2-5 확인 표)

/** 탭 바꾸기: 탭마다 쌓인 화면을 저장 · 복원. 지금 탭을 또 누르면 그 탭 첫 화면으로 */
fun NavController.navigateToTab(tab: TopTab) {
    val inThisTab = currentDestination?.hierarchy?.any { it.hasRoute(tab.graphClass) } == true
    if (inThisTab) {
        popBackStack(tab.route, inclusive = false)
        return
    }
    // 탭 첫 화면이 아닌 곳에서 홈으로(결제 완료 '홈으로'): 홈 위 화면을 저장 없이 내린다. 홈 '전체 보기'로 홈 위에 쌓은 결제 내역(ReportGraph)을
    // 탭처럼 저장하면 popUpTo<Home>이 그 스택을 홈에도 이어 두어, 바로 뒤 restoreState가 방금 내린 결제 내역을 되살리거나 소비분석 탭에 남긴다
    val onTabRoot = TopTab.entries.any { t -> currentDestination?.hasRoute(t.routeClass) == true }
    if (tab == TopTab.HOME && !onTabRoot && popBackStack(tab.route, inclusive = false)) return
    navigate(tab.graph) {
        popUpTo<Home> { saveState = true }   // 홈 탭 첫 화면은 남김 → 다른 탭 첫 화면에서 뒤로 = 홈
        launchSingleTop = true
        restoreState = true
    }
}

/** 처음부터(스플래시) 다시 — 디버그 메뉴 · 탈퇴. 로그아웃은 기기가 남아 스플래시가 간편 비밀번호로 보내므로 [toWelcome] */
fun NavController.restartFromSplash() {
    forgetSavedTabs()
    navigate(Splash) { popUpTo(graph.id) { inclusive = true } }
}

/** 가입 끝 → 연결: 뒤로 눌러도 가입 화면이 다시 뜨지 않게 */
fun NavController.finishSignup() = navigate(LinkGraph) {
    popUpTo<AuthGraph> { inclusive = true }
}

/** 가입은 했지만 연결은 나중에 → 메인 */
fun NavController.skipLinkToMain() = navigate(MainGraph) {
    popUpTo<AuthGraph> { inclusive = true }
}

/** 로그인 · 잠금 해제 끝 → 메인 */
fun NavController.finishAuthToMain() = navigate(MainGraph) {
    popUpTo<AuthGraph> { inclusive = true }
}

/** 로그인 · 잠금 해제 끝 → 초기 설정이 끝났으면 메인, 아니면 계좌 · 카드 연결 */
fun NavController.finishAuth(onboardingCompleted: Boolean) {
    if (onboardingCompleted) finishAuthToMain() else navigate(LinkGraph) { popUpTo<AuthGraph> { inclusive = true } }
}

/** 연결 · 첫 분석 끝 → 메인. 홈에서 연결을 시작했어도 홈이 두 겹이 되지 않게 스택을 비운다 */
fun NavController.finishOnboarding() = navigate(MainGraph) {
    popUpTo(graph.id) { inclusive = true }
}

/** 결제 완료: 결제 코드 · 확인 화면은 빼고 완료만 남김 → 뒤로 = 결제를 시작한 화면 */
fun NavController.toPayDone(paymentId: Long) = navigate(PayDone(paymentId)) {
    popUpTo<PayCode> { inclusive = true }
}

/**
 * 이거 사도 될까? 저장: 입력 · 결과를 빼고 잔고예보 위에 → 뒤로 = 잔고예보(계획 반영).
 * 잔고예보 밖(마이 '모든 기능' · 디버그 메뉴)에서 들어왔으면 흐름 자리에 잔고예보를 먼저 깐다 → 잔고예보에서 뒤로 = 들어온 화면(S15P31E103-586)
 */
fun NavController.toBuyCheckSaved(planId: Long) {
    (buyCheckExit() as? BuyCheckExit.ReplaceWithForecast)?.let { replaceBuyCheckWithForecast(it.firstScreen) }
    navigate(BuyCheckSaved(planId)) { popUpTo<Forecast> { inclusive = false } }
}

/**
 * 이거 사도 될까? → 잔고예보(결과 화면 '잔고예보에서 확인하기'): 잔고예보에서 들어왔으면 그 잔고예보로 돌아가고,
 * 잔고예보 밖에서 들어왔으면 흐름 자리에 잔고예보를 올린다(뒤로 = 들어온 화면). 이미 나갔으면(연타) 아무것도 안 한다
 */
fun NavController.buyCheckToForecast() {
    when (val exit = buyCheckExit()) {
        BuyCheckExit.ForecastBelow -> popBackStack<Forecast>(inclusive = false)
        is BuyCheckExit.ReplaceWithForecast -> replaceBuyCheckWithForecast(exit.firstScreen)
        BuyCheckExit.Left -> Unit
    }
}

/** 이거 사도 될까? 흐름 — 금액 입력 · 조건 · 결과 순서로 쌓인다(디버그 메뉴는 결과부터 열기도 한다). 저장 완료는 흐름을 나간 뒤 */
internal val BUY_CHECK_FLOW: List<KClass<*>> = listOf(BuyCheckInput::class, BuyCheckConditions::class, BuyCheckResult::class)

/** 이거 사도 될까?에서 잔고예보로 나가는 길 */
internal sealed interface BuyCheckExit {
    /** 잔고예보 위에서 시작했다(잔고예보 화면의 버튼) — 그 잔고예보로 돌아간다 */
    data object ForecastBelow : BuyCheckExit

    /** 잔고예보 없이 시작했다(마이 '모든 기능' · 디버그 메뉴) — 흐름 첫 화면 [firstScreen]부터 걷어 내고 그 자리에 잔고예보 */
    data class ReplaceWithForecast(val firstScreen: KClass<*>) : BuyCheckExit

    /** 흐름 화면이 스택에 없다 — 이미 나갔다(버튼 연타) */
    data object Left : BuyCheckExit
}

/** [inStack] = 그 경로 화면이 뒤 스택에 있는지. 흐름은 순서대로 쌓이므로 목록에서 처음 찾은 화면이 흐름의 맨 아래다 */
internal fun buyCheckExit(inStack: (KClass<*>) -> Boolean): BuyCheckExit {
    val first = BUY_CHECK_FLOW.firstOrNull(inStack) ?: return BuyCheckExit.Left
    return if (inStack(Forecast::class)) BuyCheckExit.ForecastBelow else BuyCheckExit.ReplaceWithForecast(first)
}

// 뒤 스택 순서를 보는 공개 API가 없어(BottomBarRules.selectedTab과 같다) 화면이 스택에 있는지만 본다
private fun NavController.buyCheckExit(): BuyCheckExit = buyCheckExit { route -> runCatching { getBackStackEntry(route) }.isSuccess }

private fun NavController.replaceBuyCheckWithForecast(firstScreen: KClass<*>) = navigate(Forecast) {
    popUpTo(firstScreen) { inclusive = true }
}

/** 이 폰에 등록된 기기가 없어짐 · 로그아웃 → 첫 화면(A-01). 스택을 모두 비운다 */
fun NavController.toWelcome() {
    forgetSavedTabs()
    navigate(AuthGraph) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * 세션이 끝나 처음 화면으로 갈 때 [navigateToTab]이 저장해 둔 탭 스택을 ViewModel째 버린다.
 * popUpTo(graph.id)는 지금 스택만 내리고 따로 저장해 둔 탭은 남겨서, 버리지 않으면 다음 사람이 그 탭을 눌렀을 때 앞사람 화면이
 * 같은 ViewModel로 돌아와 새로 받기 전까지 앞사람 숫자를 보인다(소비분석은 같은 달이면 마지막 값을 둔 채 받는다).
 * 홈 그래프도 지운다. popUpTo<Home>이 홈 쪽(Home · HomeGraph · MainGraph)에 이어 둔 저장이 같이 빠져 앱을 새로 켠 것과 같아진다.
 * 저장이 없는 탭은 그냥 지나간다. 경로 객체(tab.graph)로 부르면 저장이 없을 때 지금 자리에서 그 그래프를 못 찾아 예외가 날 수 있어 클래스로 부른다.
 * 지울 때 저장된 스택을 지금 스택 위에 잠깐 되살렸다 내리므로 이동하기 전에 부른다(이동 뒤면 스플래시 위에 메인 그래프 항목이 하나 더 생겼다 빠진다)
 */
private fun NavController.forgetSavedTabs() {
    TopTab.entries.forEach { tab -> clearBackStack(tab.graphClass) }
}

/** 첫 화면 '로그인' → 기기가 있으면 간편 비밀번호(A-03), 없으면 본인인증(A-05)으로 이 폰을 다시 등록 */
internal fun welcomeLoginRoute(to: WelcomeLogin): Any = when (to) {
    WelcomeLogin.PIN -> Login
    WelcomeLogin.VERIFY -> IdentityVerify
}
