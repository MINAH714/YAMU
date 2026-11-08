package com.yamu.app.feature.forecast

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.MatchCandidates
import com.yamu.app.feature.forecast.data.MatchConfirmed
import com.yamu.app.feature.forecast.data.MatchState
import com.yamu.app.feature.forecast.data.PaymentMatch
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

/** 회차 하나의 결제 연결 — 연타는 한 번만, 응답을 못 받으면 상태를 다시 읽고, 409는 후보를 새로, 해제의 404는 이미 끊긴 것 */
class PaymentMatchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val key = 7010L to LocalDate.of(2026, 11, 5)

    private var status: PaymentMatch? = null
    private var statusCalls = 0
    private var candidateCalls = 0
    private var confirmCalls = 0
    private var deleteCalls = 0
    private var confirmResponse = CompletableDeferred<ApiResult<MatchConfirmed>>()
    private var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)

    private val repo = object : ForecastRepository by unused() {
        override suspend fun matchStatus(itemId: Long, occurrenceDate: LocalDate): ApiResult<PaymentMatch?> {
            statusCalls++
            assertEquals(key, itemId to occurrenceDate)
            return ApiResult.Success(status)
        }
        override suspend fun matchCandidates(itemId: Long, occurrenceDate: LocalDate): ApiResult<MatchCandidates> {
            candidateCalls++
            return ApiResult.Success(MatchCandidates(occurrenceDate, 55_000, null, emptyList()))
        }
        override suspend fun confirmMatch(itemId: Long, occurrenceDate: LocalDate, paymentId: Long): ApiResult<MatchConfirmed> {
            confirmCalls++
            return confirmResponse.await()
        }
        override suspend fun deleteMatch(matchId: Long): ApiResult<Unit> {
            deleteCalls++
            return deleteResult
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `열면 연결 상태를 부른다 — 연결이 없으면 null`() = runTest(dispatcher) {
        val vm = PaymentMatchViewModel(key, repo)
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.match)
        assertEquals(1, statusCalls)
    }

    @Test fun `경로 값이 없으면 아무것도 부르지 않는다`() = runTest(dispatcher) {
        val vm = PaymentMatchViewModel(null, repo)
        vm.openCandidates()
        vm.confirm(5101)
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(0, statusCalls + candidateCalls + confirmCalls)
    }

    @Test fun `후보를 연타해도 연결 요청은 한 번만 나간다`() = runTest(dispatcher) {
        val vm = PaymentMatchViewModel(key, repo)
        advanceUntilIdle()
        vm.openCandidates()
        advanceUntilIdle()

        vm.confirm(5101)
        vm.confirm(5101)
        vm.confirm(5103)
        advanceUntilIdle()
        assertEquals(1, confirmCalls)
        assertTrue(vm.state.value.working)

        status = MATCH
        confirmResponse.complete(ApiResult.Success(MatchConfirmed(801, listOf("다른 카드로 결제했어요"))))
        advanceUntilIdle()

        assertFalse(vm.state.value.working)
        assertFalse(vm.state.value.sheet)
        assertEquals(listOf("다른 카드로 결제했어요"), vm.state.value.warnings)
        assertEquals(MATCH, vm.state.value.match)                 // 연결 뒤 상태를 다시 읽는다
        assertEquals("결제와 연결했어요 · 잔고예보에서 이 회차는 실제 결제로 셉니다", vm.state.value.message)
    }

    @Test fun `연결 응답을 못 받으면 실패로 단정하지 않고 상태를 다시 읽는다`() = runTest(dispatcher) {
        val vm = PaymentMatchViewModel(key, repo)
        advanceUntilIdle()
        vm.openCandidates()
        advanceUntilIdle()

        status = MATCH                                            // 서버에는 반영됨
        vm.confirm(5101)
        confirmResponse.complete(ApiResult.Failure(ApiError.Network))
        advanceUntilIdle()

        assertEquals("응답을 받지 못해 연결 상태를 다시 확인했어요", vm.state.value.message)
        assertEquals(MATCH, vm.state.value.match)
        assertEquals(2, statusCalls)
        assertFalse(vm.state.value.sheet)
        assertEquals(1, candidateCalls)                           // 후보는 다시 부르지 않는다
    }

    @Test fun `409면 다른 상대와 이미 연결 — 후보를 새로 부르고 시트는 그대로`() = runTest(dispatcher) {
        val vm = PaymentMatchViewModel(key, repo)
        advanceUntilIdle()
        vm.openCandidates()
        advanceUntilIdle()

        vm.confirm(5101)
        confirmResponse.complete(ApiResult.Failure(ApiError.from(409, "CASHFLOW_MATCH_CONFLICT")))
        advanceUntilIdle()

        assertEquals("이 회차나 결제가 이미 다른 것과 연결돼 있어요 · 목록을 새로 불러왔어요", vm.state.value.message)
        assertEquals(2, candidateCalls)
        assertTrue(vm.state.value.sheet)
        assertFalse(vm.state.value.working)
    }

    @Test fun `해제는 한 번 더 묻고, 끊으면 다시 예정 지출로 센다고 알린다`() = runTest(dispatcher) {
        status = MATCH
        val vm = PaymentMatchViewModel(key, repo)
        advanceUntilIdle()

        vm.askUnlink()
        assertTrue(vm.state.value.confirmUnlink)
        vm.dismissUnlink()
        assertFalse(vm.state.value.confirmUnlink)
        assertEquals(0, deleteCalls)

        status = null
        vm.askUnlink()
        vm.unlink()
        vm.unlink()
        advanceUntilIdle()

        assertEquals(1, deleteCalls)
        assertNull(vm.state.value.match)
        assertFalse(vm.state.value.confirmUnlink)
        assertEquals("연결을 끊었어요 · 이 회차가 다시 예정 지출로 들어가요", vm.state.value.message)
    }

    @Test fun `해제의 404는 이미 끊긴 연결로 본다`() = runTest(dispatcher) {
        status = MATCH
        val vm = PaymentMatchViewModel(key, repo)
        advanceUntilIdle()

        status = null
        deleteResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        vm.unlink()
        advanceUntilIdle()

        assertNull(vm.state.value.match)
        assertEquals("이미 끊긴 연결이에요", vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test fun `해제가 다른 이유로 실패하면 연결을 그대로 둔다`() = runTest(dispatcher) {
        status = MATCH
        val vm = PaymentMatchViewModel(key, repo)
        advanceUntilIdle()

        deleteResult = ApiResult.Failure(ApiError.from(500, "INTERNAL_ERROR"))
        vm.unlink()
        advanceUntilIdle()

        assertEquals(MATCH, vm.state.value.match)
        assertEquals("잠시 뒤 다시 시도해 주세요", vm.state.value.message)
    }

    private companion object {
        val MATCH = PaymentMatch(801, 5101, "애플짐 역삼점", 55_000, 55_000, MatchState.ACTIVE)

        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
