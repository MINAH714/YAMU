package com.yamu.app.feature.link

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.HomeTodo
import com.yamu.app.feature.home.data.SyncResult
import com.yamu.app.feature.home.data.TodayCard
import com.yamu.app.feature.home.data.TodoTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `그릴 것이 있으면 나왔다고 쓴다`() = runTest(dispatcher) {
        val withTodo = summary.copy(todos = listOf(HomeTodo("통신비만 우리카드로", "실적 달성 추천", TodoTone.Primary, 1)))
        val vm = FirstAnalysisViewModel(Home(ApiResult.Success(withTodo)))
        advanceUntilIdle()

        assertFalse(vm.state.value.nothingToShow)
        assertEquals("첫 분석이 나왔어요", vm.state.value.title)
    }

    /** S15P31E103-435 — 예상 잔액 · 할 일 · 오늘 쓸 카드가 모두 없으면 제목 아래가 비지 않게 */
    @Test fun `받았지만 그릴 것이 없으면 거래가 적다고 쓴다`() = runTest(dispatcher) {
        val vm = FirstAnalysisViewModel(Home(ApiResult.Success(summary)))
        advanceUntilIdle()

        assertTrue(vm.state.value.nothingToShow)
        assertEquals("아직 분석할 거래가 적어요", vm.state.value.title)
    }

    @Test fun `오늘 쓸 카드 하나만 있어도 빈 결과가 아니다`() = runTest(dispatcher) {
        val vm = FirstAnalysisViewModel(Home(ApiResult.Success(summary.copy(todayCard = TodayCard("KB국민카드", null)))))
        advanceUntilIdle()

        assertFalse(vm.state.value.nothingToShow)
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
