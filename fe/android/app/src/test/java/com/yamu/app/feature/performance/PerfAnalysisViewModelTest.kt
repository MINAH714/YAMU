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
import com.yamu.app.feature.performance.data.MovableItem

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

    @Test fun `지출 이동 중 출발 카드로 전환하면 이동 뒤 그 카드의 월말 예상도 갱신한다`() = runTest(dispatcher) {
        val repo = MoveRefreshRepo()
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.moveAndFill(); runCurrent()
        vm.select(22); runCurrent()
        assertEquals(250_000L, vm.state.value.analysis?.projected)
        repo.move.complete(Unit); runCurrent()
        assertEquals(CARD_ID, repo.decidedId)
        assertEquals(22L, vm.state.value.analysis?.userCardId)
        assertEquals(200_000L, vm.state.value.analysis?.projected)
        assertEquals(2, repo.bReads)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.acting)
        finish(vm)
    }

    @Test fun `이동 전에 시작한 출발 카드 조회가 늦게 와도 이동 후 재조회 결과를 덮지 않는다`() = runTest(dispatcher) {
        val repo = MoveRefreshRepo().apply { delayFirstB = true }
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.moveAndFill(); runCurrent()
        vm.select(22); runCurrent()
        repo.move.complete(Unit); runCurrent()
        assertEquals(200_000L, vm.state.value.analysis?.projected)
        repo.oldB.complete(ApiResult.Success(sample(22).copy(projected = 250_000))); runCurrent()
        assertEquals(22L, vm.state.value.analysis?.userCardId)
        assertEquals(200_000L, vm.state.value.analysis?.projected)
        assertFalse(vm.state.value.loading)
        finish(vm)
    }

    @Test fun `이동 뒤 현재 카드 재조회 실패는 이전 분석 대신 조회 오류를 알린다`() = runTest(dispatcher) {
        val repo = MoveRefreshRepo().apply { failReloadB = true }
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.moveAndFill(); runCurrent()
        vm.select(22); runCurrent()
        repo.move.complete(Unit); runCurrent()
        assertEquals(22L, vm.state.value.selectedId)
        assertNull(vm.state.value.analysis)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.acting)
        assertFalse(vm.state.value.loading)
        finish(vm)
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

    @Test fun `옮기기 연타도 한 번만 보내고 두 쓰기가 성공하면 다시 분석한다`() = runTest(dispatcher) {
        val repo = MoveRepo()
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.moveAndFill(); vm.moveAndFill(); runCurrent()
        assertEquals(1, repo.moves)
        assertEquals(1, repo.decisions)
        assertEquals(2, repo.reads)
        assertTrue(vm.state.value.message!!.contains("내는 곳에서도"))
        finish(vm)
    }

    @Test fun `이동 실패면 판단을 보내지 않고 응답 유실은 결과 불명으로 알린다`() = runTest(dispatcher) {
        for (error in listOf(ApiError.Conflict("CONFLICT"), ApiError.Network)) {
            val repo = MoveRepo().apply { moved = ApiResult.Failure(error) }
            val vm = PerfAnalysisViewModel(CARD_ID, repo)
            runCurrent()
            vm.moveAndFill(); runCurrent()
            assertEquals(1, repo.moves)
            assertEquals(0, repo.decisions)
            assertEquals(if (error.isOutcomeUnknown) 2 else 1, repo.reads)
            assertEquals(error.isOutcomeUnknown, vm.state.value.message!!.contains("변경 결과를 확인하지 못했어요"))
            assertFalse(vm.state.value.acting)
            finish(vm)
        }
    }

    @Test fun `이동 뒤 판단 실패와 결과 불명을 구분하고 자동으로 쓰기를 반복하지 않는다`() = runTest(dispatcher) {
        for (error in listOf(ApiError.Conflict("CONFLICT"), ApiError.Server("ERROR"), ApiError.Network)) {
            val repo = MoveRepo().apply { recorded = ApiResult.Failure(error) }
            val vm = PerfAnalysisViewModel(CARD_ID, repo)
            runCurrent()
            vm.moveAndFill(); runCurrent()
            val message = vm.state.value.message!!
            assertTrue(message.contains("바꿔 뒀어요"))
            assertEquals(error.isOutcomeUnknown, message.contains("판단 기록 결과는 확인하지 못했어요"))
            assertEquals(!error.isOutcomeUnknown, message.contains("판단은 기록하지 못했어요"))
            assertEquals(1, repo.moves)
            assertEquals(1, repo.decisions)
            assertEquals(2, repo.reads)
            assertFalse(vm.state.value.acting)
            finish(vm)
        }
    }

    @Test fun `부분 성공 뒤 분석 재조회도 실패하면 결과 안내와 조회 오류를 모두 남긴다`() = runTest(dispatcher) {
        val repo = MoveRepo().apply {
            recorded = ApiResult.Failure(ApiError.Network)
            failReload = true
        }
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.moveAndFill(); runCurrent()
        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.message!!.contains("판단 기록 결과는 확인하지 못했어요"))
        assertFalse(vm.state.value.loading)
        finish(vm)
    }

    @Test fun `이동 중 카드가 바뀌면 뒤이은 판단 저장은 원래 카드로 하고 현재 화면은 유지한다`() = runTest(dispatcher) {
        val move = CompletableDeferred<ApiResult<Unit>>()
        val repo = MoveRepo().apply { waitMove = move }
        val vm = PerfAnalysisViewModel(CARD_ID, repo)
        runCurrent()
        vm.moveAndFill(); runCurrent()
        vm.select(22); runCurrent()
        move.complete(ApiResult.Success(Unit)); runCurrent()
        assertEquals(CARD_ID, repo.decidedId)
        assertEquals(22L, vm.state.value.analysis?.userCardId)
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.acting)
        finish(vm)
    }

    private class MoveRepo : PerformanceRepository by unused() {
        var moved: ApiResult<Unit> = ApiResult.Success(Unit)
        var recorded: ApiResult<Unit> = ApiResult.Success(Unit)
        var waitMove: CompletableDeferred<ApiResult<Unit>>? = null
        var failReload = false
        var moves = 0
        var decisions = 0
        var reads = 0
        var decidedId: Long? = null
        override fun cachedOverview(): PerfOverview? = null
        override suspend fun overview(): ApiResult<PerfOverview> = ApiResult.Failure(ApiError.Network)
        override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> {
            reads++
            if (failReload && reads > 1) return ApiResult.Failure(ApiError.Network)
            return ApiResult.Success(sample(userCardId).copy(movable = MovableItem(3, "통신비", 55_000, "2026-11-25", null)))
        }
        override suspend fun moveCashflow(cashflowItemId: Long, userCardId: Long): ApiResult<Unit> {
            moves++
            return waitMove?.await() ?: moved
        }
        override suspend fun decide(userCardId: Long, decision: PerfDecision?, analysisVersion: String?): ApiResult<Unit> {
            decisions++
            decidedId = userCardId
            assertEquals(PerfDecision.FILL, decision)
            return recorded
        }
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

    private class MoveRefreshRepo : PerformanceRepository by unused() {
        val move = CompletableDeferred<Unit>()
        val oldB = CompletableDeferred<ApiResult<PerfAnalysis>>()
        var moved = false
        var delayFirstB = false
        var failReloadB = false
        var bReads = 0
        var decidedId: Long? = null
        override fun cachedOverview(): PerfOverview? = null
        override suspend fun overview(): ApiResult<PerfOverview> = ApiResult.Failure(ApiError.Network)
        override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> {
            if (userCardId == 22L) {
                bReads++
                if (delayFirstB && bReads == 1) return oldB.await()
                if (moved && failReloadB) return ApiResult.Failure(ApiError.Network)
            }
            return ApiResult.Success(sample(userCardId).copy(
                projected = if (userCardId == 22L && moved) 200_000 else 250_000,
                movable = MovableItem(3, "통신비", 50_000, "2026-11-25", null),
            ))
        }
        override suspend fun moveCashflow(cashflowItemId: Long, userCardId: Long): ApiResult<Unit> {
            move.await()
            moved = true
            return ApiResult.Success(Unit)
        }
        override suspend fun decide(userCardId: Long, decision: PerfDecision?, analysisVersion: String?): ApiResult<Unit> {
            decidedId = userCardId
            return ApiResult.Success(Unit)
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
