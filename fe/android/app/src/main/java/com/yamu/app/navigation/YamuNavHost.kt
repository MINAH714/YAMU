package com.yamu.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.yamu.app.BuildConfig
import com.yamu.app.core.designsystem.component.gallery.ComponentGallery
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.designsystem.theme.screenSwapEnter
import com.yamu.app.core.designsystem.theme.screenSwapExit
import androidx.compose.runtime.rememberCoroutineScope
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.findActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.yamu.app.feature.auth.PinChangeEnd
import com.yamu.app.feature.auth.PinChangeRoute
import com.yamu.app.feature.auth.PinLoginRoute
import com.yamu.app.feature.auth.PinPadRoute
import com.yamu.app.feature.auth.PinSetupRoute
import com.yamu.app.feature.auth.SignupDoneRoute
import com.yamu.app.feature.auth.PhoneVerifyRoute
import com.yamu.app.feature.auth.RecoveryIntroRoute
import com.yamu.app.feature.auth.SignupAccountsRoute
import com.yamu.app.feature.auth.SignupNext
import com.yamu.app.feature.auth.SignupOneWonRoute
import com.yamu.app.feature.auth.TermsRoute
import com.yamu.app.feature.auth.TermDocRoute
import com.yamu.app.feature.auth.WelcomeRoute
import com.yamu.app.feature.auth.SplashRoute
import com.yamu.app.feature.auth.data.SessionStart
import kotlinx.coroutines.launch
import com.yamu.app.feature.debug.DebugMenuScreen
import com.yamu.app.feature.home.HomeRoute
import com.yamu.app.feature.payment.CancelCodeRoute
import com.yamu.app.feature.payment.PayCodeRoute
import com.yamu.app.feature.payment.PayDoneRoute
import com.yamu.app.feature.payment.PaymentDetailRoute
import com.yamu.app.feature.forecast.BuyCheckInputRoute
import com.yamu.app.feature.forecast.BuyCheckResultRoute
import com.yamu.app.feature.forecast.BuyCheckSavedRoute
import com.yamu.app.feature.forecast.BuyConditionsRoute
import com.yamu.app.feature.forecast.toRoute
import com.yamu.app.feature.forecast.CashflowCreateRoute
import com.yamu.app.feature.forecast.CashflowListRoute
import com.yamu.app.feature.forecast.ForecastRoute
import com.yamu.app.feature.forecast.LivingCostRoute
import com.yamu.app.feature.performance.BenefitLimitsRoute
import com.yamu.app.feature.performance.CardPerformanceRoute
import com.yamu.app.feature.performance.PerfAnalysisRoute
import com.yamu.app.feature.performance.PerfOverviewRoute
import com.yamu.app.feature.link.CardRegisterRoute
import com.yamu.app.feature.link.CashflowConfirmRoute
import com.yamu.app.feature.link.FirstAnalysisRoute
import com.yamu.app.feature.my.AccountManageRoute
import com.yamu.app.feature.my.CardDetailRoute
import com.yamu.app.feature.my.CardManageRoute
import com.yamu.app.feature.my.CardOrderRoute
import com.yamu.app.feature.my.MyCardsRoute
import com.yamu.app.feature.my.MyRoute
import com.yamu.app.feature.my.NotificationSettingsRoute
import com.yamu.app.feature.my.NotificationsRoute
import com.yamu.app.feature.my.ProfileRoute
import com.yamu.app.feature.my.SecurityRoute
import com.yamu.app.feature.my.WithdrawRoute
import com.yamu.app.feature.my.data.NotiTarget
import com.yamu.app.feature.report.BenefitReportRoute
import com.yamu.app.feature.report.PaymentsRoute
import com.yamu.app.feature.report.ReportRoute
import com.yamu.app.feature.billing.BillDetailRoute
import com.yamu.app.feature.billing.BillingRoute

/**
 * 앱 전체 화면 지도 — 2단계에서 빈 화면(PlaceholderScreen)으로 깔고, 3 ~ 10단계에서 모두 진짜 화면으로 바꿨다.
 * 화면 사이 이동만 여기서 정하고, 화면 안 내용은 feature/ 아래 각 Route가 맡는다.
 */
@Composable
fun YamuNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val back: () -> Unit = { navController.popBackStack() }
    val debugMenu: (() -> Unit)? = if (BuildConfig.DEBUG) ({ navController.navigate(DebugMenu) }) else null
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val openPinPad: (String) -> Unit = { purpose -> navController.navigate(PinPad(purpose)) }
    // 화면 교체는 통째로 0.2초 페이드(라이브러리 기본 0.7초 대신). 앞뒤 이동 모두 같고, 옮기거나 미끄러뜨리지 않는다
    val reduce = YamuTheme.reduceMotion

    NavHost(
        navController = navController,
        startDestination = Splash,
        modifier = modifier,
        enterTransition = { screenSwapEnter(reduce) },
        exitTransition = { screenSwapExit(reduce) },
        popEnterTransition = { screenSwapEnter(reduce) },
        popExitTransition = { screenSwapExit(reduce) },
    ) {

        composable<Splash> {
            SplashRoute(
                onDecided = { start ->
                    val target: Any = when (start) {
                        SessionStart.Welcome -> AuthGraph
                        SessionStart.Login -> Login
                    }
                    navController.navigate(target) { popUpTo<Splash> { inclusive = true } }
                },
            )
        }

        // ── 시작 · 가입 · 로그인 (4단계: 실제 화면 — 08 문서) ──
        navigation<AuthGraph>(startDestination = Welcome) {
            composable<Welcome> {
                WelcomeRoute(
                    onStart = { navController.navigate(Terms) },
                    // 로그아웃 뒤처럼 이 폰에 기기가 있으면 간편 비밀번호, 없으면 본인인증 → 기기 재등록(2차 QA 14)
                    onLogin = { to -> navController.navigate(welcomeLoginRoute(to)) { launchSingleTop = true } },
                    onDebugMenu = debugMenu,
                )
            }
            composable<Login> {
                PinLoginRoute(
                    onLoggedIn = { done -> navController.finishAuth(done) },
                    onNoDevice = { navController.toWelcome() },
                    onDebugMenu = debugMenu,
                )
            }
            composable<Terms> {
                TermsRoute(
                    onBack = back,
                    onVerify = { navController.navigate(IdentityVerify) },
                    onAccounts = { navController.navigate(SignupAccounts) },   // 로그인으로 들어와 본인인증을 이미 마친 가입
                    onOpenTerm = { doc -> navController.navigate(TermsDoc(doc.name)) { launchSingleTop = true } },
                )
            }
            composable<TermsDoc> { entry -> TermDocRoute(entry.toRoute<TermsDoc>().doc, onBack = back) }
            // 가입 진행 토큰 만료 · 앱이 다시 켜져 진행 상태가 없음 → 본인인증부터.
            // 까닭은 스낵바 대신 본인인증 화면 위에 띄우고, 넣었던 칸 · 약관 동의는 저장소가 남겨 둔다(SignupRepository.restart)
            val restartSignup: () -> Unit = {
                navController.navigate(IdentityVerify) { popUpTo<Welcome> { inclusive = false } }
            }
            composable<IdentityVerify> {
                PhoneVerifyRoute(
                    onBack = back,
                    onNext = { next ->
                        val target: Any = when (next) {
                            SignupNext.TERMS -> Terms
                            SignupNext.ACCOUNTS -> SignupAccounts
                            SignupNext.PIN -> PinSetup   // 계좌 연결을 마치고 PIN 만들기에서 뒤로 왔다(1원 다시 안 받음)
                            SignupNext.RECOVERY -> RecoveryIntro
                        }
                        navController.navigate(target)
                    },
                )
            }
            composable<RecoveryIntro> {
                RecoveryIntroRoute(
                    onBack = back,
                    onKeepPin = { navController.navigate(SignupAccounts) },
                    onNewPin = { navController.navigate(PinSetup) },
                )
            }
            composable<SignupAccounts> {
                SignupAccountsRoute(onBack = back, onSent = { navController.navigate(SignupOneWon) }, onRestart = restartSignup)
            }
            composable<SignupOneWon> {
                SignupOneWonRoute(
                    onBack = back,
                    // 계좌 연결이 끝나면 1원 · 계좌 화면으로 돌아가지 않게
                    onLinked = { navController.navigate(PinSetup) { popUpTo<IdentityVerify> { inclusive = false } } },
                    onRecovered = {
                        navController.navigate(Login) { popUpTo(navController.graph.id) { inclusive = true } }
                        showMessage("이 폰을 등록했어요 · 간편 비밀번호로 로그인해 주세요")
                    },
                    onRestart = restartSignup,
                )
            }
            composable<PinSetup> {
                PinSetupRoute(
                    onBack = back,
                    onFinished = { navController.navigate(SignupDone) { popUpTo<AuthGraph> { inclusive = false } } },
                    // PIN 화면을 스택에서 빼서 뒤로 가면 A-08로 돌아간다(남겨 두면 완료 상태가 다시 계좌 화면으로 보낸다)
                    onRecoveryPinSet = { navController.navigate(SignupAccounts) { popUpTo<RecoveryIntro> { inclusive = false } } },
                    onRestart = restartSignup,
                )
            }
            composable<SignupDone> {
                SignupDoneRoute(
                    onLinkNow = { navController.finishSignup() },
                    onLater = { navController.skipLinkToMain() },
                )
            }
        }

        // ── 계좌 · 카드 연결 (5단계: 실제 화면 — 09 문서) ──
        navigation<LinkGraph>(startDestination = CardRegister) {
            composable<CardRegister> {
                // 가입 · 로그인에서 바로 왔으면 스택에 이 화면뿐 — 뒤로 = 메인. 홈 · 잔고예보에서 왔으면 그 화면으로
                val hasPrevious = navController.previousBackStackEntry != null
                CardRegisterRoute(
                    onboarding = true,
                    onLeave = if (hasPrevious) back else ({ navController.finishOnboarding() }),
                    onFinished = { navController.navigate(CashflowConfirm()) },
                    onCloseDone = { navController.finishOnboarding() },
                    onOpenPinPad = openPinPad,
                    onSkip = { navController.finishOnboarding() },
                )
            }
            composable<CashflowConfirm> { entry ->
                // 가입 끝에서는 첫 분석으로, 홈 · 잔고예보의 월급 안내에서 왔으면 저장 뒤 그 화면으로 돌아가 다시 불러온다
                val fromHome = entry.toRoute<CashflowConfirm>().fromHome
                CashflowConfirmRoute(
                    fromHome = fromHome,
                    onBack = back,
                    onSaved = if (fromHome) back else ({ navController.navigate(FirstAnalysis) }),
                )
            }
            composable<FirstAnalysis> {
                FirstAnalysisRoute(onFinish = { navController.finishOnboarding() })
            }
        }

        // ── 메인(탭 5개) ──
        navigation<MainGraph>(startDestination = HomeGraph) {

            navigation<HomeGraph>(startDestination = Home) {
                composable<Home> {
                    // 3단계: 실제 화면(07 문서 3-5)
                    HomeRoute(
                        onPay = { navController.navigate(PayCode) },
                        onForecast = { navController.navigate(Forecast) },
                        onCashflow = { navController.navigate(CashflowList) },
                        onNotifications = { navController.navigate(Notifications) },
                        onMyCards = { navController.navigate(MyCards) },
                        onStartLinking = { navController.navigate(LinkGraph) },
                        onTodo = { userCardId -> navController.navigate(PerfAnalysis(userCardId ?: 1L)) },
                        onRecentPayment = { paymentId -> navController.navigate(PaymentDetail(paymentId)) },
                        // 결제 내역은 홈 위에 쌓고(뒤로 = 홈), 소비분석은 하단 탭을 누른 것과 같이 탭을 바꾼다(2차 QA 19 · 39)
                        onPayments = { navController.navigate(Payments) },
                        onReport = { navController.navigateToTab(TopTab.REPORT) },
                        onRegisterIncome = { navController.navigate(CashflowConfirm(fromHome = true)) },
                        onAddCard = { navController.navigate(AddCard) },
                    )
                }
                // 9 · 10단계: 실제 화면(13 문서)
                composable<Notifications> {
                    NotificationsRoute(
                        onBack = back,
                        onSettings = { navController.navigate(NotificationSettings) },
                        onOpen = { target ->
                            when (target) {
                                is NotiTarget.Card -> navController.navigate(PerfAnalysis(target.userCardId))
                                is NotiTarget.Payment -> navController.navigate(PaymentDetail(target.paymentId))
                                NotiTarget.Forecast -> navController.navigate(Forecast)
                                NotiTarget.Limits -> navController.navigate(BenefitLimits)
                                NotiTarget.None -> Unit
                            }
                        },
                    )
                }
                composable<MyCards> {
                    MyCardsRoute(
                        onBack = back,
                        onAddCard = { navController.navigate(AddCard) },
                        onAnalysis = { id -> navController.navigate(PerfAnalysis(id)) },
                        onCardDetail = { id -> navController.navigate(CardPerformance(id)) },
                    )
                }
                // 8단계: 실제 화면(12 문서)
                composable<Forecast> {
                    ForecastRoute(
                        onBack = back,
                        onBuyCheck = { navController.navigate(BuyCheckInput) },
                        onCashflowList = { navController.navigate(CashflowList) },
                        onLink = { navController.navigate(LinkGraph) },
                        onAlertSettings = { navController.navigate(NotificationSettings) },
                        onLivingCost = { navController.navigate(LivingCostDetail) },
                        onRegisterIncome = { navController.navigate(CashflowConfirm(fromHome = true)) },   // 저장하면 잔고예보로 돌아온다(Task 8.2.74)
                    )
                }
                composable<LivingCostDetail> { LivingCostRoute(onBack = back, onLink = { navController.navigate(LinkGraph) }) }
                composable<CashflowList> {
                    CashflowListRoute(
                        onBack = back,
                        onLink = { navController.navigate(LinkGraph) },
                        onAdd = { navController.navigate(CashflowCreate) },
                        onEdit = { id, occurrence -> navController.navigate(CashflowEdit(id, occurrence.toString())) },
                    )
                }
                composable<CashflowCreate> { CashflowCreateRoute(onBack = back, onDone = back) }
                composable<CashflowEdit> { CashflowCreateRoute(onBack = back, onDone = back) }   // 같은 화면 · 수정 모드(경로 값으로 구분)
                composable<BuyCheckInput> {
                    BuyCheckInputRoute(
                        onBack = back,
                        onNext = { amount, title -> navController.navigate(BuyCheckConditions(amount, title)) },
                    )
                }
                composable<BuyCheckConditions> {
                    BuyConditionsRoute(
                        onBack = back,
                        onCompare = { query -> navController.navigate(query.toRoute()) },
                    )
                }
                composable<BuyCheckResult> {
                    BuyCheckResultRoute(
                        onBack = back,
                        onEditConditions = back,
                        onEditAmount = { if (!navController.popBackStack<BuyCheckInput>(inclusive = false)) back() },
                        onForecast = { if (!navController.popBackStack<Forecast>(inclusive = false)) back() },
                        onPay = { navController.navigate(PayCode) },
                        onSaved = { planId -> navController.toBuyCheckSaved(planId) },
                    )
                }
                composable<BuyCheckSaved> {
                    BuyCheckSavedRoute(
                        onForecast = back,
                        onDeleted = { message ->
                            back()
                            showMessage(message)
                        },
                    )
                }
            }

            navigation<PerfGraph>(startDestination = PerfOverview) {
                // 7단계: 실제 화면(11 문서)
                composable<PerfOverview> {
                    PerfOverviewRoute(
                        onAnalysis = { id -> navController.navigate(PerfAnalysis(id)) },
                        onCardDetail = { id -> navController.navigate(CardPerformance(id)) },
                        onAddCard = { navController.navigate(AddCard) },
                    )
                }
                composable<CardPerformance> {
                    CardPerformanceRoute(onBack = back, onAnalysis = { id -> navController.backOrNavigate(PerfAnalysis(id)) })
                }
                composable<PerfAnalysis> {
                    PerfAnalysisRoute(onBack = back, onCardDetail = { id -> navController.backOrNavigate(CardPerformance(id)) })
                }
                composable<BenefitLimits> { BenefitLimitsRoute(onBack = back, onPay = { navController.navigate(PayCode) }) }
            }

            navigation<ReportGraph>(startDestination = Report) {
                // 9단계: 실제 화면(13 문서)
                composable<Report> {
                    ReportRoute(
                        onBenefits = { navController.navigate(BenefitReport) },
                        onPayments = { navController.navigate(Payments) },
                    )
                }
                composable<BenefitReport> {
                    BenefitReportRoute(onBack = back, onPayments = { navController.navigate(Payments) })
                }
                composable<Payments> {
                    PaymentsRoute(
                        onBack = back,
                        onDetail = { paymentId -> navController.navigate(PaymentDetail(paymentId)) },
                    )
                }
            }

            navigation<MyGraph>(startDestination = My) {
                // 10단계: 실제 화면(13 문서)
                composable<My> {
                    MyRoute(
                        onNotifications = { navController.navigate(Notifications) },
                        onProfile = { navController.navigate(Profile) },
                        onCardManage = { navController.navigate(CardManage) },
                        onAccountManage = { navController.navigate(AccountManage) },
                        onNotificationSettings = { navController.navigate(NotificationSettings) },
                        onSecurity = { navController.navigate(Security) },
                        onBilling = { navController.navigate(Billing) },
                        onOpenPinPad = openPinPad,   // 숨은 시연 모드 본인 확인(S15P31E103-434)
                        onNotReady = showMessage,
                        onLoggedOut = {
                            // 첫 화면으로. 스플래시로 가면 이 폰에 남은 기기 때문에 곧바로 간편 비밀번호 화면이 열린다(2차 QA 14)
                            navController.toWelcome()
                            showMessage("로그아웃했어요")
                        },
                        onDebugMenu = debugMenu,
                    )
                }
                composable<CardManage> {
                    CardManageRoute(
                        onBack = back,
                        onAddCard = { navController.navigate(AddCard) },
                        onCardDetail = { id -> navController.navigate(CardDetail(id)) },
                        onCardOrder = { navController.navigate(CardOrder) },
                    )
                }
                composable<CardDetail> {
                    CardDetailRoute(
                        onBack = back,
                        onUnregistered = { name ->
                            back()
                            showMessage("${name}${name.objectParticle()} 해제했어요")
                        },
                        onOpenPinPad = openPinPad,
                        onBills = { id -> navController.navigate(CardBills(id)) },
                        onPerformance = { id -> navController.navigate(CardPerformance(id)) },
                        onAccounts = { navController.navigate(AccountManage) { launchSingleTop = true } },
                    )
                }
                composable<CardOrder> {
                    CardOrderRoute(
                        onBack = back,
                        onSaved = {
                            back()
                            showMessage("카드 순서를 저장했어요")
                        },
                    )
                }
                composable<AddCard> {
                    CardRegisterRoute(
                        onboarding = false,
                        onLeave = back,
                        onFinished = back,
                        onCloseDone = back,
                        onOpenPinPad = openPinPad,
                    )
                }
                composable<AccountManage> { AccountManageRoute(onBack = back) }
                composable<NotificationSettings> { NotificationSettingsRoute(onBack = back) }
                composable<Profile> { ProfileRoute(onBack = back, onOpenPinPad = openPinPad, onWithdraw = { navController.navigate(Withdraw) }) }
                composable<Withdraw> {
                    WithdrawRoute(
                        onBack = back,
                        onOpenPinPad = openPinPad,
                        onWithdrawn = {
                            navController.restartFromSplash()   // 스택을 비워 뒤로 가도 앱 안 화면으로 돌아가지 않게
                            showMessage("탈퇴했어요")
                        },
                    )
                }
                composable<Security> {
                    SecurityRoute(onBack = back, onOpenPinPad = openPinPad, onChangePin = { navController.navigate(PinChange) })
                }
                composable<PinChange> {
                    PinChangeRoute(
                        onOpenPinPad = openPinPad,
                        onEnd = { end ->
                            back()
                            when (end) {
                                PinChangeEnd.Changed -> showMessage("간편 비밀번호를 바꿨어요")
                                is PinChangeEnd.Closed -> end.message?.let(showMessage)
                            }
                        },
                    )
                }
                // 청구 · 환급 — 마이 탭 전체와 카드 상세의 한 카드가 같은 화면(경로 값으로 구분)
                val openBill: (Long) -> Unit = { billId -> navController.navigate(BillDetail(billId)) }
                val openPayment: (Long) -> Unit = { paymentId -> navController.navigate(PaymentDetail(paymentId)) }
                composable<Billing> { BillingRoute(onBack = back, onBill = openBill, onPayment = openPayment) }
                composable<CardBills> { BillingRoute(onBack = back, onBill = openBill, onPayment = openPayment) }
                composable<BillDetail> { BillDetailRoute(onBack = back, onPayment = openPayment) }
            }

            // 결제 — 탭 밖 전체 화면. 어느 탭에서 열든 그 탭 위에 쌓인다
            // 6단계: 실제 화면(10 문서). 결제 코드 → (가맹점이 찍음) → 결제 요청 확인까지 한 화면
            composable<PayCode> {
                PayCodeRoute(
                    onClose = { message ->
                        back()
                        message?.let(showMessage)   // 앱 범위에서 띄워야 화면이 닫혀도 보인다
                    },
                    onPaid = { paymentId -> navController.toPayDone(paymentId) },
                    onOpenPinPad = openPinPad,
                    onAddCard = { navController.navigate(AddCard) { popUpTo<PayCode> { inclusive = true } } },
                )
            }
            composable<PayDone> {
                PayDoneRoute(
                    onHome = {
                        navController.popBackStack()
                        navController.navigateToTab(TopTab.HOME)
                    },
                    onDetail = { paymentId -> navController.navigate(PaymentDetail(paymentId)) },
                    onPerformance = { userCardId -> navController.navigate(CardPerformance(userCardId)) },
                )
            }
            composable<PaymentDetail> { PaymentDetailRoute(onBack = back, onCancel = { r -> navController.navigate(CancelCode(r.paymentId)) }) }
            // 결제 취소 — 결제할 때와 같은 코드를 띄우고 매장 POS가 찍어 취소한다. 취소가 확인되면 닫고 상세로 돌아간다
            composable<CancelCode> {
                CancelCodeRoute(
                    onClose = { message ->
                        back()
                        message?.let(showMessage)
                    },
                    // 가맹점이 취소 대신 결제로 찍었으면 결제 완료로 — 이 코드 화면은 빼고
                    onPaid = { paymentId -> navController.navigate(PayDone(paymentId)) { popUpTo<CancelCode> { inclusive = true } } },
                    onOpenPinPad = openPinPad,
                    onAddCard = { navController.navigate(AddCard) { popUpTo<CancelCode> { inclusive = true } } },
                )
            }
        }

        // 간편 비밀번호로 본인 인증 — 그래프 밖이라 어느 흐름에서든 열린다(09 문서 5-3)
        composable<PinPad> { PinPadRoute(onClose = back) }

        // 앱 자동 잠금 — 보던 화면 위에 얹고, 풀면 그 화면으로(13 문서 9·10-2). 뒤로 = 앱 내리기(건너뛸 수 없음)
        composable<Relock> {
            val activity = LocalContext.current.findActivity()
            BackHandler { activity?.moveTaskToBack(true) }
            PinLoginRoute(
                onLoggedIn = { back() },
                onNoDevice = { navController.toWelcome() },
                onDebugMenu = null,
            )
        }

        // ── 디버그(debug 빌드만) ──
        if (BuildConfig.DEBUG) {
            composable<DebugMenu> { DebugMenuScreen(navController) }
            composable<ComponentGallery> { ComponentGallery(onBack = back) }
        }
    }
}

/**
 * 바로 앞 화면이 [route]와 같은 화면 · 같은 경로 값이면 그리로 돌아가고, 아니면 새로 연다.
 * R-02(카드 실적 상세)와 R-03(실적 분석)은 서로를 여는 링크가 있어 오갈 때마다 두 화면이 쌓였다(S15P31E103-451 리뷰).
 * R-03은 탭으로 카드가 바뀌므로 화면 종류만이 아니라 카드(경로 값)까지 같을 때만 돌아간다 — 다르면 새로 연다
 */
private inline fun <reified T : Any> NavHostController.backOrNavigate(route: T) {
    val previous = previousBackStackEntry
    if (previous != null && previous.destination.hasRoute<T>() && previous.toRoute<T>() == route) popBackStack()
    else navigate(route)
}
