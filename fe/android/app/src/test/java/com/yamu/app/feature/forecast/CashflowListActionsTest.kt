package com.yamu.app.feature.forecast

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.CashflowRow
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.testForecast
import com.yamu.app.feature.link.data.CashflowCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.lang.reflect.Proxy
import java.time.LocalDate

/** F-02 줄 끝 휴지통 · 확인 전 후보 시트(Task 8.2.73) — 줄마다 연타를 막고, 결과를 모르면 다시 보내지 않고 목록을 새로 받는다 */
class CashflowListActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private var entriesCalls = 0
    private var entriesRange: Pair<LocalDate, LocalDate>? = null
    private var candidatesRange: Pair<LocalDate, LocalDate>? = null
    private var entriesGate: CompletableDeferred<Unit>? = null
    private var candidatesResult: ApiResult<List<CashflowCandidate>> = ApiResult.Success(listOf(NETFLIX))
    private var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    private var pendingDelete: CompletableDeferred<ApiResult<Unit>>? = null
    private val deletes = mutableListOf<Triple<Long, CashflowDeleteScope, LocalDate?>>()
    private var decideResult: ApiResult<Unit> = ApiResult.Success(Unit)
    private val decisions = mutableListOf<Pair<Long, Boolean>>()

    private val repo = object : ForecastRepository by unused() {
        override suspend fun forecast(): ApiResult<Forecast> = ApiResult.Success(testForecast())
        override fun cached(): Forecast? = null
        override fun selection() = ForecastSelection()
        override suspend fun accounts(): ApiResult<List<ForecastAccountOption>> = ApiResult.Success(emptyList())
        override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> {
            entriesCalls++
            entriesRange = from to to
            entriesGate?.await()
            return ApiResult.Success(listOf(RENT, SNACK))
        }
        override suspend fun pastCardEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> = ApiResult.Success(emptyList())
        override suspend fun cashflowCandidates(from: LocalDate, to: LocalDate): ApiResult<List<CashflowCandidate>> {
            candidatesRange = from to to
            return candidatesResult
        }
        override suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit> {
            deletes += Triple(itemId, scope, occurrenceDate)
            return pendingDelete?.await() ?: deleteResult
        }
        override suspend fun decideCandidate(itemId: Long, confirm: Boolean): ApiResult<Unit> {
            decisions += itemId to confirm
            return decideResult
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun loadedViewModel(): ForecastViewModel = ForecastViewModel(repo).also { it.refreshWithEntries() }

    @Test fun `F-02를 불러오면 후보 규칙도 받고, 못 받으면 비워 둔다(후보 줄은 읽기 전용)`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        assertEquals(listOf(NETFLIX), vm.state.value.candidates)

        candidatesResult = ApiResult.Failure(ApiError.Network)
        vm.refreshWithEntries()
        advanceUntilIdle()
        assertNull(vm.state.value.candidates)
        assertEquals(listOf(RENT, SNACK), vm.state.value.entries)   // 위 목록은 그대로
    }

    @Test fun `후보 목록은 회차 목록과 같은 기간(오늘 ~ 다음 월급날)으로 받는다 — 서버 기본 30일이면 그 뒤 후보가 빠진다`() = runTest(dispatcher) {
        loadedViewModel()
        advanceUntilIdle()
        val f = testForecast()
        assertEquals(f.from to (f.incomeDate ?: f.to), candidatesRange)
        assertEquals(entriesRange, candidatesRange)
    }

    @Test fun `휴지통 — 반복 · 1회 가계부 줄은 지우기를, 후보 줄은 빼기를 묻는다`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        vm.askDelete(row(RENT))
        assertEquals(CashflowAsk.Delete(RENT), vm.state.value.ask)
        vm.dismissAsk()
        vm.askDelete(candidateRow())
        assertEquals(CashflowAsk.Ignore(NETFLIX), vm.state.value.ask)
        vm.openCandidate(candidateRow())
        assertEquals(CashflowAsk.Candidate(NETFLIX, NOV_25, -13_500), vm.state.value.ask)
    }

    @Test fun `지우기 성공 — 이번 회차만은 그 회차 날짜를 보내고 알린 뒤 목록을 새로 받는다`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        val before = entriesCalls

        vm.delete(RENT, CashflowDeleteScope.THIS_ONLY)
        advanceUntilIdle()

        assertEquals(listOf(Triple(RENT.itemId, CashflowDeleteScope.THIS_ONLY, RENT.occurrenceDate)), deletes)
        assertEquals("'월세' 11월 30일 회차를 지웠어요", vm.state.value.message)
        assertEquals(before + 1, entriesCalls)
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun `지운 줄 · 정한 후보는 새 목록이 오기 전에 먼저 빠진다 — 남아 있으면 다시 눌러 404를 받는다`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        entriesGate = CompletableDeferred()   // 새로 받는 목록은 아직 오지 않음

        vm.delete(RENT, CashflowDeleteScope.ALL)
        vm.decide(NETFLIX, confirm = false)
        advanceUntilIdle()

        assertEquals(listOf(SNACK), vm.state.value.entries)
        assertEquals(emptyList<CashflowCandidate>(), vm.state.value.candidates)
        entriesGate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun `1회 항목은 ALL로 지우고 회차 날짜는 보내지 않는다 · 받침에 맞춰 을 · 를`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        vm.delete(SNACK, CashflowDeleteScope.ALL)
        advanceUntilIdle()
        assertEquals(Triple(SNACK.itemId, CashflowDeleteScope.ALL, null), deletes.single())
        assertEquals("'간식비'를 지웠어요", vm.state.value.message)
    }

    @Test fun `같은 줄 연타 — 보내는 중이면 다시 보내지 않고 휴지통도 다시 묻지 않는다`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<ApiResult<Unit>>()
        pendingDelete = gate

        vm.delete(RENT, CashflowDeleteScope.ALL)
        advanceUntilIdle()
        vm.delete(RENT, CashflowDeleteScope.ALL)
        vm.askDelete(row(RENT))
        advanceUntilIdle()
        assertEquals(1, deletes.size)
        assertNull(vm.state.value.ask)
        assertTrue(RENT.itemId in vm.state.value.busy)

        // 다른 줄은 막지 않는다
        vm.askDelete(row(SNACK))
        assertEquals(CashflowAsk.Delete(SNACK), vm.state.value.ask)

        gate.complete(ApiResult.Success(Unit))
        advanceUntilIdle()
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun `응답이 끊기면 다시 보내지 않고 목록을 새로 받아 알린다`() = runTest(dispatcher) {
        deleteResult = ApiResult.Failure(ApiError.Network)
        val vm = loadedViewModel()
        advanceUntilIdle()
        val before = entriesCalls

        vm.delete(RENT, CashflowDeleteScope.ALL)
        advanceUntilIdle()

        assertEquals(1, deletes.size)
        assertEquals(before + 1, entriesCalls)
        assertEquals("응답을 받지 못해 지워졌는지 확인하지 못했어요 · 목록을 새로 받았어요", vm.state.value.message)
    }

    @Test fun `결제와 연결된 회차 409는 줄을 집지 않고 까닭을 보이며, 409 · 404 · 400 모두 목록을 새로 받는다`() = runTest(dispatcher) {
        deleteResult = ApiResult.Failure(ApiError.from(409, "PAID_OCCURRENCE_LOCKED"))
        val vm = loadedViewModel()
        advanceUntilIdle()
        var before = entriesCalls

        // 앞으로 전부 — 누른 줄이 아니라 창 안의 다른 회차가 연결돼 있을 수 있다. 목록이 오래됐으면 새 목록이 휴지통을 숨긴다
        vm.delete(RENT, CashflowDeleteScope.ALL)
        advanceUntilIdle()
        assertEquals("결제와 연결된 회차가 있어 지울 수 없어요 · 연결된 회차를 눌러 결제 연결을 먼저 끊어 주세요", vm.state.value.message)
        assertEquals(++before, entriesCalls)

        vm.messageShown()
        deleteResult = ApiResult.Failure(ApiError.from(404, "RESOURCE_NOT_FOUND"))
        vm.delete(RENT, CashflowDeleteScope.THIS_ONLY)
        advanceUntilIdle()
        assertEquals("그사이 바뀌었거나 지워진 항목이에요 · 목록을 새로 받았어요", vm.state.value.message)
        assertEquals(++before, entriesCalls)

        // 400 — 나뉜 규칙의 다른 줄이 앞으로 전부 지우기 뒤 남아 있다가 눌린 경우('이 일정의 회차 날짜가 아닙니다')
        vm.messageShown()
        deleteResult = ApiResult.Failure(ApiError.from(400, "INVALID_REQUEST"))
        vm.delete(RENT, CashflowDeleteScope.THIS_ONLY)
        advanceUntilIdle()
        assertEquals("그사이 바뀌었거나 지워진 항목이에요 · 목록을 새로 받았어요", vm.state.value.message)
        assertEquals(++before, entriesCalls)
    }

    @Test fun `후보 넣기는 CONFIRMED, 빼기는 IGNORED로 보내고 목록을 새로 받는다`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        val before = entriesCalls

        vm.openCandidate(candidateRow())
        vm.decide(NETFLIX, confirm = true)
        advanceUntilIdle()
        assertNull(vm.state.value.ask)   // 누르면 시트를 닫는다
        assertEquals("'넷플릭스'를 고정 지출로 넣었어요", vm.state.value.message)

        vm.decide(NETFLIX, confirm = false)
        advanceUntilIdle()
        assertEquals(listOf(NETFLIX.id to true, NETFLIX.id to false), decisions)
        assertEquals("'넷플릭스'를 뺐어요", vm.state.value.message)
        assertEquals(before + 2, entriesCalls)
    }

    @Test fun `그사이 바뀐 후보(404 · 409)와 결과 불명은 목록을 새로 받고, 서버가 거절한 다른 실패는 그대로 둔다`() = runTest(dispatcher) {
        val vm = loadedViewModel()
        advanceUntilIdle()
        var before = entriesCalls

        for (e in listOf(ApiError.from(409, "CASHFLOW_STATUS_CONFLICT"), ApiError.from(404, "RESOURCE_NOT_FOUND"))) {
            decideResult = ApiResult.Failure(e)
            vm.decide(NETFLIX, confirm = true)
            advanceUntilIdle()
            assertEquals("그사이 바뀐 후보예요 · 목록을 새로 받았어요", vm.state.value.message)
            assertEquals(++before, entriesCalls)
        }

        decideResult = ApiResult.Failure(ApiError.Network)
        vm.decide(NETFLIX, confirm = false)
        advanceUntilIdle()
        assertEquals("응답을 받지 못해 반영됐는지 확인하지 못했어요 · 목록을 새로 받았어요", vm.state.value.message)
        assertEquals(++before, entriesCalls)

        decideResult = ApiResult.Failure(ApiError.from(400, "INVALID_REQUEST"))
        vm.decide(NETFLIX, confirm = false)
        advanceUntilIdle()
        assertEquals(before, entriesCalls)
        assertEquals(4, decisions.size)   // 앞 요청이 끝난 뒤 누른 것이라 모두 나갔다
    }

    private fun row(e: CashflowEntry) = CashflowRow(e.date, e.title, e.signedAmount, e.repeatLabel(), e)

    private fun candidateRow() = CashflowRow(NOV_25, NETFLIX.title, -13_500, null, null, candidate = true, pending = NETFLIX)

    private companion object {
        val TODAY: LocalDate = LocalDate.now(KST)
        val NOV_25: LocalDate = LocalDate.of(2026, 11, 25)
        val RENT_DATE: LocalDate = LocalDate.of(2026, 11, 30)
        val RENT = CashflowEntry(7001, false, "월세", 500_000, RENT_DATE, RENT_DATE, CashflowRepeat.MONTHLY, 31, null, 1, null)
        val SNACK = CashflowEntry(7002, false, "간식비", 12_000, TODAY, TODAY, CashflowRepeat.NONE, null, null, 1, null)
        val NETFLIX = CashflowCandidate(501, false, "넷플릭스", 13_500, 15, "최근 6개월 중 3달 · 마지막 10월 15일")

        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
