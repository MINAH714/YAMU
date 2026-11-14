package com.yamu.app.core.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 앱을 켰을 때 갈 곳(06 문서 2-4) */
enum class StartState(val label: String) {
    NEED_SIGNUP("처음 사용"),
    NEED_LINK("카드 0장"),        // 실서버에서 가입 뒤 '나중에 할게요'를 고른 사용자 — 계좌 1 · 카드 0
    READY("카드 6장"),           // 시드 사용자 — 계좌 2 · 카드 6장 · 지난 기록 있음
}

/** 가짜 데이터(debug)로 볼 홈 상태 — 07 문서 3-5 */
enum class HomeScenario(val label: String) {
    NORMAL("정상(가짜 사용자대로)"),
    FIRST_DAY("가입 첫날(잠정)"),
    AFTER_PAYMENT("결제 반영 직후"),
    BEFORE_INCOME("월급 등록 전"),
    NOT_LINKED("연결 전(계좌 0 · 카드 0)"),
    LEGACY_SPEC("지금 명세 모양(보완 전)"),
    PARTIAL("예상 잔액만 실패"),
    NO_PAYMENT("이번 달 결제 없음"),       // v4 혜택 히어로 0원 · '결제 없음'(S15P31E103-480)
    BENEFIT_ERROR("혜택만 실패"),          // 혜택 칸만 다시 시도, 나머지 칸은 그대로
    AUTO_SELECT_OFF("자동 선택 꺼짐"),      // 결제 칸이 기본 카드로 결제한다고 말한다(HOME-09)
    BANK_ERROR("금융망 오류"),
    NETWORK_ERROR("네트워크 없음"),
    SLOW("느린 응답(3초)"),
}

/** 가짜 데이터(debug)로 볼 연결 상태 — 09 문서 5-5 */
enum class LinkScenario(val label: String) {
    NORMAL("정상"),
    TOKEN_EXPIRED("등록 때 카드 확인 시간 지남"),
    ACCOUNT_NOT_LINKED("Mr.Life 등록 때 결제 계좌 미연결(409)"),
    CANDIDATE_EXPIRED("계좌 연결 때 목록 만료(한 번)"),
    BANK_ERROR("계좌 연결 때 금융망 오류"),
}

/** 가짜 데이터(debug)로 볼 결제 — 10 문서 6-7. 고객 앱 혼자 돌 때 '가맹점이 찍은 것처럼' */
enum class PayScenario(val label: String) {
    CAFE("3초 뒤 가맹점이 찍음 · 카페 15,000원"),
    MART("3초 뒤 가맹점이 찍음 · 마트 90,000원(할부)"),
    CHECK_DECLINED("1순위 체크카드 잔액 부족 → 다른 카드"),
    NO_SCAN("아무도 안 찍음(20초 코드)"),
    NO_CARD("가맹점이 찍었는데 쓸 수 있는 카드 없음"),
    CARDS_UNKNOWN("가맹점이 찍었는데 카드 정보 확인 못 함"),
    APPROVE_SLOW("카페 15,000원 · 승인이 4초 걸림(대기 화면 촬영)"),
    REQUEST_SHORT("카페 15,000원 · 확인 마감 15초(남은 시간 촬영)"),
}

/**
 * 가짜 데이터(debug)로 볼 '모든 기능' 공통 상태 — 04 문서 '데이터 상태 확인표'를 서버 없이 그려 보는 스위치.
 * 화면이 처음 불러오는 값(목록 · 요약 · 상세)에만 걸린다. 저장 · 결제 같은 행동은 각 기능의 상태(위)로 본다.
 */
enum class FaultScenario(val label: String) {
    NONE("정상"),
    EMPTY("빈 응답(계좌 0 · 카드 0)"),
    BANK_ERROR("금융망 오류(502)"),
    NETWORK_ERROR("네트워크 없음"),
    DATA_INSUFFICIENT("데이터 부족(422)"),
    SLOW("느린 응답(3초)"),
}

/**
 * 개발용 상태 — 디버그 메뉴에서 바꾼다.
 * startState + applyOnNextStart: 디버그 메뉴 '이 상태로 처음부터' → 가짜 인증(FakeAuthRepository)이 다음 시작 때 저장소에 반영.
 * homeScenario · linkScenario: debug 빌드 가짜 홈 · 연결 데이터. 그 기능이 진짜 서버(yamu.realApi)면 쓰이지 않는다.
 * debug 빌드는 applyOnNextStart 말고 모두 기기에 저장해 앱을 다시 켜도 남는다(src/debug DevSessionStore, S15P31E103-453).
 */
object DevSession {
    var startState by mutableStateOf(StartState.NEED_SIGNUP)
    var applyOnNextStart = false
    var homeScenario by mutableStateOf(HomeScenario.NORMAL)
    var linkScenario by mutableStateOf(LinkScenario.NORMAL)
    var payScenario by mutableStateOf(PayScenario.CAFE)
    var faultScenario by mutableStateOf(FaultScenario.NONE)

    /** debug 가짜 사용자(계좌 · 카드 · 월급) 요약 — 가짜 저장소가 채우고 디버그 메뉴가 보인다. release는 늘 비어 있다 */
    var fakeUser by mutableStateOf<List<String>>(emptyList())
}
