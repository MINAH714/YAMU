package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.BuyCheck
import com.yamu.app.feature.forecast.data.BuyMethod
import com.yamu.app.feature.forecast.data.BuyOption
import com.yamu.app.feature.forecast.data.BuyVerdict
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccount
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.SavedPlan
import kotlinx.coroutines.delay
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 잔고예보 서버 — 날마다 계산한다: 잔액 950,000 − 하루 생활비 6,000 − 예정 지출 4건(450,000) → 월급 전 350,000.
 * 숫자는 프로토타입 F-01 · I-02 · I-03 그대로, 날짜만 오늘 기준(+4 카드대금 · +9 월세 · +11 통신비 · +16 구독료 · +25 월급).
 * 계획을 저장하면 같이 계산 → 잔고예보 숫자가 바뀐다(12 문서 8-5).
 */
@Singleton
class FakeForecastRepository @Inject constructor() : ForecastRepository {

    private class Plan(val id: Long, val title: String, val amount: Long, val payDate: LocalDate)

    /** F-02a로 등록한 예정 수입 · 지출 — [amount]는 수입 +, 지출 − */
    private class Item(val title: String, val amount: Long, val date: LocalDate, val repeat: CashflowRepeat, val dayOfMonth: Int?)

    private val plans = mutableListOf<Plan>()
    private val items = mutableListOf<Item>()
    private val saved = mutableMapOf<Long, SavedPlan>()
    private var nextId = 901L
    private var last: Forecast? = null

    private val balance = 950_000L
    private val daily = 6_000L
    private val alert = 100_000L
    private val today get() = LocalDate.now(KST)
    private val income get() = today.plusDays(25)
    private val horizonTo get() = income.minusDays(1)

    private fun scheduled() = listOf(
        Triple(today.plusDays(4), "신한카드 대금", -150_000L),
        Triple(today.plusDays(9), "월세", -250_000L),
        Triple(today.plusDays(11), "통신비", -35_000L),
        Triple(today.plusDays(16), "구독료 (OTT·음악)", -15_000L),
    ) + items.flatMap { item -> occurrences(item).map { Triple(it, item.title, item.amount) } }

    /** 등록한 항목의 예보 기간 안 회차 — 가짜 서버가 계산한다(앱은 계산하지 않는다). 매월은 그달에 기준일이 없으면 말일 */
    private fun occurrences(item: Item): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var n = 0L
        while (true) {
            val d = when (item.repeat) {
                CashflowRepeat.NONE -> if (n == 0L) item.date else break
                CashflowRepeat.WEEKLY -> item.date.plusWeeks(n)
                CashflowRepeat.MONTHLY -> YearMonth.from(item.date).plusMonths(n).let { ym -> ym.atDay(minOf(item.dayOfMonth ?: item.date.dayOfMonth, ym.lengthOfMonth())) }
            }
            if (d.isAfter(horizonTo)) break
            dates += d
            n++
        }
        return dates
    }

    /** 날마다 예상 잔액 — extra(날짜, 금액)는 아직 저장 안 한 '사 보면' 출금 */
    private fun simulate(extra: List<Pair<LocalDate, Long>> = emptyList()): List<Pair<LocalDate, Long>> {
        var running = balance
        val points = mutableListOf<Pair<LocalDate, Long>>()
        var d = today
        while (!d.isAfter(horizonTo)) {
            running -= daily
            scheduled().filter { it.first == d }.forEach { running += it.third }
            plans.filter { it.payDate == d }.forEach { running -= it.amount }
            extra.filter { it.first == d }.forEach { running -= it.second }
            points += d to running
            d = d.plusDays(1)
        }
        return points
    }

    private fun min(points: List<Pair<LocalDate, Long>>) = points.minOf { it.second }
    private fun shortfall(points: List<Pair<LocalDate, Long>>) = points.firstOrNull { it.second < 0 }?.first

    /** 신용카드 청구일 — 월급 전에 사면 월급 4일 뒤(프로토타입: 11/10 월급 → 11/14 결제일), 그 뒤면 다음 달 14일 */
    private fun billDate(d: LocalDate) = if (d.isBefore(income)) income.plusDays(4) else YearMonth.from(d).plusMonths(1).atDay(14)

    override suspend fun forecast(): ApiResult<Forecast> = withFault(empty = { ApiResult.Failure(ApiError.NotFound("BASE_ACCOUNT_NOT_SET")) }) { forecastNow() }

    private suspend fun forecastNow(): ApiResult<Forecast> {
        delay(500)
        val points = simulate()
        val upcoming = scheduled().map { ForecastEvent(it.first, it.second, it.third, isPlan = false, planId = null, afterIncome = false) }
        val planEvents = plans.map { ForecastEvent(it.payDate, it.title, -it.amount, isPlan = true, planId = it.id, afterIncome = it.payDate.isAfter(horizonTo)) }
        val f = Forecast(
            currentBalance = balance,
            balanceAsOfIso = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toString(),
            minBalance = min(points),
            shortfallFrom = shortfall(points),
            incomeDate = income, incomeAmount = 2_800_000, incomeTitle = "월급",
            from = today, to = horizonTo, fallback = false,
            upcoming = upcoming,
            plans = planEvents,
            scheduledOutflow = -upcoming.filter { it.amount < 0 }.sumOf { it.amount },   // 처음엔 4건 450,000 — 등록한 지출이 더해진다
            livingCost = daily * points.size,
            points = points,
            isEstimate = true,
            accounts = listOf(ForecastAccount("KB국민은행 입출금", "생활비", "1234", balance, min(points), shortfall(points))),
        )
        last = f
        return ApiResult.Success(f)
    }

    override fun cached(): Forecast? = last

    override suspend fun check(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck> = withFault { checkNow(amount, title, purchaseDate) }

    private suspend fun checkNow(amount: Long, title: String?, purchaseDate: LocalDate): ApiResult<BuyCheck> {
        delay(800)
        if (amount <= 0) return ApiResult.Failure(ApiError.InvalidRequest("INVALID_AMOUNT"))
        val base = simulate()
        val withCheck = simulate(listOf(purchaseDate to amount))
        val creditBill = billDate(purchaseDate)
        val withCredit = simulate(listOf(creditBill to amount))
        val monthly = (amount + 2) / 3
        val withInstallment = simulate(listOf(creditBill to monthly))
        val minCheck = min(withCheck)
        val verdict = when {
            minCheck >= alert -> BuyVerdict.OK
            min(withCredit) >= 0 -> BuyVerdict.CAUTION
            else -> BuyVerdict.HOLD
        }
        val what = title?.takeIf { it.isNotBlank() }
        val m = YearMonth.now(KST).monthValue
        val reasons = if (verdict == BuyVerdict.OK) {
            val perDay = ((minCheck - alert) / 25 + daily) / 100 * 100
            listOf("월급 전까지 하루 ${"%,d".format(perDay)}원까지 써도 괜찮아요", "신용카드로 내면 월급 전 잔액은 그대로예요")
        } else {
            val from = shortfall(withCheck)
            listOfNotNull(
                from?.let { "${it.monthValue}월 ${it.dayOfMonth}일부터 잔액이 모자라요" },
                "카드 실적은 변화 없어요",
                "${income.monthValue}월 ${income.dayOfMonth}일 월급 뒤에 사면 괜찮아요",
            )
        }
        fun option(method: BuyMethod, date: LocalDate, card: Long, name: String, months: Int?, minBalance: Long, monthlyAmount: Long?, points: List<Pair<LocalDate, Long>>) =
            BuyOption(method, date, card, name, months, minBalance, monthlyAmount, shortfall(points), recommended = false)
        val options = listOf(
            option(BuyMethod.CHECK_LUMP_SUM, purchaseDate, 16, "KB국민 노리2 체크카드(KB Pay)", null, minCheck, null, withCheck),
            option(BuyMethod.CREDIT_LUMP_SUM, purchaseDate, 11, "KB국민 청춘대로 톡톡카드", null, min(withCredit), null, withCredit),
            option(BuyMethod.CREDIT_INSTALLMENT, purchaseDate, 11, "KB국민 청춘대로 톡톡카드", 3, min(withInstallment), monthly, withInstallment),
        ) + if (verdict == BuyVerdict.OK) emptyList() else listOf(BuyOption(BuyMethod.DELAY, income, 16, "KB국민 노리2 체크카드(KB Pay)", null, min(base), null, null, recommended = true))
        val headline = when (verdict) {
            BuyVerdict.OK -> "지금 사도 월급 전에 ${minCheck / 10_000}만 원 남아요"
            else -> "지금 사면 월급 전에 ${(-minCheck + 9_999) / 10_000}만 원 모자라요"
        }
        return ApiResult.Success(
            BuyCheck(
                title = what, amount = amount, purchaseDate = purchaseDate, verdict = verdict, headline = headline,
                firstMethodLabel = "체크카드",
                baselineRatio = (amount * 100.0 / 1_180_000).let { Math.round(it).toInt() },
                baselineAverage = 1_180_000, baselinePeriod = "${(m + 6) % 12 + 1}~${(m + 10) % 12 + 1}월 평균",
                reasons = reasons, options = options, isEstimate = true,
            ),
        )
    }

    override suspend fun savePlan(option: BuyOption, title: String?, amount: Long): ApiResult<SavedPlan> {
        delay(600)
        val before = min(simulate())
        val payDate = when (option.method) {
            BuyMethod.CHECK_LUMP_SUM, BuyMethod.DELAY -> option.date
            else -> billDate(option.date)
        }
        val payAmount = if (option.method == BuyMethod.CREDIT_INSTALLMENT) option.monthlyAmount ?: amount else amount
        val id = nextId++
        val name = title?.takeIf { it.isNotBlank() } ?: "구매 계획"
        plans += Plan(id, name, payAmount, payDate)
        val after = simulate()
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
        fun invalid(field: String, reason: String) = ApiResult.Failure(
            ApiError.InvalidRequest("INVALID_REQUEST", buildJsonObject { putJsonArray("fieldErrors") { addJsonObject { put("field", field); put("reason", reason) } } }),
        )
        if (body.title == "INVALID") return invalid("title", "서버가 거절한 이름이에요(시험값)")
        val day = body.recurrence?.dayOfMonth
        if (day != null && day != form.date.dayOfMonth && form.date != YearMonth.from(form.date).atEndOfMonth()) {
            return invalid("recurrence.dayOfMonth", "매월 반복일은 첫 발생일의 날짜와 같아야 합니다(말일 시작이면 더 큰 날도 됩니다).")
        }
        items += Item(body.title, if (form.isIncome) body.amount else -body.amount, form.date, form.repeat, day)
        if (body.title == "TIMEOUT") return ApiResult.Failure(ApiError.Network)
        return ApiResult.Success(nextId++)
    }
}
