package com.yamu.app.feature.performance

import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import com.yamu.app.feature.performance.data.PerfDecision
import com.yamu.app.feature.performance.data.PerfGuide

/** R-03 — 분석 API(#18)가 아직 없는 서버(BE 375 전)에서 '준비 중'으로 끝나지 않고 R-02로 이어지는지(S15P31E103-432) */
class PerfAnalysisViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Repo(private val reply: ApiResult<PerfAnalysis>) : PerformanceRepository by unused() {
        var analysisCalls = 0
        override fun cachedOverview(): PerfOverview? = null
        override suspend fun overview(): ApiResult<PerfOverview> = ApiResult.Failure(ApiError.Network)
        override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> {
            analysisCalls++
            return reply
        }
    }

    private fun TestScope.open(repo: Repo): PerfAnalysisViewModel {
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        return vm
    }

    private fun TestScope.finish(vm: PerfAnalysisViewModel) {
        vm.viewModelScope.cancel()
        runCurrent()
    }

    @Test fun `분석 API가 없으면 오류 대신 준비 안내로 두고 R-02로 이어 줄 상태가 된다`() = runTest(dispatcher) {
        val vm = open(Repo(ApiResult.Failure(ApiError.NotSupported("API_NOT_FOUND"))))
        val s = vm.state.value
        assertFalse(s.loading)
        assertTrue(s.notReady)
        assertFalse(s.noData)
        assertNull("다시 해도 같아서 오류 문장 · 다시 시도를 띄우지 않는다", s.error)
        assertEquals(CARD_ID, s.selectedId)
        finish(vm)
    }

    @Test fun `405도 같은 안내`() = runTest(dispatcher) {
        val vm = open(Repo(ApiResult.Failure(ApiError.NotSupported("METHOD_NOT_ALLOWED"))))
        assertTrue(vm.state.value.notReady)
        finish(vm)
    }

    @Test fun `기록이 부족한 422는 지금처럼 판단 보류`() = runTest(dispatcher) {
        val vm = open(Repo(ApiResult.Failure(ApiError.DataInsufficient("DATA_INSUFFICIENT"))))
        assertTrue(vm.state.value.noData)
        assertFalse(vm.state.value.notReady)
        finish(vm)
    }

    @Test fun `그 밖의 실패는 오류와 다시 시도 — 다시 부르면 안내 상태도 지운다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Failure(ApiError.Network))
        val vm = open(repo)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.notReady)
        vm.load()
        runCurrent()
        assertEquals(2, repo.analysisCalls)
        finish(vm)
    }

    @Test fun `판단 저장 연타는 코루틴 실행 전에도 한 번만 보낸다`() = runTest(dispatcher) {
        val repo = DeferredRepo()
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.decide(PerfDecision.KEEP)
        vm.decide(PerfDecision.SKIP)
        assertTrue(vm.state.value.acting)
        runCurrent()
        assertEquals(1, repo.decisions)
        repo.saved.complete(ApiResult.Success(Unit))
        runCurrent()
        assertEquals(PerfDecision.KEEP, vm.state.value.analysis?.decision)
        assertFalse(vm.state.value.acting)
        finish(vm)
    }

    @Test fun `A 판단 저장 뒤 B로 바꾸면 A 성공과 실패가 B를 덮지 않는다`() = runTest(dispatcher) {
        for (result in listOf(ApiResult.Success(Unit), ApiResult.Failure(ApiError.Network))) {
            val repo = DeferredRepo()
            val vm = PerfAnalysisViewModel(CARD_ID, repo)
            runCurrent()
            vm.decide(PerfDecision.KEEP)
            runCurrent()
            vm.select(22)
            assertNull(vm.state.value.analysis)
            runCurrent()
            repo.saved.complete(result)
            runCurrent()
            assertEquals(22L, vm.state.value.analysis?.userCardId)
            assertNull(vm.state.value.analysis?.decision)
            assertNull(vm.state.value.message)
            assertFalse(vm.state.value.acting)
            finish(vm)
        }
    }

    @Test fun `A에서 B를 거쳐 A로 오면 이전 A 조회 응답도 버린다`() = runTest(dispatcher) {
        val first = CompletableDeferred<ApiResult<PerfAnalysis>>()
        var calls = 0
        val repo = object : PerformanceRepository by unused() {
            override fun cachedOverview(): PerfOverview? = null
            override suspend fun overview(): ApiResult<PerfOverview> = ApiResult.Failure(ApiError.Network)
            override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> {
                calls++
                return if (calls == 1) first.await() else ApiResult.Success(sample(userCardId).copy(version = "new"))
            }
        }
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.select(22); runCurrent()
        vm.select(CARD_ID); runCurrent()
        first.complete(ApiResult.Success(sample(CARD_ID).copy(version = "old")))
        runCurrent()
        assertEquals("new", vm.state.value.analysis?.version)
        finish(vm)
    }

    @Test fun `저장 중 A에서 B를 거쳐 A로 돌아오면 서버 분석을 다시 받는다`() = runTest(dispatcher) {
        val repo = DeferredRepo()
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.decide(PerfDecision.KEEP); runCurrent()
        vm.select(22); runCurrent()
        vm.select(CARD_ID); runCurrent()
        repo.saved.complete(ApiResult.Success(Unit)); runCurrent()
        assertEquals(4, repo.reads)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.acting)
        finish(vm)
    }

    private class DeferredRepo : PerformanceRepository by unused() {
        val saved = CompletableDeferred<ApiResult<Unit>>()
        var decisions = 0
        var reads = 0
        override fun cachedOverview(): PerfOverview? = null
        override suspend fun overview(): ApiResult<PerfOverview> = ApiResult.Failure(ApiError.Network)
        override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> {
            reads++
            return ApiResult.Success(sample(userCardId))
        }
        override suspend fun decide(userCardId: Long, decision: PerfDecision?, analysisVersion: String?): ApiResult<Unit> {
            decisions++
            return saved.await()
        }
    }

    private companion object {
        const val CARD_ID = 11L

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
