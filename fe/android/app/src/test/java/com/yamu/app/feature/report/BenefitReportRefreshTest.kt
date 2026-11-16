package com.yamu.app.feature.report

import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.report.data.BenefitReport
import com.yamu.app.feature.report.data.ReportMonth
import com.yamu.app.feature.report.data.ReportRepository
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
import java.time.YearMonth

class BenefitReportRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val month = YearMonth.of(2026, 11)
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `첫 진입은 한 번만 조회하고 취소 후 같은 달 복귀도 최신 집계를 받는다`() = runTest(dispatcher) {
        var calls = 0
        val vm = BenefitReportViewModel(repo { calls++; ApiResult.Success(report(if (calls == 1) 1_000 else 0)) }, ReportMonth { month })
        runCurrent()
        assertEquals(0, calls)
        vm.resume(); runCurrent()
        assertEquals(1, calls)
        assertEquals(1_000L, vm.state.value.report?.received)
        vm.resume(); runCurrent()
        assertEquals(2, calls)
        assertEquals(0L, vm.state.value.report?.received)
        assertEquals(month, vm.state.value.shown.month)
        vm.viewModelScope.cancel()
    }

    @Test fun `기존 값과 빈 집계 모두 갱신 실패를 알리고 재시도로 복구한다`() = runTest(dispatcher) {
        for (amount in listOf(1_000L, 0L)) {
            var reply: ApiResult<BenefitReport> = ApiResult.Success(report(amount))
            val vm = BenefitReportViewModel(repo { reply }, ReportMonth { month })
            vm.resume(); runCurrent()
            reply = ApiResult.Failure(ApiError.Network)
            vm.resume(); runCurrent()
            assertEquals(amount, vm.state.value.report?.received)
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
            reply = ApiResult.Success(report(2_000))
            vm.load(); runCurrent()
            assertEquals(2_000L, vm.state.value.report?.received)
            assertNull(vm.state.value.error)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `취소를 무시하는 이전 달 응답도 월을 왕복한 뒤 새 집계를 덮지 않는다`() = runTest(dispatcher) {
        val old = CompletableDeferred<ApiResult<BenefitReport>>()
        var calls = 0
        val vm = BenefitReportViewModel(repo {
            if (++calls == 1) withContext(NonCancellable) { old.await() } else ApiResult.Success(report(500))
        }, ReportMonth { month })
        vm.resume(); runCurrent()
        vm.select(month.minusMonths(1)); runCurrent()
        vm.select(month); runCurrent()
        old.complete(ApiResult.Success(report(1_000))); runCurrent()
        assertEquals(month, vm.state.value.shown.month)
        assertEquals(500L, vm.state.value.report?.received)
        assertNull(vm.state.value.error)
        vm.viewModelScope.cancel()
    }

    @Test fun `최초 조회 실패는 재시도 가능하고 다른 달에 이전 값을 보이지 않는다`() = runTest(dispatcher) {
        var reply: ApiResult<BenefitReport> = ApiResult.Success(report(1_000))
        val vm = BenefitReportViewModel(repo { reply }, ReportMonth { month })
        vm.resume(); runCurrent()
        reply = ApiResult.Failure(ApiError.Network)
        vm.select(month.minusMonths(1)); runCurrent()
        assertNull(vm.state.value.report)
        assertNotNull(vm.state.value.error)
        reply = ApiResult.Success(report(500))
        vm.load(); runCurrent()
        assertEquals(500L, vm.state.value.report?.received)
        assertNull(vm.state.value.error)
        vm.viewModelScope.cancel()
    }

    private fun report(amount: Long) = BenefitReport("11월", amount, null, 0, emptyList(), emptyList())
    private fun repo(load: suspend () -> ApiResult<BenefitReport>) = object : ReportRepository by unused() {
        override suspend fun benefits(month: YearMonth) = load()
    }
    private fun unused(): ReportRepository = Proxy.newProxyInstance(
        ReportRepository::class.java.classLoader, arrayOf(ReportRepository::class.java),
    ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as ReportRepository
}
