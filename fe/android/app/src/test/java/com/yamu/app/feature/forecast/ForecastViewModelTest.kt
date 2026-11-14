package com.yamu.app.feature.forecast

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.testForecast
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

/** F-01 · F-02 — 예보가 실패해도 회차로 목록을 그리고, 기간 · 계좌를 바꾸면 그 값으로 다시 부른다 */
class ForecastViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var cachedForecast: Forecast? = null
    private var forecastResult: ApiResult<Forecast> = ApiResult.Failure(FORECAST_ERROR)
    private var pendingForecast: CompletableDeferred<ApiResult<Forecast>>? = null
    private var forecastCalls = 0
    private var entriesResponse = CompletableDeferred<ApiResult<List<CashflowEntry>>>()
    private var entriesCalls = 0
    private var selected = ForecastSelection()

    private val repo = object : ForecastRepository by unused() {
        override suspend fun forecast(): ApiResult<Forecast> {
            forecastCalls++
            return pendingForecast?.also { pendingForecast = null }?.await() ?: forecastResult
        }
        override fun cached() = cachedForecast
        override fun selection() = selected
        override fun select(selection: ForecastSelection) {
            selected = selection
        }
        override suspend fun accounts(): ApiResult<List<ForecastAccountOption>> = ApiResult.Success(ACCOUNTS)
        override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> {
            entriesCalls++
            return entriesResponse.await()
        }
        override suspend fun pastCardEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> {
            pastRange = from to to
            return pastResult
        }
        override suspend fun cashflowCandidates(from: LocalDate, to: LocalDate) = ApiResult.Success(emptyList<com.yamu.app.feature.link.data.CashflowCandidate>())
    }
    private var pastResult: ApiResult<List<CashflowEntry>> = ApiResult.Success(emptyList())
    private var pastRange: Pair<LocalDate, LocalDate>? = null

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
        cachedForecast = testForecast()
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        advanceUntilIdle()

        assertFalse(vm.state.value.cashflowLoading)
    }

    @Test fun `계좌를 잇지 않았으면 회차를 부르지 않고 빈 상태로 끝난다`() = runTest(dispatcher) {
        forecastResult = ApiResult.Failure(ApiError.from(400, ForecastRepository.NO_ACCOUNT))
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        advanceUntilIdle()

        assertTrue(vm.state.value.noAccount)
        assertFalse(vm.state.value.cashflowLoading)
        assertEquals(0, entriesCalls)
    }

    @Test fun `옛 서버 코드 BASE_ACCOUNT_NOT_SET도 빈 상태`() = runTest(dispatcher) {
        forecastResult = ApiResult.Failure(ApiError.from(404, "BASE_ACCOUNT_NOT_SET"))
        val vm = ForecastViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.noAccount)
    }

    @Test fun `기간 · 계좌를 고르면 기억하고 그 값으로 다시 부른다 — 모두 고르면 전체(all)`() = runTest(dispatcher) {
        forecastResult = ApiResult.Success(testForecast())
        val vm = ForecastViewModel(repo)
        vm.loadAccounts()
        advanceUntilIdle()

        vm.apply(ForecastPeriod.DAYS_14, setOf(2L))
        advanceUntilIdle()
        assertEquals(ForecastSelection(ForecastPeriod.DAYS_14, setOf(2L)), selected)
        assertEquals(selected, vm.state.value.selection)
        assertEquals(1, forecastCalls)

        vm.apply(null, setOf(1L, 2L))
        advanceUntilIdle()
        assertNull(selected.accountIds)
        assertTrue("고르지 않은 기본(기준 계좌)과 나눈다(-484)", selected.all)
        assertEquals(2, forecastCalls)

        vm.apply(null, setOf(1L))   // 하나만 고르면 그 계좌 — 전체 표시는 지운다
        advanceUntilIdle()
        assertEquals(ForecastSelection(null, setOf(1L)), selected)
    }

    @Test fun `계좌 목록이 오기 전에 '이대로 보기' — 전체 · 일부 선택은 그대로, 기간만 바뀐다(!313 리뷰)`() = runTest(dispatcher) {
        forecastResult = ApiResult.Success(testForecast())
        val vm = ForecastViewModel(repo)
        vm.loadAccounts()
        advanceUntilIdle()
        vm.apply(null, setOf(1L, 2L))   // 전체(all)
        advanceUntilIdle()
        assertTrue(selected.all)

        vm.apply(ForecastPeriod.DAYS_7, null)   // 시트가 계좌를 모른 채(불러오는 중 · 실패) 닫힘
        advanceUntilIdle()
        assertEquals(ForecastSelection(ForecastPeriod.DAYS_7, null, all = true), selected)

        vm.apply(null, setOf(2L))
        advanceUntilIdle()
        vm.apply(ForecastPeriod.DAYS_14, null)
        advanceUntilIdle()
        assertEquals("일부 선택도 기준 계좌로 바뀌지 않는다", ForecastSelection(ForecastPeriod.DAYS_14, setOf(2L)), selected)
    }

    @Test fun `다시 부르면 앞 요청의 늦은 결과는 버린다`() = runTest(dispatcher) {
        val slow = CompletableDeferred<ApiResult<Forecast>>()
        pendingForecast = slow
        val vm = ForecastViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        forecastResult = ApiResult.Success(testForecast(horizonDays = 14, isNextIncome = false))
        vm.refresh()
        advanceUntilIdle()
        slow.complete(ApiResult.Success(testForecast(horizonDays = 30, isNextIncome = false)))
        advanceUntilIdle()

        assertEquals(14, vm.state.value.forecast?.horizonDays)
    }

    @Test fun `지난 7일 카드 예정 지출은 어제까지 따로 부른다`() = runTest(dispatcher) {
        val past = CashflowEntry(7010, false, "헬스장", 55_000, YESTERDAY, YESTERDAY, CashflowRepeat.NONE, null, null, null, 11)
        pastResult = ApiResult.Success(listOf(past))
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        entriesResponse.complete(ApiResult.Success(emptyList()))
        advanceUntilIdle()

        val today = LocalDate.now(KST)
        assertEquals(today.minusDays(PAST_MATCH_DAYS) to today.minusDays(1), pastRange)
        assertEquals(listOf(past), vm.state.value.pastEntries)
    }

    @Test fun `지난 회차를 못 받아도 위 목록은 그대로고 지난 목록만 비운다`() = runTest(dispatcher) {
        pastResult = ApiResult.Failure(ApiError.Network)
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        entriesResponse.complete(ApiResult.Success(emptyList()))
        advanceUntilIdle()

        assertEquals(emptyList<CashflowEntry>(), vm.state.value.entries)
        assertNull(vm.state.value.entriesError)
        assertEquals(emptyList<CashflowEntry>(), vm.state.value.pastEntries)
    }

    @Test fun `예정된 돈의 기간 — 월급날 기준이면 월급 전까지, 아니면 실제 일수`() {
        assertEquals("월급 전까지", cashflowPeriod(testForecast()))
        assertEquals("30일 동안", cashflowPeriod(null))
        assertEquals("14일 동안", cashflowPeriod(testForecast(isNextIncome = false, horizonDays = 14)))
        assertEquals("30일 동안", cashflowPeriod(testForecast(isNextIncome = false, horizonDays = null)))
    }

    private companion object {
        val FORECAST_ERROR = ApiError.from(404, "NOT_FOUND")
        val ENTRIES_ERROR: ApiError = ApiError.Network
        val YESTERDAY: LocalDate = LocalDate.now(KST).minusDays(1)
        val ACCOUNTS = listOf(
            ForecastAccountOption(1, "월급 통장", "신한은행", "4021", 412_000, isPrimary = true),
            ForecastAccountOption(2, "생활비 통장", "KB국민은행", "7730", 268_000, isPrimary = false),
        )

        /** 안 쓰는 메서드를 부르면 테스트가 실패해야 하므로 Proxy는 예외를 던진다 */
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
