package com.yamu.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import com.yamu.app.feature.auth.WelcomeLogin

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

/** 이거 사도 될까? 저장: 입력 · 결과를 빼고 잔고예보 위에 → 뒤로 = 잔고예보(계획 반영) */
fun NavController.toBuyCheckSaved(planId: Long) = navigate(BuyCheckSaved(planId)) {
    popUpTo<Forecast> { inclusive = false }
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
