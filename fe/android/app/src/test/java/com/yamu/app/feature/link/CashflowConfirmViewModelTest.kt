package com.yamu.app.feature.link

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowCreateRequest
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowDraft
import com.yamu.app.feature.link.data.CashflowPlan
import com.yamu.app.feature.link.data.CashflowRepository
import com.yamu.app.feature.link.data.IncomeAccount
import com.yamu.app.feature.link.data.IncomeInput
import com.yamu.app.feature.link.data.SavedIncome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/** B-06 — 머리 글이 상태와 맞는지(B-10), 찾은 게 없어도 하루 생활비는 보이는 값만 저장하는지, 월급을 한 번만 등록하는지(-358) */
class CashflowConfirmViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class Repo(private val result: ApiResult<CashflowDraft>) : CashflowRepository {
        var plan: CashflowPlan? = null
            private set
        val incomeCalls = mutableListOf<Pair<CashflowCreateRequest, String>>()
        var incomeResults = ArrayDeque<ApiResult<Long>>()
        var confirmResults = ArrayDeque<ApiResult<Unit>>()

        override suspend fun draft() = result

        override suspend fun createIncome(body: CashflowCreateRequest, idempotencyKey: String): ApiResult<Long> {
            incomeCalls += body to idempotencyKey
            return incomeResults.removeFirstOrNull() ?: ApiResult.Success(900)
        }

        override suspend fun confirm(plan: CashflowPlan): ApiResult<Unit> {
            this.plan = plan
            return confirmResults.removeFirstOrNull() ?: ApiResult.Success(Unit)
        }
    }

    private val accounts = listOf(IncomeAccount(1, "월급 통장 ··1234", isPrimary = false), IncomeAccount(2, "주 계좌 ··5678", isPrimary = true))

    /** 지금 서버 — 반복 지출만 후보로 오고 월급 후보는 없다(BE -349) */
    private val expensesOnly = CashflowDraft(
        income = null,
        expenses = listOf(CashflowCandidate(302, false, "넷플릭스", 17_000, 5, "최근 6개월 중 3달 · 마지막 10월 5일")),
        bills = emptyList(),
        dailyLivingCost = 18_000,
        incomeAccounts = accounts,
    )

    /** 옛 서버처럼 수입 후보가 오는 경우 */
    private val withIncomeCandidate = CashflowDraft(
        income = CashflowCandidate(301, isIncome = true, title = "월급", amount = 2_800_000, dayOfMonth = 10),
        expenses = listOf(CashflowCandidate(302, false, "월세", 500_000, 31)),
        bills = emptyList(),
        dailyLivingCost = 18_000,
        incomeAccounts = accounts,
    )

    private fun empty(dailyLivingCost: Long?, incomeAccounts: List<IncomeAccount> = emptyList()) =
        CashflowDraft(null, emptyList(), emptyList(), dailyLivingCost, incomeAccounts = incomeAccounts)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `불러오는 중에는 찾고 있다고만 쓴다`() = runTest(dispatcher) {
        val headline = CashflowConfirmViewModel(Repo(ApiResult.Success(expensesOnly))).state.value.headline

        assertEquals("고정 지출을\n찾고 있어요", headline.title)
        assertNull(headline.sub)
    }

    @Test fun `서버가 찾지 않는 월급은 찾았다고 쓰지 않고 직접 넣게 한다`() = runTest(dispatcher) {
        val vm = CashflowConfirmViewModel(Repo(ApiResult.Success(expensesOnly)))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("고정 지출을\n채워 뒀어요", "다르면 눌러서 고치고 월급은 직접 넣어 주세요"), vm.state.value.headline)
        assertEquals(18_000L, vm.state.value.dailyCost)

        vm.saveIncome(IncomeInput(2_500_000, 25, 2))
        assertEquals(CashflowHeadline("고정 지출을\n채워 뒀어요", "다르면 눌러서 고쳐요"), vm.state.value.headline)
    }

    @Test fun `수입 후보가 오면 월급과 고정 지출을 채워 뒀다고 쓴다`() = runTest(dispatcher) {
        val vm = CashflowConfirmViewModel(Repo(ApiResult.Success(withIncomeCandidate)))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("월급과 고정 지출을\n채워 뒀어요", "다르면 눌러서 고쳐요"), vm.state.value.headline)
        assertFalse(vm.state.value.draft!!.canEnterIncome)
    }

    @Test fun `찾은 게 없으면 채워 뒀다고 쓰지 않고 월급을 넣을 수 있으면 그걸 권한다`() = runTest(dispatcher) {
        val noAccount = CashflowConfirmViewModel(Repo(ApiResult.Success(empty(dailyLivingCost = null))))
        val withAccount = CashflowConfirmViewModel(Repo(ApiResult.Success(empty(dailyLivingCost = null, incomeAccounts = accounts))))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("찾은 고정 지출이\n아직 없어요", "나중에 잔고예보에서 넣을 수 있어요"), noAccount.state.value.headline)
        assertEquals(CashflowHeadline("찾은 고정 지출이\n아직 없어요", "월급을 넣어 두면 월급날까지 예보해 드려요"), withAccount.state.value.headline)
    }

    @Test fun `못 불러오면 실패 머리 — 이유와 다시 시도는 아래 오류 칸에`() = runTest(dispatcher) {
        val vm = CashflowConfirmViewModel(Repo(ApiResult.Failure(ApiError.Network)))
        advanceUntilIdle()

        assertEquals(CashflowHeadline("고정 지출을\n불러오지 못했어요", null), vm.state.value.headline)
        assertNotNull(vm.state.value.loadError)
    }

    @Test fun `찾은 게 없어도 화면에 보이는 하루 생활비는 저장한다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(empty(dailyLivingCost = 18_000)))
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        assertEquals(CashflowPlan(decisions = emptyList(), incomeId = null, dailyLivingCost = 18_000), repo.plan)
        assertTrue(repo.incomeCalls.isEmpty())
        assertTrue(vm.state.value.saved)
    }

    @Test fun `찾은 게 없고 하루 생활비도 없으면 아무것도 보내지 않고 넘어간다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(empty(dailyLivingCost = null, incomeAccounts = accounts)))
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        assertNull(repo.plan)
        assertTrue(vm.state.value.saved)
    }

    @Test fun `넣은 월급은 매월 예정 수입으로 등록하고 그 ID를 잔고예보 월급으로 지정한다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(expensesOnly))
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()

        vm.saveIncome(IncomeInput(2_500_000, 31, 1))
        vm.confirm()
        advanceUntilIdle()

        val (body, _) = repo.incomeCalls.single()
        assertEquals("INCOME", body.type)
        assertEquals("월급", body.title)
        assertEquals(2_500_000L, body.amount)
        assertEquals("MONTHLY", body.recurrence?.frequency)
        assertEquals(31, body.recurrence?.dayOfMonth)
        assertEquals(1L, body.accountId)
        assertEquals(900L, repo.plan?.createdIncomeId)
        assertTrue(vm.state.value.saved)
    }

    @Test fun `등록 응답을 못 받으면 월급 칸을 잠그고 다시 누르면 같은 키 · 같은 본문으로 보낸다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(expensesOnly)).apply { incomeResults.add(ApiResult.Failure(ApiError.Network)) }
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()
        vm.saveIncome(IncomeInput(2_500_000, 25, 2))

        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.state.value.incomeLocked)
        assertEquals("응답을 받지 못했어요 · 다시 눌러도 월급은 한 번만 들어가요", vm.state.value.saveError)
        assertNull(repo.plan)   // 월급 결과를 모르면 뒤 단계로 가지 않는다

        vm.saveIncome(IncomeInput(3_000_000, 10, 1))   // 잠겼으면 바꾸지 않는다
        assertEquals(IncomeInput(2_500_000, 25, 2), vm.state.value.newIncome)

        vm.confirm()
        advanceUntilIdle()
        assertEquals(2, repo.incomeCalls.size)
        assertEquals(repo.incomeCalls[0], repo.incomeCalls[1])   // 키와 본문 모두 같다
        assertTrue(vm.state.value.saved)
    }

    @Test fun `월급은 등록됐는데 뒤 단계가 실패하면 다시 눌러도 월급을 다시 만들지 않는다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(expensesOnly)).apply { confirmResults.add(ApiResult.Failure(ApiError.Server("INTERNAL_ERROR"))) }
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()
        vm.saveIncome(IncomeInput(2_500_000, 25, 2))

        vm.confirm()
        advanceUntilIdle()
        assertNotNull(vm.state.value.saveError)
        assertTrue(vm.state.value.incomeLocked)

        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, repo.incomeCalls.size)
        assertEquals(900L, repo.plan?.createdIncomeId)
        assertTrue(vm.state.value.saved)
    }

    @Test fun `서버가 거절한 등록은 잠그지 않아 고쳐서 다시 보낼 수 있다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(expensesOnly)).apply { incomeResults.add(ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))) }
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()
        vm.saveIncome(IncomeInput(2_500_000, 25, 2))

        vm.confirm()
        advanceUntilIdle()
        assertFalse(vm.state.value.incomeLocked)

        vm.saveIncome(IncomeInput(2_500_000, 25, 1))
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1L, repo.incomeCalls[1].first.accountId)
        assertEquals(repo.incomeCalls[0].second, repo.incomeCalls[1].second)   // 기록이 안 남는 실패라 같은 키여도 새로 만든다
        assertTrue(vm.state.value.saved)
    }

    @Test fun `정해 둔 월급이 있으면 새로 넣지 않는다`() = runTest(dispatcher) {
        val repo = Repo(ApiResult.Success(expensesOnly.copy(savedIncome = SavedIncome(77, "월급", 2_000_000, 25))))
        val vm = CashflowConfirmViewModel(repo)
        advanceUntilIdle()

        assertFalse(vm.state.value.draft!!.canEnterIncome)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(repo.incomeCalls.isEmpty())
        assertNull(repo.plan?.createdIncomeId)
    }

    @Test fun `월급 칸 검사 — 금액은 1원 ~ 1,000억 원, 날짜는 1~31`() {
        assertEquals("금액을 넣어 주세요", incomeAmountError(""))
        assertEquals("1원 이상 넣어 주세요", incomeAmountError("0"))
        assertEquals("1,000억 원까지 넣을 수 있어요", incomeAmountError("100000000001"))
        assertNull(incomeAmountError("2500000"))
        assertEquals("1~31 사이로 넣어 주세요", incomeDayError("0"))
        assertEquals("1~31 사이로 넣어 주세요", incomeDayError("32"))
        assertNull(incomeDayError("31"))
    }
}
