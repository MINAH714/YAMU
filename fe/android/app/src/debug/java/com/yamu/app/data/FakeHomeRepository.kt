package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.feature.home.data.BalanceForecastDto
import com.yamu.app.feature.home.data.CardBenefit
import com.yamu.app.feature.home.data.CardItemDto
import com.yamu.app.feature.home.data.CardsDto
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeBenefitReportDto
import com.yamu.app.feature.home.data.HomeBenefitSummaryDto
import com.yamu.app.feature.home.data.HomeBenefitType
import com.yamu.app.feature.home.data.HomeBenefitTypeDto
import com.yamu.app.feature.home.data.HomeCardBenefitDto
import com.yamu.app.feature.home.data.HomeForecastPreviewDto
import com.yamu.app.feature.home.data.HomeYamuBenefitDto
import com.yamu.app.feature.home.data.MISSING_CARD_BENEFITS
import com.yamu.app.feature.home.data.toCardBenefits
import com.yamu.app.feature.home.data.HomeCardsData
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeForecastDto
import com.yamu.app.feature.home.data.HomeForecastSummaryDto
import com.yamu.app.feature.home.data.toBrief
import com.yamu.app.feature.performance.data.LimitCardDto
import com.yamu.app.feature.performance.data.LimitDto
import com.yamu.app.feature.home.data.HomeDto
import com.yamu.app.feature.home.data.HomeMonthlyDto
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.NextIncomeDto
import com.yamu.app.feature.home.data.PaySettings
import com.yamu.app.feature.home.data.toBenefit
import com.yamu.app.feature.payment.data.BenefitKind
import java.time.YearMonth
import com.yamu.app.feature.home.data.OnboardingDto
import com.yamu.app.feature.home.data.PerformanceAlertDto
import com.yamu.app.feature.home.data.RecentPaymentDto
import com.yamu.app.feature.home.data.SyncResult
import com.yamu.app.feature.home.data.TodayCardDto
import com.yamu.app.feature.home.data.TodoDto
import com.yamu.app.feature.home.data.toSummary
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
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
class FakeHomeRepository @Inject constructor(
    private val my: FakeMyRepository,
    private val bank: FakeBank,
    private val forecasts: FakeForecastRepository,
    private val payments: FakePaymentRepository,
    private val performance: FakePerformanceRepository,
) : HomeRepository {

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

    /**
     * v4 혜택 히어로 — 가짜 소비분석과 같은 숫자(시드 사용자 32,400원 + 이번 실행 결제 혜택). 시드 몫 중 19,300원은 확정,
     * 이번 실행 결제는 즉시할인만 확정이고 청구할인 · 캐시백 · 적립은 확정 전(BE realization과 같은 구분). 응답은 진짜와 같은 변환(toBenefit)을 거친다
     */
    override suspend fun loadBenefit(month: YearMonth): ApiResult<HomeBenefit> {
        val scenario = scenario()
        delay(if (scenario == HomeScenario.SLOW) 3_000 else 600)
        return when (scenario) {
            HomeScenario.BANK_ERROR, HomeScenario.BENEFIT_ERROR -> ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))
            HomeScenario.NETWORK_ERROR -> ApiResult.Failure(ApiError.Network)
            else -> ApiResult.Success(monthlyDto(scenario, month).toBenefit(LocalDate.now(KST))!!)
        }
    }

    private fun monthlyDto(scenario: HomeScenario, month: YearMonth): HomeMonthlyDto {
        val lines = benefitLines(scenario, month) ?: return HomeMonthlyDto(month.toString(), totalSpent = 0, benefitSummary = HomeBenefitSummaryDto(expected = 0, actual = 0))
        return HomeMonthlyDto(
            yearMonth = month.toString(),
            periodTo = LocalDate.now(KST).toString(),
            totalSpent = (if (bank.seeded) 842_000 else 0) + payments.approved().sumOf { it.amount },
            benefitSummary = HomeBenefitSummaryDto(expected = lines.sumOf { it.amount }, actual = lines.filter { it.confirmed }.sumOf { it.amount }),
        )
    }

    /** 가짜 혜택 한 줄 — 어느 카드 · 어떤 종류 · 확정인지. 홈 히어로 · 종류별 칸 · 카드 줄이 모두 이 줄들을 더한다 */
    private data class BenefitLine(val userCardId: Long?, val type: HomeBenefitType, val amount: Long, val confirmed: Boolean)

    /**
     * [month]의 가짜 혜택 줄 — 시드 사용자 몫(32,400원 중 19,300원 확정)에 이번 실행 결제의 혜택을 더한다.
     * 즉시할인만 확정, 청구할인 · 캐시백 · 적립은 확정 전(BE realization과 같은 구분). 이번 달이 아니거나 결제가 없으면 null
     */
    private fun benefitLines(scenario: HomeScenario, month: YearMonth): List<BenefitLine>? {
        if (month != YearMonth.now(KST) || scenario == HomeScenario.NOT_LINKED || scenario == HomeScenario.NO_PAYMENT) return null
        val seeded = if (bank.seeded) SEED_BENEFITS else emptyList()
        val session = payments.approved().flatMap { p ->
            p.benefits.map { b ->
                when (b.kind) {
                    BenefitKind.INSTANT -> BenefitLine(p.cardId, HomeBenefitType.DISCOUNT, b.amount, confirmed = true)
                    BenefitKind.CASHBACK -> BenefitLine(p.cardId, HomeBenefitType.CASHBACK, b.amount, confirmed = false)
                    BenefitKind.POINT -> BenefitLine(p.cardId, HomeBenefitType.POINT, b.amount, confirmed = false)
                    BenefitKind.BILLING, BenefitKind.UNKNOWN -> BenefitLine(p.cardId, HomeBenefitType.DISCOUNT, b.amount, confirmed = false)
                }
            }
        }
        return seeded + session
    }

    /** 새 홈 칸 benefitSummary(BE -490) — 위 줄들의 합. '원화로 못 바꾼 혜택'이면 적립 한 줄을 금액 없이 센다 */
    private fun yamuBenefitDto(scenario: HomeScenario): HomeYamuBenefitDto {
        val month = YearMonth.now(KST)
        val lines = benefitLines(scenario, month).orEmpty()
        val unconverted = if (scenario == HomeScenario.UNCONVERTED) 1L else 0L
        fun List<BenefitLine>.confirmed() = filter { it.confirmed }.sumOf { it.amount }
        fun List<BenefitLine>.pending() = filterNot { it.confirmed }.sumOf { it.amount }
        return HomeYamuBenefitDto(
            yearMonth = month.toString(),
            source = "YAMU",
            status = "PARTIAL",
            totalBenefit = lines.sumOf { it.amount },
            confirmedBenefit = lines.confirmed(),
            unconfirmedBenefit = lines.pending(),
            unconvertedBenefitCount = unconverted,
            byType = HomeBenefitType.entries.map { t ->
                val of = lines.filter { it.type == t }
                HomeBenefitTypeDto(t.name, of.sumOf { it.amount }, of.confirmed(), of.pending(), if (t == HomeBenefitType.POINT) unconverted else 0)
            },
        )
    }

    /**
     * 새 홈 칸 forecastPreview(BE -490) — 가짜 잔고예보 기준 계좌 · 30일 응답에서 오늘을 빼고 내일부터 30점의 표시선(보수적 우선) 최저.
     * BE HomeForecastPreview와 같은 규칙(같은 금액이면 가장 빠른 날, 위험일은 예상 · 보수적 선 중 하나라도 안전 잔액 미만인 첫날)
     */
    private fun previewDto(accountId: Long, asOf: String): HomeForecastPreviewDto? {
        val dto = forecasts.homeBrief(accountId) ?: return null
        val points = dto.series.drop(1)
        val line = points.map { p -> p.date to (p.conservative ?: p.expected ?: return null) }
        val (minDate, min) = line.minByOrNull { it.second } ?: return null
        val threshold = dto.alertThreshold
        val risk = threshold?.let { t -> points.firstOrNull { p -> (p.expected ?: Long.MAX_VALUE) < t || (p.conservative ?: Long.MAX_VALUE) < t }?.date }
        return HomeForecastPreviewDto(
            accountId = accountId,
            currentBalance = dto.currentTotalBalance,
            from = points.first().date,
            to = points.last().date,
            horizonDays = points.size,
            minBalance = min,
            minBalanceDate = minDate,
            nextRiskDate = risk,
            alertThreshold = threshold,
            balanceAsOf = asOf,
            calculatedAt = dto.calculatedAt,
            isEstimate = true,
            isProvisional = dto.livingCost?.projectedAmount == null,
        )
    }

    /** 카드별 받은 혜택(BE -489 reports/monthly/benefits cards) — 위 줄들을 카드별로. 진짜와 같은 변환(toCardBenefits)을 거친다 */
    override suspend fun loadCardBenefits(month: YearMonth): ApiResult<Map<Long, CardBenefit>> {
        val scenario = scenario()
        delay(if (scenario == HomeScenario.SLOW) 3_000 else 500)
        if (scenario == HomeScenario.NETWORK_ERROR) return ApiResult.Failure(ApiError.Network)
        if (scenario == HomeScenario.OLD_SERVER) return ApiResult.Failure(ApiError.Unknown(MISSING_CARD_BENEFITS))
        val cards = benefitLines(scenario, month).orEmpty().filter { it.userCardId != null }.groupBy { it.userCardId!! }.map { (id, of) ->
            HomeCardBenefitDto(
                userCardId = id,
                expectedBenefit = of.sumOf { it.amount },
                confirmedBenefit = of.filter { it.confirmed }.sumOf { it.amount },
                unconfirmedBenefit = of.filterNot { it.confirmed }.sumOf { it.amount },
                unconvertedBenefitCount = if (scenario == HomeScenario.UNCONVERTED && id == POINT_CARD) 1 else 0,
            )
        }
        return ApiResult.Success(HomeBenefitReportDto(cards).toCardBenefits()!!)
    }

    /** 결제 설정 — 가짜 마이(카드 관리에서 바꾼 자동 선택)와 가짜 카드의 기본 카드. '자동 선택 꺼짐' 상태면 꺼짐 + 기본 카드(없으면 첫 카드) */
    override suspend fun loadPaySettings(): ApiResult<PaySettings> {
        val scenario = scenario()
        if (scenario == HomeScenario.NETWORK_ERROR) return ApiResult.Failure(ApiError.Network)
        val auto = my.autoSelectOn
        val cards = (bank.overview() as? ApiResult.Success)?.data?.cards.orEmpty()
        val default = cards.firstOrNull { it.isDefault }?.userCardId
        return ApiResult.Success(
            if (scenario == HomeScenario.AUTO_SELECT_OFF) PaySettings(autoSelect = false, defaultUserCardId = default ?: cards.firstOrNull()?.userCardId)
            else PaySettings(auto, default),
        )
    }

    /**
     * 내 카드 혜택 현황 — 실적은 가짜 실적관리와 같은 값을 카드 목록 순서로(BE #65가 카드 목록 순서),
     * 한도는 가짜 실적관리 R-06과 같은 남은 금액에 한도 이름을 붙였다. Mr.Life의 통신비 할인은 횟수 한도라 금액을 모른다(null)
     */
    override suspend fun loadCards(): ApiResult<HomeCardsData> {
        val scenario = scenario()
        if (scenario == HomeScenario.NETWORK_ERROR) return ApiResult.Failure(ApiError.Network)
        val overview = when (val o = performance.overview()) {
            is ApiResult.Failure -> return o
            is ApiResult.Success -> o.data
        }
        val order = bank.registeredCardIds()
        val cards = overview.all.sortedBy { order.indexOf(it.userCardId).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
        val registered = order.toSet()
        fun limit(name: String, remaining: Long?, status: String = "AVAILABLE", reason: String? = null) = LimitDto(name, remaining, status, reason)
        val limits = listOf(
            LimitCardDto(16, "KB국민 노리2 체크카드(KB Pay)", remainingAmount = 1_100, limits = listOf(limit("GS25·CU 5% 할인", 1_100, "NEAR_END"))),
            LimitCardDto(15, "신한카드 Mr.Life", remainingAmount = 6_700, limits = listOf(limit("TIME 서비스 10% 할인", 6_700), limit("통신비 할인(월 1회)", null))),
            LimitCardDto(11, "KB국민 청춘대로 톡톡카드", remainingAmount = 5_800, limits = listOf(limit("스타벅스 50% 할인", 5_800))),
            LimitCardDto(14, "삼성 iD ON 카드", remainingAmount = 2_600, limits = listOf(limit("생활 업종 공유 한도", 2_600))),
            LimitCardDto(13, "LOCA LIKIT Shop", remainingAmount = 0, limits = listOf(limit("쿠팡·편의점 공유 한도", 0, "EXHAUSTED"))),
            LimitCardDto(12, "현대카드ZERO Edition3(포인트형)", remainingAmount = null, limits = emptyList()),
        ).filter { it.userCardId in registered }
        return ApiResult.Success(HomeCardsData(cards, limits))
    }

    /** 잔고예보 요약 — 가짜 잔고예보와 같은 계산(연결 계좌 전체 · 30일, -599). '잔고예보만 실패'면 실패 */
    override suspend fun loadForecastBrief(): ApiResult<HomeForecastBrief> {
        val scenario = scenario()
        delay(if (scenario == HomeScenario.SLOW) 3_000 else 500)
        if (scenario == HomeScenario.FORECAST_ERROR) return ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))
        if (scenario == HomeScenario.NETWORK_ERROR) return ApiResult.Failure(ApiError.Network)
        val dto = forecasts.homeBriefAll() ?: return ApiResult.Failure(ApiError.InvalidRequest("ACCOUNT_SELECTION_REQUIRED"))
        val views = bank.accountViews()
        val label = views.singleOrNull()?.let { "${it.bank} ·${it.last4}" } ?: "계좌 ${views.size}개 전체".takeIf { views.size > 1 }
        return ApiResult.Success(
            HomeForecastDto(
                currentTotalBalance = dto.currentTotalBalance,
                summary = dto.summary?.let { HomeForecastSummaryDto(it.minBalance, it.minBalanceDate) },
                calculatedAt = dto.calculatedAt,
            ).toBrief(accountId = null, accountLabel = label),
        )
    }

    /**
     * 홈 상태 — '모든 기능 상태'가 정상이 아니면 그쪽이 먼저(빈 응답 = 연결 전). 데이터 부족은 홈 상태 '가입 첫날'로 본다.
     * '정상'은 가짜 사용자를 따른다: 연결한 계좌가 없으면 연결 전, 카드가 0장이면 카드 0장 홈(S15P31E103-453)
     */
    private fun scenario(): HomeScenario = when (DevSession.faultScenario) {
        FaultScenario.EMPTY -> HomeScenario.NOT_LINKED
        FaultScenario.BANK_ERROR -> HomeScenario.BANK_ERROR
        FaultScenario.NETWORK_ERROR -> HomeScenario.NETWORK_ERROR
        FaultScenario.SLOW -> HomeScenario.SLOW
        FaultScenario.NONE, FaultScenario.DATA_INSUFFICIENT ->
            DevSession.homeScenario.takeUnless { it == HomeScenario.NORMAL && bank.linkedAccountCount() == 0 } ?: HomeScenario.NOT_LINKED
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(KST)

    private fun dto(scenario: HomeScenario): HomeDto {
        val synced = now().minusMinutes(3).toOffsetDateTime().toString()
        // 예상 잔액 · 다음 월급은 가짜 잔고예보와 같은 계산 — BE HomeService처럼 기준 계좌 하나로(S15P31E103-454). 월급이 없으면 다음 월급 없음
        val f = forecasts.homeForecast()
        val forecast = f?.let {
            BalanceForecastDto(
                accountId = it.accountId,
                currentBalance = it.currentBalance,
                endBalancePredicted = it.endBalance,
                nextRiskDate = it.nextRiskDate?.toString(),
                horizonDays = it.horizonDays,
                minBalance = it.minBalance,
                nextIncome = it.nextIncome?.let { (date, amount) -> NextIncomeDto(date = date.toString(), amount = amount, label = "월급") },
                isEstimate = true,
                isProvisional = scenario == HomeScenario.FIRST_DAY || it.provisional,
            )
        }
        // 카드 수 · 할 일 · 오늘의 카드는 가짜 사용자가 등록한 카드만(0장이면 실서버 새 사용자처럼 셋 다 없음)
        val registered = bank.registeredCardIds().toSet()
        // 새 홈 칸(BE -490) — 기준 계좌가 있으면 같은 계좌로 forecastPreview도(BE HomeService와 같음)
        val preview = f?.let { previewDto(it.accountId, synced) }
        val lotteTodo = TodoDto("롯데카드는 통신비만 옮기면 채워져요", "실적 달성 추천", "PRIMARY", 13)
        val todos = listOf(lotteTodo, TodoDto("삼성카드는 안 채우는 게 유리해요", "추가 소비 비추천", "ERROR", 14))
            .filter { it.userCardId in registered }
        val base = HomeDto(
            cards = CardsDto(count = registered.size, items = bank.cardViews().map { CardItemDto(it.userCardId, nickname = it.product) }),
            balanceForecast = forecast,
            unreadNotificationCount = my.unreadCount(),   // 알림함(H-02)과 같은 수(13 문서 9·10-6 ⑥)
            dataSyncedAt = synced,
            todos = todos,
            onboarding = OnboardingDto("DONE"),
            todayCard = TodayCardDto(11, "KB국민카드", "스타벅스 50% · 버거 20% 할인").takeIf { 11L in registered },
            benefitSummary = yamuBenefitDto(scenario),
            forecastPreview = preview,
        )
        return when (scenario) {
            HomeScenario.FIRST_DAY -> base.copy(
                unreadNotificationCount = 0,
                todos = listOf(lotteTodo).filter { it.userCardId in registered },   // 잠정은 카드 머리 '잠정 판단'이 말한다
            )
            HomeScenario.AFTER_PAYMENT -> base.copy(
                recentPayment = RecentPaymentDto(938L, "스타벅스 역삼점", 15_000, "KB국민카드", 7_500, paidAt = now().toOffsetDateTime().toString()),   // 방금 결제
                // 할인 뒤 낸 7,500원만큼 줄어든 예상 잔액 — 홈 금액이 7,500원 내려가며 굴러가는 걸 볼 수 있게
                balanceForecast = forecast?.let { it.copy(currentBalance = it.currentBalance?.minus(7_500), minBalance = it.minBalance?.minus(7_500)) },
                forecastPreview = preview?.let { it.copy(currentBalance = it.currentBalance?.minus(7_500), minBalance = it.minBalance?.minus(7_500)) },
            )
            // 잔고예보 월급을 정하기 전 — BE는 수입 없이 30일을 계산해 끝 잔액이 음수가 되기 쉽다(실서버 김야무 11/12 모양). 홈은 지금 잔액 히어로 + 월급 안내.
            // 가짜 사용자에 월급이 없으면 '정상'도 이 모양(다음 월급 없음)이 된다. 여기는 끝 잔액이 음수인 모양을 일부러 보는 칸
            HomeScenario.BEFORE_INCOME -> base.copy(
                balanceForecast = BalanceForecastDto(
                    accountId = f?.accountId ?: 1,
                    currentBalance = f?.currentBalance ?: 0,
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
            // 옛 잔고 영역만 실패 — 새 서버는 forecastPreview를 따로 만들어 요약은 그대로 보인다(BE HomeService)
            HomeScenario.PARTIAL -> base.copy(balanceForecast = null, partialErrors = listOf(JsonPrimitive("balanceForecast")))
            // BE는 기준 계좌가 없으면 balanceForecast · forecastPreview를 null로 준다(실패 아님) — 홈 요약은 계좌 연결 안내
            HomeScenario.NO_ACCOUNT -> base.copy(balanceForecast = null, forecastPreview = null)
            // 새 칸만 실패 — BE는 칸을 null로 두고 partialErrors에 {area, code}를 적는다
            HomeScenario.BENEFIT_ERROR -> base.copy(benefitSummary = null, partialErrors = listOf(areaError("benefitSummary")))
            HomeScenario.FORECAST_ERROR -> base.copy(forecastPreview = null, partialErrors = listOf(areaError("forecastPreview")))
            // BE -490 전 서버 — 두 칸이 없다. 홈은 reports/monthly · forecasts/balance로 돌아간다
            HomeScenario.OLD_SERVER -> base.copy(benefitSummary = null, forecastPreview = null)
            // 가입 때 1원 인증으로 계좌만 붙고 카드 등록을 미룬 사용자(실서버는 onboarding DONE · cards 0장, -430)는 '정상'이 가짜 사용자를 따라 그린다
            else -> base
        }
    }

    private fun areaError(area: String) = JsonObject(mapOf("area" to JsonPrimitive(area), "code" to JsonPrimitive("INTERNAL_ERROR")))

    private companion object {
        /**
         * 시드 사용자의 이번 달 혜택(가짜 소비분석과 같은 합 32,400원 · 확정 19,300원). 청춘대로 · Mr.Life · 노리2 세 장에 나눴고
         * 캐시백 · 적립은 아직 확정 전이라 종류별 칸에 '확정 전'이 보인다
         */
        val SEED_BENEFITS = listOf(
            BenefitLine(11, HomeBenefitType.DISCOUNT, 9_800, confirmed = true),
            BenefitLine(15, HomeBenefitType.DISCOUNT, 6_500, confirmed = true),
            BenefitLine(16, HomeBenefitType.DISCOUNT, 3_000, confirmed = true),
            BenefitLine(11, HomeBenefitType.DISCOUNT, 6_000, confirmed = false),
            BenefitLine(15, HomeBenefitType.CASHBACK, 4_100, confirmed = false),
            BenefitLine(16, HomeBenefitType.POINT, 3_000, confirmed = false),
        )

        /** '원화로 못 바꾼 혜택' 상태에서 적립 한 줄을 금액 없이 세는 카드(노리2) */
        const val POINT_CARD = 16L
    }
}
