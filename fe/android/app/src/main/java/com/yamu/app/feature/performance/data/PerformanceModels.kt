package com.yamu.app.feature.performance.data

import com.yamu.app.core.format.toWon
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.IssuerDto
import com.yamu.app.feature.link.data.brandOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ── 서버 요청 · 응답 — #65 카드별 실적 · #66 실적 상세 · #18 유불리 분석 · #35 판단 기록 · #32 혜택 한도. 11 문서 7-2 ──

@Serializable data class CardPerformancesDto(
    val yearMonth: String? = null,
    val daysLeft: Int? = null,
    val cards: List<CardPerformanceItemDto> = emptyList(),
    val dataSyncedAt: String? = null,
)

@Serializable data class CardPerformanceItemDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuer: IssuerDto? = null,              // 보완안
    val issuerName: String? = null,
    val cardType: String = "CREDIT",
    val targetAmount: Long? = null,
    val targetLabel: String? = null,
    val recognizedAmount: Long = 0,
    val remainingAmount: Long = 0,
    val status: String = "ON_TRACK",            // ACHIEVED · ON_TRACK · AT_RISK · NO_TARGET · INSUFFICIENT_DATA
    // ── 보완안(11 문서 7-6 ① ②) ──
    val guide: String? = null,
    val decision: JsonElement? = null,
    val isProvisional: Boolean = false,
    val recentChange: RecentChangeDto? = null,
)

@Serializable data class RecentChangeDto(val amount: Long, val approvedAt: String? = null, val merchantName: String? = null)

@Serializable data class PerformanceDetailDto(
    val userCardId: Long,
    val yearMonth: String? = null,
    val summary: PerfSummaryDto = PerfSummaryDto(),
    val tiers: List<TierDto> = emptyList(),
    val nextMonthImpact: NextMonthImpactDto? = null,
    val dataSyncedAt: String? = null,
)

@Serializable data class PerfSummaryDto(
    val targetAmount: Long? = null,
    val recognizedAmount: Long = 0,
    val remainingAmount: Long = 0,
    val status: String = "ON_TRACK",
)

@Serializable data class TierDto(
    val threshold: Long,
    val label: String,
    val benefitSummary: String? = null,
    val benefitAmount: Long? = null,            // 보완안 ⑥
    val achieved: Boolean? = null,              // 보완안 ⑥
)

@Serializable data class NextMonthImpactDto(val message: String? = null)

@Serializable data class PerformanceAnalysisDto(
    val userCardId: Long,
    val yearMonth: String? = null,
    val asOf: String? = null,
    val targetAmount: Long? = null,
    val recognizedAmount: Long = 0,
    val remainingAmount: Long = 0,
    val projection: ProjectionDto? = null,
    val guide: String? = null,
    val headline: String? = null,
    val message: String? = null,
    val benefitsAtStake: List<BenefitAtStakeDto> = emptyList(),
    val isEstimate: Boolean = true,
    val steps: List<StepDto> = emptyList(),
    val movableItems: List<MovableItemDto> = emptyList(),
    val shortfall: Long? = null,
    val extraBenefitIfMet: Long? = null,
    val decision: JsonElement? = null,
)

@Serializable data class ProjectionDto(
    val projectedRecognizedAmount: Long? = null,
    val likelihood: String? = null,
    val shortfallIfTrend: Long? = null,
)

@Serializable data class BenefitAtStakeDto(val name: String, val typicalMonthlyValue: Long? = null)

@Serializable data class StepDto(val no: Int = 0, val label: String, val amount: Long? = null, val note: String? = null)

@Serializable data class MovableItemDto(
    val cashflowItemId: Long,
    val title: String,
    val date: String? = null,
    val amount: Long = 0,
    val eligible: Boolean = false,
    val deadline: String? = null,
    val note: String? = null,
    val reason: String? = null,
)

@Serializable data class MoveCashflowRequest(val userCardId: Long)

@Serializable data class BenefitLimitsDto(
    val yearMonth: String? = null,
    val summary: LimitSummaryDto = LimitSummaryDto(),
    val cards: List<LimitCardDto> = emptyList(),
    val dataSyncedAt: String? = null,
)

@Serializable data class LimitSummaryDto(val receivedAmount: Long = 0, val remainingAmount: Long = 0)

@Serializable data class LimitCardDto(
    val userCardId: Long,
    val nickname: String? = null,
    val issuer: IssuerDto? = null,              // 보완안 ⑦
    val remainingAmount: Long? = null,
    val limits: List<LimitDto> = emptyList(),
)

@Serializable data class LimitDto(
    val name: String,
    val remainingAmount: Long? = null,
    val status: String = "AVAILABLE",           // AVAILABLE · NEAR_END · EXHAUSTED · LOCKED
    val lockedReason: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

/**
 * 유불리 판정(PerformanceGuide) — 판정은 서버가 하고 앱은 이름 · 색만 붙인다.
 * [label]이 null인 판정은 태그를 그리지 않는다 — KEEP_NATURAL('자연 소비 유지')은 헷갈린다는 의견으로 뺐다(서버 값은 그대로 읽는다)
 */
enum class PerfGuide(val label: String?, val order: Int) {
    ACHIEVE_RECOMMENDED("실적 달성 추천", 0),
    KEEP_NATURAL(null, 1),
    EXTRA_SPENDING_NOT_RECOMMENDED("추가 소비 비추천", 2),
    INSUFFICIENT_DATA("판단 보류", 3),
    ALREADY_ACHIEVED("달성", 4);

    companion object {
        fun from(value: String?): PerfGuide? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** 사용자가 기록한 판단(#35). 앱은 KEEP을 더 보내지 않지만 이미 기록된 값은 읽어 '다시 판단하기'로 되돌릴 수 있게 둔다 */
enum class PerfDecision(val label: String) {
    FILL("채우기"),
    KEEP("평소대로 쓰기"),
    SKIP("채우지 않기");

    companion object {
        fun from(value: String?): PerfDecision? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** #18 · #65의 decision — 문자열('SKIP')이든 객체({decision: 'SKIP', …})든 읽는다 */
fun readDecision(element: JsonElement?): PerfDecision? = when (element) {
    is JsonPrimitive -> PerfDecision.from(element.contentOrNull)
    is JsonObject -> PerfDecision.from(element["decision"]?.jsonPrimitive?.contentOrNull)
    else -> null
}

data class CardPerf(
    val userCardId: Long,
    val brand: Brand,
    val name: String,
    val target: Long?,
    val targetLabel: String?,
    val recognized: Long,
    val remaining: Long,
    val achieved: Boolean,
    val noTarget: Boolean,
    val guide: PerfGuide?,
    val decision: PerfDecision?,
    val provisional: Boolean,
    val recentChange: Long?,
    val recentAtIso: String?,
    val nickname: String? = null,
    /** 서버 status AT_RISK — 지금 추세면 목표에 못 미친다 */
    val atRisk: Boolean = false,
) {
    /** 기준 전 — 판정을 보여 줄 카드 */
    val pending: Boolean get() = !achieved && !noTarget

    /** 목록 태그 — 판정이 있으면 판정(태그 없는 판정이면 없음), 없으면 AT_RISK만 '미달 예상' */
    val tagLabel: String? get() = if (guide != null) guide.label else AT_RISK_LABEL.takeIf { atRisk }
}

/**
 * 서버 status AT_RISK의 이름. ON_TRACK엔 태그를 붙이지 않는다 —
 * 서버가 그달 첫 5일은 추세를 보지 않고 ON_TRACK으로 두므로 '달성 예상'이라 말할 수 없어서
 */
const val AT_RISK_LABEL = "미달 예상"

data class PerfOverview(
    val yearMonth: String?,
    val daysLeft: Int?,
    val pending: List<CardPerf>,
    val others: List<CardPerf>,
    val reflectedAtIso: String?,
    val syncedAtIso: String?,
) {
    val all: List<CardPerf> get() = pending + others
}

/** [benefitAmount]는 실적이 이 기준일 때 한 달에 받을 수 있는 원화 혜택의 최대치(아래 구간 혜택까지 누적). 모르거나 한도 없는 혜택이 끼면 null */
data class PerfTier(val label: String, val benefit: String?, val benefitAmount: Long?, val achieved: Boolean) {
    /** 금액 칸 — 구간마다 '최대 n원'. null은 0원이 아니라 금액을 모르는 것이라 비운다 */
    val amountText: String get() = benefitAmount?.let { "최대 ${it.toWon()}원" } ?: ""
}

/**
 * 실적 화면에 보일 구간. 새 혜택이 열리는 구간(혜택 이름이 있는 구간)이 하나뿐이면 그 구간 한 줄만 남기고,
 * 한도만 커지는 위 구간 줄은 뺀다(노리2 · Mr.Life처럼 20만에 혜택이 다 열리고 30만부터는 금액만 커지는 카드).
 * 서버는 그 구간에서 새로 열린 혜택만 보내지만 같은 이름이 여러 구간에 와도 하나로 센다.
 * 남긴 줄의 '달성'은 지운 위 구간 중 하나라도 달성했으면 켠다. 혜택 이름이 둘 이상이거나 하나도 없으면 구간을 두되,
 * 혜택 이름도 금액도 없는 줄은 뺀다(IBK나라사랑 50만 · 100만처럼 한도만 커지는데 원화 월 최대치를 모르는 구간은 기준 금액만 남아 빈칸처럼 보인다)
 */
fun List<PerfTier>.visibleTiers(): List<PerfTier> {
    val names = mapNotNull { it.benefit?.takeIf(String::isNotBlank) }.distinct()
    if (names.size != 1) return filter { !it.benefit.isNullOrBlank() || it.benefitAmount != null }
    val keep = indexOfFirst { it.benefit == names.single() }
    return listOf(this[keep].copy(achieved = drop(keep).any { it.achieved }))
}

data class PerfDetail(
    val userCardId: Long,
    val month: Int?,
    val target: Long?,
    val targetLabel: String?,
    val recognized: Long,
    val remaining: Long,
    val achieved: Boolean,
    val noTarget: Boolean,
    val tiers: List<PerfTier>,
    /** 서버 nextMonthImpact.message — 목표가 없거나 실적 행이 없으면 null */
    val nextMonthMessage: String?,
    /** 서버 summary.status AT_RISK */
    val atRisk: Boolean = false,
)

data class PerfStep(val label: String, val amount: Long?, val note: String?)

data class MovableItem(val cashflowItemId: Long, val title: String, val amount: Long, val deadlineIso: String?, val note: String?)

data class PerfAnalysis(
    val userCardId: Long,
    val month: Int?,
    val guide: PerfGuide,
    val headline: String?,
    val target: Long?,
    val projected: Long?,
    val shortfall: Long?,
    val extraBenefit: Long?,
    val benefitNames: List<String>,
    val steps: List<PerfStep>,
    val movable: MovableItem?,
    val projectedIfMoved: Long? = null,         // what-if 결과(따로 부름)
    val decision: PerfDecision?,
    val isEstimate: Boolean,
    val version: String?,                       // #35 analysisVersion
)

enum class LimitTag(val label: String) { NEAR_END("임박"), EXHAUSTED("소진"), LOCKED("잠김"), UNLIMITED("한도 없음") }

data class LimitCard(val userCardId: Long, val brand: Brand, val name: String, val remaining: Long?, val tag: LimitTag?, val lockedReason: String?)

data class BenefitLimits(val month: Int?, val remaining: Long, val received: Long, val cards: List<LimitCard>)

// ── 서버 값 → 화면 값 ──

/** '2026-10' → 10 */
fun monthOf(yearMonth: String?): Int? = yearMonth?.takeLast(2)?.toIntOrNull()

/** 카드사 표시 — 카드사가 없으면 별명 첫 단어('신한 생활' → '신한') */
private fun perfBrand(issuer: IssuerDto?, issuerName: String?, nickname: String?): Brand {
    issuer?.let { return brandOf(it.name, color = it.color) }
    issuerName?.let { return brandOf(it) }
    val name = nickname ?: "카드"
    return brandOf(name, shortName = name.substringBefore(' '))
}

fun CardPerformanceItemDto.toCardPerf(): CardPerf {
    val achieved = status.equals("ACHIEVED", ignoreCase = true)
    val noTarget = status.equals("NO_TARGET", ignoreCase = true) || targetAmount == null
    val brand = perfBrand(issuer, issuerName, nickname)
    return CardPerf(
        userCardId = userCardId,
        brand = brand,
        name = issuer?.name ?: issuerName ?: nickname ?: "카드",
        target = targetAmount,
        targetLabel = targetLabel,
        recognized = recognizedAmount,
        remaining = remainingAmount,
        achieved = achieved,
        noTarget = noTarget,
        guide = PerfGuide.from(guide) ?: if (achieved) PerfGuide.ALREADY_ACHIEVED else null,
        decision = readDecision(decision),
        provisional = isProvisional,
        recentChange = recentChange?.amount?.takeIf { it != 0L },
        recentAtIso = recentChange?.approvedAt,
        nickname = nickname,
        atRisk = status.equals("AT_RISK", ignoreCase = true),
    )
}

/** 기준 전: 판정 순 → 남은 금액 적은 순. 그 밖: 달성 → 실적 조건 없음 */
fun List<CardPerf>.toOverview(yearMonth: String?, daysLeft: Int?, syncedAtIso: String?): PerfOverview {
    // 같은 카드사 카드가 두 장이면(KB국민 체크 · 적립) 카드사 이름 대신 별명
    val dupes = groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    val named = map { card -> card.nickname?.takeIf { card.name in dupes }?.let { card.copy(name = it) } ?: card }
    val (pending, others) = named.partition { it.pending }
    return PerfOverview(
        yearMonth = yearMonth,
        daysLeft = daysLeft,
        pending = pending.sortedWith(compareBy({ it.guide?.order ?: PerfGuide.INSUFFICIENT_DATA.order }, { it.remaining })),
        others = others.sortedBy { if (it.achieved) 0 else 1 },
        reflectedAtIso = named.mapNotNull { it.recentAtIso }.maxOrNull(),
        syncedAtIso = syncedAtIso,
    )
}

fun PerformanceDetailDto.toDetail(): PerfDetail {
    val noTarget = summary.status.equals("NO_TARGET", ignoreCase = true) || summary.targetAmount == null
    return PerfDetail(
        userCardId = userCardId,
        month = monthOf(yearMonth),
        target = summary.targetAmount,
        targetLabel = tiers.firstOrNull { it.threshold == summary.targetAmount }?.label,
        recognized = summary.recognizedAmount,
        remaining = summary.remainingAmount,
        achieved = summary.status.equals("ACHIEVED", ignoreCase = true),
        noTarget = noTarget,
        // 구간 달성은 서버 값(achieved)을 쓰고, 없으면 인정 실적 ≥ 기준으로 표시만
        tiers = tiers.sortedBy { it.threshold }.map { PerfTier(it.label, it.benefitSummary, it.benefitAmount, it.achieved ?: (summary.recognizedAmount >= it.threshold)) },
        nextMonthMessage = nextMonthImpact?.message?.takeIf { it.isNotBlank() },
        atRisk = summary.status.equals("AT_RISK", ignoreCase = true),
    )
}

fun PerformanceAnalysisDto.toAnalysis(): PerfAnalysis {
    val guide = PerfGuide.from(guide) ?: if (remainingAmount <= 0) PerfGuide.ALREADY_ACHIEVED else PerfGuide.INSUFFICIENT_DATA
    return PerfAnalysis(
        userCardId = userCardId,
        month = monthOf(yearMonth),
        guide = guide,
        headline = headline ?: message,
        target = targetAmount,
        projected = projection?.projectedRecognizedAmount,
        shortfall = shortfall ?: projection?.shortfallIfTrend,
        extraBenefit = extraBenefitIfMet ?: benefitsAtStake.mapNotNull { it.typicalMonthlyValue }.takeIf { it.isNotEmpty() }?.sum(),
        benefitNames = benefitsAtStake.map { it.name },
        steps = steps.sortedBy { it.no }.map { PerfStep(it.label, it.amount, it.note) },
        movable = movableItems.firstOrNull { it.eligible }?.let { MovableItem(it.cashflowItemId, it.title, it.amount, it.deadline, it.note) },
        decision = readDecision(decision),
        isEstimate = isEstimate,
        version = asOf,
    )
}

fun LimitCardDto.toLimitCard(): LimitCard {
    val statuses = limits.map { it.status.uppercase() }
    val tag = when {
        limits.isEmpty() -> LimitTag.UNLIMITED
        "NEAR_END" in statuses -> LimitTag.NEAR_END
        statuses.all { it == "LOCKED" } -> LimitTag.LOCKED
        statuses.all { it == "EXHAUSTED" || it == "LOCKED" } -> LimitTag.EXHAUSTED
        else -> null
    }
    return LimitCard(
        userCardId = userCardId,
        brand = perfBrand(issuer, null, nickname),
        name = nickname ?: issuer?.name ?: "카드",
        remaining = if (tag == LimitTag.UNLIMITED) null else remainingAmount ?: limits.mapNotNull { it.remainingAmount }.sum(),
        tag = tag,
        lockedReason = limits.firstOrNull { it.status.equals("LOCKED", ignoreCase = true) }?.lockedReason,
    )
}

fun BenefitLimitsDto.toLimits() = BenefitLimits(
    month = monthOf(yearMonth),
    remaining = summary.remainingAmount,
    received = summary.receivedAmount,
    cards = cards.map { it.toLimitCard() },
)
