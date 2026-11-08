package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.feature.forecast.data.BalanceForecastDto
import com.yamu.app.feature.forecast.data.BuyCheck
import com.yamu.app.feature.forecast.data.BuyMethod
import com.yamu.app.feature.forecast.data.BuyOption
import com.yamu.app.feature.forecast.data.BuyVerdict
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
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
import com.yamu.app.feature.forecast.data.accountIdsFrom
import com.yamu.app.feature.forecast.data.toForecast
import kotlinx.coroutines.delay
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 잔고예보 서버 — BE BalanceForecast와 같은 모양을 만들어 앱의 매퍼를 그대로 거친다.
 * 계좌 둘(월급 통장 412,000 · 생활비 통장 268,000), 하루 생활비 6,000(계좌마다 3,000), +4 카드 청구(진행 중, 예상 선) · 카드 사용 예상(보수적 선만),
 * +9 월세 · +11 통신비 · +16 구독료(확정 선), +25 월급. 그래서 보수적 선만 월급 전에 안전 잔액(100,000) 아래로 내려간다.
 * 디버그 메뉴 '데이터 부족'이면 생활비 기록이 없는 응답(보수적 선 null · INSUFFICIENT)을 준다.
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

    private val items = mutableListOf(
        monthly(7001, "월세", 250_000, today.plusDays(9)),
        monthly(7002, "통신비", 35_000, today.plusDays(11)),
        monthly(7003, "구독료 (OTT·음악)", 15_000, today.plusDays(16)),
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
        val fromItems = entriesBetween(today, to).map { Flow(it.accountId ?: 2, it.date, it.signedAmount, if (it.isIncome) "SCHEDULED_INCOME" else "SCHEDULED_OUTFLOW", "CONFIRMED", it.title) }
        val salary = listOf(Flow(1, income, 2_800_000, "SCHEDULED_INCOME", "CONFIRMED", "월급"))
        val planFlows = plans.map { Flow(2, it.payDate, -it.amount, "SCHEDULED_OUTFLOW", "CONFIRMED", it.title) }
        return listOf(bill, estimate) + fromItems + salary + planFlows
    }

    private fun options(): List<ForecastAccountOption> = accountList.map { ForecastAccountOption(it.id, it.name, it.bank, it.last4, it.balance, it.primary) }

    /** 날마다 세 선 · 위험 구간 · 요약 — BE ForecastCalculator · RiskDetector 규칙을 줄여 흉내 낸다 */
    private fun build(selection: ForecastSelection, extra: List<Flow> = emptyList(), noLiving: Boolean = false): BalanceForecastDto {
        val ids = selection.accountIdsFrom(options()).toSet()
        val accounts = accountList.filter { it.id in ids }
        val to = horizonTo(selection.period)
        val flows = (flows(to) + extra).filter { it.accountId in ids && !it.date.isBefore(today) && !it.date.isAfter(to) }
        val living = accounts.map { if (noLiving) null else it.dailyLiving }
        // 생활비 기록이 없는 계좌도 카드 사용 추정이 있으면 생활비 0원으로 보수적 선을 낸다. 한 계좌라도 없으면 통합 보수적 선은 null
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
        // 날마다 기준 미만인 가장 확실한 선 → 같은 선이 이어지면 한 구간
        fun lineOf(p: SeriesPointDto) = when {
            p.confirmed!! < alert -> "CONFIRMED"
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
            fun value(p: SeriesPointDto) = when (line) {
                "CONFIRMED" -> p.confirmed!!
                "EXPECTED" -> p.expected!!
                else -> p.conservative!!
            }
            val lowest = part.minOf { value(it) }
            val lowestDate = part.first { value(it) == lowest }.date
            val allowed = when (line) {
                "CONFIRMED" -> setOf("CONFIRMED")
                "EXPECTED" -> setOf("CONFIRMED", "EXPECTED")
                else -> setOf("CONFIRMED", "EXPECTED", "CONSERVATIVE")
            }
            val causes = flows.filter { f -> f.amount < 0 && f.confidence in allowed && !f.date.isBefore(dates[i]) && !f.date.isAfter(dates[j]) }
                .map { RiskCauseDto(it.type, it.title, it.amount, it.date.toString()) } +
                if (line == "CONSERVATIVE" && daily > 0) listOf(RiskCauseDto("LIVING_COST_ESTIMATE", "예상 생활비", -daily * dates.indexOf(LocalDate.parse(lowestDate)), lowestDate)) else emptyList()
            windows += RiskWindowDto(dates[i].toString(), dates[j].toString(), if (line == "CONFIRMED") "DANGER" else "WARNING", line, lowest, alert - lowest, causes)
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
                dailyAverage = if (living.all { it != null }) daily else null,
                method = "ML_V1",
                dataOrigin = if (living.all { it != null }) "REAL" else "INSUFFICIENT",
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
            return ApiResult.Success(build(current, noLiving = true).toForecast().also { last = it })
        }
        return withFault(empty = { ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT)) }) {
            delay(500)
            ApiResult.Success(build(current).toForecast().also { last = it })
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

    /** 가장 나쁜 선의 날짜별 잔액 — [extra]는 아직 저장하지 않은 '사 보면' 출금(확정 선) */
    private fun worst(extra: List<Pair<LocalDate, Long>> = emptyList()): List<Pair<LocalDate, Long>> =
        build(current, extra.map { (d, a) -> Flow(2, d, -a, "SIMULATED_PURCHASE", "CONFIRMED", "가상 구매") }).series
            .map { LocalDate.parse(it.date) to (it.conservative ?: it.expected ?: 0) }

    private fun min(points: List<Pair<LocalDate, Long>>) = points.minOf { it.second }
    private fun shortfall(points: List<Pair<LocalDate, Long>>) = points.firstOrNull { it.second < 0 }?.first

    /** 신용카드 청구일 — 월급 전에 사면 월급 4일 뒤, 그 뒤면 다음 달 14일 */
    private fun billDate(d: LocalDate) = if (d.isBefore(income)) income.plusDays(4) else YearMonth.from(d).plusMonths(1).atDay(14)

    // ── #33 판단(I-02 · I-03) — S15P31E103-326에서 가상 구매 시뮬레이션으로 바꾼다 ──

    override suspend fun check(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck> = withFault {
        delay(800)
        if (amount <= 0) return@withFault ApiResult.Failure(ApiError.InvalidRequest("INVALID_AMOUNT"))
        val withCheck = worst(listOf(purchaseDate to amount))
        val creditBill = billDate(purchaseDate)
        val withCredit = worst(listOf(creditBill to amount))
        val monthly = (amount + 2) / 3
        val withInstallment = worst(listOf(creditBill to monthly))
        val minCheck = min(withCheck)
        val verdict = when {
            minCheck >= alert -> BuyVerdict.OK
            min(withCredit) >= 0 -> BuyVerdict.CAUTION
            else -> BuyVerdict.HOLD
        }
        fun option(method: BuyMethod, date: LocalDate, card: Long, name: String, months: Int?, points: List<Pair<LocalDate, Long>>, monthlyAmount: Long?) =
            BuyOption(method, date, card, name, months, min(points), monthlyAmount, shortfall(points), recommended = false)
        val options = listOf(
            option(BuyMethod.CHECK_LUMP_SUM, purchaseDate, 16, "KB국민 노리2 체크카드(KB Pay)", null, withCheck, null),
            option(BuyMethod.CREDIT_LUMP_SUM, purchaseDate, 11, "KB국민 청춘대로 톡톡카드", null, withCredit, null),
            option(BuyMethod.CREDIT_INSTALLMENT, purchaseDate, 11, "KB국민 청춘대로 톡톡카드", 3, withInstallment, monthly),
        ) + if (verdict == BuyVerdict.OK) emptyList() else listOf(BuyOption(BuyMethod.DELAY, income, 16, "KB국민 노리2 체크카드(KB Pay)", null, min(worst()), null, null, recommended = true))
        val headline = when (verdict) {
            BuyVerdict.OK -> "지금 사도 월급 전에 ${minCheck / 10_000}만 원 남아요"
            else -> "지금 사면 월급 전에 ${(-minCheck + 9_999) / 10_000}만 원 모자라요"
        }
        ApiResult.Success(
            BuyCheck(
                title = title?.takeIf { it.isNotBlank() }, amount = amount, purchaseDate = purchaseDate, verdict = verdict, headline = headline,
                firstMethodLabel = "체크카드", baselineRatio = null, baselineAverage = null, baselinePeriod = null,
                reasons = shortfall(withCheck)?.let { listOf("${it.monthValue}월 ${it.dayOfMonth}일부터 잔액이 모자라요") }.orEmpty(),
                options = options, isEstimate = true,
            ),
        )
    }

    override suspend fun savePlan(option: BuyOption, title: String?, amount: Long): ApiResult<SavedPlan> {
        delay(600)
        val before = min(worst())
        val payDate = when (option.method) {
            BuyMethod.CHECK_LUMP_SUM, BuyMethod.DELAY -> option.date
            else -> billDate(option.date)
        }
        val payAmount = if (option.method == BuyMethod.CREDIT_INSTALLMENT) option.monthlyAmount ?: amount else amount
        val id = nextId++
        val name = title?.takeIf { it.isNotBlank() } ?: "구매 계획"
        plans += Plan(id, name, payAmount, payDate)
        val after = worst()
        val plan = SavedPlan(id, name, amount, option.date, before, min(after), shortfall(after))
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
     * F-02a 등록 — 시험값(모의 POS처럼 영문): 이름 TIMEOUT은 응답 못 받음(결과 불명, 실제로는 등록됨) · INVALID는 서버가 이름 칸을 거절(fieldErrors).
     * 말일 반복은 시작일이 그달 말일이 아니면 서버처럼 거절한다
     */
    override suspend fun createItem(form: CashflowForm): ApiResult<Long> {
        delay(700)
        val body = form.toRequest()
        if (body.title == "INVALID") return invalid("title", "서버가 거절한 이름이에요(시험값)")
        val day = body.recurrence?.dayOfMonth
        if (day != null && day != form.date.dayOfMonth && form.date != YearMonth.from(form.date).atEndOfMonth()) {
            return invalid("recurrence.dayOfMonth", "매월 반복일은 첫 발생일의 날짜와 같아야 합니다(말일 시작이면 더 큰 날도 됩니다).")
        }
        val id = nextId++
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
        lastEntries.firstOrNull { it.itemId == itemId && it.occurrenceDate == occurrenceDate }

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
        if (scope == CashflowDeleteScope.THIS_ONLY && item.repeat != CashflowRepeat.NONE) {
            excluded += itemId to (occurrenceDate ?: return invalid("occurrenceDate", "지울 회차가 필요해요"))
            return ApiResult.Success(Unit)
        }
        if (!item.date.isBefore(today)) items.removeAt(index) else items[index] = item.copy(endDate = today.minusDays(1))
        return ApiResult.Success(Unit)
    }
}
