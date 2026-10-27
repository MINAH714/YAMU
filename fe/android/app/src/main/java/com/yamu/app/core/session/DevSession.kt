package com.yamu.app.core.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 앱을 켰을 때 갈 곳(06 문서 2-4) */
enum class StartState(val label: String) {
    NEED_SIGNUP("처음 사용"),
    NEED_LINK("로그인 · 연결 전"),
    READY("로그인 · 연결 끝"),
}

/** 가짜 데이터(debug)로 볼 홈 상태 — 07 문서 3-5 */
enum class HomeScenario(val label: String) {
    NORMAL("정상"),
    FIRST_DAY("가입 첫날(잠정)"),
    AFTER_PAYMENT("결제 반영 직후"),
    NOT_LINKED("연결 전"),
    LEGACY_SPEC("지금 명세 모양(보완 전)"),
    PARTIAL("예상 잔액만 실패"),
    BANK_ERROR("금융망 오류"),
    NETWORK_ERROR("네트워크 없음"),
    SLOW("느린 응답(3초)"),
}

/** 가짜 데이터(debug)로 볼 연결 상태 — 09 문서 5-5 */
enum class LinkScenario(val label: String) {
    NORMAL("정상(계좌 1 · 카드 6)"),
    NO_PRODUCTS("내 명의 상품 없음"),
    ALREADY_VERIFIED("본인 계좌 이미 확인함(1원 없이)"),
    BANK_ERROR("연결 확정 때 금융망 오류"),
}

/** 가짜 데이터(debug)로 볼 결제 — 10 문서 6-7. 고객 앱 혼자 돌 때 '가맹점이 찍은 것처럼' */
enum class PayScenario(val label: String) {
    CAFE("3초 뒤 가맹점이 찍음 · 카페 15,000원"),
    MART("3초 뒤 가맹점이 찍음 · 마트 90,000원(할부)"),
    CHECK_DECLINED("1순위 체크카드 잔액 부족 → 다른 카드"),
    NO_SCAN("아무도 안 찍음(20초 코드)"),
}

/**
 * 가짜 데이터(debug)로 볼 '모든 기능' 공통 상태 — 04 문서 '데이터 상태 확인표'를 서버 없이 그려 보는 스위치.
 * 화면이 처음 불러오는 값(목록 · 요약 · 상세)에만 걸린다. 저장 · 결제 같은 행동은 각 기능의 상태(위)로 본다.
 */
enum class FaultScenario(val label: String) {
    NONE("정상"),
    EMPTY("데이터 없음(새 사용자)"),
    BANK_ERROR("금융망 오류(502)"),
    NETWORK_ERROR("네트워크 없음"),
    DATA_INSUFFICIENT("데이터 부족(422)"),
    SLOW("느린 응답(3초)"),
}

/**
 * 개발용 상태 — 디버그 메뉴에서 바꾼다.
 * startState + applyOnNextStart: 디버그 메뉴 '이 상태로 처음부터' → 가짜 인증(FakeAuthRepository)이 다음 시작 때 저장소에 반영.
 * homeScenario · linkScenario: debug 빌드 가짜 홈 · 연결 데이터. 진짜 API(USE_FAKE_API=false)에서는 쓰이지 않는다.
 */
object DevSession {
    var startState by mutableStateOf(StartState.NEED_SIGNUP)
    var applyOnNextStart = false
    var homeScenario by mutableStateOf(HomeScenario.NORMAL)
    var linkScenario by mutableStateOf(LinkScenario.NORMAL)
    var payScenario by mutableStateOf(PayScenario.CAFE)
    var faultScenario by mutableStateOf(FaultScenario.NONE)
}
