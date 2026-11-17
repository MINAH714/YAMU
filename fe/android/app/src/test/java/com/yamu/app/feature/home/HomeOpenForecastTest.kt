package com.yamu.app.feature.home

import android.content.Context
import android.content.ContextWrapper
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.core.security.SessionStore
import com.yamu.app.feature.forecast.data.ForecastApi
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastRepositoryImpl
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.home.data.CardBenefit
import com.yamu.app.feature.home.data.ForecastSummary
import com.yamu.app.feature.home.data.HomeApi
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeCardsData
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeRepositoryImpl
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.NewHomeArea
import com.yamu.app.feature.home.data.PaySettings
import com.yamu.app.feature.home.data.SyncResult
import com.yamu.app.feature.link.data.CashflowApi
import com.yamu.app.feature.link.data.LinkApi
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.report.data.ReportJump
import com.yamu.app.feature.report.data.ReportMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.YearMonth

/**
 * 홈 잔고예보 카드 · '잔고예보 보기'(S15P31E103-599) — 기준 계좌 하나가 아니라 연결 계좌 전체 · 30일.
 * 11/16(-548)까지는 카드 숫자가 서버 홈 칸(기준 계좌)이고 버튼도 기준 계좌 하나를 골라 열었다
 */
class HomeOpenForecastTest {
    private val dispatcher = StandardTestDispatcher()

    /** SessionStore는 만들 때 파일을 열지 않는다. 여기서 부르는 openForecast · refresh는 세션을 읽지 않는다 */
    private val noFilesContext = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private var selected = ForecastSelection(ForecastPeriod.DAYS_7, setOf(2L))   // 잔고예보에서 7일 · 계좌 하나를 골라 둔 상태

    /** select · selection만 쓰고, 다른 메서드를 부르면 실패한다 */
    private val forecasts: ForecastRepository = Proxy.newProxyInstance(
        ForecastRepository::class.java.classLoader,
        arrayOf(ForecastRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "select" -> { selected = args[0] as ForecastSelection; Unit }
            "selection" -> selected
            else -> error("안 쓰는 메서드: ${method.name}")
        }
    } as ForecastRepository

    private lateinit var server: MockWebServer

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        Dispatchers.resetMain()
        if (::server.isInitialized) server.close()
    }

    private fun viewModel(home: HomeRepository = unused()): HomeViewModel {
        val months = ReportMonth()
        return HomeViewModel(home, SessionStore(noFilesContext), months, ReportJump(months), forecasts, unused<LinkRepository>())
    }

    // ── '잔고예보 보기' ──

    @Test fun `잔고예보 보기는 골라 둔 기간 · 계좌를 연결 계좌 전체 · 30일로 덮어쓴다`() {
        viewModel().openForecast()

        assertEquals(ForecastSelection(ForecastPeriod.DAYS_30, all = true), selected)
        assertTrue("계좌 칩이 전체로 보여야 한다", selected.coversAll)
    }

    @Test fun `홈 요약을 받기 전이라 기준 계좌를 몰라도 전체로 연다`() {
        val vm = viewModel()
        assertNull(vm.state.value.content)

        vm.openForecast()

        assertEquals(HOME_FORECAST_SELECTION, selected)
    }

    // ── 카드 숫자 ──

    /** 서버 홈 칸은 기준 계좌(1) 하나 — 박준수처럼 기준 계좌 1,050,000원 */
    private val basePreview = HomeForecastBrief(1, null, 1_050_000, 900_000, null, null, fromTomorrow = true)
    private val allBrief = HomeForecastBrief(null, "계좌 3개 전체", 2_480_000, 1_730_000, null, null)

    private fun summary(preview: NewHomeArea<HomeForecastBrief>) = HomeSummary(
        forecast = ForecastSummary("지금 잔액", 1_050_000, false, false, null, null, null, accountId = 1, currentBalance = 1_050_000),
        recentPayment = null, todos = emptyList(), cardCount = 6, unreadCount = 0, needsLinking = false, syncedAtIso = null, failedAreas = emptySet(),
        forecastPreview = preview,
    )

    /** 홈 응답과 잔고예보 요약만 주고 나머지 칸은 실패(이 테스트는 잔고예보 칸만 본다) */
    private class Home(private val home: HomeSummary, private val brief: ApiResult<HomeForecastBrief>) : HomeRepository {
        var briefCalls = 0
        private val off = ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))
        override suspend fun loadHome(): ApiResult<HomeSummary> = ApiResult.Success(home)
        override suspend fun sync(): ApiResult<SyncResult> = ApiResult.Success(SyncResult(null, 0))
        override suspend fun loadBenefit(month: YearMonth): ApiResult<HomeBenefit> = off
        override suspend fun loadPaySettings(): ApiResult<PaySettings> = off
        override suspend fun loadCards(): ApiResult<HomeCardsData> = off
        override suspend fun loadForecastBrief(): ApiResult<HomeForecastBrief> { briefCalls++; return brief }
        override suspend fun loadCardBenefits(month: YearMonth): ApiResult<Map<Long, CardBenefit>> = off
    }

    @Test fun `카드 숫자는 서버 홈 칸(기준 계좌)이 아니라 연결 계좌 전체 합`() = runTest(dispatcher) {
        val home = Home(summary(NewHomeArea.Value(basePreview)), ApiResult.Success(allBrief))
        val vm = viewModel(home)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(HomeSection.Ready(allBrief), vm.state.value.forecast)
        assertEquals(1, home.briefCalls)
    }

    @Test fun `서버 홈 칸만 실패해도 전체 합은 따로 받아 그린다`() = runTest(dispatcher) {
        val home = Home(summary(NewHomeArea.Failed), ApiResult.Success(allBrief))
        val vm = viewModel(home)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(HomeSection.Ready(allBrief), vm.state.value.forecast)
    }

    @Test fun `계좌가 없는 홈이면 전체 합을 부르지 않는다`() = runTest(dispatcher) {
        val home = Home(summary(NewHomeArea.None), ApiResult.Success(allBrief))
        val vm = viewModel(home)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(0, home.briefCalls)
        assertEquals(HomeSection.Loading, vm.state.value.forecast)
    }

    @Test fun `다시 시도는 홈부터가 아니라 전체 합만 다시 부른다`() = runTest(dispatcher) {
        val failed = ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))
        var result: ApiResult<HomeForecastBrief> = failed
        val home = object : HomeRepository by Home(summary(NewHomeArea.Value(basePreview)), failed) {
            var calls = 0
            override suspend fun loadForecastBrief(): ApiResult<HomeForecastBrief> { calls++; return result }
        }
        val vm = viewModel(home)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(HomeSection.Failed(failed.error), vm.state.value.forecast)

        result = ApiResult.Success(allBrief)
        vm.retryForecast()
        advanceUntilIdle()

        assertEquals(HomeSection.Ready(allBrief), vm.state.value.forecast)
        assertEquals(2, home.calls)
    }

    // ── 홈 카드와 F-01이 같은 요청 ──

    private var forecastQueries = mutableListOf<String?>()
    private var accountsJson = """{"success":true,"data":{"accounts":[
        {"accountId":1,"bank":{"code":"004","name":"KB국민은행"},"maskedAccountNo":"****-1234","balance":300000,"isPrimary":true},
        {"accountId":2,"bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-5678","balance":200000,"isPrimary":false}]}}"""

    private fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/forecasts/balance" -> {
                    forecastQueries += request.url.query
                    json(200, """{"success":true,"data":{"accountIds":[1,2],"currentTotalBalance":500000,"summary":{"minBalance":120000,"minBalanceDate":"2026-11-28"}}}""")
                }
                "/accounts" -> json(200, accountsJson)
                else -> json(404, """{"success":false,"data":null,"error":{"code":"API_NOT_FOUND","message":"없음"}}""")
            }
        }
        server.start()
    }

    @Test fun `홈 카드는 F-01 '잔고예보 보기'와 같은 계좌 · 기간으로 부른다`() = runTest {
        startServer()
        val retrofit = server.retrofit()
        val f01 = ForecastRepositoryImpl(retrofit.create(ForecastApi::class.java), retrofit.create(LinkApi::class.java), retrofit.create(CashflowApi::class.java))
        val home = HomeRepositoryImpl(retrofit.create(HomeApi::class.java))

        val brief = (home.loadForecastBrief() as ApiResult.Success).data
        f01.select(HOME_FORECAST_SELECTION)
        val forecast = (f01.forecast() as ApiResult.Success).data

        // 계좌 · 기간은 같고, F-01만 계좌별 결과(안전 잔액 아래로 내려가는 계좌 카드)를 더 받는다
        assertEquals(listOf("accountIds=1&accountIds=2&until=DAYS&days=30"), forecastQueries.map { it?.removeSuffix("&includeAccountDetails=true") }.distinct())
        assertEquals(listOf(false, true), forecastQueries.map { it.orEmpty().endsWith("&includeAccountDetails=true") })
        assertEquals(2, forecastQueries.size)
        assertEquals(forecast.currentBalance, brief.currentBalance)
        assertEquals(500_000L, brief.currentBalance)
        assertEquals(120_000L, brief.minBalance)
        assertNull("전체 합은 기준 계좌가 없다", brief.accountId)
        assertEquals("계좌 2개 전체", brief.accountLabel)
        assertEquals("계좌 2개 전체", forecast.accountLabel(f01.selection().coversAll))
    }

    @Test fun `연결 계좌가 없으면 예보를 부르지 않고 실패`() = runTest {
        accountsJson = """{"success":true,"data":{"accounts":[]}}"""
        startServer()
        val home = HomeRepositoryImpl(server.retrofit().create(HomeApi::class.java))

        val r = home.loadForecastBrief()

        assertTrue(r is ApiResult.Failure)
        assertEquals(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT), (r as ApiResult.Failure).error)
        assertTrue(forecastQueries.isEmpty())
    }

    private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
}
