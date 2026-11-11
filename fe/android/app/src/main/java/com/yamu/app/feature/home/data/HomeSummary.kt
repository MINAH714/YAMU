package com.yamu.app.feature.home.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 홈 화면이 그릴 값만 — 서버 DTO를 화면에 그대로 쓰지 않는다 */
data class HomeSummary(
    val forecast: ForecastSummary?,
    val recentPayment: RecentPayment?,
    val todos: List<HomeTodo>,
    val cardCount: Int,
    val unreadCount: Int,
    val needsLinking: Boolean,
    val syncedAtIso: String?,
    val failedAreas: Set<String>,
    val todayCard: TodayCard? = null,
)

data class ForecastSummary(
    val label: String,
    val amount: Long,
    val isEstimate: Boolean,
    val isProvisional: Boolean,
    val incomeDate: LocalDate?,
    val daysToIncome: Long?,
    val shortageFrom: LocalDate?,
    /** 잔고예보 월급을 아직 정하지 않았다(nextIncome 없음) — 홈이 월급 등록(B-06)으로 이끈다(S15P31E103-429) */
    val needsIncome: Boolean = false,
)

enum class TodoTone { Neutral, Primary, Success, Warning, Error }

data class HomeTodo(val title: String, val tag: String?, val tone: TodoTone, val userCardId: Long?)

data class TodayCard(val cardName: String, val benefit: String?)

data class RecentPayment(
    val paymentId: Long,
    val merchantName: String,
    val amount: Long,
    val cardName: String?,
    val benefitAmount: Long?,
)

/** 서버 응답 → 화면 값. 보완안 필드가 있으면 그걸, 없으면 지금 명세 필드로 대신(07 문서 3-5 표) */
fun HomeDto.toSummary(today: LocalDate): HomeSummary = HomeSummary(
    forecast = balanceForecast?.toForecast(today),
    recentPayment = recentPayment?.let {
        RecentPayment(it.paymentId, it.merchantName, it.amount, it.cardName, it.benefitAmount)
    },
    todos = todos?.take(MAX_TODOS)?.map { it.toTodo() }
        ?: performanceAlerts.take(MAX_TODOS).map { it.toTodo() },
    cardCount = cards?.count ?: 0,
    unreadCount = unreadNotificationCount,
    needsLinking = onboarding?.step == "LINK" || (onboarding == null && (cards?.count ?: 0) == 0),
    syncedAtIso = dataSyncedAt,
    failedAreas = partialErrors.mapNotNull { e ->
        when (e) {
            is JsonPrimitive -> e.contentOrNull
            is JsonObject -> e["area"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }.toSet(),
    todayCard = todayCard?.let { TodayCard(it.cardName, it.benefit) },
)

private const val MAX_TODOS = 2

private fun BalanceForecastDto.toForecast(today: LocalDate): ForecastSummary? {
    val income = parseDate(nextIncome?.date)
    // 월급을 정하기 전이면 BE는 수입 없이 30일을 계산해 끝 잔액이 줄기만 한다. 그 숫자 대신 지금 잔액을 주인공으로 두고
    // 월급 등록으로 이끈다. 위험일도 수입 없이 본 날짜라 홈에서는 말하지 않는다(잔고예보 화면에는 남음).
    // 지금 잔액이 0원 이하면 히어로를 쓸 수 없어 아래의 예전 카드로 간다(S15P31E103-429)
    if (income == null && currentBalance != null && currentBalance > 0) {
        return ForecastSummary(
            label = "지금 잔액",
            amount = currentBalance,
            isEstimate = false,
            isProvisional = isProvisional ?: false,
            incomeDate = null,
            daysToIncome = null,
            shortageFrom = null,
            needsIncome = true,
        )
    }
    val (label, amount) = when {
        // 월급 전까지 가장 낮아지는 잔액이라 잔고예보 첫 카드와 같은 이름 — '월급 전에 이만큼 남는다'로 읽히지 않게
        minBalance != null && income != null -> "월급 전 가장 낮을 때" to minBalance
        endBalancePredicted != null -> "${horizonDays ?: 30}일 뒤 예상 잔액" to endBalancePredicted
        currentBalance != null -> "지금 잔액" to currentBalance
        else -> return null
    }
    return ForecastSummary(
        label = label,
        amount = amount,
        isEstimate = isEstimate ?: (label != "지금 잔액"),
        isProvisional = isProvisional ?: false,
        incomeDate = income,
        daysToIncome = income?.let { ChronoUnit.DAYS.between(today, it) },
        shortageFrom = parseDate(nextRiskDate),
        needsIncome = income == null,
    )
}

private fun TodoDto.toTodo() = HomeTodo(
    title = title,
    tag = tag,
    tone = when (tone?.uppercase()) {
        "PRIMARY" -> TodoTone.Primary
        "SUCCESS" -> TodoTone.Success
        "WARNING" -> TodoTone.Warning
        "ERROR" -> TodoTone.Error
        else -> TodoTone.Neutral
    },
    userCardId = userCardId,
)

/** 지금 명세 모양: 실적 부족 카드 → '야무가 챙겨 봤어요' 한 줄 */
private fun PerformanceAlertDto.toTodo() = HomeTodo(
    title = "${nickname ?: "카드"} 실적까지 ${remainingAmount.toWon()}원 남았어요",
    tag = daysLeft?.let { "마감 D-$it" },
    tone = TodoTone.Warning,
    userCardId = userCardId,
)
