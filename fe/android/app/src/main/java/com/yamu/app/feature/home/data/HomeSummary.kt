package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
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
    val paidAtIso: String? = null,   // 승인 시각(서버 UTC) — 줄에는 paidAtLabel로
)

/**
 * 최근 결제 줄 시각(H-01c) — 1분 안 '방금' · 1시간 안 '23분 전' · 오늘 '오늘 14:05' · 어제 '어제 17:05' · 그 전 '11/10 17:05'.
 * 서버 시계가 5분까지 앞서 있으면 '방금'으로 본다. 시각이 없거나 못 읽으면 null(줄에는 카드 이름만)
 */
fun paidAtLabel(iso: String?, now: ZonedDateTime = ZonedDateTime.now(KST)): String? {
    if (iso.isNullOrBlank()) return null
    val at = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(KST) }.getOrNull() ?: return null
    val nowKst = now.withZoneSameInstant(KST)
    val minutes = Duration.between(at, nowKst).toMinutes()   // 0으로 잘라서 59초 전 · 30초 뒤는 0
    if (minutes in -CLOCK_SKEW_MINUTES..0) return "방금"
    if (minutes in 1..59) return "${minutes}분 전"
    val hm = "%02d:%02d".format(at.hour, at.minute)
    val today = nowKst.toLocalDate()
    return when (at.toLocalDate()) {
        today -> "오늘 $hm"
        today.minusDays(1) -> "어제 $hm"
        else -> "${at.monthValue}/${at.dayOfMonth} $hm"
    }
}

private const val CLOCK_SKEW_MINUTES = 5L

/** 서버 응답 → 화면 값. 보완안 필드가 있으면 그걸, 없으면 지금 명세 필드로 대신(07 문서 3-5 표) */
fun HomeDto.toSummary(today: LocalDate): HomeSummary = HomeSummary(
    forecast = balanceForecast?.toForecast(today),
    recentPayment = recentPayment?.let {
        RecentPayment(it.paymentId, it.merchantName, it.amount, it.cardName, it.benefitAmount, it.paidAt)
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
