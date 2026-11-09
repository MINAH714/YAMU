package com.yamu.app.feature.forecast

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowField
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.CashflowUpdateRequest
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.MatchState
import com.yamu.app.feature.forecast.data.PaymentMatch
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** F-02a · F-02b 저장 실패 — 결과 불명(다시 못 보냄)과 실패(다시 보냄), 404가 가리키는 쪽 */
class CashflowCreateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var createResult: ApiResult<Long> = ApiResult.Success(1)
    private var updateResult: ApiResult<Unit> = ApiResult.Success(Unit)

    private val repo = object : ForecastRepository by unused() {
        override suspend fun createItem(form: CashflowForm) = createResult
        override fun cachedEntry(itemId: Long, occurrenceDate: LocalDate) = ONCE
        override suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest) = updateResult
    }
    private val link = object : LinkRepository by unused() {
        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> = ApiResult.Success(LinkedOverview(emptyList(), emptyList(), null))
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.adding(): CashflowCreateViewModel = CashflowCreateViewModel(SavedStateHandle(), repo, link).also {
        advanceUntilIdle()
        it.edit { f -> f.copy(title = "월세", amount = 500_000, date = LocalDate.now().plusDays(1)).withAccount(3) }
    }

    private fun TestScope.editing(): CashflowCreateViewModel =
        CashflowCreateViewModel(SavedStateHandle(mapOf("itemId" to ONCE.itemId, "occurrenceDate" to ONCE.occurrenceDate.toString())), repo, link).also { advanceUntilIdle() }

    @Test fun `코드 없는 504 · 성공 응답 읽기 실패는 결과 불명이라 다시 보내지 못하게 한다`() = runTest(dispatcher) {
        listOf(ApiError.from(504, null), ApiError.from(502, null), ApiError.Unknown("PARSE_ERROR"), ApiError.Network).forEach { e ->
            createResult = ApiResult.Failure(e)
            val vm = adding()
            vm.save()
            advanceUntilIdle()
            assertTrue("$e", vm.state.value.unknownResult)
        }
    }

    @Test fun `서버 코드가 있는 오류는 실패라 다시 보낼 수 있다`() = runTest(dispatcher) {
        listOf(ApiError.from(502, "FIN_API_ERROR"), ApiError.from(500, "INTERNAL_ERROR"), ApiError.from(400, "INVALID_REQUEST")).forEach { e ->
            createResult = ApiResult.Failure(e)
            val vm = adding()
            vm.save()
            advanceUntilIdle()
            assertFalse("$e", vm.state.value.unknownResult)
        }
    }

    @Test fun `수정 중 404 — 계좌를 안 보냈으면 항목이 사라진 것`() = runTest(dispatcher) {
        updateResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val vm = editing()
        vm.edit { it.copy(amount = 550_000) }
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.gone)
        assertNull(vm.state.value.errors[CashflowField.PAYMENT])
    }

    @Test fun `수정 중 404 — 계좌를 바꿔 보냈으면 수단 칸에 알리고 저장을 막지 않는다`() = runTest(dispatcher) {
        updateResult = ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val vm = editing()
        vm.edit { it.withAccount(4) }
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.state.value.gone)
        assertEquals("고른 계좌나 카드를 찾을 수 없어요 · 다른 걸 고르거나 목록에서 다시 확인해 주세요", vm.state.value.errors[CashflowField.PAYMENT])
    }

    @Test fun `결제와 연결된 앞으로의 회차도 지난 회차처럼 저장 · 삭제를 잠그고, 끊으면 다시 연다`() {
        val today = LocalDate.of(2026, 11, 9)
        val tomorrow = CARD.copy(occurrenceDate = today.plusDays(1), date = today.plusDays(1))
        val yesterday = CARD.copy(occurrenceDate = today.minusDays(1), date = today.minusDays(1))
        val matched = PaymentMatch(801, 5101, "애플짐 역삼점", 55_000, 55_000, MatchState.ACTIVE)

        assertEquals(EditLock.MATCHED, editLockOf(tomorrow, today, matched))
        // 결제가 취소돼도 연결을 끊기 전까지 BE는 잠근다
        assertEquals(EditLock.MATCHED, editLockOf(tomorrow, today, matched.copy(state = MatchState.REVIEW_REQUIRED)))
        assertEquals(EditLock.NONE, editLockOf(tomorrow, today, null))
        assertEquals(EditLock.NONE, editLockOf(tomorrow.copy(date = today), today, null))    // 오늘 회차는 아직 고칠 수 있다
        assertEquals(EditLock.PAST, editLockOf(yesterday, today, matched))
        assertEquals(EditLock.PAST, editLockOf(yesterday, today, null))
        assertEquals(EditLock.NONE, editLockOf(null, today, null))                            // 추가 화면
    }

    private companion object {
        val CARD = CashflowEntry(
            itemId = 7010, isIncome = false, title = "헬스장", amount = 55_000, occurrenceDate = LocalDate.of(2026, 11, 10), date = LocalDate.of(2026, 11, 10),
            repeat = CashflowRepeat.NONE, dayOfMonth = null, endDate = null, accountId = null, userCardId = 11,
        )
        val ONCE = LocalDate.now().plusDays(1).let { day ->
            CashflowEntry(
                itemId = 41, isIncome = false, title = "월세", amount = 500_000, occurrenceDate = day, date = day,
                repeat = CashflowRepeat.NONE, dayOfMonth = null, endDate = null, accountId = 3, userCardId = null,
            )
        }

        /** 안 쓰는 메서드를 부르면 테스트가 실패해야 하므로 Proxy는 예외를 던진다 */
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
