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
