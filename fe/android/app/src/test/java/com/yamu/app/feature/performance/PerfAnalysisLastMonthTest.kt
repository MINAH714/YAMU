package com.yamu.app.feature.performance

import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.MonthRecognized
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfDetail
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerfTier
import com.yamu.app.feature.performance.data.PerformanceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** R-03 카드 그림 아래 지난달 한 줄 — 지난달 · 구간을 따로 받아도 카드끼리 섞이지 않고, 못 받으면 줄만 숨긴다(S15P31E103-602) */
class PerfAnalysisLastMonthTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Repo(
        var lastMonth: ApiResult<MonthRecognized> = ApiResult.Success(MonthRecognized("2026-10", mapOf(A to 350_000L, B to 120_000L))),
    ) : PerformanceRepository by unused() {
        val askedMonths = mutableListOf<String>()
        val detailCalls = mutableListOf<Long>()
        val waits = mutableMapOf<Long, CompletableDeferred<Unit>>()
        override fun cachedOverview(): PerfOverview = PerfOverview("2026-11", 13, listOf(card(A), card(B), card(C)), emptyList(), null)
        override suspend fun recognizedIn(yearMonth: String): ApiResult<MonthRecognized> {
            askedMonths += yearMonth
            return lastMonth
        }
        override suspend fun detail(userCardId: Long): ApiResult<PerfDetail> {
            detailCalls += userCardId
            waits[userCardId]?.await()
            val thresholds = if (userCardId == A) listOf(300_000L, 500_000L) else listOf(200_000L)
            return ApiResult.Success(
                PerfDetail(userCardId, 11, thresholds.last(), null, 0, thresholds.last(), false, false,
                    thresholds.map { PerfTier("${it / 10_000}만 원 이상", null, null, false, it) }, null),
            )
        }
        override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> = ApiResult.Success(sample(userCardId))
    }

    private fun TestScope.finish(vm: PerfAnalysisViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test fun `서버의 이번 달에서 한 달 전을 묻고 고른 카드 줄을 채운 구간 기준으로 만든다`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = PerfAnalysisViewModel(A, repo)
        runCurrent()
        assertEquals(listOf("2026-10"), repo.askedMonths)
        assertEquals(listOf(A), repo.detailCalls)
        assertEquals("10월 350,000원 · 30만 원 구간 달성 (116%)", vm.state.value.lastMonthOf(A)?.text)
        assertNotNull("분석은 그대로", vm.state.value.analysis)
        finish(vm)
    }

    @Test fun `지난달을 못 받으면 줄만 숨기고 구간도 부르지 않는다`() = runTest(dispatcher) {
        val repo = Repo(lastMonth = ApiResult.Failure(ApiError.Network))
        val vm = PerfAnalysisViewModel(A, repo)
        runCurrent()
        vm.select(B); runCurrent()
        assertNull(vm.state.value.lastMonthOf(A))
        assertNull(vm.state.value.lastMonthOf(B))
        assertEquals(emptyList<Long>(), repo.detailCalls)
        assertEquals(B, vm.state.value.analysis?.userCardId)
        finish(vm)
    }

    @Test fun `지난달 기록이 없는 카드는 구간을 부르지 않고 줄이 없다`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = PerfAnalysisViewModel(C, repo)
        runCurrent()
        assertEquals(emptyList<Long>(), repo.detailCalls)
        assertNull(vm.state.value.lastMonthOf(C))
        finish(vm)
    }

    @Test fun `A 구간이 늦게 와도 B 줄에 붙지 않고 다시 와도 한 번만 부른다`() = runTest(dispatcher) {
        val repo = Repo().apply { waits[A] = CompletableDeferred() }
        val vm = PerfAnalysisViewModel(A, repo)
        runCurrent()
        vm.select(B); runCurrent()
        assertEquals("10월 120,000원 · 20만 원 구간까지 60%", vm.state.value.lastMonthOf(B)?.text)
        assertNull(vm.state.value.lastMonthOf(A))
        vm.select(A); runCurrent()
        assertEquals("받는 중인 A는 다시 부르지 않는다", listOf(A, B), repo.detailCalls)
        repo.waits.getValue(A).complete(Unit); runCurrent()
        assertEquals("10월 350,000원 · 30만 원 구간 달성 (116%)", vm.state.value.lastMonthOf(A)?.text)
        assertEquals("10월 120,000원 · 20만 원 구간까지 60%", vm.state.value.lastMonthOf(B)?.text)
        vm.select(B); runCurrent()
        assertEquals(listOf(A, B), repo.detailCalls)
        finish(vm)
    }

    @Test fun `구간을 못 받으면 그 카드 줄만 숨기고 다음에 고르면 다시 부른다`() = runTest(dispatcher) {
        var fail = true
        val repo = object : PerformanceRepository by Repo() {
            val base = Repo()
            var calls = 0
            override suspend fun detail(userCardId: Long): ApiResult<PerfDetail> {
                calls++
                return if (fail) ApiResult.Failure(ApiError.Network) else base.detail(userCardId)
            }
        }
        val vm = PerfAnalysisViewModel(A, repo)
        runCurrent()
        assertNull(vm.state.value.lastMonthOf(A))
        assertNotNull(vm.state.value.analysis)
        fail = false
        vm.select(B); runCurrent()
        vm.select(A); runCurrent()
        assertEquals(3, repo.calls)
        assertNotNull(vm.state.value.lastMonthOf(A))
        finish(vm)
    }

    private companion object {
        const val A = 11L
        const val B = 22L
        const val C = 33L

        fun card(id: Long) = CardPerf(
            userCardId = id, brand = brandOf("우리카드"), name = "카드$id", target = 300_000, targetLabel = null,
            recognized = 100_000, remaining = 200_000, achieved = false, noTarget = false, guide = PerfGuide.KEEP_NATURAL,
            decision = null, provisional = false, recentChange = null, recentAtIso = null,
        )

        fun sample(id: Long) = PerfAnalysis(
            userCardId = id, month = 11, guide = PerfGuide.KEEP_NATURAL, headline = null,
            target = 300_000, projected = 250_000, shortfall = 50_000, extraBenefit = 5_000,
            benefitNames = emptyList(), steps = emptyList(), movable = null, decision = null,
            isEstimate = true, version = "v1",
        )

        fun unused(): PerformanceRepository = Proxy.newProxyInstance(
            PerformanceRepository::class.java.classLoader,
            arrayOf(PerformanceRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as PerformanceRepository
    }
}
