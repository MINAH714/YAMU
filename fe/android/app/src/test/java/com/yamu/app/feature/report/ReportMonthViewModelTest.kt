package com.yamu.app.feature.report

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.link.data.CardsOnlyLink
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.report.data.AssetFlow
import com.yamu.app.feature.report.data.AssetFlowRepository
import com.yamu.app.feature.report.data.MonthFlow
import com.yamu.app.feature.report.data.MonthFlowResult
import com.yamu.app.feature.report.data.ReportSection
import com.yamu.app.feature.report.data.ReportJump
import com.yamu.app.feature.report.data.JumpRequest
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import java.time.LocalDate
import java.time.ZonedDateTime
import com.yamu.app.feature.report.data.BenefitReport
import com.yamu.app.feature.report.data.CsvFile
import com.yamu.app.feature.report.data.MonthlySpend
import com.yamu.app.feature.report.data.PaymentsPage
import com.yamu.app.feature.report.data.ReportMonth
import com.yamu.app.feature.report.data.ReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/** S-01 · S-04 — 같은 달을 같이 쓰고, 빠르게 넘겨도 마지막에 고른 달만 남는다 */
class ReportMonthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubReports()
    private val assets = StubAssets()
    private val link = CardsOnlyLink()
    private var now = NOV
    private val months = ReportMonth { now }

    /** 자산 흐름 시험은 오늘을 11/13으로 고정 — 1일이면 지난날이 없어 지난 잔고를 부르지 않는 규칙이 끼어들지 않게 */
    private val fixed = ReportMonth({ now }, { now.atDay(13) })

    // ── 자산 흐름 · 다음 달(S15P31E103-483) ──

    @Test fun `다음 달은 쓴 돈이 없는 달 — 월 API를 부르지 않고 자산 흐름만`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        vm.select(DEC); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(DEC, s.shown.month)
        assertTrue(s.shown.isNextMonth)
        assertNull(s.spend)
        assertEquals(false, s.loading)
        assertEquals(listOf(NOV), repo.monthlyCalls)
        assertEquals(LocalDate.of(2026, 12, 1), s.assetDate)   // 다음 달은 1일
        assertEquals(false, s.shown.canNext)                   // 다음 달 하나까지
    }

    @Test fun `소비분석이 다음 달이어도 혜택 리포트는 이번 달로 연다`() = runTest(dispatcher) {
        val report = ReportViewModel(repo, fixed, assets)
        report.refresh(); advanceUntilIdle()   // 예보가 다음 달에 닿아야 다음 달이 열린다
        report.select(DEC); advanceUntilIdle()
        val benefits = BenefitReportViewModel(repo, fixed, link)
        advanceUntilIdle()
        assertEquals(NOV, benefits.state.value.shown.month)
        assertEquals(listOf(NOV), repo.benefitCalls)
        assertEquals(DEC, report.state.value.shown.month)   // 소비분석은 보던 다음 달 그대로
    }

    @Test fun `30일 예보는 돌아올 때만 받고, 달을 바꾸면 그달 자산 흐름만 다시 받는다`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        vm.select(OCT); advanceUntilIdle()
        vm.select(DEC); advanceUntilIdle()   // 다음 달도 asset-flows가 앞날 예보를 준다
        assertEquals(1, assets.loads)
        assertEquals(listOf(NOV, OCT, DEC), assets.monthCalls)
        assertEquals(DEC, vm.state.value.asset.month?.month)
        vm.refresh(); advanceUntilIdle()     // 잔고예보에서 돌아옴 — 예정 수입·지출이 바뀌었을 수 있다
        assertEquals(2, assets.loads)
        assertEquals(listOf(NOV, OCT, DEC, DEC), assets.monthCalls)
    }

    @Test fun `달을 빠르게 넘기면 늦게 온 앞 달 자산 흐름이 새 달을 덮지 않는다`() = runTest(dispatcher) {
        assets.monthDelays[OCT] = 1_000
        assets.monthDelays[SEP] = 100
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        vm.select(OCT)
        advanceTimeBy(10)
        vm.select(SEP)
        advanceUntilIdle()
        assertEquals(SEP, vm.state.value.asset.month?.month)
    }

    @Test fun `옛 서버(asset-flows 404)면 오류 없이 비워 예보만으로, 실패면 다시 시도 문구`() = runTest(dispatcher) {
        assets.monthResults[NOV] = MonthFlowResult.Unsupported
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        assertNull(vm.state.value.asset.month)
        assertNull(vm.state.value.asset.monthError)
        assets.monthResults[NOV] = MonthFlowResult.Failed(ApiError.Network)
        vm.retryMonth(); advanceUntilIdle()
        assertTrue(vm.state.value.asset.monthError != null)
        assertTrue("자산 흐름의 30일 예보는 그대로", vm.state.value.asset.flow != null)
        assertEquals(11, vm.state.value.spend?.month)
    }

    @Test fun `자산 흐름이 실패해도 사용금액은 그대로, 같은 실패면 마지막 예보 유지`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        val first = vm.state.value.asset.flow
        assets.fail = ApiError.Network
        vm.refresh(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(11, s.spend?.month)
        assertTrue(s.asset.error != null)
        assertEquals("마지막 값", first, s.asset.flow)
    }

    @Test fun `예보만 실패한 새로고침은 받아 둔 예보를 지우지 않고 마지막 값 안내를 붙인다(!312 리뷰)`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        val first = vm.state.value.asset.flow!!
        assertTrue(first.forecast != null)
        assets.forecastFail = ApiError.BankUnavailable("FIN_API_UNAVAILABLE")
        vm.refresh(); advanceUntilIdle()
        val a = vm.state.value.asset
        assertEquals("7 · 14 · 30일 칸과 앞날은 지난 예보로", first.forecast, a.flow?.forecast)
        assertEquals("기준 시각도 그 예보를 받은 때", first.loadedAt, a.flow?.loadedAt)
        assertTrue("'… · 마지막 값을 보여 드려요' 안내", a.error != null)
        assets.forecastFail = null
        vm.refresh(); advanceUntilIdle()
        assertNull("다시 받으면 안내를 거둔다", vm.state.value.asset.error)
    }

    @Test fun `처음부터 예보가 없으면 지어내지 않는다 — 안내 없이 예보 자리만 빈다`() = runTest(dispatcher) {
        assets.forecastFail = ApiError.Network
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        assertNull(vm.state.value.asset.flow?.forecast)
        assertNull(vm.state.value.asset.error)
    }

    @Test fun `다음 달은 예보가 다음 달 1일에 닿을 때만 연다(!312 리뷰)`() = runTest(dispatcher) {
        assets.forecast = assets.forecastUntil(LocalDate.of(2026, 11, 30), balance = 500_000)   // 말일에서 끝나는 예보
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        assertEquals(false, vm.state.value.shown.canNext)
        vm.select(DEC); advanceUntilIdle()
        assertEquals("골라도 이번 달", NOV, vm.state.value.shown.month)
        assets.forecast = assets.forecastUntil(LocalDate.of(2026, 12, 13), balance = 500_000)
        vm.refresh(); advanceUntilIdle()
        assertEquals(true, vm.state.value.shown.canNext)
    }

    @Test fun `다음 달을 보던 중 예보가 다음 달에 닿지 않게 되면 이번 달로 돌아간다`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        vm.select(DEC); advanceUntilIdle()
        assertEquals(DEC, vm.state.value.shown.month)
        assets.fail = ApiError.Network   // 통째로 실패하면 마지막 예보가 남아 다음 달도 남는다
        vm.refresh(); advanceUntilIdle()
        assertEquals(DEC, vm.state.value.shown.month)
        assets.fail = null
        assets.forecast = assets.forecastUntil(LocalDate.of(2026, 11, 30), balance = 500_000)
        vm.refresh(); advanceUntilIdle()
        assertEquals(NOV, vm.state.value.shown.month)
        assertEquals(11, vm.state.value.spend?.month)
    }

    @Test fun `홈에서 내려오기 — 요청 뒤에 끝난 불러오기(이번 달)를 기다린다(!312 리뷰)`() = runTest(dispatcher) {
        val jumps = ReportJump(fixed)
        val vm = ReportViewModel(repo, fixed, assets, jumps)
        vm.refresh(); advanceUntilIdle()
        vm.select(OCT); advanceUntilIdle()            // 지난 방문 — 10월 배치로 끝나 있다
        jumps.request(ReportSection.ASSET_FLOW)       // 홈 '자산 흐름 보기' — 달은 이번 달로
        val request = vm.jump.value!!
        assertEquals("지난 배치로는 내려가지 않는다", false, vm.state.value.readyForJump(request))
        vm.refresh()                                  // 탭이 다시 보임
        advanceTimeBy(10)
        assertEquals("불러오는 중", false, vm.state.value.readyForJump(request))
        advanceUntilIdle()
        assertEquals(NOV, vm.state.value.shown.month)
        assertEquals(true, vm.state.value.readyForJump(request))
        vm.jumped(request)
        assertNull("내려간 뒤 요청은 남지 않는다", vm.jump.value)
    }


    @Test fun `계좌가 없으면 자산 흐름은 빈 상태, 사용금액은 그대로`() = runTest(dispatcher) {
        assets.fail = ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT)
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.asset.noAccount)
        assertEquals(11, vm.state.value.spend?.month)
    }

    @Test fun `고른 날은 돌아와도 남고, 달을 바꾸면 그달 처음 날, 오늘로는 이번 달 오늘`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 13), vm.state.value.assetDate)
        vm.selectDate(LocalDate.of(2026, 11, 20))
        vm.refresh(); advanceUntilIdle()   // 잔고예보에 다녀옴(ANALYSIS-14)
        assertEquals(LocalDate.of(2026, 11, 20), vm.state.value.assetDate)
        vm.select(OCT); advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 31), vm.state.value.assetDate)   // 지난달은 말일
        vm.goToday(); advanceUntilIdle()
        assertEquals(NOV, vm.state.value.shown.month)
        assertEquals(LocalDate.of(2026, 11, 13), vm.state.value.assetDate)
    }

    @Test fun `홈 '자산 흐름 보기'로 내려오면 오늘을 고른다`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, fixed, assets)
        vm.refresh(); advanceUntilIdle()
        vm.selectDate(LocalDate.of(2026, 11, 25))
        vm.jumped(JumpRequest(ReportSection.ASSET_FLOW, 1))
        assertEquals(LocalDate.of(2026, 11, 13), vm.state.value.assetDate)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `S-01 — 달을 빠르게 넘기면 마지막 달의 숫자만 남는다`() = runTest(dispatcher) {
        repo.delays[OCT] = 1_000   // 10월이 9월보다 늦게 온다
        repo.delays[SEP] = 100
        val vm = ReportViewModel(repo, months, assets)
        vm.refresh(); advanceUntilIdle()
        vm.select(OCT)
        advanceTimeBy(10)   // 10월 요청이 나가 있는 동안
        vm.select(SEP)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(SEP, s.shown.month)
        assertEquals(9, s.spend?.month)
        assertEquals(listOf(NOV, OCT, SEP), repo.monthlyCalls)
    }

    @Test fun `S-01 — 달을 바꾸면 앞 달 숫자를 비우고 불러오는 중, 실패하면 다시 시도`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, months, assets)
        vm.refresh(); advanceUntilIdle()
        repo.fail = true
        vm.select(OCT)
        assertNull(vm.state.value.spend)   // 11월 숫자를 10월 머리 아래 두지 않는다
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        assertNull(vm.state.value.spend)
        assertEquals(OCT, vm.state.value.shown.month)
        assertTrue(vm.state.value.error != null)
    }

    @Test fun `S-01 — 같은 달이 실패하면 마지막 값을 그대로`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, months, assets)
        vm.refresh(); advanceUntilIdle()
        repo.fail = true
        vm.refresh(); advanceUntilIdle()
        assertEquals(11, vm.state.value.spend?.month)
        assertTrue(vm.state.value.error != null)
    }

    @Test fun `S-01 — 새로 만들어지면 이번 달부터(앞사람이 고른 달이 남지 않게)`() = runTest(dispatcher) {
        months.select(SEP)
        val vm = ReportViewModel(repo, months, assets)
        assertEquals(NOV, vm.state.value.shown.month)
        assertEquals(NOV, months.current().month)
    }

    @Test fun `S-01에서 고른 달로 S-04가 열리고, S-04에서 바꾼 달을 S-01이 이어 본다`() = runTest(dispatcher) {
        val report = ReportViewModel(repo, months, assets)
        report.refresh(); advanceUntilIdle()
        report.select(OCT); advanceUntilIdle()
        val benefits = BenefitReportViewModel(repo, months, link)   // S-01 → S-04
        advanceUntilIdle()
        assertEquals(OCT, benefits.state.value.shown.month)
        assertEquals(listOf(OCT), repo.benefitCalls)
        benefits.select(SEP); advanceUntilIdle()
        report.refresh(); advanceUntilIdle()                   // S-01로 돌아옴(화면이 다시 보일 때 refresh)
        assertEquals(SEP, report.state.value.shown.month)
        assertEquals(9, report.state.value.spend?.month)
    }

    @Test fun `S-04 — 돌아왔을 때 달이 같으면 다시 부르지 않고, 다르면 그 달로`() = runTest(dispatcher) {
        val benefits = BenefitReportViewModel(repo, months, link)
        advanceUntilIdle()
        benefits.resume(); advanceUntilIdle()
        assertEquals(listOf(NOV), repo.benefitCalls)
        months.select(OCT)                                     // 결제 내역(S-05)에서 바꿈
        benefits.resume(); advanceUntilIdle()
        assertEquals(listOf(NOV, OCT), repo.benefitCalls)
        assertEquals("10월", benefits.state.value.report?.periodLabel)
    }

    @Test fun `S-04 카드 줄 — 이번 달이면 등록된 카드를 받고, 지난달이면 부르지 않는다`() = runTest(dispatcher) {
        link.result = ApiResult.Success(listOf(LinkedCard(11, brandOf("신한카드"), "Mr.Life", false, "0011", 14, null, null)))
        val benefits = BenefitReportViewModel(repo, months, link)
        advanceUntilIdle()
        assertEquals(setOf(11L), benefits.state.value.cardIds)
        assertEquals(1, link.calls)
        benefits.select(OCT); advanceUntilIdle()
        assertEquals("지난달은 R-02로 가지 않으니 카드 목록도 받지 않는다", 1, link.calls)
        benefits.select(NOV); advanceUntilIdle()
        assertEquals(2, link.calls)
    }

    @Test fun `S-04 카드 줄 — 카드 상세에서 해지하고 돌아오면 다시 받고, 받지 못하면 누르기를 끈다`() = runTest(dispatcher) {
        link.result = ApiResult.Success(listOf(LinkedCard(11, brandOf("신한카드"), "Mr.Life", false, "0011", 14, null, null)))
        val benefits = BenefitReportViewModel(repo, months, link)
        advanceUntilIdle()
        link.result = ApiResult.Failure(ApiError.Network)
        benefits.resume(); advanceUntilIdle()
        assertEquals(listOf(NOV), repo.benefitCalls)   // 같은 달이면 혜택은 다시 안 부른다
        assertEquals(2, link.calls)
        assertNull(benefits.state.value.cardIds)
    }

    @Test fun `S-04 — 달을 빠르게 넘기면 늦게 온 앞 달 응답이 덮어쓰지 않는다`() = runTest(dispatcher) {
        repo.delays[OCT] = 1_000
        repo.delays[SEP] = 100
        val benefits = BenefitReportViewModel(repo, months, link)
        advanceUntilIdle()   // 처음 11월
        benefits.select(OCT)
        advanceTimeBy(10)   // 10월 요청이 나가 있는 동안
        benefits.select(SEP)
        advanceUntilIdle()
        assertEquals(listOf(NOV, OCT, SEP), repo.benefitCalls)
        assertEquals(SEP, benefits.state.value.shown.month)
        assertEquals("9월", benefits.state.value.report?.periodLabel)
    }

    @Test fun `이번 달을 따라가다 달이 바뀌면 새 달을 본다(앱을 켜 둔 채 1일 자정)`() = runTest(dispatcher) {
        val vm = ReportViewModel(repo, months, assets)
        vm.refresh(); advanceUntilIdle()
        now = DEC
        vm.refresh(); advanceUntilIdle()
        assertEquals(DEC, vm.state.value.shown.month)
        assertEquals(12, vm.state.value.spend?.month)
    }

    private class StubReports : ReportRepository {
        val delays = mutableMapOf<YearMonth, Long>()
        var fail = false
        val monthlyCalls = mutableListOf<YearMonth>()
        val benefitCalls = mutableListOf<YearMonth>()

        override suspend fun monthly(month: YearMonth): ApiResult<MonthlySpend> {
            monthlyCalls += month
            delay(delays[month] ?: 100)
            if (fail) return ApiResult.Failure(ApiError.Network)
            return ApiResult.Success(MonthlySpend("${month.monthValue}월", month.monthValue, isLiving = true, spent = 1_000, diffFromPrev = null, categories = emptyList(), benefit = 0, paymentCount = 1))
        }

        override suspend fun benefits(month: YearMonth): ApiResult<BenefitReport> {
            benefitCalls += month
            delay(delays[month] ?: 100)
            return ApiResult.Success(BenefitReport("${month.monthValue}월", received = 100, yamuExtra = null, missedTotal = 0, missed = emptyList(), byPlace = emptyList()))
        }

        override suspend fun payments(page: Int, month: YearMonth): ApiResult<PaymentsPage> = error("안 씀")
        override suspend fun exportCsv(month: YearMonth): ApiResult<CsvFile> = error("안 씀")
    }

    private class StubAssets : AssetFlowRepository {
        var loads = 0
        var fail: ApiError? = null
        val monthCalls = mutableListOf<YearMonth>()
        /** 달마다 응답 — 없으면 그달 빈 응답(Loaded). [Unsupported]는 옛 서버 */
        val monthResults = mutableMapOf<YearMonth, MonthFlowResult>()
        val monthDelays = mutableMapOf<YearMonth, Long>()
        /** 계좌는 받고 예보만 실패 — 저장소가 Success(forecast = null, forecastError)로 준다 */
        var forecastFail: ApiError? = null
        /** 성공할 때 예보 — 11/13부터 30일(12/13까지, 다음 달 1일에 닿는다) */
        var forecast: Forecast? = forecastUntil(LocalDate.of(2026, 12, 13), balance = 500_000)
        private val account = ForecastAccountOption(2, "생활비 통장", "신한", "5678", 268_000, isPrimary = true)

        override suspend fun load(): ApiResult<AssetFlow> {
            loads++
            delay(50)
            fail?.let { return ApiResult.Failure(it) }
            val at = ZonedDateTime.of(2026, 11, 13, 9, loads, 0, 0, KST)
            forecastFail?.let { return ApiResult.Success(AssetFlow(listOf(account), forecast = null, forecastError = it, loadedAt = at)) }
            return ApiResult.Success(AssetFlow(listOf(account), forecast = forecast, loadedAt = at))
        }

        fun forecastUntil(end: LocalDate, balance: Long): Forecast {
            val from = LocalDate.of(2026, 11, 13)
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { day(it, expected = balance, conservative = null) }.toList()
            return testForecast(days = days, from = from, to = end, accountIds = listOf(2))
        }

        override suspend fun month(month: YearMonth): MonthFlowResult {
            monthCalls += month
            delay(monthDelays[month] ?: 30)
            return monthResults[month] ?: MonthFlowResult.Loaded(MonthFlow(month, listOf(2), 268_000, null, emptyList(), null, null, emptyList()))
        }
    }

    private companion object {
        val DEC: YearMonth = YearMonth.of(2026, 12)
        val NOV: YearMonth = YearMonth.of(2026, 11)
        val OCT: YearMonth = YearMonth.of(2026, 10)
        val SEP: YearMonth = YearMonth.of(2026, 9)
    }
}
