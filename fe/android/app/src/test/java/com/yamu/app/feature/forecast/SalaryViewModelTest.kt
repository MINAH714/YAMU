package com.yamu.app.feature.forecast

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.*
import com.yamu.app.feature.forecast.data.*
import com.yamu.app.feature.link.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SalaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val input = IncomeInput(3_000_000, 25, 7)

    private class Repo : SalaryRepository {
        var data: ApiResult<SalaryData> = ApiResult.Success(SalaryData(null, listOf(IncomeAccount(7, "계좌", true)), null))
        var createResult: ApiResult<Long> = ApiResult.Success(123)
        var designateResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var updateResult: ApiResult<Unit> = ApiResult.Success(Unit)
        val creates = mutableListOf<Pair<CashflowCreateRequest, String>>()
        val designations = mutableListOf<Long>()
        val updates = mutableListOf<Pair<Long, CashflowUpdateRequest>>()
        override suspend fun load(today: LocalDate) = data
        override suspend fun create(body: CashflowCreateRequest, key: String): ApiResult<Long> { creates += body to key; return createResult }
        override suspend fun designate(id: Long): ApiResult<Unit> { designations += id; return designateResult }
        override suspend fun update(id: Long, body: CashflowUpdateRequest): ApiResult<Unit> { updates += id to body; return updateResult }
    }

    @Test fun `연타해도 생성과 지정은 한 번`() = runTest(dispatcher) {
        val repo = Repo(); val vm = SalaryViewModel(repo, SavedStateHandle())
        vm.load(); advanceUntilIdle(); vm.save(input); vm.save(input); advanceUntilIdle()
        assertEquals(1, repo.creates.size); assertEquals(listOf(123L), repo.designations)
        assertEquals(1, vm.state.value.revision)
    }

    @Test fun `지정만 실패하면 생성 ID로 지정만 재시도`() = runTest(dispatcher) {
        val repo = Repo().apply { designateResult = ApiResult.Failure(ApiError.Network) }
        val vm = SalaryViewModel(repo, SavedStateHandle())
        vm.load(); advanceUntilIdle(); vm.save(input); advanceUntilIdle()
        assertTrue(vm.state.value.locked)
        repo.designateResult = ApiResult.Success(Unit)
        vm.save(input); advanceUntilIdle()
        assertEquals(1, repo.creates.size); assertEquals(listOf(123L, 123L), repo.designations)
    }

    @Test fun `생성 결과 불명은 재생성 후에도 같은 본문과 키`() = runTest(dispatcher) {
        val repo = Repo().apply { createResult = ApiResult.Failure(ApiError.Network) }
        val handle = SavedStateHandle()
        val first = SalaryViewModel(repo, handle)
        first.load(); advanceUntilIdle(); first.save(input); advanceUntilIdle()
        val restored = SalaryViewModel(repo, handle)
        restored.load(); advanceUntilIdle()
        restored.save(input.copy(amount = 99)); advanceUntilIdle()
        assertEquals(1, repo.creates.size)
        repo.createResult = ApiResult.Success(123)
        restored.save(input); advanceUntilIdle()
        assertEquals(repo.creates[0], repo.creates[1])
    }

    @Test fun `조회 실패는 미등록으로 저장하지 않는다`() = runTest(dispatcher) {
        val repo = Repo().apply { data = ApiResult.Failure(ApiError.Network) }
        val vm = SalaryViewModel(repo, SavedStateHandle())
        vm.load(); advanceUntilIdle(); vm.save(input); advanceUntilIdle()
        assertTrue(repo.creates.isEmpty()); assertNotNull(vm.state.value.error)
    }

    @Test fun `생성 뒤 계좌가 없어져도 지정 단계는 재시도하고 새로 만들지 않는다`() = runTest(dispatcher) {
        val repo = Repo().apply { designateResult = ApiResult.Failure(ApiError.Network) }
        val handle = SavedStateHandle()
        val vm = SalaryViewModel(repo, handle)
        vm.load(); advanceUntilIdle(); vm.save(input); advanceUntilIdle()
        repo.data = ApiResult.Success(SalaryData(null, emptyList(), null))
        repo.designateResult = ApiResult.Success(Unit)
        val restored = SalaryViewModel(repo, handle)
        restored.load(); advanceUntilIdle(); restored.save(input); advanceUntilIdle()
        assertEquals(1, repo.creates.size); assertEquals(listOf(123L, 123L), repo.designations)
    }

    @Test fun `잘못된 금액 날짜 다른 계좌는 요청하지 않는다`() = runTest(dispatcher) {
        val repo = Repo(); val vm = SalaryViewModel(repo, SavedStateHandle())
        vm.load(); advanceUntilIdle()
        listOf(input.copy(amount = 0), input.copy(amount = -1), input.copy(day = 32), input.copy(accountId = 9)).forEach { vm.save(it) }
        advanceUntilIdle(); assertTrue(repo.creates.isEmpty())
    }

    private fun entry(date: LocalDate) = CashflowEntry(123, true, "월급", 2_000_000, date, date, CashflowRepeat.MONTHLY, 25, null, 7, null)

    @Test fun `수정은 기존 다음 회차부터 이후 전부이고 생성하지 않는다`() = runTest(dispatcher) {
        val next = entry(LocalDate.now(com.yamu.app.core.format.KST).plusDays(5))
        val repo = Repo().apply { data = ApiResult.Success(SalaryData(SettingsIncomeDto(123, "월급", 2_000_000, 25, 7), listOf(IncomeAccount(7, "계좌", true)), next)) }
        val vm = SalaryViewModel(repo, SavedStateHandle())
        vm.load(); advanceUntilIdle(); vm.save(input); advanceUntilIdle()
        assertTrue(repo.creates.isEmpty()); assertTrue(repo.designations.isEmpty())
        assertEquals(next.occurrenceDate.toString(), repo.updates.single().second.occurrenceDate)
        assertEquals("THIS_AND_FUTURE", repo.updates.single().second.scope)
    }

    @Test fun `수정 결과 불명은 자동 재전송 없이 재조회 요구`() = runTest(dispatcher) {
        val next = entry(LocalDate.now(com.yamu.app.core.format.KST).plusDays(5))
        val repo = Repo().apply {
            data = ApiResult.Success(SalaryData(SettingsIncomeDto(123, "월급", 2_000_000, 25, 7), listOf(IncomeAccount(7, "계좌", true)), next))
            updateResult = ApiResult.Failure(ApiError.Network)
        }
        val vm = SalaryViewModel(repo, SavedStateHandle())
        vm.load(); advanceUntilIdle(); vm.save(input); advanceUntilIdle(); vm.save(input); advanceUntilIdle()
        assertEquals(1, repo.updates.size); assertTrue(vm.state.value.mustReload)
    }

    @Test fun `말일 변경은 짧은 달 말일이고 오늘 회차를 다시 만들지 않는다`() {
        val today = LocalDate.of(2027, 2, 25)
        val patch = salaryPatch(entry(LocalDate.of(2027, 3, 25)), input.copy(day = 31), today)
        assertEquals("2027-02-28", patch.date); assertEquals(31, patch.recurrence?.dayOfMonth)
        assertEquals("2027-03-25", patch.occurrenceDate)
        assertEquals("2027-03-31", input.copy(day = 31).toRequest(LocalDate.of(2027, 2, 28)).date)
    }

    @Test fun `금액만 변경하면 반복 종료일과 기준일은 보내지 않는다`() {
        val patch = salaryPatch(entry(LocalDate.of(2027, 2, 25)).copy(endDate = LocalDate.of(2027, 12, 25)), input, LocalDate.of(2027, 2, 1))
        assertNull(patch.recurrence); assertNull(patch.date)
    }
}
