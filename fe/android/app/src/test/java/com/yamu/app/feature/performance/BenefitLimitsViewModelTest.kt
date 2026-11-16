package com.yamu.app.feature.performance

import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.CardsOnlyLink
import com.yamu.app.feature.performance.data.BenefitLimits
import com.yamu.app.feature.performance.data.PerformanceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.lang.reflect.Proxy

class BenefitLimitsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `최초 화면 복귀 조회는 한 번만 하고 결제 후 복귀 때 새 한도를 받는다`() = runTest(dispatcher) {
        var calls = 0
        val vm = BenefitLimitsViewModel(repo { calls++; ApiResult.Success(limits(if (calls == 1) 10_000 else 7_000)) }, CardsOnlyLink())
        runCurrent()
        assertEquals(0, calls)
        vm.load(); runCurrent()
        assertEquals(1, calls)
        assertEquals(10_000L, vm.state.value.limits?.remaining)
        vm.load(); runCurrent()
        assertEquals(2, calls)
        assertEquals(7_000L, vm.state.value.limits?.remaining)
        vm.viewModelScope.cancel()
    }

    @Test fun `재조회 실패는 마지막 한도와 경고를 남기고 재시도 성공은 경고를 지운다`() = runTest(dispatcher) {
        var reply: ApiResult<BenefitLimits> = ApiResult.Success(limits(10_000))
        val vm = BenefitLimitsViewModel(repo { reply }, CardsOnlyLink())
        vm.load(); runCurrent()
        reply = ApiResult.Failure(ApiError.Network)
        vm.load(); runCurrent()
        assertEquals(10_000L, vm.state.value.limits?.remaining)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        reply = ApiResult.Success(limits(5_000))
        vm.load(); runCurrent()
        assertEquals(5_000L, vm.state.value.limits?.remaining)
        assertNull(vm.state.value.error)
        vm.viewModelScope.cancel()
    }

    @Test fun `취소를 무시하고 늦게 온 이전 조회도 새 값을 덮지 않는다`() = runTest(dispatcher) {
        val old = CompletableDeferred<ApiResult<BenefitLimits>>()
        var calls = 0
        val vm = BenefitLimitsViewModel(repo {
            if (++calls == 1) withContext(NonCancellable) { old.await() } else ApiResult.Success(limits(3_000))
        }, CardsOnlyLink())
        vm.load(); runCurrent()
        vm.load(); runCurrent()
        old.complete(ApiResult.Success(limits(10_000))); runCurrent()
        assertEquals(3_000L, vm.state.value.limits?.remaining)
        assertFalse(vm.state.value.loading)
        vm.viewModelScope.cancel()
    }

    @Test fun `최초 오류와 카드 없는 정상 응답을 구분한다`() = runTest(dispatcher) {
        var reply: ApiResult<BenefitLimits> = ApiResult.Failure(ApiError.Network)
        val vm = BenefitLimitsViewModel(repo { reply }, CardsOnlyLink())
        vm.load(); runCurrent()
        assertNull(vm.state.value.limits)
        assertNotNull(vm.state.value.error)
        reply = ApiResult.Success(limits(0))
        vm.load(); runCurrent()
        assertTrue(vm.state.value.limits!!.cards.isEmpty())
        assertNull(vm.state.value.error)
        vm.viewModelScope.cancel()
    }

    private fun repo(load: suspend () -> ApiResult<BenefitLimits>) = object : PerformanceRepository by unused() {
        override suspend fun limits() = load()
    }

    private fun limits(remaining: Long) = BenefitLimits(11, remaining, 0, emptyList())

    private fun unused(): PerformanceRepository = Proxy.newProxyInstance(
        PerformanceRepository::class.java.classLoader, arrayOf(PerformanceRepository::class.java),
    ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as PerformanceRepository
}
