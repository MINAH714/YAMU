package com.yamu.app.feature.forecast.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.BigDecimalSerializer
import com.yamu.app.feature.link.data.Brand
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate

// ── 가상 구매 시뮬레이션 — POST /api/forecasts/simulations(BE SimulationRequest · SimulationResponse, docs/be/forecast.md) ──

/**
 * 요청 본문. 앱 JSON은 기본값 · null을 보내지 않으므로(encodeDefaults · explicitNulls 끔)
 * - [paymentChannel]은 기본값을 두지 않는다. 빠지면 BE가 400(2026-11-06 필수)
 * - 일시불이면 [plan] · [installmentMonths]를 아예 뺀다. null을 보내면 BE가 '보냈다'로 보고 400 INVALID_INSTALLMENT
 * - [userCardId]를 빼면 혜택이 가장 큰 카드(추천 1순위)로 계산한다
 */
@Serializable data class SimulationRequest(
    val accountIds: List<Long>,
    val amount: Long,
    val paymentChannel: String,
    val merchantCategoryCode: String? = null,
    val merchantId: Long? = null,
    val productName: String? = null,
    val purchaseDate: String? = null,
    val userCardId: Long? = null,
    val plan: String? = null,
    val installmentMonths: Int? = null,
    val until: String? = null,
    val days: Int? = null,
)

@Serializable data class SimulationResponseDto(
    val card: SimCardDto? = null,
    val benefit: SimBenefitDto? = null,
    val schedule: List<SimScheduleDto> = emptyList(),
    val comparison: SimComparisonDto? = null,
    val series: List<SimSeriesPointDto> = emptyList(),
    val alternatives: List<SimAlternativeDto>? = null,
    val assumptions: List<SimAssumptionDto> = emptyList(),
    val missingInformation: List<SimMissingDto> = emptyList(),
    val creditLimit: SimCreditLimitDto? = null,
    val outsideSelectionImpact: SimOutsideDto? = null,
    val message: String? = null,
    val isEstimate: Boolean = true,
)

/** recommended — 사용자가 카드를 고르지 않아 추천 1순위로 계산했으면 true */
@Serializable data class SimCardDto(val userCardId: Long, val nickname: String? = null, val cardType: String? = null, val recommended: Boolean = false)

/** 혜택 합계 · 줄 금액은 소수가 올 수 있다(포인트도 원화 환산). 계산을 끝내지 못했으면 benefit 자체가 null */
@Serializable data class SimBenefitDto(
    @Serializable(with = BigDecimalSerializer::class) val netBenefit: BigDecimal? = null,
    val benefits: List<SimBenefitItemDto> = emptyList(),
    val chargedAmount: Long? = null,
)

@Serializable data class SimBenefitItemDto(
    val benefitId: Long? = null,
    val name: String? = null,
    val type: String? = null,
    val realization: String? = null,
    @Serializable(with = BigDecimalSerializer::class) val amount: BigDecimal? = null,
)

@Serializable data class SimScheduleDto(val date: String? = null, val amount: Long = 0, val label: String? = null)

/**
 * [riskStartDate] — 안전 잔액 아래로 처음 내려가는 날(BE -347 !198, 잔고예보 riskWindows[].from과 같은 규칙, SAFE면 null).
 * 통합 · 계좌별 위험 구간 중 가장 이른 날이라 앱이 날짜별 통합 잔액(series)으로 대신 구하면 늦은 날이 나오거나 빠진다(!206 리뷰).
 * 그래서 이 칸이 없는 옛 응답이면 앱은 날짜를 계산하지 않고 빼고 보인다
 */
@Serializable data class SimSnapshotDto(
    val minBalance: Long? = null,
    val minBalanceDate: String? = null,
    val endBalance: Long? = null,
    val status: String? = null,
    val riskStartDate: String? = null,
)

@Serializable data class SimComparisonDto(
    val before: SimSnapshotDto? = null,
    val after: SimSnapshotDto? = null,
    val withinPeriodImpact: Long = 0,
    val beyondPeriodImpact: Long = 0,
)

/** 가장 낮은 선(conservative 'AI 생활비 반영', 없으면 expected 'AI 생활비 제외') 기준 전후 잔액 */
@Serializable data class SimSeriesPointDto(val date: String, val before: Long? = null, val after: Long? = null)

/** 비교하지 못한 카드는 netBenefit이 null이고 이유는 note. recommended는 사용자가 다른 카드를 골랐을 때 1순위 카드 */
@Serializable data class SimAlternativeDto(
    val userCardId: Long,
    val nickname: String? = null,
    val cardType: String? = null,
    val plan: String? = null,
    val installmentMonths: Int? = null,
    @Serializable(with = BigDecimalSerializer::class) val netBenefit: BigDecimal? = null,
    val note: String? = null,
    val recommended: Boolean = false,
)

@Serializable data class SimAssumptionDto(val id: String? = null, val description: String? = null)

@Serializable data class SimMissingDto(val code: String? = null, val field: String? = null)

/** status — 지금은 늘 SKIPPED(모의 신용한도를 저장 · 조회하지 않음) */
@Serializable data class SimCreditLimitDto(val status: String? = null, val availableLimit: Long? = null)

@Serializable data class SimOutsideDto(val accountId: Long? = null, val amount: Long = 0, val note: String? = null)

/** GET /api/merchant-categories */
@Serializable data class MerchantCategoryDto(
    val merchantCategoryCode: String,
    val merchantCategoryName: String,
    // 지원 여부가 없는 구버전 응답을 일반 구매로 추정하지 않는다.
    val simulationSupported: Boolean = false,
) {
    fun toCategory() = MerchantCategory(merchantCategoryCode, merchantCategoryName, simulationSupported)
}

/** GET /api/merchants?merchantCategoryCode= */
@Serializable data class CatalogMerchantDto(val merchantId: Long, val brandCode: String? = null, val merchantName: String, val merchantCategoryCode: String? = null)

// ── 앱 안에서 쓰는 값 ──

enum class PaymentChannel(val label: String) {
    OFFLINE("매장"), ONLINE("온라인");

    companion object {
        /** 온라인으로 결제하는 업종 — BE 업종(MerchantCategories)에는 온라인 표시가 없어 앱이 정한다(S15P31E103-363, 점검 B-14) */
        private val ONLINE_CATEGORIES = setOf("ONLINE_MARKETPLACE", "DELIVERY_APP", "STREAMING_SERVICE", "MEMBERSHIP_SUBSCRIPTION")

        /** 업종을 고를 때 맞추는 결제 방식 기본값. 사용자가 그 뒤 바꾸는 것은 막지 않는다 */
        fun forCategory(categoryCode: String): PaymentChannel = if (categoryCode in ONLINE_CATEGORIES) ONLINE else OFFLINE
    }
}

data class MerchantCategory(val code: String, val name: String, val simulationSupported: Boolean = true)

data class CatalogMerchant(val id: Long, val name: String)

/** 카드 고르기 줄 — 쓸 수 없는 카드는 [unusableReason]이 있고 고를 수 없다. [brand]는 그림이 없을 때 그리는 카드사 색 카드 */
data class SimCardOption(
    val userCardId: Long,
    val name: String,
    val productName: String?,
    val isCredit: Boolean,
    val unusableReason: String?,
    val brand: Brand? = null,
)

/** 가상 구매 조건 — 결과 화면에서 카드 · 결제 방식 · 날짜를 바꾸면 이 값만 바꿔 다시 부른다. [installmentMonths]가 null이면 일시불 */
data class SimulationQuery(
    val amount: Long,
    val productName: String?,
    val categoryCode: String,
    val categoryName: String?,
    val merchantId: Long?,
    val merchantName: String?,
    val channel: PaymentChannel,
    val userCardId: Long?,
    val installmentMonths: Int?,
    val purchaseDate: LocalDate,
) {
    val isInstallment: Boolean get() = installmentMonths != null

    fun toRequest(accountIds: List<Long>, period: ForecastPeriod?) = SimulationRequest(
        accountIds = accountIds,
        amount = amount,
        paymentChannel = channel.name,
        merchantCategoryCode = categoryCode,
        merchantId = merchantId,
        productName = productName?.takeIf { it.isNotBlank() },
        purchaseDate = purchaseDate.toString(),
        userCardId = userCardId,
        plan = if (installmentMonths != null) "INSTALLMENT" else null,
        installmentMonths = installmentMonths,
        until = period?.until,
        days = period?.days,
    )

    companion object {
        /** 가격 상한 1,000억 원 — BE SimulationRequest와 같고, 예정 수입·지출 상한과도 같다 */
        const val AMOUNT_MAX = 100_000_000_000L

        /** 할부 — BE PaymentTerms: 신용카드 · 50,000원 이상 · 2~12개월 */
        const val INSTALLMENT_MIN_AMOUNT = 50_000L
        val INSTALLMENT_MONTHS = 2..12
    }
}

data class SimCard(val userCardId: Long, val name: String, val isCredit: Boolean, val recommendedTop: Boolean)

data class BenefitLine(val name: String, val amount: BigDecimal?)

data class SimBenefit(val net: BigDecimal?, val lines: List<BenefitLine>, val charged: Long?)

data class SimSnapshot(val minBalance: Long?, val minBalanceDate: LocalDate?, val status: RiskLevel, val riskStartDate: LocalDate? = null)

data class SimWithdrawal(val date: LocalDate?, val amount: Long, val label: String, val beyondPeriod: Boolean)

data class SimDay(val date: LocalDate, val before: Long?, val after: Long?)

data class AltCard(val userCardId: Long, val name: String, val detail: String?, val net: BigDecimal?, val note: String?, val recommended: Boolean)

data class Simulation(
    val query: SimulationQuery,
    val card: SimCard,
    val benefit: SimBenefit?,
    val missing: List<String>,
    val schedule: List<SimWithdrawal>,
    val before: SimSnapshot?,
    val after: SimSnapshot?,
    val beyondPeriodImpact: Long,
    val days: List<SimDay>,
    val alternatives: List<AltCard>,
    val assumptions: List<String>,
    val creditLimitSkipped: Boolean,
    val outsideNote: String?,
    val message: String?,
    val isEstimate: Boolean,
    /** 계산한 계좌 — 카드를 알면 그 카드의 출금 계좌 하나(저장소가 채운다, S15P31E103-548) */
    val accounts: List<ForecastAccountOption> = emptyList(),
)

fun SimulationResponseDto.toSimulation(query: SimulationQuery): Simulation {
    val lastDay = series.mapNotNull { parseDate(it.date) }.maxOrNull()
    fun snapshot(s: SimSnapshotDto?) = s?.let { SimSnapshot(it.minBalance, parseDate(it.minBalanceDate), enumOf(it.status, RiskLevel.UNKNOWN), parseDate(it.riskStartDate)) }
    return Simulation(
        query = query,
        card = SimCard(
            userCardId = card?.userCardId ?: query.userCardId ?: 0,
            name = card?.nickname?.takeIf { it.isNotBlank() } ?: "고른 카드",
            isCredit = card?.cardType.equals("CREDIT", ignoreCase = true),
            recommendedTop = card?.recommended ?: false,
        ),
        benefit = benefit?.let { b ->
            SimBenefit(b.netBenefit, b.benefits.map { BenefitLine(it.name?.takeIf { n -> n.isNotBlank() } ?: "혜택", it.amount) }, b.chargedAmount)
        },
        missing = missingInformation.mapNotNull { missingText(it.code, benefitComputed = benefit != null) }.distinct(),
        schedule = schedule.map {
            val date = parseDate(it.date)
            SimWithdrawal(date, it.amount, it.label?.takeIf { l -> l.isNotBlank() } ?: "출금", beyondPeriod = date != null && lastDay != null && date.isAfter(lastDay))
        },
        before = snapshot(comparison?.before),
        after = snapshot(comparison?.after),
        beyondPeriodImpact = comparison?.beyondPeriodImpact ?: 0,
        days = series.mapNotNull { p -> parseDate(p.date)?.let { SimDay(it, p.before, p.after) } }.sortedBy { it.date },
        alternatives = alternatives.orEmpty().map { a ->
            AltCard(
                userCardId = a.userCardId,
                name = a.nickname?.takeIf { it.isNotBlank() } ?: "다른 카드",
                detail = listOfNotNull(
                    when (a.cardType?.uppercase()) { "CREDIT" -> "신용"; "CHECK" -> "체크"; else -> null },
                    if (a.plan.equals("INSTALLMENT", ignoreCase = true)) a.installmentMonths?.let { "할부 ${it}개월" } ?: "할부" else "일시불",
                ).joinToString(" · "),
                net = a.netBenefit,
                note = a.note?.takeIf { it.isNotBlank() },
                recommended = a.recommended,
            )
        },
        assumptions = assumptions.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } }.distinct(),
        creditLimitSkipped = creditLimit?.status.equals("SKIPPED", ignoreCase = true),
        outsideNote = outsideSelectionImpact?.note?.takeIf { it.isNotBlank() },
        message = message?.takeIf { it.isNotBlank() },
        isEstimate = isEstimate,
    )
}

/**
 * 혜택을 끝까지 계산하지 못한 이유(missingInformation.code) → 한 줄. 모르는 코드는 일반 문장인데,
 * 혜택을 계산했으면(일부를 뺀 것) '빼고 계산했어요', 못 했으면(benefit null) 위 '혜택은 계산하지 못했어요'의 이유로 읽히게 쓴다
 */
internal fun missingText(code: String?, benefitComputed: Boolean): String? = when (code?.uppercase()) {
    null -> null
    "MERCHANT_CATEGORY_MISSING" -> "업종을 고르지 않아 혜택은 계산하지 않았어요."
    "BRAND_MISSING" -> "브랜드를 고르면 브랜드 혜택까지 계산할 수 있어요."
    "INSUFFICIENT_BALANCE" -> "오늘 잔액이 부족한 체크카드라 혜택을 판정하지 못했어요. 잔고 비교는 그대로 볼 수 있어요."
    "INSTANT_DISCOUNT_TIMING_UNKNOWN" -> "할인이 언제 적용되는지 몰라 혜택을 계산하지 못했어요."
    // 고른 카드인데 추천 엔진이 DB를 읽다 실패 — 잠깐의 읽기 실패라 다시 시도하면 된다(BE EngineSimulationRecommender, !176 리뷰)
    "RECOMMENDATION_READ_FAILED" -> "혜택 정보를 읽지 못했어요. 잠시 뒤 다시 시도해 주세요."
    // 고른 카드가 추천 엔진의 계산 대상에 없었다
    "CARD_NOT_EVALUATED" -> "이 카드는 이번 계산에서 혜택을 비교하지 못했어요."
    else -> if (benefitComputed) "혜택 조건 중 확인하지 못한 것이 있어 일부 혜택은 빼고 계산했어요." else "확인하지 못한 혜택 조건이 있어요."
}

/** 오류 화면에서 고르는 다음 행동 */
enum class SimAction(val label: String?) {
    PICK_CATEGORY("업종 고르기"),
    PICK_CARD("카드 직접 고르기"),
    OTHER_CARD("다른 카드 고르기"),
    PICK_DATE("날짜 다시 고르기"),
    LUMP_SUM("일시불로 보기"),
    RETRY("다시 시도"),
    BACK("금액 다시 넣기"),
}

/** 계산하지 못한 이유 — 제목 · 설명 · 다음 행동. [toRecommended]면 '추천 카드로' 버튼을 같이 둔다 */
data class SimProblem(val title: String, val body: String?, val action: SimAction, val toRecommended: Boolean = false)

/**
 * 시뮬레이션 실패 → 화면 문구(docs/be/forecast.md 「가상 구매 시뮬레이션 API」).
 * 422 DATA_INSUFFICIENT는 details.reason, 400 CARD_NOT_ELIGIBLE도 details.reason으로 나눈다
 */
fun ApiError.toSimProblem(): SimProblem {
    val reason = detailString("reason")?.uppercase()
    val fields = fieldErrors()
    return when {
        this is ApiError.DataInsufficient -> when (reason) {
            "CATEGORY_REQUIRED" -> SimProblem("어디서 살지 골라 주세요", "업종을 고르면 혜택이 가장 큰 카드를 찾아 드려요", SimAction.PICK_CATEGORY)
            "NO_ELIGIBLE_CARD" -> SimProblem("지금 이 구매에 쓸 수 있는 카드가 없어요", "카드를 직접 고르면 잔고가 어떻게 바뀌는지는 볼 수 있어요", SimAction.PICK_CARD)
            "BENEFIT_DATA_INSUFFICIENT" -> SimProblem("카드 혜택 정보가 부족해 추천할 수 없어요", "카드를 직접 고르면 잔고 비교는 볼 수 있어요", SimAction.PICK_CARD)
            "PAYMENT_AVAILABILITY_UNKNOWN" -> SimProblem("카드 결제 가능 여부를 확인하지 못했어요", "잔액 · 한도 확인이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요", SimAction.RETRY)
            "RECOMMENDATION_UNAVAILABLE" -> SimProblem("추천 카드를 지금 계산할 수 없어요", "카드를 직접 고르면 볼 수 있어요", SimAction.PICK_CARD)
            "CARD_ACCOUNT_UNKNOWN" -> SimProblem("이 카드의 출금 계좌를 알 수 없어요", "다른 카드를 골라 주세요", SimAction.OTHER_CARD, toRecommended = true)
            "BILLING_CYCLE_UNKNOWN" -> SimProblem("카드 결제일을 몰라 출금일을 계산할 수 없어요", "다른 카드를 골라 주세요", SimAction.OTHER_CARD, toRecommended = true)
            else -> SimProblem("지금은 계산할 수 없어요", "데이터가 더 모이면 알려 드려요", SimAction.RETRY)
        }
        code == "CARD_NOT_ELIGIBLE" -> SimProblem(
            when (reason) {
                "CARD_INACTIVE" -> "꺼 둔 카드예요"
                "CARD_SUSPENDED" -> "카드사에서 정지한 카드예요"
                "CARD_EXPIRED" -> "유효기간이 지난 카드예요"
                "UNPAID_BILL" -> "미납 청구서가 있어 결제할 수 없는 카드예요"
                else -> "이 카드로는 결제할 수 없어요"
            },
            "다른 카드를 고르거나 혜택이 가장 큰 카드로 볼 수 있어요",
            SimAction.OTHER_CARD,
            toRecommended = true,
        )
        code == "INVALID_INSTALLMENT" -> SimProblem("할부는 신용카드로 5만 원 이상, 2~12개월만 돼요", "일시불로 보거나 다른 카드를 골라 주세요", SimAction.LUMP_SUM)
        code == "MERCHANT_OR_CATEGORY_REQUIRED" -> SimProblem("어디서 살지 골라 주세요", "업종을 고르면 혜택이 가장 큰 카드를 찾아 드려요", SimAction.PICK_CATEGORY)
        code == "MERCHANT_NOT_FOUND" -> SimProblem("고른 브랜드를 찾을 수 없어요", "업종과 브랜드를 다시 골라 주세요", SimAction.PICK_CATEGORY)
        code == "INVALID_AMOUNT" -> SimProblem("금액을 다시 확인해 주세요", "1원부터 1,000억 원까지 볼 수 있어요", SimAction.BACK)
        "purchaseDate" in fields -> SimProblem("구매일은 오늘 이후로 골라 주세요", "날짜가 지났어요", SimAction.PICK_DATE)
        this is ApiError.NotFound -> SimProblem("고른 카드를 찾을 수 없어요", "카드 목록을 다시 불러와 골라 주세요", SimAction.OTHER_CARD, toRecommended = true)
        this is ApiError.BankUnavailable -> SimProblem("은행 연결이 늦어요", "잠시 뒤 다시 시도해 주세요", SimAction.RETRY)
        this == ApiError.Network -> SimProblem("인터넷 연결을 확인해 주세요", null, SimAction.RETRY)
        else -> SimProblem("지금은 계산할 수 없어요", "잠시 뒤 다시 시도해 주세요", SimAction.RETRY)
    }
}

/**
 * 계획으로 저장할 수 있는지 — 일시불만(가계부에 할부 칸이 없어 전액이 첫 청구일 하루에 잡힌다, 2026-11-08 결정).
 * 전액 즉시할인이라 빠지는 돈이 없으면 저장할 것이 없다
 */
val Simulation.canSavePlan: Boolean get() = !query.isInstallment && (benefit?.charged ?: query.amount) > 0

/** I-02 [계획 저장] → #20 본문. 청구 금액(즉시할인 뒤)이 있으면 그 금액, 카드는 계산한 카드 */
fun Simulation.toPlanRequest() = PurchasePlanRequest(
    type = "EXPENSE",
    title = query.productName?.takeIf { it.isNotBlank() }?.take(30) ?: "구매 계획",
    amount = benefit?.charged ?: query.amount,
    date = query.purchaseDate.toString(),
    userCardId = card.userCardId,
)
