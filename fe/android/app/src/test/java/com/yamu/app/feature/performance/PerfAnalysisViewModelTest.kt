package com.yamu.app.feature.performance

import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

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

    private companion object {
        const val CARD_ID = 11L

        fun unused(): PerformanceRepository = Proxy.newProxyInstance(
            PerformanceRepository::class.java.classLoader,
            arrayOf(PerformanceRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as PerformanceRepository
    }
}
