package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs

// ── 서버 응답 — GET /api/forecasts/balance(BE BalanceForecast, docs/be/forecast.md 「결과」) ──
// 종류 · 확신도 · 이유 같은 값은 문자열로 받는다. BE가 새 값을 더해도 읽기는 깨지지 않고, 화면은 아는 값만 따로 그린다

@Serializable data class BalanceForecastDto(
    val accountIds: List<Long> = emptyList(),
    val currentTotalBalance: Long? = null,
    val horizon: HorizonDto? = null,
    val alertThreshold: Long? = null,
    val summary: ForecastSummaryDto? = null,
    val series: List<SeriesPointDto> = emptyList(),
    val riskWindows: List<RiskWindowDto> = emptyList(),
    val livingCost: ForecastLivingCostDto? = null,
    val beyondHorizon: List<BeyondHorizonItemDto> = emptyList(),
    val excluded: List<ExcludedItemDto> = emptyList(),
    val isEstimate: Boolean = true,
    val calculatedAt: String? = null,
)

/**
 * 출금일이 예보 기간 뒤인 카드 청구 · 신용카드 사용 추정(BE -348 !199). 선과 위험에는 넣지 않았고 선택 계좌에서 빠지는 것만 온다.
 * 청구는 title이 "카드 이름 N월 청구", 사용 추정은 근거 문장이다
 */
@Serializable data class BeyondHorizonItemDto(
    val date: String? = null,
    val type: String? = null,
    val title: String? = null,
    val amount: Long = 0,
    val confidence: String? = null,
)

/** type은 실제로 계산한 기준 — NEXT_INCOME이면 to가 다음 월급일 전날, 월급을 못 찾으면 DAYS(30일) */
@Serializable data class HorizonDto(val type: String? = null, val from: String? = null, val to: String? = null, val days: Int? = null)

/**
 * 금액은 가장 낮은 선(conservative 'AI 생활비 반영', 없으면 expected 'AI 생활비 제외') 기준.
 * status는 두 선 기준이다 — expected가 안전 잔액 미만이면 DANGER, conservative만 미만이면 WARNING(BE -356 !223)
 */
@Serializable data class ForecastSummaryDto(
    val status: String? = null,
    val aggregateStatus: String? = null,
    val minBalance: Long? = null,
    val minBalanceDate: String? = null,
    val endBalance: Long? = null,
    val hasAccountRisk: Boolean = false,
    val atRiskAccountCount: Int = 0,
)

/**
 * 앱은 expected('AI 생활비 제외')와 conservative('AI 생활비 반영') 두 선만 그린다. confirmed는 응답에 남아 받기만 한다(BE -356).
 * conservative는 생활비 자료가 없는 계좌가 하나라도 있으면 null(BE는 일부만 더한 값을 주지 않는다)
 */
@Serializable data class SeriesPointDto(
    val date: String,
    val confirmed: Long? = null,
    val expected: Long? = null,
    val conservative: Long? = null,
    val events: List<ForecastEventDto> = emptyList(),
)

@Serializable data class ForecastEventDto(val type: String? = null, val title: String? = null, val amount: Long = 0, val confidence: String? = null)

@Serializable data class RiskWindowDto(
    val from: String? = null,
    val to: String? = null,
    val level: String? = null,
    val line: String? = null,
    val lowestBalance: Long? = null,
    val shortfall: Long? = null,
    val causes: List<RiskCauseDto> = emptyList(),
)

@Serializable data class RiskCauseDto(val type: String? = null, val title: String? = null, val amount: Long = 0, val date: String? = null)

/** 예상 생활비는 projectedAmount만 보인다. dailyAverage · monthlyAverage는 지난 3개월 실적이라 예측 값이 아니다(BE 11/5 안내) */
@Serializable data class ForecastLivingCostDto(
    val projectedAmount: Long? = null,
    val dailyAverage: Long? = null,
    val monthlyAverage: Long? = null,
    val method: String? = null,
    val dataOrigin: String? = null,
    val confidence: String? = null,
)

@Serializable data class ExcludedItemDto(
    val cashflowItemId: Long? = null,
    val cardBillId: Long? = null,
    val date: String? = null,
    val title: String? = null,
    val amount: Long? = null,
    val reason: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

/**
 * 입출금의 확신도. CONFIRMED · EXPECTED는 두 선 모두에, CONSERVATIVE는 'AI 생활비 반영' 선에만 들어간다.
 * 위험 구간의 선([RiskWindow.line])도 이 값으로 받는다 — BE -356부터 EXPECTED · CONSERVATIVE만 오고, 옛 서버는 CONFIRMED를 보낼 수 있다.
 * 모르는 값은 [UNKNOWN] — 목록에는 그대로 보이고 선 설명에서는 '기타'로 묶인다
 */
enum class FlowConfidence { CONFIRMED, EXPECTED, CONSERVATIVE, UNKNOWN }

/** 입출금 종류. 위험 원인에만 LIVING_COST_ESTIMATE(누적 예상 생활비)가 더 있다 */
enum class FlowType { SCHEDULED_INCOME, SCHEDULED_OUTFLOW, CARD_BILLING, SIMULATED_PURCHASE, CARD_SPENDING_ESTIMATE, LIVING_COST_ESTIMATE, UNKNOWN }

/** 위험 수준 — 'AI 생활비 제외' 선(expected)이 안전 잔액 아래면 DANGER, 'AI 생활비 반영' 선(conservative)만 아래면 WARNING(BE -356 !223) */
enum class RiskLevel { SAFE, WARNING, DANGER, UNKNOWN }

/** 예상 생활비 계산 방식 — AI(ML_V1) · 최근 소비(TSB) · 계좌마다 다름(MIXED) */
enum class LivingMethod(val label: String?) { ML_V1("AI 예측"), TSB("최근 소비 기준"), MIXED("계좌마다 다른 방식"), UNKNOWN(null) }

internal inline fun <reified E : Enum<E>> enumOf(value: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: fallback

/** 예보 기간 — 보내지 않으면(null) 잔고예보 설정을 따른다. N일은 until=DAYS와 days를 함께 보내야 한다(BE -318) */
enum class ForecastPeriod(val until: String, val days: Int?, val label: String) {
    NEXT_INCOME("NEXT_INCOME", null, "월급날까지"),
    DAYS_7("DAYS", 7, "7일"),
    DAYS_14("DAYS", 14, "14일"),
    DAYS_30("DAYS", 30, "30일"),
}

/** 화면에서 고른 기간 · 계좌. null은 '정하지 않음'(기간은 서버 설정, 계좌는 연결 계좌 전체). 앱을 켜 둔 동안만 기억한다 */
data class ForecastSelection(val period: ForecastPeriod? = null, val accountIds: Set<Long>? = null)

/** 계좌 고르기 시트의 한 줄. [cardIds]는 이 계좌에서 출금하는 등록 카드(GET /accounts의 linkedCards) */
data class ForecastAccountOption(
    val accountId: Long,
    val name: String,
    val bankName: String?,
    val last4: String,
    val balance: Long?,
    val isPrimary: Boolean,
    val cardIds: Set<Long> = emptySet(),
)

/**
 * 계좌를 일부만 골랐을 때 예정 수입·지출 목록도 같은 계좌로 거르기 위한 범위(가계부 조회 API는 계좌를 거르지 않는다).
 * 계좌 지정 줄은 그 계좌, 카드 줄은 그 카드의 출금 계좌로 본다 — BE CashflowEvents와 같은 귀속 규칙
 */
data class AccountScope(val accountIds: Set<Long>, val cardIds: Set<Long>) {
    /** 계좌도 카드도 없는 줄은 어느 계좌인지 몰라(BE도 예보에서 빼고 알림) 그대로 보인다 */
    fun includes(entry: CashflowEntry): Boolean = when {
        entry.accountId != null -> entry.accountId in accountIds
        entry.userCardId != null -> entry.userCardId in cardIds
        else -> true
    }
}

/** 고른 계좌가 연결 계좌 전체가 아닐 때만 범위가 있다(전체면 거를 것이 없다) */
fun ForecastSelection.scopeFrom(accounts: List<ForecastAccountOption>): AccountScope? {
    val chosen = accountIdsFrom(accounts).toSet()
    if (chosen.size == accounts.size) return null
    return AccountScope(chosen, accounts.filter { it.accountId in chosen }.flatMap { it.cardIds }.toSet())
}

data class ForecastEvent(val date: LocalDate, val type: FlowType, val title: String, val amount: Long, val confidence: FlowConfidence) {
    /** 'AI 생활비 반영' 선에만 넣은 추정(카드 사용 예상) — '추정' 태그 */
    val isEstimate: Boolean get() = type == FlowType.CARD_SPENDING_ESTIMATE || confidence == FlowConfidence.CONSERVATIVE

    /** 카드 청구 — 가계부 항목이 아니라 F-02에서 고칠 수 없다 */
    val isCardBill: Boolean get() = type == FlowType.CARD_BILLING

    /** 진행 중인 청구처럼 확신도가 EXPECTED인 돈(두 선 모두에 들어감) — 금액이 아직 정해지지 않아 '예상' 태그 */
    val isExpected: Boolean get() = confidence == FlowConfidence.EXPECTED

    /** 줄 이름. 카드 사용 예상은 title이 근거 문장이라 이름은 고정하고 title은 보조 줄에 그대로 둔다 */
    val label: String get() = if (type == FlowType.CARD_SPENDING_ESTIMATE) "카드 사용 예상" else title

    val basis: String? get() = title.takeIf { type == FlowType.CARD_SPENDING_ESTIMATE }
}

/** 하루 끝 잔액 — 선 하나가 없으면('AI 생활비 반영' 선 null) 그 값만 null. [confirmed]는 받기만 하고 그리지 않는다 */
data class ForecastDay(val date: LocalDate, val confirmed: Long?, val expected: Long?, val conservative: Long?, val events: List<ForecastEvent>)

data class RiskCause(val type: FlowType, val title: String, val amount: Long, val date: LocalDate?) {
    val label: String get() = when (type) {
        FlowType.CARD_SPENDING_ESTIMATE -> "카드 사용 예상"
        FlowType.LIVING_COST_ESTIMATE -> "예상 생활비"
        else -> title
    }

    /** 보조 줄 — 카드 사용 예상은 근거 문장, 생활비는 누적한 마지막 날 */
    fun sub(livingMethod: LivingMethod?): String? = when (type) {
        FlowType.CARD_SPENDING_ESTIMATE -> title
        FlowType.LIVING_COST_ESTIMATE -> listOfNotNull(date?.let { "${it.monthValue}월 ${it.dayOfMonth}일까지" }, livingMethod?.label).joinToString(" · ").ifEmpty { null }
        else -> date?.let { "${it.monthValue}월 ${it.dayOfMonth}일" }
    }
}

data class RiskWindow(
    val from: LocalDate,
    val to: LocalDate,
    val level: RiskLevel,
    val line: FlowConfidence,
    val lowestBalance: Long?,
    val shortfall: Long?,
    val causes: List<RiskCause>,
)

data class ForecastLivingCost(val projectedAmount: Long?, val method: LivingMethod, val insufficient: Boolean)

/** 계산에서 뺀 것 — 이유마다 문장 하나 */
data class ExcludedNote(val reason: String?, val text: String)

/** '두 선은 이렇게 계산했어요'의 한 줄 */
data class LineStep(val label: String, val amount: Long, val note: String? = null)

/**
 * 한 선의 설명 — [line]은 EXPECTED('AI 생활비 제외') · CONSERVATIVE('AI 생활비 반영'), 그 선에 더한 돈과 기간 끝 잔액. 생활비 선이 없으면 [end]가 null.
 * [note]는 줄을 더해도 끝 잔액이 되지 않을 때 그 까닭을 적은 한 줄이다
 */
data class LineGroup(val line: FlowConfidence, val steps: List<LineStep>, val end: Long?, val note: String? = null)

data class Forecast(
    val accountIds: List<Long>,
    val currentBalance: Long?,
    val from: LocalDate?,
    val to: LocalDate?,
    val isNextIncome: Boolean,
    val horizonDays: Int?,
    val alertThreshold: Long?,
    val status: RiskLevel,
    val hasAccountRisk: Boolean,
    val atRiskAccountCount: Int,
    val minBalance: Long?,
    val minBalanceDate: LocalDate?,
    val endBalance: Long?,
    val days: List<ForecastDay>,
    val riskWindows: List<RiskWindow>,
    val livingCost: ForecastLivingCost?,
    val excluded: List<ExcludedNote>,
    val isEstimate: Boolean,
    val calculatedAtIso: String?,
    val scope: AccountScope? = null,   // 계좌를 일부만 골랐을 때 — 응답에는 없고 저장소가 붙인다
    val beyondHorizon: List<ForecastEvent> = emptyList(),   // 기간 뒤에 빠질 카드값(출금일순, 0원 제외). 선에는 없다
) {
    /** 다음 월급일 — 기간 기준이 NEXT_INCOME일 때만(끝 날은 월급 전날). DAYS면 월급을 못 찾았거나 N일을 고른 것 */
    val incomeDate: LocalDate? get() = to?.takeIf { isNextIncome }?.plusDays(1)

    /** 'AI 생활비 반영' 선(conservative)이 있는지 — 날마다 같이 오거나 같이 빠진다 */
    val hasConservative: Boolean get() = days.any { it.conservative != null }

    /** 기간 안 입출금(0원 제외, 날짜순) */
    val upcoming: List<ForecastEvent> get() = days.flatMap { it.events }.filter { it.amount != 0L }

    /**
     * 기간 안에 실제로 빠지는 예정 출금 — 'AI 생활비 반영' 선에만 넣은 추정은 예정 출금이 아니라 뺀다(BE summary.largestOutflow와 같은 기준).
     * 합계([scheduledOutflow])와 F-02 머리의 건수가 이 목록 하나를 같이 쓴다
     */
    val scheduledOutflows: List<ForecastEvent> get() = upcoming.filter { it.amount < 0 && !it.isEstimate }

    /** 예정 출금 합(양수) */
    val scheduledOutflow: Long get() = -scheduledOutflows.sumOf { it.amount }

    /** 안전 잔액 아래로 처음 내려가는 날 */
    val riskFrom: LocalDate? get() = riskWindows.minOfOrNull { it.from }

    /**
     * 두 선마다 무엇을 더했는지 — 'AI 생활비 제외'(서버 expected) → 'AI 생활비 반영'(conservative). 금액은 서버 이벤트 합, 끝 잔액은 서버 선의 마지막 날 그대로.
     * 첫 선은 확신도 CONFIRMED · EXPECTED 돈(확인 전 지출 후보 · 진행 중 청구 포함)과 확신도를 모르는 돈('기타'). 그리지 않는 확정 선과 나누지 않으므로
     * 같은 이름(예정 지출 등)은 확신도가 달라도 한 줄로 합친다. BE는 지출만 후보로 찾아 EXPECTED 수입은 없다(날마다 confirmed ≥ expected). 둘째 선은 첫 선에서 예상 생활비와 CONSERVATIVE 돈을 더 뺀 것이다
     */
    fun lineGroups(): List<LineGroup> {
        val events = upcoming
        fun steps(of: List<ForecastEvent>, name: (ForecastEvent) -> String) = of.groupBy(name).map { (label, list) ->
            LineStep(if (list.size > 1) "$label ${list.size}건" else label, list.sumOf { it.amount })
        }.sortedBy { it.label.startsWith("기타") }   // 모르는 종류는 맨 끝
        val last = days.lastOrNull()
        val withoutLiving = steps(events.filter { it.confidence != FlowConfidence.CONSERVATIVE }) { e ->
            when {
                e.confidence == FlowConfidence.UNKNOWN -> "기타"
                e.type == FlowType.SCHEDULED_INCOME -> "예정 수입"
                e.type == FlowType.SCHEDULED_OUTFLOW -> "예정 지출"
                e.type == FlowType.CARD_BILLING && e.confidence == FlowConfidence.CONFIRMED -> "확정된 카드 청구"
                e.type == FlowType.CARD_BILLING -> "진행 중인 카드 청구"
                else -> "기타"
            }
        }
        val living = livingCost?.takeIf { !it.insufficient }?.projectedAmount?.let { LineStep("예상 생활비", -it, livingCost.method.label) }
        val withLiving = listOfNotNull(living) + steps(events.filter { it.confidence == FlowConfidence.CONSERVATIVE }) {
            if (it.type == FlowType.CARD_SPENDING_ESTIMATE) "앞으로 쓸 신용카드 금액" else "기타 추정"
        }
        return listOf(
            LineGroup(FlowConfidence.EXPECTED, withoutLiving, last?.expected),
            LineGroup(FlowConfidence.CONSERVATIVE, withLiving, last?.conservative, note = PARTIAL_LIVING_NOTE.takeIf { hasPartialLiving }),
        )
    }

    /**
     * 여러 계좌 중 일부만 생활비 기록이 있을 수 있는 경우. BE는 한 계좌라도 생활비가 없으면 통합 livingCost를 INSUFFICIENT(금액 null)로 주지만,
     * conservative는 계좌마다 계산해 기록 없는 계좌도 카드 추정이 있으면 생활비 0원으로 낸다(LivingCostProjector.total · ForecastCalculator).
     * 그래서 선에는 기록 있는 계좌의 생활비가 들어 있는데 금액은 받지 못한다 — 앱이 거꾸로 계산하지 않고 그 사실만 알린다
     */
    val hasPartialLiving: Boolean get() = livingCost?.insufficient == true && hasConservative && accountIds.size > 1
}

/** [Forecast.hasPartialLiving] — 기록 있는 계좌가 하나도 없을 때도 맞는 문장(어느 쪽인지 응답만으로는 모른다) */
internal const val PARTIAL_LIVING_NOTE = "생활비 기록이 있는 계좌가 있으면 그 계좌의 예상 생활비는 금액을 따로 받지 못해 끝 잔액에만 들어 있어요"

// ── 서버 값 → 화면 값 ──

fun BalanceForecastDto.toForecast(): Forecast {
    val days = series.mapNotNull { p ->
        val date = parseDate(p.date) ?: return@mapNotNull null
        ForecastDay(
            date = date,
            confirmed = p.confirmed,
            expected = p.expected,
            conservative = p.conservative,
            events = p.events.map { e ->
                ForecastEvent(date, enumOf(e.type, FlowType.UNKNOWN), e.title?.takeIf { it.isNotBlank() } ?: "예정된 돈", e.amount, enumOf(e.confidence, FlowConfidence.UNKNOWN))
            },
        )
    }.sortedBy { it.date }
    val living = livingCost?.let {
        ForecastLivingCost(it.projectedAmount, enumOf(it.method, LivingMethod.UNKNOWN), insufficient = it.dataOrigin.equals("INSUFFICIENT", ignoreCase = true) || it.projectedAmount == null)
    }
    return Forecast(
        accountIds = accountIds,
        currentBalance = currentTotalBalance,
        from = parseDate(horizon?.from),
        to = parseDate(horizon?.to),
        isNextIncome = horizon?.type.equals("NEXT_INCOME", ignoreCase = true),
        horizonDays = horizon?.days,
        alertThreshold = alertThreshold,
        status = enumOf(summary?.status, RiskLevel.UNKNOWN),
        hasAccountRisk = summary?.hasAccountRisk ?: false,
        atRiskAccountCount = summary?.atRiskAccountCount ?: 0,
        minBalance = summary?.minBalance,
        minBalanceDate = parseDate(summary?.minBalanceDate),
        endBalance = summary?.endBalance,
        days = days,
        riskWindows = riskWindows.mapNotNull { w ->
            val from = parseDate(w.from) ?: return@mapNotNull null
            RiskWindow(
                from = from,
                to = parseDate(w.to) ?: from,
                level = enumOf(w.level, RiskLevel.UNKNOWN),
                line = enumOf(w.line, FlowConfidence.UNKNOWN),
                lowestBalance = w.lowestBalance,
                shortfall = w.shortfall,
                causes = w.causes.map { RiskCause(enumOf(it.type, FlowType.UNKNOWN), it.title?.takeIf { t -> t.isNotBlank() } ?: "예정된 돈", it.amount, parseDate(it.date)) },
            )
        }.sortedBy { it.from },
        livingCost = living,
        excluded = excluded.map { it.toNote() },
        isEstimate = isEstimate,
        calculatedAtIso = calculatedAt,
        beyondHorizon = beyondHorizon.mapNotNull { b ->
            val date = parseDate(b.date) ?: return@mapNotNull null
            ForecastEvent(date, enumOf(b.type, FlowType.UNKNOWN), b.title?.takeIf { it.isNotBlank() } ?: "카드값", b.amount, enumOf(b.confidence, FlowConfidence.UNKNOWN))
        }.filter { it.amount != 0L }.sortedBy { it.date },
    )
}

/** 뺀 이유 → 문장(BE ExcludedItem.Reason). 모르는 이유는 '○○ 1건은 빼고 계산했어요' */
fun ExcludedItemDto.toNote(): ExcludedNote {
    val name = title?.trim()?.takeIf { it.isNotEmpty() }
    val thing = name ?: "예정된 돈"
    val text = when (reason?.uppercase()) {
        "AMOUNT_UNKNOWN" -> "금액을 정하지 않은 ${thing}${thing.topicParticle()} 빼고 계산했어요."
        "NO_WITHDRAWAL_ACCOUNT" -> "${thing}${thing.topicParticle()} 어느 계좌에서 나갈지 몰라 빼고 계산했어요."
        "CARD_UNAVAILABLE" -> "${thing}${thing.topicParticle()} 결제할 카드를 찾을 수 없어 빼고 계산했어요."
        "CARD_SUSPENDED_OR_EXPIRED" -> "${thing}${thing.topicParticle()} 카드가 정지됐거나 유효기간이 지나 빼고 계산했어요."
        "BILLING_CYCLE_UNKNOWN" -> "${thing}${thing.topicParticle()} 카드 결제일을 몰라 빼고 계산했어요."
        "TODAY_INCOME_UNVERIFIED" -> "오늘 들어올 ${thing}${thing.topicParticle()} 이미 들어왔는지 몰라 빼고 계산했어요."
        "UNPAID_BILL" -> "${amount?.let { "미납된 카드 청구 ${abs(it).toWon()}원은" } ?: "미납된 카드 청구는"}다시 빠질 날이 정해지지 않아 빼고 계산했어요."
        "CREDIT_SPENDING_UNKNOWN" -> "${name ?: "신용카드"}${(name ?: "신용카드").topicParticle()} 사용 기록이 부족해 앞으로 쓸 금액을 반영하지 못했어요."
        else -> "$thing 1건은 빼고 계산했어요."
    }
    return ExcludedNote(reason, text)
}

/** 받침이 있으면 '이', 없으면 '가' — '알뜰카드가' · '신한 삼성카드 Ⅱ가' */
internal fun String.subjectParticle(): String {
    val last = trimEnd().lastOrNull() ?: return "가"
    if (last !in '가'..'힣') return "가"
    return if ((last - '가') % 28 != 0) "이" else "가"
}

/** 받침이 있으면 '은', 없으면 '는' — '관리비는' · '월세는' */
internal fun String.topicParticle(): String {
    val last = trimEnd().lastOrNull() ?: return "는"
    if (last !in '가'..'힣') return "는"
    return if ((last - '가') % 28 != 0) "은" else "는"
}

/**
 * #20 구매 계획 = POST /cashflow-items(BE CreateCashflowItemRequest). type · date는 필수, 계좌와 카드 중 하나만 보낸다.
 * BE에 PURCHASE_PLAN · 할부 칸이 없어 일반 예정 지출로 저장된다
 */
@Serializable data class PurchasePlanRequest(
    val type: String,
    val title: String,
    val amount: Long,
    val date: String,
    val accountId: Long? = null,
    val userCardId: Long? = null,
)

@Serializable data class CashflowCreatedDto(
    val cashflowItemId: Long,
    val occurrenceDate: String? = null,
)

data class SavedPlan(
    val id: Long,
    val title: String,
    val amount: Long,
    val date: LocalDate,
    val minBefore: Long?,
    val minAfter: Long?,
    val shortfallFrom: LocalDate?,
)
