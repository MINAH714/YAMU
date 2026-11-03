package com.yamu.app.navigation

import kotlinx.serialization.Serializable

// 화면 경로 — 06 문서 2-2 표. 주석은 프로토타입 ID(android-proto/screens). 인자는 ID만 넘긴다.

// ── 그래프 ──
@Serializable data object AuthGraph
@Serializable data object LinkGraph
@Serializable data object MainGraph
@Serializable data object HomeGraph
@Serializable data object PayGraph
@Serializable data object PerfGraph
@Serializable data object ReportGraph
@Serializable data object MyGraph

// ── 시작 · 가입 (AuthGraph) ──
@Serializable data object Splash
@Serializable data object Welcome            // A-01
@Serializable data object Login              // A-03 · A-03b PIN · 지문 로그인(앱을 열 때)
@Serializable data object Terms              // A-04
@Serializable data object IdentityVerify     // A-05 휴대폰 본인인증(가입 · 첫 화면 로그인 둘 다)
@Serializable data object SignupAccounts     // A-05b 계좌 고르기(재등록은 연결된 계좌 하나)
@Serializable data object SignupOneWon       // A-05c 1원 인증
@Serializable data object RecoveryIntro      // A-08 이 폰 등록(이미 가입한 사람)
@Serializable data object PinSetup           // A-06
@Serializable data object SignupDone         // A-07

// ── 계좌 · 카드 연결 (LinkGraph) ──
@Serializable data object CardRegister       // C-01 카드 번호 입력 · 확인 · 계좌 연결 · 완료(같은 화면의 단계 — 번호를 인자로 넘기지 않음)
@Serializable data object CashflowConfirm    // B-06
@Serializable data object FirstAnalysis      // B-07 · B-07b(알림 권한은 시스템 창)

// ── 홈 탭 (HomeGraph) ──
@Serializable data object Home               // H-01 · H-01d · H-01c(상태)
@Serializable data object Notifications      // H-02
@Serializable data object MyCards            // H-03
@Serializable data object Forecast           // F-01 · F-01d · F-01i(상태)
@Serializable data object CashflowList       // F-02
@Serializable data object CashflowCreate     // F-02a 예정 수입·지출 추가(F-02의 +)
/** F-02b 수정 · 삭제 — F-02 줄의 항목 ID와 원래 회차(서버에 그대로 돌려보내는 값) */
@Serializable data class CashflowEdit(val itemId: Long, val occurrenceDate: String)
@Serializable data object BuyCheckInput      // I-01 (F-04 사기 전에 확인을 합침)
/** I-02 · I-03(상태) · I-04(시트) — 결과는 저장된 게 아니라 이 입력으로 #33을 부른 값이라 입력을 넘긴다 */
@Serializable data class BuyCheckResult(val amount: Long, val title: String? = null, val purchaseDate: String)
@Serializable data class BuyCheckSaved(val planId: Long)            // I-05

// ── 카드추천 탭 (PayGraph) ──
@Serializable data object Recommend          // P-01

// ── 결제(탭 밖 전체 화면) ──
@Serializable data object PayCode            // P-06a(인증) · P-06 · P-06c(결제 요청 확인 — 같은 화면의 단계, 10 문서 6-7)
@Serializable data class PayDone(val paymentId: Long)               // P-07
@Serializable data class PaymentDetail(val paymentId: Long)         // P-08

// ── 실적관리 탭 (PerfGraph) ──
@Serializable data object PerfOverview       // R-01 · R-01d(상태)
@Serializable data class CardPerformance(val userCardId: Long)      // R-02
@Serializable data class PerfAnalysis(val userCardId: Long)         // R-03 · R-03b · R-03c(상태)
@Serializable data object BenefitLimits      // R-06

// ── 소비분석 탭 (ReportGraph) ──
@Serializable data object Report             // S-01
@Serializable data object BenefitReport      // S-04
@Serializable data object Payments           // S-05

// ── 마이 탭 (MyGraph) ──
@Serializable data object My                 // M-01
@Serializable data object CardManage         // M-02
@Serializable data object CardOrder          // M-02g 카드 순서
@Serializable data class CardDetail(val userCardId: Long)   // M-03 · M-03c(해제 시트)
@Serializable data object AddCard            // C-11 카드 추가(CardRegister와 같은 화면)
@Serializable data object AccountManage      // M-01b 계좌 관리(월급 · 고정 지출은 빼고 계좌만)
@Serializable data object NotificationSettings // M-04
@Serializable data object Profile            // M-06
@Serializable data object Security           // M-08

// ── 공통 ──
@Serializable data object Relock             // 앱 자동 잠금 — A-03b 화면을 보던 화면 위에(13 문서 9·10-2)
@Serializable data class PinPad(val purpose: String)   // 지문 대신 간편 비밀번호(A-06 키패드 디자인)

// ── 디버그(debug 빌드만) ──
@Serializable data object DebugMenu
@Serializable data object ComponentGallery
