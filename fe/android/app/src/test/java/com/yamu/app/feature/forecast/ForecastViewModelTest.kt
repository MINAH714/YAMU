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
        override suspend fun saveAlertThreshold(amount: Long): ApiResult<Unit> {
            savedThresholds += amount
            return pendingSave?.also { pendingSave = null }?.await() ?: saveResult
        }
    }
    private val savedThresholds = mutableListOf<Long>()
    private var saveResult: ApiResult<Unit> = ApiResult.Success(Unit)
    private var pendingSave: CompletableDeferred<ApiResult<Unit>>? = null
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

    @Test fun `지난 예보가 남아 있어도 회차 응답 전에는 불러오는 중이다 — 예보 줄만 그렸다가 모양이 바뀌지 않게`() = runTest(dispatcher) {
        cachedForecast = testForecast()
        forecastResult = ApiResult.Success(testForecast())
        val vm = ForecastViewModel(repo)
        assertTrue(vm.state.value.cashflowLoading)   // 화면이 처음 그려질 때(새로 부르기 전)도

        vm.refreshWithEntries()
        advanceUntilIdle()
        assertTrue(vm.state.value.cashflowLoading)   // 예보는 왔지만 회차는 아직

        entriesResponse.complete(ApiResult.Success(emptyList()))
        advanceUntilIdle()
        assertFalse(vm.state.value.cashflowLoading)
    }

    @Test fun `회차를 못 받으면 받아 둔 예보 줄로 그린다`() = runTest(dispatcher) {
        cachedForecast = testForecast()
        forecastResult = ApiResult.Success(testForecast())
        val vm = ForecastViewModel(repo)
        vm.refreshWithEntries()
        entriesResponse.complete(ApiResult.Failure(ENTRIES_ERROR))
        advanceUntilIdle()

        assertFalse(vm.state.value.cashflowLoading)
        assertNull(vm.state.value.entries)
        assertEquals(ENTRIES_ERROR.userMessage(), vm.state.value.entriesError)
        assertTrue(vm.state.value.forecast != null)
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

    // ── 안전 잔액(S15P31E103-593) ──

    @Test fun `안전 잔액을 저장하면 시트를 닫고 알린 뒤 예보를 다시 받는다 — 0원도 보낸다`() = runTest(dispatcher) {
        forecastResult = ApiResult.Success(testForecast())
        val vm = ForecastViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        val before = forecastCalls

        vm.openThreshold()
        vm.saveThreshold(0)
        advanceUntilIdle()

        assertEquals(listOf(0L), savedThresholds)
        val s = vm.state.value
        assertFalse(s.thresholdOpen)
        assertFalse(s.thresholdSaving)
        assertEquals("안전 잔액을 0원으로 바꿨어요", s.message)
        assertEquals(before + 1, forecastCalls)
    }

    @Test fun `보내는 중에 다시 눌러도 한 번만 보낸다`() = runTest(dispatcher) {
        val gate = CompletableDeferred<ApiResult<Unit>>()
        pendingSave = gate
        val vm = ForecastViewModel(repo)
        vm.openThreshold()
        vm.saveThreshold(200_000)
        vm.saveThreshold(200_000)
        advanceUntilIdle()
        vm.saveThreshold(300_000)
        advanceUntilIdle()
        assertEquals(listOf(200_000L), savedThresholds)
        assertTrue(vm.state.value.thresholdSaving)

        gate.complete(ApiResult.Success(Unit))
        advanceUntilIdle()
        assertFalse(vm.state.value.thresholdSaving)
        assertEquals(listOf(200_000L), savedThresholds)
    }

    @Test fun `서버가 거절하면 시트를 열어 둔 채 까닭을 보이고 예보는 다시 받지 않는다`() = runTest(dispatcher) {
        val rejected = ApiError.from(400, "INVALID_REQUEST")
        saveResult = ApiResult.Failure(rejected)
        val vm = ForecastViewModel(repo)
        vm.openThreshold()
        vm.saveThreshold(100_000)
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.thresholdOpen)
        assertFalse(s.thresholdSaving)
        assertEquals(rejected.userMessage(), s.thresholdError)
        assertNull(s.message)
        assertEquals(0, forecastCalls)
    }

    @Test fun `응답을 못 받으면 결과를 모른다고 알리고 예보를 다시 받아 지금 값을 보인다`() = runTest(dispatcher) {
        saveResult = ApiResult.Failure(ApiError.Network)
        val vm = ForecastViewModel(repo)
        vm.openThreshold()
        vm.saveThreshold(100_000)
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.thresholdOpen)
        assertTrue(s.thresholdError!!.startsWith("응답을 받지 못해 저장됐는지 확인하지 못했어요"))
        assertEquals(1, forecastCalls)
    }

    @Test fun `저장 중에 시트를 닫았다가 거절되면 스낵바로 알린다`() = runTest(dispatcher) {
        val gate = CompletableDeferred<ApiResult<Unit>>()
        pendingSave = gate
        val vm = ForecastViewModel(repo)
        vm.openThreshold()
        vm.saveThreshold(100_000)
        advanceUntilIdle()
        vm.closeThreshold()
        val rejected = ApiError.from(400, "INVALID_REQUEST")
        gate.complete(ApiResult.Failure(rejected))
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.thresholdOpen)
        assertNull(s.thresholdError)
        assertEquals(rejected.userMessage(), s.message)
    }

    @Test fun `0원 아래 · 20억 원 넘는 값은 보내지 않는다`() = runTest(dispatcher) {
        val vm = ForecastViewModel(repo)
        vm.openThreshold()
        vm.saveThreshold(-1)
        vm.saveThreshold(2_000_000_001)
        advanceUntilIdle()
        assertTrue(savedThresholds.isEmpty())
        assertFalse(vm.state.value.thresholdSaving)
    }

    @Test fun `시트 설명은 지금 값을 만 · 억 단위로 덧붙인다`() {
        val lead = "잔액이 이 금액 아래로 내려가는 날을 미리 보여 드려요. 잔액 부족 알림도 이 금액이 기준이에요."
        assertEquals("$lead 지금은 30만 원이에요.", safeBalanceNote(300_000))
        assertEquals("$lead 지금은 0원이에요.", safeBalanceNote(0))
        assertEquals(lead, safeBalanceNote(null))
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
