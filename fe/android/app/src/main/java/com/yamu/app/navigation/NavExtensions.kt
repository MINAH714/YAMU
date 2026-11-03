package com.yamu.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy

// 이동 도우미 — '뒤로 가면 어디로 가나'를 여기서 정한다(06 문서 2-5 확인 표)

/** 탭 바꾸기: 탭마다 쌓인 화면을 저장 · 복원. 지금 탭을 또 누르면 그 탭 첫 화면으로 */
fun NavController.navigateToTab(tab: TopTab) {
    val inThisTab = currentDestination?.hierarchy?.any { it.hasRoute(tab.graphClass) } == true
    if (inThisTab) {
        popBackStack(tab.route, inclusive = false)
        return
    }
    navigate(tab.graph) {
        popUpTo<Home> { saveState = true }   // 홈 탭 첫 화면은 남김 → 다른 탭 첫 화면에서 뒤로 = 홈
        launchSingleTop = true
        restoreState = true
    }
}

/** 처음부터(스플래시) 다시 — 디버그 메뉴 · 로그아웃 */
fun NavController.restartFromSplash() = navigate(Splash) {
    popUpTo(graph.id) { inclusive = true }
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

/** 이 폰에 등록된 기기가 없어짐 → 첫 화면(A-01). 스택을 모두 비운다 */
fun NavController.toWelcome() = navigate(AuthGraph) {
    popUpTo(graph.id) { inclusive = true }
}
