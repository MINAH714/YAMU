package com.yamu.app.feature.home.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * #58 GET /home 응답.
 * 위쪽 = 지금 명세(10/20 모양), 아래쪽 '보완안' = reports/API 명세 보완안 3번 제안(백엔드 확정 전 — 없으면 null).
 * 모르는 필드는 무시하고 없는 필드는 기본값이라, 백엔드가 어느 모양으로 줘도 그려진다(07 문서 3-5).
 */
@Serializable
data class HomeDto(
    val cards: CardsDto? = null,
    val performanceAlerts: List<PerformanceAlertDto> = emptyList(),
    val balanceForecast: BalanceForecastDto? = null,
    val monthSummary: MonthSummaryDto? = null,
    val unreadNotificationCount: Int = 0,
    val partialErrors: List<JsonElement> = emptyList(),
    val dataSyncedAt: String? = null,
    // ── 보완안(확정 전) ──
    val todos: List<TodoDto>? = null,
    val recentPayment: RecentPaymentDto? = null,
    val onboarding: OnboardingDto? = null,
    val todayCard: TodayCardDto? = null,
    // ── BE -490(!317): 이번 달 YAMU 이용 혜택 · 기준 계좌 내일부터 30일 최저 잔고. 옛 서버엔 없다 ──
    // 없을 때(옛 서버)와 비워 보냈을 때(기준 계좌 없음) · 실패(partialErrors)는 toSummary가 가른다(NewHomeArea)
    val benefitSummary: HomeYamuBenefitDto? = null,
    val forecastPreview: HomeForecastPreviewDto? = null,
)

/**
 * 홈 benefitSummary(BE -490) — 이번 한국월 YAMU 결제로 생긴(이용월 기준, 지급월이 아님) 혜택.
 * [totalBenefit]에 [confirmedBenefit]이 들어 있고 [unconfirmedBenefit] = 전체 − 확정이라 셋을 더하지 않는다.
 * 금액은 원화로 바꿀 수 있는 혜택만 — 못 바꾼 줄 수가 [unconvertedBenefitCount]. 칸이 빠져도 홈 전체가 깨지지 않게 모두 기본값
 */
@Serializable
data class HomeYamuBenefitDto(
    val yearMonth: String? = null,
    /** YAMU(새 홈은 YAMU 결제만) */
    val source: String? = null,
    /** PARTIAL · CONFIRMED — 이번 달은 다 확정돼도 PARTIAL */
    val status: String? = null,
    val totalBenefit: Long? = null,
    val confirmedBenefit: Long? = null,
    val unconfirmedBenefit: Long? = null,
    val unconvertedBenefitCount: Long = 0,
    /** 할인 DISCOUNT · 캐시백 CASHBACK · 적립 POINT 순서. 모르는 종류는 홈이 건너뛴다 */
    val byType: List<HomeBenefitTypeDto> = emptyList(),
)

/** 종류별 혜택(혜택 리포트 TypeAmount와 같은 모양). 적립도 원화로 바꾼 원이다(포인트 수량이 아님) */
@Serializable
data class HomeBenefitTypeDto(
    val type: String? = null,
    val expectedBenefit: Long? = null,
    val confirmedBenefit: Long? = null,
    val unconfirmedBenefit: Long? = null,
    val unconvertedBenefitCount: Long = 0,
)

/**
 * 홈 forecastPreview(BE -490) — 기준 계좌 하나, 내일 ~ 오늘+30일(30점)의 표시선(보수적 우선, 없으면 예상) 최저 잔고.
 * forecasts/balance 30일은 오늘을 포함한 31점이라 그 summary.minBalance와 섞지 않는다. 기준 계좌가 없으면 BE가 null(실패 아님)
 */
@Serializable
data class HomeForecastPreviewDto(
    val accountId: Long? = null,
    /** 예보 시작에 쓴 지금 잔고(오늘 일말 예상이 아님) */
    val currentBalance: Long? = null,
    val from: String? = null,
    val to: String? = null,
    val horizonDays: Int? = null,
    val minBalance: Long? = null,
    /** 최저일. 같은 금액이면 가장 빠른 날 */
    val minBalanceDate: String? = null,
    val nextRiskDate: String? = null,
    val alertThreshold: Long? = null,
    /** 잔액을 금융망에서 읽은 UTC 시각 — 홈 '지금 잔고 · ○○ 기준'은 calculatedAt 대신 이것 */
    val balanceAsOf: String? = null,
    val calculatedAt: String? = null,
    val isEstimate: Boolean? = null,
    /** 생활비 자료가 부족해 보수적 선 없이 계산했는지 — 확정 결과처럼 말하지 않는다 */
    val isProvisional: Boolean? = null,
)

@Serializable
data class CardsDto(val count: Int = 0, val items: List<CardItemDto> = emptyList())

@Serializable
data class CardItemDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuerColor: String? = null,
    val maskedNumber: String? = null,
    val cardType: String? = null,
)

@Serializable
data class PerformanceAlertDto(
    val userCardId: Long,
    val nickname: String? = null,
    val remainingAmount: Long = 0,
    val achievementRate: Double? = null,
    val daysLeft: Int? = null,
)

@Serializable
data class BalanceForecastDto(
    val accountId: Long? = null,
    val currentBalance: Long? = null,
    val endBalancePredicted: Long? = null,
    val nextRiskDate: String? = null,
    val horizonDays: Int? = null,
    // ── 보완안: 다음 월급까지 기준 ──
    val minBalance: Long? = null,
    val nextIncome: NextIncomeDto? = null,
    val isEstimate: Boolean? = null,
    val isProvisional: Boolean? = null,
)

@Serializable
data class NextIncomeDto(val date: String, val amount: Long? = null, val label: String? = null)

@Serializable
data class MonthSummaryDto(val yearMonth: String? = null, val totalSpent: Long? = null, val totalBenefit: Long? = null)

@Serializable
data class TodoDto(
    val title: String,
    val tag: String? = null,
    /** PRIMARY · SUCCESS · WARNING · ERROR · NEUTRAL */
    val tone: String? = null,
    val userCardId: Long? = null,
)

@Serializable
data class RecentPaymentDto(
    val paymentId: Long,
    val merchantName: String,
    val amount: Long,
    val cardName: String? = null,
    val benefitAmount: Long? = null,
    val paidAt: String? = null,
)

/** B-07 · P-01 '오늘 쓸 카드' — 자주 쓰는 업종 기준 서버 추천(보완안 09 문서 5-7 ⑦) */
@Serializable
data class TodayCardDto(val userCardId: Long? = null, val cardName: String, val benefit: String? = null)

/** step: LINK(연결 전) · CASHFLOW(월급 · 고정 지출 확인 전) · DONE */
@Serializable
data class OnboardingDto(val step: String)

/** #76 POST /sync 응답 */
@Serializable
data class SyncDto(
    val syncedAt: String? = null,
    val newTransactions: Int = 0,
    val failed: List<JsonElement> = emptyList(),
)
