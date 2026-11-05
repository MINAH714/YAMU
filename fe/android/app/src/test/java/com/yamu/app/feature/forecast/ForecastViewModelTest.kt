package com.yamu.app.feature.forecast

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

/** F-02 — 예보가 실패해도 회차로 목록을 그리므로 회차 응답 전에는 오류 화면을 보여 주지 않는다 */
class ForecastViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var cachedForecast: Forecast? = null
    private var forecastResult: ApiResult<Forecast> = ApiResult.Failure(FORECAST_ERROR)
    private var entriesResponse = CompletableDeferred<ApiResult<List<CashflowEntry>>>()
    private var entriesCalls = 0

    private val repo = object : ForecastRepository by unused() {
        override suspend fun forecast() = forecastResult
        override fun cached() = cachedForecast
        override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> {
            entriesCalls++
            return entriesResponse.await()
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `예보가 실패해도 회차 응답 전에는 불러오는 중이다`() = runTest(dispatcher) {
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        advanceUntilIdle()

        assertEquals(FORECAST_ERROR.userMessage(), vm.state.value.error)
        assertTrue(vm.state.value.cashflowLoading)

        entriesResponse.complete(ApiResult.Success(emptyList()))
        advanceUntilIdle()

        assertFalse(vm.state.value.cashflowLoading)
        assertEquals(emptyList<CashflowEntry>(), vm.state.value.entries)
    }

    @Test fun `예보와 회차가 모두 실패하면 회차 오류 문구를 보여 준다`() = runTest(dispatcher) {
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        entriesResponse.complete(ApiResult.Failure(ENTRIES_ERROR))
        advanceUntilIdle()

        assertFalse(vm.state.value.cashflowLoading)
        assertEquals(ENTRIES_ERROR.userMessage(), vm.state.value.cashflowError)
    }

    @Test fun `오류 화면에서 다시 시도하면 불러오는 중으로 돌아갔다가 목록을 받는다`() = runTest(dispatcher) {
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        entriesResponse.complete(ApiResult.Failure(ENTRIES_ERROR))
        advanceUntilIdle()

        entriesResponse = CompletableDeferred()
        vm.refreshWithEntries()
        advanceUntilIdle()
        assertTrue(vm.state.value.cashflowLoading)

        entriesResponse.complete(ApiResult.Success(emptyList()))
        advanceUntilIdle()
        assertFalse(vm.state.value.cashflowLoading)
        assertEquals(null, vm.state.value.entriesError)
    }

    @Test fun `회차를 이미 받았으면 다시 불러오는 동안에도 목록을 그대로 둔다`() = runTest(dispatcher) {
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        entriesResponse.complete(ApiResult.Success(emptyList()))
        advanceUntilIdle()

        entriesResponse = CompletableDeferred()
        vm.refreshWithEntries()
        advanceUntilIdle()

        assertFalse(vm.state.value.cashflowLoading)
    }

    @Test fun `지난 예보가 남아 있으면 회차 응답 전에도 그 예보로 그린다`() = runTest(dispatcher) {
        cachedForecast = FORECAST
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        advanceUntilIdle()

        assertFalse(vm.state.value.cashflowLoading)
    }

    @Test fun `계좌를 잇지 않았으면 회차를 부르지 않고 빈 상태로 끝난다`() = runTest(dispatcher) {
        forecastResult = ApiResult.Failure(ApiError.from(400, "BASE_ACCOUNT_NOT_SET"))
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        advanceUntilIdle()

        assertTrue(vm.state.value.noAccount)
        assertFalse(vm.state.value.cashflowLoading)
        assertEquals(0, entriesCalls)
    }

    @Test fun `예정된 돈의 기간 — 월급일이 있는 예보만 월급 전까지이고 나머지는 30일`() {
        val payday = LocalDate.of(2026, 11, 25)

        assertEquals("월급 전까지", cashflowPeriod(FORECAST.copy(incomeDate = payday)))
        assertEquals("30일 동안", cashflowPeriod(null))
        assertEquals("30일 동안", cashflowPeriod(FORECAST.copy(incomeDate = null)))
        assertEquals("30일 동안", cashflowPeriod(FORECAST.copy(incomeDate = payday, fallback = true)))
    }

    private companion object {
        val FORECAST_ERROR = ApiError.from(404, "NOT_FOUND")
        val ENTRIES_ERROR: ApiError = ApiError.Network

        val FORECAST = Forecast(
            currentBalance = 100_000,
            balanceAsOfIso = null,
            minBalance = null,
            shortfallFrom = null,
            incomeDate = null,
            incomeAmount = null,
            incomeTitle = "월급",
            from = null,
            to = null,
            fallback = false,
            upcoming = emptyList(),
            plans = emptyList(),
            scheduledOutflow = 0,
            livingCost = null,
            points = emptyList(),
            isEstimate = false,
            accounts = emptyList(),
        )

        /** 안 쓰는 메서드를 부르면 테스트가 실패해야 하므로 Proxy는 예외를 던진다 */
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
