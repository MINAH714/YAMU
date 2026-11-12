package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.feature.forecast.data.BalanceForecastDto
import com.yamu.app.feature.forecast.data.BeyondHorizonItemDto
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CatalogMerchant
import com.yamu.app.feature.forecast.data.MerchantCategory
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.SimAlternativeDto
import com.yamu.app.feature.forecast.data.SimAssumptionDto
import com.yamu.app.feature.forecast.data.SimBenefitDto
import com.yamu.app.feature.forecast.data.SimBenefitItemDto
import com.yamu.app.feature.forecast.data.SimCardDto
import com.yamu.app.feature.forecast.data.SimCardOption
import com.yamu.app.feature.forecast.data.SimComparisonDto
import com.yamu.app.feature.forecast.data.SimCreditLimitDto
import com.yamu.app.feature.forecast.data.SimScheduleDto
import com.yamu.app.feature.forecast.data.SimSeriesPointDto
import com.yamu.app.feature.forecast.data.SimSnapshotDto
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.SimulationQuery
import com.yamu.app.feature.forecast.data.SimulationResponseDto
import com.yamu.app.feature.forecast.data.toPlanRequest
import com.yamu.app.feature.forecast.data.toSimulation
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.CashflowUpdateRequest
import com.yamu.app.feature.forecast.data.ExcludedItemDto
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastEventDto
import com.yamu.app.feature.forecast.data.ForecastLivingCostDto
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.ForecastSummaryDto
import com.yamu.app.feature.forecast.data.HorizonDto
import com.yamu.app.feature.forecast.data.RiskCauseDto
import com.yamu.app.feature.forecast.data.RiskWindowDto
import com.yamu.app.feature.forecast.data.SavedPlan
import com.yamu.app.feature.forecast.data.SeriesPointDto
import com.yamu.app.feature.forecast.data.AccountLivingCostDto
import com.yamu.app.feature.forecast.data.LivingCostDetail
import com.yamu.app.feature.forecast.data.LivingCostDetailDto
import com.yamu.app.feature.forecast.data.LivingExclusionDto
import com.yamu.app.feature.forecast.data.LivingPeriodDto
import com.yamu.app.feature.forecast.data.LivingSourceDto
import com.yamu.app.feature.forecast.data.MatchCandidateDto
import com.yamu.app.feature.forecast.data.MatchCandidates
import com.yamu.app.feature.forecast.data.MatchCandidatesDto
import com.yamu.app.feature.forecast.data.MatchConfirmed
import com.yamu.app.feature.forecast.data.MatchConfirmedDto
import com.yamu.app.feature.forecast.data.MatchViewDto
import com.yamu.app.feature.forecast.data.PaceCheckDto
import com.yamu.app.feature.forecast.data.PaymentMatch
import com.yamu.app.feature.forecast.data.accountIdsFrom
import com.yamu.app.feature.forecast.data.isMatchable
import com.yamu.app.feature.forecast.data.toCandidates
import com.yamu.app.feature.forecast.data.toConfirmed
import com.yamu.app.feature.forecast.data.toDetail
import com.yamu.app.feature.forecast.data.toMatch
import com.yamu.app.feature.forecast.data.scopeFrom
import com.yamu.app.feature.forecast.data.toForecast
import kotlinx.coroutines.delay
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * debug 전용 가짜 잔고예보 서버 — BE BalanceForecast와 같은 모양을 만들어 앱의 매퍼를 그대로 거친다.
 * 계좌 둘(월급 통장 412,000 · 생활비 통장 268,000), 하루 생활비 6,000(계좌마다 3,000), +4 카드 청구(진행 중, EXPECTED) · 카드 사용 예상(CONSERVATIVE),
 * +2 미용실(생활카드 예정 지출) · +9 월세 · +11 통신비 · +16 구독료(CONFIRMED), +25 월급.
 * 그래서 'AI 생활비 반영' 선(conservative)만 월급 전에 안전 잔액(100,000) 아래로 내려가 WARNING이다. 위험 판정은 BE -356 !223의 두 선 기준을 따른다.
 * 지난 7일에는 생활카드 예정 지출 셋(헬스장 · 병원 · 택시)이 있고, 병원은 결제와 연결됨 · 택시는 연결 뒤 결제가 취소된 상태로 시작한다.
 * 디버그 메뉴 '데이터 부족'이면 생활비 기록이 없는 응답('AI 생활비 반영' 선 null · INSUFFICIENT)을 준다.
 * 계획을 저장하면 같이 계산 → 잔고예보 숫자가 바뀐다(12 문서 8-5).
 */
@Singleton
class FakeForecastRepository @Inject constructor() : ForecastRepository {

    private class Plan(val id: Long, val title: String, val amount: Long, val payDate: LocalDate)

    /** 가짜 계좌 — [dailyLiving]이 null이면 생활비 기록이 없는 계좌 */
    private data class Account(val id: Long, val name: String, val bank: String, val last4: String, val balance: Long, val primary: Boolean, val dailyLiving: Long?)

    /** 미래 입출금 하나(BE FlowEvent) */
    private data class Flow(val accountId: Long, val date: LocalDate, val amount: Long, val type: String, val confidence: String, val title: String)

    /** 가계부 규칙(가짜 서버) — 처음 3개는 프로토타입 고정 지출, F-02a로 등록하면 더한다. 수정 · 삭제는 서버 규칙(docs/be/cashflow.md)을 흉내 */
    private data class Item(
        val id: Long, val isIncome: Boolean, val title: String, val amount: Long,
        val date: LocalDate, val repeat: CashflowRepeat, val dayOfMonth: Int?, val endDate: LocalDate?,
        val accountId: Long?, val userCardId: Long?,
    )

    /** 이번 회차만 고친 값 — (규칙, 원래 회차)마다 */
    private data class Override(val title: String?, val amount: Long?, val date: LocalDate?)

    private val accountList = listOf(
        Account(1, "월급 통장", "신한은행", "4021", 412_000, primary = true, dailyLiving = 3_000),
        Account(2, "생활비 통장", "KB국민은행", "7730", 268_000, primary = false, dailyLiving = 3_000),
    )
    private val plans = mutableListOf<Plan>()
    private val saved = mutableMapOf<Long, SavedPlan>()
    private var nextId = 901L
    @Volatile private var last: Forecast? = null
    @Volatile private var current = ForecastSelection()

    private val alert = 100_000L
    private val today get() = LocalDate.now(KST)
    private val income get() = today.plusDays(25)

    private fun monthly(id: Long, title: String, amount: Long, date: LocalDate) =
        Item(id, isIncome = false, title, amount, date, CashflowRepeat.MONTHLY, date.dayOfMonth, null, accountId = 1, userCardId = null)

    /** 생활카드(11, 생활비 통장 출금)로 낼 한 번짜리 예정 지출 — 결제와 연결할 수 있는 회차 */
    private fun card(id: Long, title: String, amount: Long, date: LocalDate) =
        Item(id, isIncome = false, title, amount, date, CashflowRepeat.NONE, null, null, accountId = null, userCardId = 11)

    private val items = mutableListOf(
        monthly(7001, "월세", 250_000, today.plusDays(9)),
        monthly(7002, "통신비", 35_000, today.plusDays(11)),
        monthly(7003, "구독료 (OTT·음악)", 15_000, today.plusDays(16)),
        card(7010, "헬스장", 55_000, today.minusDays(3)),
        card(7011, "병원", 18_000, today.minusDays(6)),
        card(7013, "택시", 14_000, today.minusDays(4)),
        card(7012, "미용실", 25_000, today.plusDays(2)),
    )
    private val overrides = mutableMapOf<Pair<Long, LocalDate>, Override>()
    private val excluded = mutableSetOf<Pair<Long, LocalDate>>()

    /** 규칙의 원래 회차 — 가짜 서버가 계산한다(앱은 계산하지 않는다). 매월은 그달에 기준일이 없으면 말일, 종료일 포함 */
    private fun occurrences(item: Item, until: LocalDate): List<LocalDate> {
        val limit = listOfNotNull(until, item.endDate).min()
        val dates = mutableListOf<LocalDate>()
        var n = 0L
        while (true) {
            val d = when (item.repeat) {
                CashflowRepeat.NONE -> if (n == 0L) item.date else break
                CashflowRepeat.WEEKLY -> item.date.plusWeeks(n)
                CashflowRepeat.MONTHLY -> YearMonth.from(item.date).plusMonths(n).let { ym -> ym.atDay(minOf(item.dayOfMonth ?: item.date.dayOfMonth, ym.lengthOfMonth())) }
            }
            if (d.isAfter(limit)) break
            dates += d
            n++
        }
        return dates
    }

    /** 목록 — 계산한 회차에 이번 회차 수정 · 삭제를 덮는다(옮긴 회차는 옮긴 날짜로 기간을 가린다) */
    private fun entriesBetween(from: LocalDate, to: LocalDate): List<CashflowEntry> = items.flatMap { item ->
        occurrences(item, to.plusMonths(1)).mapNotNull { occurrence ->
            if ((item.id to occurrence) in excluded) return@mapNotNull null
            val o = overrides[item.id to occurrence]
            val date = o?.date ?: occurrence
            if (date.isBefore(from) || date.isAfter(to)) return@mapNotNull null
            CashflowEntry(
                item.id, item.isIncome, o?.title ?: item.title, o?.amount ?: item.amount, occurrence, date, item.repeat,
                item.dayOfMonth.takeIf { item.repeat == CashflowRepeat.MONTHLY }, item.endDate, item.accountId, item.userCardId,
            )
        }
    }.sortedWith(compareBy({ it.date }, { it.itemId }))

    private fun LocalDate.md() = "$monthValue/$dayOfMonth"

    /** 잔고예보 설정은 NEXT_INCOME(월급 +25) — 고르지 않으면 월급 전날까지, N일이면 오늘 + N */
    private fun horizonTo(period: ForecastPeriod?): LocalDate = period?.days?.let { today.plusDays(it.toLong()) } ?: income.minusDays(1)

    /** 기간 안 모든 입출금 — 가계부 회차는 계좌가 없으면(카드) 생활비 통장에서 나간다고 본다 */
    private fun flows(to: LocalDate): List<Flow> {
        val bill = Flow(2, today.plusDays(4), -150_000, "CARD_BILLING", "EXPECTED", "신한카드 청구")
        val estimate = Flow(2, today.plusDays(4), -36_000, "CARD_SPENDING_ESTIMATE", "CONSERVATIVE", "생활카드 · ${today.plusDays(1).md()}~${today.plusDays(3).md()} 3일 × 일평균 12,000원")
        val fromItems = entriesBetween(today, to).filterNot(::paidOff).map { Flow(it.accountId ?: 2, it.date, it.signedAmount, if (it.isIncome) "SCHEDULED_INCOME" else "SCHEDULED_OUTFLOW", "CONFIRMED", it.title) }
        val salary = listOf(Flow(1, income, 2_800_000, "SCHEDULED_INCOME", "CONFIRMED", "월급"))
        val planFlows = plans.map { Flow(2, it.payDate, -it.amount, "SCHEDULED_OUTFLOW", "CONFIRMED", it.title) }
        return listOf(bill, estimate) + fromItems + salary + planFlows
    }

    /** 계좌마다 출금 카드 — 알뜰체크는 월급 통장, 생활 · 여행 · 예전카드는 생활비 통장(가상 구매 가짜 카드와 같은 번호) */
    private fun options(): List<ForecastAccountOption> = accountList.map {
        ForecastAccountOption(it.id, it.name, it.bank, it.last4, it.balance, it.primary, cardIds = if (it.id == 1L) setOf(16L) else setOf(11L, 12L, 17L))
    }

    /** 날마다 세 선(confirmed는 응답 호환용으로만) · 위험 구간 · 요약 — BE ForecastCalculator · RiskDetector 규칙을 줄여 흉내 낸다 */
    private fun build(selection: ForecastSelection, extra: List<Flow> = emptyList(), noLiving: Boolean = false): BalanceForecastDto {
        val ids = selection.accountIdsFrom(options()).toSet()
        val accounts = accountList.filter { it.id in ids }
        val to = horizonTo(selection.period)
        val flows = (flows(to) + extra).filter { it.accountId in ids && !it.date.isBefore(today) && !it.date.isAfter(to) }
        val living = accounts.map { if (noLiving) null else it.dailyLiving }
        // 생활비 기록이 없는 계좌도 카드 사용 추정이 있으면 생활비 0원으로 conservative를 낸다. 한 계좌라도 없으면 통합 conservative는 null
        val consAvailable = accounts.zip(living).all { (a, l) -> l != null || flows.any { it.accountId == a.id && it.confidence == "CONSERVATIVE" } }
        val daily = living.sumOf { it ?: 0L }
        val start = accounts.sumOf { it.balance }
        val dates = generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        var confirmed = start
        var expected = start
        var estimates = 0L
        val series = dates.mapIndexed { i, d ->
            val todays = flows.filter { it.date == d }
            todays.forEach { f ->
                when (f.confidence) {
                    "CONFIRMED" -> { confirmed += f.amount; expected += f.amount }
                    "EXPECTED" -> expected += f.amount
                    else -> estimates += f.amount
                }
            }
            SeriesPointDto(
                date = d.toString(),
                confirmed = confirmed,
                expected = expected,
                conservative = if (consAvailable) expected - daily * i + estimates else null,
                events = todays.map { ForecastEventDto(it.type, it.title, it.amount, it.confidence) },
            )
        }
        val worst = series.map { it.conservative ?: it.expected!! }
        // 날마다 기준 미만인 선(앱이 그리는 두 선만, BE -356 !223) → 같은 선이 이어지면 한 구간.
        // expected('AI 생활비 제외')가 미만이면 DANGER, conservative('AI 생활비 반영')만 미만이면 WARNING. confirmed는 보지 않는다
        fun lineOf(p: SeriesPointDto) = when {
            p.expected!! < alert -> "EXPECTED"
            p.conservative != null && p.conservative < alert -> "CONSERVATIVE"
            else -> null
        }
        val windows = mutableListOf<RiskWindowDto>()
        var i = 0
        while (i < series.size) {
            val line = lineOf(series[i]) ?: run { i++; null } ?: continue
            var j = i
            while (j + 1 < series.size && lineOf(series[j + 1]) == line) j++
            val part = series.subList(i, j + 1)
            fun value(p: SeriesPointDto) = if (line == "EXPECTED") p.expected!! else p.conservative!!
            val lowest = part.minOf { value(it) }
            val lowestDate = part.first { value(it) == lowest }.date
            val allowed = if (line == "EXPECTED") setOf("CONFIRMED", "EXPECTED") else setOf("CONFIRMED", "EXPECTED", "CONSERVATIVE")
            val causes = flows.filter { f -> f.amount < 0 && f.confidence in allowed && !f.date.isBefore(dates[i]) && !f.date.isAfter(dates[j]) }
                .map { RiskCauseDto(it.type, it.title, it.amount, it.date.toString()) } +
                if (line == "CONSERVATIVE" && daily > 0) listOf(RiskCauseDto("LIVING_COST_ESTIMATE", "예상 생활비", -daily * dates.indexOf(LocalDate.parse(lowestDate)), lowestDate)) else emptyList()
            windows += RiskWindowDto(dates[i].toString(), dates[j].toString(), if (line == "EXPECTED") "DANGER" else "WARNING", line, lowest, alert - lowest, causes)
            i = j + 1
        }
        val min = worst.minOrNull() ?: start
        val status = when {
            windows.any { it.level == "DANGER" } -> "DANGER"
            windows.isNotEmpty() -> "WARNING"
            else -> "SAFE"
        }
        val days = dates.size - 1
        val excludedItems = listOfNotNull(
            ExcludedItemDto(date = today.plusDays(14).toString(), title = "여행카드", reason = "CREDIT_SPENDING_UNKNOWN").takeIf { 2L in ids && !today.plusDays(14).isAfter(to) },
            ExcludedItemDto(cashflowItemId = 7009, date = today.plusDays(20).toString(), title = "관리비", reason = "AMOUNT_UNKNOWN").takeIf { 1L in ids && !today.plusDays(20).isAfter(to) },
        )
        return BalanceForecastDto(
            accountIds = accounts.map { it.id },
            currentTotalBalance = start,
            horizon = HorizonDto(if (selection.period?.days == null) "NEXT_INCOME" else "DAYS", today.toString(), to.toString(), days),
            alertThreshold = alert,
            summary = ForecastSummaryDto(status, status, min, series[worst.indexOf(min)].date, worst.last()),
            series = series,
            riskWindows = windows,
            livingCost = ForecastLivingCostDto(
                projectedAmount = if (living.all { it != null }) daily * days else null,
                appliedAmount = if (consAvailable) daily * days else null,   // conservative 마지막 날까지 뺀 생활비(BE -395)
                dailyAverage = if (living.all { it != null }) daily else null,
                method = "ML_V1",
                dataOrigin = if (living.all { it != null }) "REAL" else "INSUFFICIENT",
            ),
            // 기간 뒤 카드값(BE -348) — 생활비 통장(2)에서 빠지는 것만, 출금일순
            beyondHorizon = if (2L !in ids) emptyList() else listOf(
                BeyondHorizonItemDto(to.plusDays(5).toString(), "CARD_BILLING", "생활카드 ${to.plusDays(5).monthValue}월 청구", -320_000, "EXPECTED"),
                BeyondHorizonItemDto(to.plusDays(5).toString(), "CARD_SPENDING_ESTIMATE", "생활카드 · 이용기간 마감까지 10일 × 일평균 12,000원", -120_000, "CONSERVATIVE"),
            ),
            excluded = excludedItems,
            isEstimate = true,
            calculatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toLocalDateTime().toString(),
        )
    }

    override suspend fun forecast(): ApiResult<Forecast> {
        // '데이터 부족'은 실패가 아니라 생활비 기록이 없는 응답 — 잔고예보는 이때도 실패하지 않는다(docs/be/forecast.md 「에러」)
        if (DevSession.faultScenario == FaultScenario.DATA_INSUFFICIENT) {
            delay(300)
            return ApiResult.Success(build(current, noLiving = true).toForecast().copy(scope = current.scopeFrom(options())).also { last = it })
        }
        return withFault(empty = { ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT)) }) {
            delay(500)
            ApiResult.Success(build(current).toForecast().copy(scope = current.scopeFrom(options())).also { last = it })
        }
    }

    override fun cached(): Forecast? = last

    override fun selection(): ForecastSelection = current

    override fun select(selection: ForecastSelection) {
        current = selection
    }

    override suspend fun accounts(): ApiResult<List<ForecastAccountOption>> = withFault(empty = { ApiResult.Success(emptyList()) }) {
        delay(300)
        ApiResult.Success(options())
    }

    // ── I-01b · I-02 가상 구매 — BE PurchaseSimulationService를 줄여 흉내 낸다 ──
    // 시험값(상품명): CATEGORY → 422 업종 필요(카드 미선택) · NOCARD → 422 쓸 수 있는 카드 없음(카드 미선택) ·
    // UNPAID → 400 미납 카드(카드 선택) · BANK → 502 금융망. 체크카드 할부 · 5만 원 미만 할부는 400 INVALID_INSTALLMENT

    /** 가짜 카드 — 혜택은 업종 · 채널마다 비율 하나(원화 환산, 소수가 나올 수 있다) */
    private data class FakeCard(val id: Long, val name: String, val credit: Boolean, val paymentDay: Int?, val inactive: Boolean = false, val rate: (String, PaymentChannel) -> Pair<String, BigDecimal>)

    private val fakeCards = listOf(
        FakeCard(11, "생활카드", credit = true, paymentDay = 20) { cat, _ ->
            if (cat in setOf("HYPERMARKET", "SUPERMARKET", "CONVENIENCE_STORE")) "생활 업종 1% 청구할인" to BigDecimal("0.01") else "기본 0.5% 포인트" to BigDecimal("0.005")
        },
        FakeCard(12, "여행카드", credit = true, paymentDay = 14) { _, ch ->
            if (ch == PaymentChannel.ONLINE) "온라인 결제 5% 할인" to BigDecimal("0.05") else "기본 0.3% 포인트" to BigDecimal("0.003")
        },
        FakeCard(16, "알뜰체크", credit = false, paymentDay = null) { cat, _ ->
            if (cat == "COFFEE_SHOP") "카페 10% 캐시백" to BigDecimal("0.1") else "기본 0.2% 캐시백" to BigDecimal("0.002")
        },
        FakeCard(17, "예전카드", credit = true, paymentDay = 25, inactive = true) { _, _ -> "없음" to BigDecimal.ZERO },
    )

    private val fakeCategories = listOf(
        "HOSPITAL" to "병원", "DENTAL_CLINIC" to "치과", "KOREAN_MEDICINE_CLINIC" to "한의원", "VETERINARY_CLINIC" to "동물병원",
        "HYPERMARKET" to "대형마트", "SUPERMARKET" to "슈퍼마켓", "HEALTH_BEAUTY_STORE" to "헬스·뷰티 매장", "ACADEMY" to "학원",
        "BAKERY" to "베이커리·제과점", "BOOKSTORE" to "서점", "BUFFET_RESTAURANT" to "뷔페", "CAR_REPAIR" to "자동차 정비",
        "CHINESE_RESTAURANT" to "중식", "CITY_BUS" to "시내버스", "COFFEE_SHOP" to "카페", "CONVENIENCE_STORE" to "편의점",
        "DELIVERY_APP" to "배달앱", "FAST_FOOD" to "패스트푸드", "GAS_STATION" to "주유소", "GENERAL_RESTAURANT" to "일반음식점",
        "HAIR_SALON" to "미용실", "JAPANESE_RESTAURANT" to "일식", "KOREAN_RESTAURANT" to "한식", "LAUNDRY" to "세탁소",
        "MEMBERSHIP_SUBSCRIPTION" to "유료 멤버십", "MOVIE_THEATER" to "영화관", "ONLINE_MARKETPLACE" to "온라인 쇼핑", "OTHER" to "기타",
        "PHARMACY" to "약국", "STREAMING_SERVICE" to "스트리밍", "SUBWAY" to "지하철", "TAXI" to "택시", "TELECOMMUNICATION" to "통신",
        "WESTERN_RESTAURANT" to "양식",
    ).map { (code, name) -> MerchantCategory(code, name) }.sortedBy { it.name }

    private val fakeMerchants = mapOf(
        "HYPERMARKET" to listOf(CatalogMerchant(201, "이마트"), CatalogMerchant(202, "홈플러스"), CatalogMerchant(203, "롯데마트")),
        "COFFEE_SHOP" to listOf(CatalogMerchant(211, "스타벅스"), CatalogMerchant(212, "투썸플레이스")),
        "ONLINE_MARKETPLACE" to listOf(CatalogMerchant(221, "쿠팡"), CatalogMerchant(222, "11번가")),
    )

    override suspend fun categories(): ApiResult<List<MerchantCategory>> = withFault {
        delay(300)
        ApiResult.Success(fakeCategories)
    }

    override suspend fun merchants(categoryCode: String): ApiResult<List<CatalogMerchant>> = withFault {
        delay(300)
        ApiResult.Success(fakeMerchants[categoryCode].orEmpty())
    }

    override suspend fun simulationCards(): ApiResult<List<SimCardOption>> = withFault {
        delay(300)
        ApiResult.Success(fakeCards.map { SimCardOption(it.id, it.name, it.name, it.credit, if (it.inactive) "꺼 둔 카드" else null) })
    }

    /** 신용카드 청구 — 결제일 기준 단순화: 사용일이 그달 5일 이하면 그달 결제일, 아니면 다음 달 결제일. 할부는 다음 달들로 */
    private fun withdrawals(card: FakeCard, date: LocalDate, amount: Long, months: Int?): List<SimScheduleDto> {
        if (!card.credit) return listOf(SimScheduleDto(date.toString(), -amount, "체크카드 즉시 출금"))
        val first = YearMonth.from(date).plusMonths(if (date.dayOfMonth <= 5) 0 else 1)
        val n = months ?: 1
        val each = amount / n
        return (0 until n).map { i ->
            val ym = first.plusMonths(i.toLong())
            val part = if (i == 0) amount - each * (n - 1) else each
            val label = if (months == null) "일시불 (${ym.monthValue}월 청구)" else "${i + 1}/${n}회차 (${ym.monthValue}월 청구)"
            SimScheduleDto(ym.atDay(card.paymentDay ?: 25).toString(), -part, label)
        }
    }

    override suspend fun simulate(query: SimulationQuery): ApiResult<Simulation> = withFault {
        delay(700)
        val name = query.productName.orEmpty().uppercase()
        val chosen = query.userCardId?.let { id -> fakeCards.firstOrNull { it.id == id } ?: return@withFault ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND")) }
        fun reason(status: Int, code: String, reason: String) = ApiResult.Failure(ApiError.from(status, code, buildJsonObject { put("reason", reason) }))
        when {
            name == "BANK" -> return@withFault ApiResult.Failure(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
            name == "CATEGORY" && chosen == null -> return@withFault reason(422, "DATA_INSUFFICIENT", "CATEGORY_REQUIRED")
            name == "NOCARD" && chosen == null -> return@withFault reason(422, "DATA_INSUFFICIENT", "NO_ELIGIBLE_CARD")
            name == "UNPAID" && chosen != null -> return@withFault reason(400, "CARD_NOT_ELIGIBLE", "UNPAID_BILL")
            chosen?.inactive == true -> return@withFault reason(400, "CARD_NOT_ELIGIBLE", "CARD_INACTIVE")
            query.installmentMonths != null && (query.amount < SimulationQuery.INSTALLMENT_MIN_AMOUNT || chosen?.credit == false) ->
                return@withFault ApiResult.Failure(ApiError.from(400, "INVALID_INSTALLMENT"))
            query.purchaseDate.isBefore(today) -> return@withFault invalid("purchaseDate", "구매일은 오늘 이후로 골라 주세요.")
        }
        val usable = fakeCards.filter { !it.inactive && (query.installmentMonths == null || it.credit) }
        fun net(card: FakeCard) = card.rate(query.categoryCode, query.channel).let { (label, rate) -> label to (BigDecimal(query.amount) * rate).stripTrailingZeros() }
        val top = usable.maxByOrNull { net(it).second } ?: return@withFault reason(422, "DATA_INSUFFICIENT", "NO_ELIGIBLE_CARD")
        val card = chosen ?: top
        val (benefitName, benefitAmount) = net(card)
        val schedule = withdrawals(card, query.purchaseDate, query.amount, query.installmentMonths)
        // 가짜는 가상 구매 출금을 저장한 계획과 같은 생활비 통장(2)에서 뺀다
        val purchaseAccount = 2L
        val purchaseFlows = schedule.map { Flow(purchaseAccount, LocalDate.parse(it.date), it.amount, "SIMULATED_PURCHASE", "CONFIRMED", it.label ?: "가상 구매") }
        val before = build(current)
        val after = build(current, purchaseFlows)
        val to = LocalDate.parse(before.horizon!!.to)
        // riskStartDate — 위험 구간 중 가장 이른 시작일(BE -347 !198). 가짜는 통합 선 구간만 본다
        fun snapshot(dto: BalanceForecastDto) = SimSnapshotDto(dto.summary?.minBalance, dto.summary?.minBalanceDate, dto.summary?.endBalance, dto.summary?.status, dto.riskWindows.mapNotNull { it.from }.minOrNull())
        val beyond = schedule.filter { LocalDate.parse(it.date).isAfter(to) }.sumOf { it.amount }
        val afterMin = after.summary?.minBalance ?: 0
        val afterDate = after.summary?.minBalanceDate?.let(LocalDate::parse)
        // 체크카드 구매일(기간 안) 출금 계좌의 구매 후 잔액(그 계좌의 가장 나쁜 선)이 0원 아래면 BE처럼 안내를 덧붙인다(-347).
        // 통합 합계로 보면 다른 계좌 잔액이 출금 계좌의 마이너스를 가린다(!206 리뷰). 출금 계좌를 고르지 않았으면 그날 잔액을 몰라 붙이지 않는다
        val checkOverdrawn = !card.credit && !query.purchaseDate.isAfter(to) && purchaseAccount in current.accountIdsFrom(options()) &&
            build(current.copy(accountIds = setOf(purchaseAccount)), purchaseFlows).series
                .firstOrNull { it.date == query.purchaseDate.toString() }?.let { (it.conservative ?: it.expected ?: 0) < 0 } == true
        val message = buildString {
            val d = afterDate?.let { "${it.monthValue}월 ${it.dayOfMonth}일" } ?: "기간 중"
            append(if (afterMin < 0) "$d 잔액이 ${"%,d".format(-afterMin)}원 모자라요." else "$d 잔액이 ${"%,d".format(afterMin)}원까지 내려가요.")
            if (beyond < 0) append(" 예보 기간 밖에서 ${"%,d".format(-beyond)}원이 더 빠져요.")
            if (checkOverdrawn) append(" 체크카드는 구매일 잔액보다 큰 금액은 결제되지 않을 수 있어요.")
        }
        val alternatives = fakeCards.filter { !it.inactive && it.id != card.id }.map { alt ->
            val comparable = query.installmentMonths == null || alt.credit
            SimAlternativeDto(
                userCardId = alt.id, nickname = alt.name, cardType = if (alt.credit) "CREDIT" else "CHECK",
                plan = if (query.installmentMonths != null && alt.credit) "INSTALLMENT" else "LUMP_SUM",
                installmentMonths = query.installmentMonths.takeIf { alt.credit },
                netBenefit = if (comparable) net(alt).second else null,
                note = if (comparable) null else "체크카드는 할부를 쓸 수 없어요",
                recommended = chosen != null && alt.id == top.id,
            )
        }.sortedByDescending { it.netBenefit ?: BigDecimal(-1) }
        val dto = SimulationResponseDto(
            card = SimCardDto(card.id, card.name, if (card.credit) "CREDIT" else "CHECK", recommended = chosen == null),
            benefit = SimBenefitDto(benefitAmount, listOf(SimBenefitItemDto(name = benefitName, type = "DISCOUNT", amount = benefitAmount)), query.amount),
            schedule = schedule,
            comparison = SimComparisonDto(snapshot(before), snapshot(after), schedule.filter { !LocalDate.parse(it.date).isAfter(to) }.sumOf { it.amount }, beyond),
            series = before.series.zip(after.series).map { (b, a) -> SimSeriesPointDto(b.date, b.conservative ?: b.expected, a.conservative ?: a.expected) },
            alternatives = alternatives,
            assumptions = listOfNotNull(
                SimAssumptionDto("YAMU_PURCHASE_TIME_NOON", "구매일 낮 12시에 결제한다고 보고 계산했어요."),
                SimAssumptionDto("YAMU_BRAND_NOT_SELECTED", "브랜드를 고르지 않아 브랜드 혜택은 빼고 계산했어요.").takeIf { query.merchantId == null },
            ),
            creditLimit = SimCreditLimitDto("SKIPPED", null).takeIf { card.credit },
            message = message,
        )
        ApiResult.Success(dto.toSimulation(query))
    }

    /** 같은 Idempotency-Key는 처음 만든 번호(BE -358) — 등록 · 계획 저장이 같이 쓴다 */
    private val createdByKey = mutableMapOf<String, Long>()

    override suspend fun savePlan(simulation: Simulation, idempotencyKey: String): ApiResult<SavedPlan> {
        delay(600)
        val body = simulation.toPlanRequest()
        createdByKey[idempotencyKey]?.let { id -> saved[id]?.let { return ApiResult.Success(it) } }
        val id = nextId++
        createdByKey[idempotencyKey] = id
        // 가계부의 카드 예정 지출은 청구 출금일에 빠진다 — 일시불만 저장하므로 첫 회차 날짜
        plans += Plan(id, body.title, body.amount, simulation.schedule.firstOrNull()?.date ?: simulation.query.purchaseDate)
        val plan = SavedPlan(id, body.title, body.amount, simulation.query.purchaseDate, simulation.before?.minBalance, simulation.after?.minBalance, null)
        saved[id] = plan
        return ApiResult.Success(plan)
    }

    override fun savedPlan(planId: Long): SavedPlan? = saved[planId]

    override suspend fun deletePlan(planId: Long): ApiResult<Unit> {
        delay(400)
        plans.removeAll { it.id == planId }
        saved.remove(planId)
        return ApiResult.Success(Unit)
    }

    /**
     * F-02a 등록 — 시험값(모의 POS처럼 영문): 이름 TIMEOUT은 처음 한 번 응답 못 받음(결과 불명, 실제로는 등록됨) · INVALID는 서버가 이름 칸을 거절(fieldErrors).
     * 말일 반복은 시작일이 그달 말일이 아니면 서버처럼 거절한다. 같은 키로 다시 보내면 처음 만든 번호를 돌려준다
     */
    override suspend fun createItem(form: CashflowForm, idempotencyKey: String): ApiResult<Long> {
        delay(700)
        val body = form.toRequest()
        createdByKey[idempotencyKey]?.let { return ApiResult.Success(it) }
        if (body.title == "INVALID") return invalid("title", "서버가 거절한 이름이에요(시험값)")
        val day = body.recurrence?.dayOfMonth
        if (day != null && day != form.date.dayOfMonth && form.date != YearMonth.from(form.date).atEndOfMonth()) {
            return invalid("recurrence.dayOfMonth", "매월 반복일은 첫 발생일의 날짜와 같아야 합니다(말일 시작이면 더 큰 날도 됩니다).")
        }
        val id = nextId++
        createdByKey[idempotencyKey] = id
        items += Item(id, form.isIncome, body.title, body.amount, form.date, form.repeat, day, form.effectiveEndDate, body.accountId, body.userCardId)
        if (body.title == "TIMEOUT") return ApiResult.Failure(ApiError.Network)
        return ApiResult.Success(id)
    }

    private fun invalid(field: String, reason: String) = ApiResult.Failure(
        ApiError.InvalidRequest("INVALID_REQUEST", buildJsonObject { putJsonArray("fieldErrors") { addJsonObject { put("field", field); put("reason", reason) } } }),
    )

    private var lastEntries: List<CashflowEntry> = emptyList()

    override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> = withFault {
        delay(400)
        ApiResult.Success(entriesBetween(from, to).also { lastEntries = it })
    }

    override fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry? =
        (lastEntries + lastPast).firstOrNull { it.itemId == itemId && it.occurrenceDate == occurrenceDate }

    /**
     * 수정 — 이번 회차만은 그 회차만 덮고, 이후 전부는 적용일부터 새 규칙(첫 회차면 제자리, 아니면 앞 규칙을 전날로 닫는다).
     * 반복을 안 보내면 주기 · 종료일을 이어받고, 날짜만 옮긴 매월은 새 날짜가 기준일. 시험값: 이름 PAID는 결제된 회차(409)
     */
    override suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit> {
        delay(600)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val item = items[index]
        if (body.title == "PAID") return ApiResult.Failure(ApiError.Conflict("PAID_OCCURRENCE_LOCKED"))
        val occurrence = body.occurrenceDate?.let(LocalDate::parse)
        if (occurrence != null && (itemId to occurrence) in matches) return ApiResult.Failure(ApiError.Conflict("PAID_OCCURRENCE_LOCKED"))
        val newDate = body.date?.let(LocalDate::parse)
        if (newDate != null && newDate.isBefore(today)) return invalid("date", "오늘 이후 날짜만 고를 수 있어요")
        if (body.scope == "THIS_ONLY" && item.repeat != CashflowRepeat.NONE) {
            if (body.recurrence != null) return invalid("recurrence", "이번 회차만 고칠 때는 반복을 바꿀 수 없어요")
            val key = itemId to (occurrence ?: return invalid("occurrenceDate", "고칠 회차가 필요해요"))
            val prev = overrides[key]
            overrides[key] = Override(body.title ?: prev?.title, body.amount ?: prev?.amount, newDate ?: prev?.date)
            return ApiResult.Success(Unit)
        }
        val applied = occurrence ?: item.date
        val start = newDate ?: applied
        val r = body.recurrence
        val repeat = when (r?.frequency) {
            null -> item.repeat
            "WEEKLY" -> CashflowRepeat.WEEKLY
            "MONTHLY" -> CashflowRepeat.MONTHLY
            else -> CashflowRepeat.NONE
        }
        val next = item.copy(
            title = body.title ?: item.title,
            amount = body.amount ?: item.amount,
            date = start,
            repeat = repeat,
            dayOfMonth = when {
                r != null -> r.dayOfMonth
                newDate != null && repeat == CashflowRepeat.MONTHLY -> newDate.dayOfMonth
                else -> item.dayOfMonth
            },
            endDate = if (r != null) r.endDate?.let(LocalDate::parse) else item.endDate,
            accountId = body.accountId ?: if (body.userCardId != null) null else item.accountId,
            userCardId = body.userCardId ?: if (body.accountId != null) null else item.userCardId,
        )
        val cutoff = minOf(applied, start)
        overrides.keys.removeAll { it.first == itemId && !it.second.isBefore(cutoff) }   // 따로 고친 미래 회차는 새 규칙이 덮는다
        excluded.removeAll { it.first == itemId && !it.second.isBefore(cutoff) }
        if (!cutoff.isAfter(item.date)) {
            items[index] = next                                                           // 제자리 수정
        } else {
            items[index] = item.copy(endDate = cutoff.minusDays(1))                       // 앞 규칙은 전날로 닫고
            items += next.copy(id = nextId++)                                             // 새 규칙(응답 ID가 바뀜)
        }
        return ApiResult.Success(Unit)
    }

    /** 삭제 — 이번 회차만은 그 회차를 빼고, 전체는 오늘 이후를 없앤다(오늘 이후 시작이면 행째, 시작했으면 어제로 닫음) */
    override suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit> {
        delay(500)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val item = items[index]
        if (occurrenceDate != null && (itemId to occurrenceDate) in matches) return ApiResult.Failure(ApiError.Conflict("PAID_OCCURRENCE_LOCKED"))
        if (scope == CashflowDeleteScope.THIS_ONLY && item.repeat != CashflowRepeat.NONE) {
            excluded += itemId to (occurrenceDate ?: return invalid("occurrenceDate", "지울 회차가 필요해요"))
            return ApiResult.Success(Unit)
        }
        if (!item.date.isBefore(today)) items.removeAt(index) else items[index] = item.copy(endDate = today.minusDays(1))
        return ApiResult.Success(Unit)
    }

    // 확인 전 후보(Task 8.2.73) — 가짜 예보에는 후보 줄이 없어 비워 둔다. 후보 시트는 실서버(realApi=all)에서 본다
    override suspend fun cashflowCandidates(): ApiResult<List<com.yamu.app.feature.link.data.CashflowCandidate>> = withFault { ApiResult.Success(emptyList()) }

    override suspend fun decideCandidate(itemId: Long, confirm: Boolean): ApiResult<Unit> = notFound()

    // ── 결제 연결 — BE PaymentMatchService를 줄여 흉내 낸다(앞뒤 7일 · 3일 안이면 예정일 근처 · 최대 10건) ──

    /** 내 YAMU 승인 결제 — [daysAgo]일 전 낮 12시 30분(KST), 응답의 승인 시각은 BE처럼 UTC. [canceled]면 연결 뒤 취소된 결제 */
    private data class FakePayment(val id: Long, val merchant: String, val amount: Long, val approvedAmount: Long, val daysAgo: Long, val cardId: Long, val canceled: Boolean = false)

    private val payments = listOf(
        FakePayment(5101, "애플짐 역삼점", 55_000, 55_000, 3, 11),
        FakePayment(5102, "GS25 역삼점", 4_800, 4_800, 2, 16),
        FakePayment(5103, "스타벅스 강남R점", 6_500, 6_500, 1, 11),
        FakePayment(5104, "준오헤어 강남점", 25_000, 22_500, 1, 12),   // 즉시할인 — 결제 금액이 예정과 같아 같은 금액으로 본다
        FakePayment(5105, "연세내과의원", 18_000, 18_000, 6, 11),
        FakePayment(5106, "카카오T", 14_000, 14_000, 4, 11, canceled = true),
        FakePayment(5107, "쿠팡", 32_000, 32_000, 9, 11),
    )

    private data class FakeMatch(val id: Long, val paymentId: Long)

    /** (규칙, 원래 회차) → 연결. 병원은 연결됨, 택시는 연결 뒤 결제 취소로 시작 */
    private val matches = mutableMapOf(
        (7011L to today.minusDays(6)) to FakeMatch(801, 5105),
        (7013L to today.minusDays(4)) to FakeMatch(802, 5106),
    )
    private var nextMatchId = 803L

    private var lastPast: List<CashflowEntry> = emptyList()

    private fun FakePayment.date(): LocalDate = today.minusDays(daysAgo)
    private fun FakePayment.approvedAtUtc() = "${date()}T03:30:00Z"   // BE UtcLocalDateTimeSerializer처럼 Z를 붙인다
    private fun FakePayment.view(m: FakeMatch) = MatchViewDto(
        m.id, id, merchant, amount, approvedAmount,
        paymentStatus = if (canceled) "CANCELED" else "APPROVED",
        matchStatus = if (canceled) "REVIEW_REQUIRED" else "ACTIVE",
        matchedAt = "${date()}T04:00:00Z",
    )

    /** 연결돼 실제 결제로 센 회차 — 잔고예보의 예정 지출에서 뺀다(결제가 취소됐으면 다시 센다) */
    private fun paidOff(e: CashflowEntry): Boolean =
        matches[e.itemId to e.occurrenceDate]?.let { m -> payments.firstOrNull { it.id == m.paymentId }?.canceled == false } == true

    private fun entryOf(itemId: Long, occurrence: LocalDate): CashflowEntry? =
        entriesBetween(occurrence.minusMonths(2), occurrence.plusMonths(2)).firstOrNull { it.itemId == itemId && it.occurrenceDate == occurrence }

    private fun daysApart(e: CashflowEntry, p: FakePayment) = ChronoUnit.DAYS.between(e.date, p.date())
    private fun sameAmount(e: CashflowEntry, p: FakePayment) = e.amount == p.amount || e.amount == p.approvedAmount
    private fun score(e: CashflowEntry, p: FakePayment) = (if (sameAmount(e, p)) 3 else 0) + (if (e.userCardId == p.cardId) 2 else 0)
    private fun reasons(e: CashflowEntry, p: FakePayment) = listOfNotNull(
        "AMOUNT_MATCH".takeIf { sameAmount(e, p) },
        "CARD_MATCH".takeIf { e.userCardId == p.cardId },
        "DATE_NEAR".takeIf { abs(daysApart(e, p)) <= 3 },
    )
    private fun warnings(e: CashflowEntry, p: FakePayment) = listOfNotNull(
        "AMOUNT_MISMATCH".takeUnless { sameAmount(e, p) },
        "CARD_MISMATCH".takeUnless { e.userCardId == p.cardId },
        "DATE_FAR".takeIf { abs(daysApart(e, p)) > 7 },
    )

    private fun notFound() = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))

    override suspend fun pastCardEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> = withFault {
        delay(300)
        ApiResult.Success(entriesBetween(from, to).filter { it.isMatchable }.also { lastPast = it })
    }

    override suspend fun matchStatus(itemId: Long, occurrenceDate: LocalDate): ApiResult<PaymentMatch?> = withFault {
        delay(300)
        val m = matches[itemId to occurrenceDate]
        ApiResult.Success(m?.let { payments.firstOrNull { p -> p.id == it.paymentId }?.view(it)?.toMatch() })
    }

    override suspend fun matchCandidates(itemId: Long, occurrenceDate: LocalDate): ApiResult<MatchCandidates> = withFault {
        delay(500)
        val e = entryOf(itemId, occurrenceDate) ?: return@withFault notFound()
        if (!e.isMatchable) return@withFault invalid("cashflowItemId", "카드를 지정한 예정 지출만 결제와 연결할 수 있어요")
        val taken = matches.values.map { it.paymentId }.toSet()
        val list = payments.filter { it.id !in taken && !it.canceled && abs(daysApart(e, it)) <= 7 }
            .sortedWith(compareByDescending<FakePayment> { score(e, it) }.thenBy { abs(daysApart(e, it)) })
            .take(10)
        val now = matches[itemId to occurrenceDate]?.let { m -> payments.firstOrNull { it.id == m.paymentId }?.view(m) }
        val dto = MatchCandidatesDto(
            itemId, occurrenceDate.toString(), e.date.toString(), e.amount, now,
            list.map { p ->
                MatchCandidateDto(
                    p.id, p.merchant, p.amount, p.approvedAmount, p.approvedAtUtc(), p.cardId,
                    cardType = if (fakeCards.firstOrNull { it.id == p.cardId }?.credit == false) "CHECK" else "CREDIT",
                    matchReasons = reasons(e, p), warnings = warnings(e, p),
                )
            },
        )
        ApiResult.Success(dto.toCandidates())
    }

    /** 확정 — 같은 짝이면 그대로 200(멱등), 회차나 결제가 다른 상대와 이미 연결이면 409 */
    override suspend fun confirmMatch(itemId: Long, occurrenceDate: LocalDate, paymentId: Long): ApiResult<MatchConfirmed> {
        delay(600)
        val e = entryOf(itemId, occurrenceDate) ?: return notFound()
        val p = payments.firstOrNull { it.id == paymentId } ?: return notFound()
        if (!e.isMatchable || p.canceled) return invalid("paymentId", "승인된 카드 결제만 연결할 수 있어요")
        val key = itemId to occurrenceDate
        val existing = matches[key]
        val m = when {
            existing?.paymentId == paymentId -> existing
            existing != null || matches.values.any { it.paymentId == paymentId } -> return ApiResult.Failure(ApiError.Conflict("CASHFLOW_MATCH_CONFLICT"))
            else -> FakeMatch(nextMatchId++, paymentId).also { matches[key] = it }
        }
        return ApiResult.Success(MatchConfirmedDto(m.id, itemId, occurrenceDate.toString(), paymentId, "ACTIVE", p.approvedAtUtc(), warnings(e, p)).toConfirmed())
    }

    override suspend fun deleteMatch(matchId: Long): ApiResult<Unit> {
        delay(400)
        val key = matches.entries.firstOrNull { it.value.id == matchId }?.key ?: return notFound()
        matches.remove(key)
        return ApiResult.Success(Unit)
    }

    // ── F-01b 생활비 예측 — 잔고예보와 같은 계좌 · 기간. '데이터 부족'이면 BE처럼 422 ──

    override suspend fun livingCost(): ApiResult<LivingCostDetail> = withFault(empty = { ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT)) }) {
        delay(500)
        val ids = current.accountIdsFrom(options()).toSet()
        val accounts = accountList.filter { it.id in ids }
        val to = horizonTo(current.period)
        val days = ChronoUnit.DAYS.between(today, to).toInt()
        val daily = accounts.sumOf { it.dailyLiving ?: 0L }
        val month = YearMonth.from(today)
        val dto = LivingCostDetailDto(
            accountIds = accounts.map { it.id },
            projectedAmount = daily * days,
            dailyAverage = daily,
            monthlyAverage = daily * 30,
            method = "ML_V1",
            dataOrigin = "REAL",
            confidence = "HIGH",
            period = LivingPeriodDto(today.toString(), to.toString(), days, if (current.period?.days == null) "NEXT_INCOME" else "DAYS"),
            sources = listOf(3L to 93L, 2L to 108L, 1L to 99L).map { (n, pct) -> LivingSourceDto(month.minusMonths(n).toString(), daily * 30 * pct / 100) },
            excluded = listOf(
                LivingExclusionDto("RECURRING", "매달 같은 가맹점 · 같은 금액으로 나간 정기 결제는 뺐어요"),
                LivingExclusionDto("CREDIT_PAYMENT", "신용카드 결제는 청구 출금으로 따로 세요"),
                LivingExclusionDto("UNCLASSIFIED", "분류하지 못한 결제는 뺐어요"),
                LivingExclusionDto("LARGE", "할부 · 100만 원 넘는 결제 · 평소보다 10배 넘게 큰 결제는 뺐어요"),
            ),
            dailyTarget = 5_000,
            paceCheck = PaceCheckDto(thisMonthDaily = daily * 12 / 10, averageDaily = daily, overRatio = BigDecimal("0.2"), flagged = false),
            accountLivingCosts = accounts.map { a ->
                AccountLivingCostDto(a.id, a.dailyLiving, a.dailyLiving?.times(30), a.dailyLiving?.times(days), "ML_V1".takeIf { a.dailyLiving != null })
            },
        )
        ApiResult.Success(dto.toDetail(accountList.associate { it.id to it.name }))
    }
}
