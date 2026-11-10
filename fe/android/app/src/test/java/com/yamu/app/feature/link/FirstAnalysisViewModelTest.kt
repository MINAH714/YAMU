package com.yamu.app.feature.link

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** B-07 — 실패했는데 "첫 분석이 나왔어요"가 보이지 않는다(B-09). `GET /api/home`이 아직 없어 release에선 404가 온다 */
class FirstAnalysisViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class Home(private val result: ApiResult<HomeSummary>) : HomeRepository {
        override suspend fun loadHome() = result
        override suspend fun sync(): ApiResult<SyncResult> = error("B-07은 새로고침하지 않는다")
    }

    private val summary = HomeSummary(
        forecast = null,
        recentPayment = null,
        todos = emptyList(),
        cardCount = 1,
        unreadCount = 0,
        needsLinking = false,
        syncedAtIso = null,
        failedAreas = emptySet(),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `불러오는 중`() = runTest(dispatcher) {
        assertEquals("야무가 계산 중이에요", FirstAnalysisViewModel(Home(ApiResult.Success(summary))).state.value.title)
    }

    @Test fun `받으면 나왔다고 쓴다`() = runTest(dispatcher) {
        val vm = FirstAnalysisViewModel(Home(ApiResult.Success(summary)))
        advanceUntilIdle()

        assertEquals("첫 분석이 나왔어요", vm.state.value.title)
    }

    @Test fun `홈 API가 없어 404가 와도 나왔다고 쓰지 않는다`() = runTest(dispatcher) {
        val vm = FirstAnalysisViewModel(Home(ApiResult.Failure(ApiError.NotFound("API_NOT_FOUND"))))
        advanceUntilIdle()

        assertTrue(vm.state.value.failed)
        assertEquals("첫 분석을 불러오지 못했어요", vm.state.value.title)
    }

    @Test fun `연결이 끊겨도 같은 실패 제목`() = runTest(dispatcher) {
        val vm = FirstAnalysisViewModel(Home(ApiResult.Failure(ApiError.Network)))
        advanceUntilIdle()

        assertEquals("첫 분석을 불러오지 못했어요", vm.state.value.title)
    }
}
