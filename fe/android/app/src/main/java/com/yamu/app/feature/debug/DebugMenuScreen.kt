package com.yamu.app.feature.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.yamu.app.BuildConfig
import com.yamu.app.core.network.ApiFeature
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.component.YamuSwitch
import com.yamu.app.core.designsystem.theme.YamuMotionDebug
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.LinkScenario
import com.yamu.app.core.session.PayScenario
import com.yamu.app.core.session.AssetFlowScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.core.session.StartState
import com.yamu.app.navigation.AccountManage
import com.yamu.app.navigation.AddCard
import com.yamu.app.navigation.BenefitLimits
import com.yamu.app.navigation.BenefitReport
import com.yamu.app.navigation.BuyCheckInput
import com.yamu.app.navigation.BuyCheckResult
import com.yamu.app.navigation.CardDetail
import com.yamu.app.navigation.CardManage
import com.yamu.app.navigation.CardOrder
import com.yamu.app.navigation.CardPerformance
import com.yamu.app.navigation.CardRegister
import com.yamu.app.navigation.CashflowConfirm
import com.yamu.app.navigation.CashflowList
import com.yamu.app.navigation.ComponentGallery
import com.yamu.app.navigation.Forecast
import com.yamu.app.navigation.Login
import com.yamu.app.navigation.MyCards
import com.yamu.app.navigation.NotificationSettings
import com.yamu.app.navigation.Notifications
import com.yamu.app.navigation.PayCode
import com.yamu.app.navigation.PaymentDetail
import com.yamu.app.navigation.Payments
import com.yamu.app.navigation.PerfAnalysis
import com.yamu.app.navigation.PinPad
import com.yamu.app.navigation.Profile
import com.yamu.app.navigation.Security
import com.yamu.app.navigation.SignupDone
import com.yamu.app.navigation.Terms
import com.yamu.app.core.ui.SecureScreenDebug
import com.yamu.app.navigation.restartFromSplash
import com.yamu.app.navigation.Withdraw

/** 디버그 메뉴(debug 빌드만) — 시작 상태 바꾸기 · 화면 바로 열기 · 컴포넌트 갤러리. 프로토타입 index.html 화면 목록과 같은 역할 */
@Composable
fun DebugMenuScreen(navController: NavController, viewModel: DebugMenuViewModel = hiltViewModel()) {
    val c = YamuTheme.colors
    val states = StartState.entries
    val check by viewModel.check.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { YamuTopBar("디버그 메뉴", onBack = { navController.popBackStack() }) },
        containerColor = c.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .consumeWindowInsets(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            YamuCard {
                Text("시작 상태", style = YamuTheme.typography.titleM, color = c.text1)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = states.map { it.label },
                    selectedIndex = states.indexOf(DevSession.startState),
                    onSelect = { DevSession.startState = states[it] },
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    "이 상태로 처음부터",
                    onClick = {
                        DevSession.applyOnNextStart = true   // 가짜 인증이 다음 시작 때 토큰 · 세션에 반영
                        navController.restartFromSplash()
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ApiFeature.AUTH.isReal) "가입 · 로그인이 진짜 서버를 써서 시작 상태와 가짜 인증 시험값은 쓰이지 않아요(yamu.realApi)"
                    else "카드 0장 = 실서버에서 가입 뒤 '나중에 할게요'를 고른 사용자(계좌 1 · 카드 0 · 지난 기록 없음) · 카드 6장 = 시드 사용자(계좌 2 · 지난 결제 · 실적 있음). 둘 다 로그인한 상태로 이 폰을 기기 7번으로 등록해 둬요. 처음 사용에서 가입하면 1원 때 고른 계좌만 연결된 새 사용자가 돼요. 이 메뉴에서 고른 값과 등록한 카드 · 계좌는 앱을 다시 켜도 남아요.\n" +
                        "가짜 인증 시험값: 본인인증 번호 01012345678은 가입 · 01099998888은 이미 가입(이 폰 재등록) · 그 밖은 시연 사용자 아님(이름 · 생년월일은 아무거나). 인증번호는 받으면 바로 채워지고 1원 숫자는 문자 알림을 누르면 채워짐(1원 1004 · 0000은 시간 지남). 간편 비밀번호 기본 135790(5번 틀리면 30분 잠김, 앱을 다시 켜면 135790으로) · 로그인 때 000000은 서버에서 기기가 없어진 경우",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
            }
            FakeUserCard()
            ServerCard(check, onCheck = viewModel::checkServer)
            YamuCard {
                Text("모든 기능 상태 (가짜 데이터)", style = YamuTheme.typography.titleM, color = c.text1)
                Text(
                    "화면이 처음 불러오는 값에 걸려요(04 문서 데이터 상태 확인표). 고른 뒤 화면을 다시 열거나 당겨서 새로고침. 정상으로 두면 아래 기능별 상태대로. " +
                        "빈 응답은 계좌까지 없는 응답이라 실서버 새 사용자와 달라요 — 새 사용자는 위 시작 상태 '카드 0장'으로 봐요" +
                        if (ApiFeature.real.isEmpty()) "" else ". 진짜 서버를 쓰는 기능에는 걸리지 않아요",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
                FaultScenario.entries.forEach { scenario ->
                    ListRow(
                        scenario.label,
                        trailing = { RoundCheckbox(checked = DevSession.faultScenario == scenario, onCheckedChange = null) },
                        onClick = { DevSession.faultScenario = scenario },
                    )
                }
                // 모든 기능 · 홈 · 연결 · 결제 상태는 앱을 다시 켜도 남는다(S15P31E103-453) — 폰을 넘기기 전에 한 번에 되돌리는 버튼
                Spacer(Modifier.height(8.dp))
                NeutralButton(
                    "기능 · 홈 · 연결 · 결제 상태 모두 정상으로",
                    onClick = {
                        DevSession.faultScenario = FaultScenario.NONE
                        DevSession.homeScenario = HomeScenario.NORMAL
                        DevSession.linkScenario = LinkScenario.NORMAL
                        DevSession.payScenario = PayScenario.CAFE
                    },
                    enabled = DevSession.faultScenario != FaultScenario.NONE || DevSession.homeScenario != HomeScenario.NORMAL ||
                        DevSession.linkScenario != LinkScenario.NORMAL || DevSession.payScenario != PayScenario.CAFE,
                )
                Text(
                    "고른 상태는 앱을 다시 켜도 남아요. 폰을 넘기기 전에 눌러 두세요. 시작 상태와 등록한 카드 · 계좌는 그대로예요",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
            }
            YamuCard {
                Text("홈 상태 (가짜 데이터)", style = YamuTheme.typography.titleM, color = c.text1)
                Text(
                    if (ApiFeature.HOME.isReal) "홈이 진짜 서버를 써서 여기서 바꿀 수 없어요(yamu.realApi)"
                    else "고른 뒤 홈으로 돌아가면 바로 바뀌어요. 정상은 가짜 사용자가 연결한 계좌 · 등록한 카드를 따라가요(카드 0장이면 카드 0장 홈)",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
                HomeScenario.entries.forEach { scenario ->
                    ListRow(
                        scenario.label,
                        trailing = { RoundCheckbox(checked = DevSession.homeScenario == scenario, onCheckedChange = null) },
                        onClick = { DevSession.homeScenario = scenario },
                    )
                }
            }
            YamuCard {
                Text("소비분석 자산 흐름 (가짜 데이터)", style = YamuTheme.typography.titleM, color = c.text1)
                Text(
                    if (ApiFeature.FORECAST.isReal) "잔고예보가 진짜 서버를 써서 여기서 바꿀 수 없어요(yamu.realApi)"
                    else "월별 자산 흐름 API(asset-flows)는 BE !315에 있고 11/14 운영 서버엔 아직 없어요. 옛 서버면 지난날이 빗금이고 예보만 그려요. 소비분석으로 돌아가면 바로 바뀌어요",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
                AssetFlowScenario.entries.forEach { scenario ->
                    ListRow(
                        scenario.label,
                        trailing = { RoundCheckbox(checked = DevSession.assetFlowScenario == scenario, onCheckedChange = null) },
                        onClick = { DevSession.assetFlowScenario = scenario },
                    )
                }
            }
            YamuCard {
                Text("연결 상태 (가짜 데이터)", style = YamuTheme.typography.titleM, color = c.text1)
                Text(
                    if (ApiFeature.LINK.isReal) "연결이 진짜 서버를 써서 여기서 바꿀 수 없어요(yamu.realApi)"
                    else "카드 번호: 9410 0000 0000 + 1021 톡톡 · 5527 트래블로그 체크 · 5096 Mr.Life(결제 계좌 먼저) · 0001 만료 · 0002 정지 · 0003 이미 등록 · 그 밖은 없는 카드",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
                LinkScenario.entries.forEach { scenario ->
                    ListRow(
                        scenario.label,
                        trailing = { RoundCheckbox(checked = DevSession.linkScenario == scenario, onCheckedChange = null) },
                        onClick = { DevSession.linkScenario = scenario },
                    )
                }
            }
            YamuCard {
                Text("결제 상태 (가짜 데이터)", style = YamuTheme.typography.titleM, color = c.text1)
                Text(
                    if (ApiFeature.PAYMENT.isReal) "결제가 진짜 서버를 써서 여기서 바꿀 수 없어요(yamu.realApi)"
                    else "고객 앱 혼자 돌 때 가짜 가맹점이 코드를 찍어요. 모의 POS 앱과 이어서 결제하는 건 실제 서버로만 돼요",
                    style = YamuTheme.typography.bodyS,
                    color = c.text3,
                )
                PayScenario.entries.forEach { scenario ->
                    ListRow(
                        scenario.label,
                        trailing = { RoundCheckbox(checked = DevSession.payScenario == scenario, onCheckedChange = null) },
                        onClick = { DevSession.payScenario = scenario },
                    )
                }
            }
            YamuCard {
                ListRow("컴포넌트 갤러리", chevron = true, onClick = { navController.navigate(ComponentGallery) })
                ListRow(
                    "모션 줄이기 강제",
                    sub = "시스템 '애니메이션 삭제'처럼 이동 · 크기 · 회전을 끄고 페이드만 남겨요. 촬영 · 점검용",
                    trailing = { YamuSwitch(checked = YamuMotionDebug.forceReduceMotion, onCheckedChange = null) },
                    onClick = { YamuMotionDebug.forceReduceMotion = !YamuMotionDebug.forceReduceMotion },
                )
                ListRow(
                    "화면 캡처 막기 끄기",
                    sub = "간편 비밀번호 · 카드 번호 화면도 녹화되게 해요. 앱을 다시 켜면 꺼져요. 촬영 · 점검용",
                    trailing = { YamuSwitch(checked = SecureScreenDebug.allowCapture, onCheckedChange = null) },
                    onClick = { SecureScreenDebug.allowCapture = !SecureScreenDebug.allowCapture },
                )
            }
            YamuCard {
                Text("화면 바로 열기", style = YamuTheme.typography.titleM, color = c.text1)
                val screens: List<Pair<String, Any>> = listOf(
                    "A-03 로그인" to Login,
                    "A-04 약관 동의" to Terms,
                    "A-07 가입 완료" to SignupDone,
                    "C-01 카드 등록" to CardRegister,
                    "B-06 월급 · 고정 지출" to CashflowConfirm(),
                    "H-02 알림" to Notifications,
                    "H-03 내 카드" to MyCards,
                    "F-01 잔고예보" to Forecast,
                    "F-02 예정 수입 · 지출" to CashflowList,
                    "I-01 이거 사도 될까?" to BuyCheckInput,
                    "I-02 결과(400,000원 · 대형마트)" to BuyCheckResult(400_000, "태블릿", categoryCode = "HYPERMARKET", categoryName = "대형마트", channel = "OFFLINE", purchaseDate = java.time.LocalDate.now().toString()),
                    "P-06 결제 코드" to PayCode,
                    "P-08 거래 상세" to PaymentDetail(938L),
                    "R-02 카드 실적 상세(신한)" to CardPerformance(userCardId = 2L),
                    "R-03 유불리 분석(우리)" to PerfAnalysis(userCardId = 1L),
                    "R-06 혜택 한도" to BenefitLimits,
                    "S-04 혜택 리포트" to BenefitReport,
                    "S-05 결제 내역" to Payments,
                    "M-02 카드 관리" to CardManage,
                    "M-02g 카드 순서" to CardOrder,
                    "M-03 카드 상세" to CardDetail(userCardId = 12L),
                    "C-11 카드 추가" to AddCard,
                    "M-01b 계좌 관리" to AccountManage,
                    "M-04 알림 설정" to NotificationSettings,
                    "M-06 회원 정보" to Profile,
                    "M-07b 회원 탈퇴" to Withdraw,
                    "M-08 보안 · 인증" to Security,
                    "간편 비밀번호" to PinPad("BARCODE"),
                )
                screens.forEach { (label, route) ->
                    ListRow(label, chevron = true, onClick = { navController.navigate(route) })
                }
            }
            Spacer(Modifier.height(56.dp))
        }
    }
}

/** 지금 가짜 사용자(계좌 · 카드 · 월급) — 가짜 저장소들이 함께 읽는 값(S15P31E103-454). 앱을 다시 켜도 남는다 */
@Composable
private fun FakeUserCard() {
    val c = YamuTheme.colors
    val lines = DevSession.fakeUser
    YamuCard {
        Text("가짜 사용자 (지금 상태)", style = YamuTheme.typography.titleM, color = c.text1)
        Spacer(Modifier.height(4.dp))
        if (lines.isEmpty()) {
            Text("아직 읽지 않았어요", style = YamuTheme.typography.bodyS, color = c.text2)
        } else {
            lines.forEach { Text(it, style = YamuTheme.typography.bodyS, color = c.text2) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            (if (ApiFeature.LINK.isReal) "연결이 진짜 서버라 계좌 · 카드는 서버 값이고, 이 칸은 가짜로 남은 기능(홈 · 실적 · 결제 …)만 따라요. " else "") +
                "홈 · 내 카드 · 실적 · 결제 · 잔고예보 · 소비분석 · 알림이 이 값을 함께 써요. 바꾸려면 위 시작 상태로 처음부터, 카드는 카드 등록 · 해제, 월급은 B-06에서",
            style = YamuTheme.typography.bodyS,
            color = c.text3,
        )
    }
}

/** 서버 주소 · 진짜로 쓰는 기능 · 연결 확인(GET /api/health). 주소와 기능은 local.properties로 바꾸고 다시 빌드 */
@Composable
private fun ServerCard(check: ServerCheck, onCheck: () -> Unit) {
    val c = YamuTheme.colors
    val real = ApiFeature.real
    YamuCard {
        Text("서버 연결", style = YamuTheme.typography.titleM, color = c.text1)
        Spacer(Modifier.height(4.dp))
        Text("서버: ${BuildConfig.BASE_URL}", style = YamuTheme.typography.bodyS, color = c.text2)
        Text(
            if (real.isEmpty()) "진짜 서버를 쓰는 기능: 없음(전부 가짜)"
            else "진짜 서버를 쓰는 기능: ${real.joinToString(" · ") { it.label }}",
            style = YamuTheme.typography.bodyS,
            color = c.text2,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("서버 연결 확인", onClick = onCheck, loading = check == ServerCheck.Checking)
        val result = when (check) {
            ServerCheck.Idle, ServerCheck.Checking -> null
            is ServerCheck.Ok -> "연결됨 · ${check.status} · ${check.millis}ms" to c.success
            is ServerCheck.Fail -> "연결 안 됨 · ${check.reason}" to c.error
        }
        if (result != null) {
            Spacer(Modifier.height(8.dp))
            Text(result.first, style = YamuTheme.typography.bodyS, color = result.second)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "local.properties의 yamu.baseUrl(주소) · yamu.realApi(진짜로 쓸 기능, 쉼표로)를 바꾸고 다시 빌드해요. 로컬 BE는 adb reverse tcp:8080 tcp:8080 뒤 localhost",
            style = YamuTheme.typography.bodyS,
            color = c.text3,
        )
    }
}
