package com.yamu.app.feature.link

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowDraft
import com.yamu.app.feature.link.data.CashflowPlan
import com.yamu.app.feature.link.data.CashflowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** B-06 — 머리 글이 상태와 맞는지(B-10), 찾은 게 없어도 하루 생활비는 보이는 값만 저장하는지 */
class CashflowConfirmViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class Repo(private val result: ApiResult<CashflowDraft>) : CashflowRepository {
        var plan: CashflowPlan? = null
            private set

        override suspend fun draft() = result

        override suspend fun confirm(plan: CashflowPlan): ApiResult<Unit> {
            this.plan = plan
            return ApiResult.Success(Unit)
        }
    }

    private val filled = CashflowDraft(
        income = CashflowCandidate(301, isIncome = true, title = "월급", amount = 2_800_000, dayOfMonth = 10),
        expenses = listOf(CashflowCandidate(302, false, "월세", 500_000, 31)),
        bills = emptyList(),
        dailyLivingCost = 18_000,
    )

    private fun empty(dailyLivingCost: Long?) = CashflowDraft(null, emptyList(), emptyList(), dailyLivingCost)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `불러오는 중에는 찾고 있다고만 쓴다`() = runTest(dispatcher) {
        val headline = CashflowConfirmViewModel(Repo(ApiResult.Success(filled))).state.value.headline

        assertEquals("월급과 고정 지출을\n찾고 있어요", headline.title)
        assertNull(headline.sub)
    }

    @Test fun `찾은 게 있으면 채워 뒀다고 쓰고 제안한 하루 생활비를 넣어 둔다`() = runTest(dispatcher) {
        val vm = CashflowConfirmViewModel(Repo(ApiResult.Success(filled)))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("월급과 고정 지출을\n채워 뒀어요", "다르면 눌러서 고쳐요"), vm.state.value.headline)
        assertEquals(18_000L, vm.state.value.dailyCost)
    }

    @Test fun `찾은 게 없으면 채워 뒀다고 쓰지 않는다`() = runTest(dispatcher) {
        val vm = CashflowConfirmViewModel(Repo(ApiResult.Success(empty(dailyLivingCost = null))))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("찾은 월급과 고정 지출이\n아직 없어요", "나중에 잔고예보에서 넣을 수 있어요"), vm.state.value.headline)
    }

    @Test fun `못 불러오면 실패 머리 — 이유와 다시 시도는 아래 오류 칸에`() = runTest(dispatcher) {
        val vm = CashflowConfirmViewModel(Repo(ApiResult.Failure(ApiError.Network)))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("월급과 고정 지출을\n불러오지 못했어요", null), vm.state.value.headline)
        assertNotNull(vm.state.value.loadError)
    }

    @Test fun `찾은 게 없어도 화면에 보이는 하루 생활비는 저장한다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(empty(dailyLivingCost = 18_000)))
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        assertEquals(CashflowPlan(decisions = emptyList(), incomeId = null, dailyLivingCost = 18_000), repo.plan)
        assertTrue(vm.state.value.saved)
    }

    @Test fun `찾은 게 없고 하루 생활비도 없으면 아무것도 보내지 않고 넘어간다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(empty(dailyLivingCost = null)))
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        assertNull(repo.plan)
        assertTrue(vm.state.value.saved)
    }
}
