package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.home.data.BalanceForecastDto
import com.yamu.app.feature.home.data.CardsDto
import com.yamu.app.feature.home.data.HomeDto
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.NextIncomeDto
import com.yamu.app.feature.home.data.OnboardingDto
import com.yamu.app.feature.home.data.PerformanceAlertDto
import com.yamu.app.feature.home.data.RecentPaymentDto
import com.yamu.app.feature.home.data.SyncResult
import com.yamu.app.feature.home.data.TodayCardDto
import com.yamu.app.feature.home.data.TodoDto
import com.yamu.app.feature.home.data.toSummary
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 홈 — 백엔드 #58이 준비되기 전 화면 상태를 전부 보기 위한 것(release에는 들어가지 않음).
 * 응답 모양은 API 명세(지금 명세 + 보완안). 진짜와 같은 변환(toSummary)을 거친다.
 */
@Singleton
class FakeHomeRepository @Inject constructor(private val my: FakeMyRepository) : HomeRepository {

    override suspend fun loadHome(): ApiResult<HomeSummary> {
        val scenario = scenario()
        delay(if (scenario == HomeScenario.SLOW) 3_000 else 500)
        return when (scenario) {
            HomeScenario.BANK_ERROR -> ApiResult.Failure(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
            HomeScenario.NETWORK_ERROR -> ApiResult.Failure(ApiError.Network)
            else -> ApiResult.Success(dto(scenario).toSummary(LocalDate.now(KST)))
        }
    }

    override suspend fun sync(): ApiResult<SyncResult> {
        delay(400)
        return when (scenario()) {
            HomeScenario.BANK_ERROR -> ApiResult.Failure(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
            HomeScenario.NETWORK_ERROR -> ApiResult.Failure(ApiError.Network)
            else -> ApiResult.Success(SyncResult(now().toOffsetDateTime().toString(), failedCount = 0))
        }
    }

    /** 홈 상태 — '모든 기능 상태'가 정상이 아니면 그쪽이 먼저(데이터 없음 = 연결 전). 데이터 부족은 홈 상태 '가입 첫날'로 본다 */
    private fun scenario(): HomeScenario = when (DevSession.faultScenario) {
        FaultScenario.EMPTY -> HomeScenario.NOT_LINKED
        FaultScenario.BANK_ERROR -> HomeScenario.BANK_ERROR
        FaultScenario.NETWORK_ERROR -> HomeScenario.NETWORK_ERROR
        FaultScenario.SLOW -> HomeScenario.SLOW
        FaultScenario.NONE, FaultScenario.DATA_INSUFFICIENT -> DevSession.homeScenario
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(KST)

    private fun dto(scenario: HomeScenario): HomeDto {
        val synced = now().minusMinutes(3).toOffsetDateTime().toString()
        val payday = nextPayday()
        val forecast = BalanceForecastDto(
            accountId = 3,
            currentBalance = 950_000,
            minBalance = 350_000,
            nextIncome = NextIncomeDto(date = payday.toString(), amount = 2_800_000, label = "월급"),
            isEstimate = true,
            isProvisional = scenario == HomeScenario.FIRST_DAY,
        )
        val todos = listOf(
            TodoDto("롯데카드는 통신비만 옮기면 채워져요", "실적 달성 추천", "PRIMARY", 13),
            TodoDto("삼성카드는 안 채우는 게 유리해요", "추가 소비 비추천", "ERROR", 14),
        )
        val base = HomeDto(
            cards = CardsDto(count = 6),
            balanceForecast = forecast,
            unreadNotificationCount = my.unreadCount(),   // 알림함(H-02)과 같은 수(13 문서 9·10-6 ⑥)
            dataSyncedAt = synced,
            todos = todos,
            onboarding = OnboardingDto("DONE"),
            todayCard = TodayCardDto(2, "KB국민카드", "스타벅스 50% · 버거 20% 할인"),
        )
        return when (scenario) {
            HomeScenario.FIRST_DAY -> base.copy(
                unreadNotificationCount = 0,
                todos = listOf(TodoDto("롯데카드는 통신비만 옮기면 채워져요", "실적 달성 추천", "PRIMARY", 13)),   // 잠정은 카드 머리 '잠정 판단'이 말한다
            )
            HomeScenario.AFTER_PAYMENT -> base.copy(
                recentPayment = RecentPaymentDto(938L, "스타벅스 역삼점", 15_000, "KB국민카드", 7_500),
                // 할인 뒤 낸 7,500원만큼 줄어든 예상 잔액 — 홈 금액이 350,000 → 342,500으로 굴러가는 걸 볼 수 있게
                balanceForecast = forecast.copy(currentBalance = 950_000 - 7_500, minBalance = 350_000 - 7_500),
            )
            // 잔고예보 월급을 정하기 전 — BE는 수입 없이 30일을 계산해 끝 잔액이 음수가 되기 쉽다(실서버 김야무 11/12 모양). 홈은 지금 잔액 히어로 + 월급 안내
            HomeScenario.BEFORE_INCOME -> base.copy(
                balanceForecast = BalanceForecastDto(
                    accountId = 3,
                    currentBalance = 950_000,
                    endBalancePredicted = -275_622,
                    nextRiskDate = LocalDate.now(KST).plusDays(21).toString(),
                    horizonDays = 30,
                    minBalance = -275_622,
                    isEstimate = true,
                    isProvisional = false,
                ),
            )
            HomeScenario.NOT_LINKED -> HomeDto(
                cards = CardsDto(count = 0),
                onboarding = OnboardingDto("LINK"),
                dataSyncedAt = null,
            )
            HomeScenario.LEGACY_SPEC -> HomeDto(   // 지금 명세 예시(10/20 모양) 그대로
                cards = CardsDto(count = 4),
                performanceAlerts = listOf(PerformanceAlertDto(15, "메인 신용카드", 120_000, 60.0, 11)),
                balanceForecast = BalanceForecastDto(
                    accountId = 3,
                    currentBalance = 620_000,
                    endBalancePredicted = 2_214_500,
                    nextRiskDate = LocalDate.now(KST).plusDays(9).toString(),
                    horizonDays = 30,
                ),
                unreadNotificationCount = 3,
                dataSyncedAt = synced,
            )
            HomeScenario.PARTIAL -> base.copy(balanceForecast = null, partialErrors = listOf(JsonPrimitive("balanceForecast")))
            else -> base
        }
    }

    /** 다음 10일(월급날 예시) */
    /** 가짜 잔고예보(FakeForecastRepository)와 같은 월급날 — 오늘 + 25일 */
    private fun nextPayday(): LocalDate = LocalDate.now(KST).plusDays(25)
}
