package com.yamu.app.feature.performance

import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.link.data.CardsOnlyLink
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.CardPerformancesDto
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.data.manLabel
import com.yamu.app.feature.performance.data.toCardPerf
import com.yamu.app.feature.performance.data.toOverview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** 실적 관리 목록(R-01) — 상태 글자 · 차례 · 여러 단계 카드 · 전월 실적(S15P31E103-614) */
class PerfOverviewListTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `여러 단계 카드는 넘긴 가장 높은 단계까지 달성, 하나도 못 넘기면 미달성, 다 넘기면 달성`() {
        assertEquals("20만원 달성", nori(210_000, target = 300_000).stateLabel)
        assertEquals("40만원 달성", nori(450_000, target = 600_000).stateLabel)
        assertEquals("미달성", nori(150_000, target = 200_000).stateLabel)
        assertEquals("달성", nori(820_000, target = 800_000, achieved = true).stateLabel)
        assertEquals("노리2 210,000 / 300,000원 20만원 달성", nori(210_000, target = 300_000).rowLabel(thisMonth = true))
    }

    @Test fun `한 단계 카드 · 실적 조건 없는 카드의 상태`() {
        assertEquals("달성", card(1, 320_000, 300_000, achieved = true).stateLabel)
        assertEquals("미달성", card(1, 270_000, 300_000).stateLabel)
        assertEquals("실적 조건 없음", card(1, 150_000, null).stateLabel)
    }

    @Test fun `목록은 채운 비율 순이고 실적 조건 없는 카드는 맨 아래`() {
        val cards = listOf(
            card(12, 150_000, null),
            card(14, 180_000, 300_000),
            card(13, 320_000, 400_000),
            card(15, 320_000, 300_000, achieved = true),
            card(11, 270_000, 300_000),
            card(16, 0, 300_000, noRecord = true),
        )
        assertEquals(listOf(15L, 11L, 13L, 14L, 16L, 12L), cards.byFill().map { it.userCardId })
    }

    @Test fun `게이지 아래 한 줄 — 이번 달과 전월의 실적 조건 없는 카드 말투`() {
        assertEquals("320,000 / 300,000원", card(1, 320_000, 300_000, achieved = true).amountLine(thisMonth = true))
        assertEquals("이번 달 150,000원 썼어요", card(1, 150_000, null).amountLine(thisMonth = true))
        assertEquals("120,000원 썼어요", card(1, 120_000, null).amountLine(thisMonth = false))
        assertEquals("카드1 기록 없음", card(1, 0, 300_000, noRecord = true).rowLabel(thisMonth = false))
    }

    @Test fun `서버 thresholds는 0 이하를 빼고 겹치면 하나로 작은 순, 없는 응답은 빈 목록`() {
        val json = """{"yearMonth":"2026-11","daysLeft":13,"cards":[
            {"userCardId":16,"nickname":"노리2","targetAmount":300000,"recognizedAmount":210000,"remainingAmount":90000,"status":"AT_RISK",
             "thresholds":[400000,200000,0,300000,200000,800000,600000]},
            {"userCardId":11,"nickname":"톡톡","targetAmount":300000,"recognizedAmount":0,"remainingAmount":300000,"status":"INSUFFICIENT_DATA"}]}"""
        val dto = YamuJson.decodeFromString(CardPerformancesDto.serializer(), json)
        val nori = dto.cards[0].toCardPerf()
        assertEquals(listOf(200_000L, 300_000L, 400_000L, 600_000L, 800_000L), nori.thresholds)
        assertEquals(200_000L, nori.reachedThreshold)
        assertFalse(nori.noRecord)
        val tok = dto.cards[1].toCardPerf()
        assertTrue("보내지 않는 서버(배포 전)면 막대 게이지", tok.thresholds.isEmpty())
        assertTrue(tok.noRecord)
    }

    @Test fun `단계 이름은 만 원 단위면 n만원, 아니면 원 단위`() {
        assertEquals("20만원", manLabel(200_000))
        assertEquals("215,000원", manLabel(215_000))
    }

    @Test fun `전월 실적은 처음 펼칠 때 서버 이번 달의 한 달 전으로 한 번만 받는다`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = PerfOverviewViewModel(repo, CardsOnlyLink())
        vm.refresh(); runCurrent()
        vm.loadLastMonth(); runCurrent()
        vm.loadLastMonth(); runCurrent()
        assertEquals(listOf("2026-10"), repo.lastMonthCalls)
        assertEquals("2026-10", vm.state.value.lastMonth?.yearMonth)
        vm.viewModelScope.cancel()
    }

    @Test fun `이번 달 목록이 오기 전에는 전월을 부르지 않는다`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = PerfOverviewViewModel(repo, CardsOnlyLink())
        vm.loadLastMonth(); runCurrent()
        assertTrue(repo.lastMonthCalls.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun `전월 실적 실패는 오류로 두고 다시 시도하면 받는다`() = runTest(dispatcher) {
        val repo = Repo(lastMonth = ApiResult.Failure(ApiError.Network))
        val vm = PerfOverviewViewModel(repo, CardsOnlyLink())
        vm.refresh(); runCurrent()
        vm.loadLastMonth(); runCurrent()
        assertTrue(vm.state.value.lastMonthFailed)
        assertNull(vm.state.value.lastMonth)
        repo.lastMonth = ApiResult.Success(month("2026-10"))
        vm.loadLastMonth(); runCurrent()
        assertFalse(vm.state.value.lastMonthFailed)
        assertEquals("2026-10", vm.state.value.lastMonth?.yearMonth)
        vm.viewModelScope.cancel()
    }

    @Test fun `탭에 돌아오면 받아 둔 전월도 조용히 다시 받고, 실패하면 받아 둔 값 그대로`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = PerfOverviewViewModel(repo, CardsOnlyLink())
        vm.refresh(); runCurrent()
        vm.loadLastMonth(); runCurrent()
        repo.lastMonth = ApiResult.Failure(ApiError.Network)
        vm.refresh(); runCurrent()
        assertEquals(listOf("2026-10", "2026-10"), repo.lastMonthCalls)
        assertEquals("2026-10", vm.state.value.lastMonth?.yearMonth)
        assertFalse("조용히 다시 받기 실패는 오류로 보이지 않는다", vm.state.value.lastMonthFailed)
        vm.viewModelScope.cancel()
    }

    @Test fun `달이 바뀌면 받아 둔 전월은 버리고 새 전월을 받는다`() = runTest(dispatcher) {
        val repo = Repo()
        val vm = PerfOverviewViewModel(repo, CardsOnlyLink())
        vm.refresh(); runCurrent()
        vm.loadLastMonth(); runCurrent()
        repo.thisMonth = "2026-12"
        vm.refresh(); runCurrent()
        assertNull("11월 목록 옆에 10월 실적을 두지 않는다", vm.state.value.lastMonth)
        vm.loadLastMonth(); runCurrent()
        assertEquals("2026-11", vm.state.value.lastMonth?.yearMonth)
        vm.viewModelScope.cancel()
    }

    @Test fun `받는 사이 달이 바뀌면 늦게 온 전월은 버린다`() = runTest(dispatcher) {
        val late = CompletableDeferred<ApiResult<PerfOverview>>()
        val repo = Repo(wait = late)
        val vm = PerfOverviewViewModel(repo, CardsOnlyLink())
        vm.refresh(); runCurrent()
        vm.loadLastMonth(); runCurrent()
        repo.thisMonth = "2026-12"
        vm.refresh(); runCurrent()
        late.complete(ApiResult.Success(month("2026-10"))); runCurrent()
        assertNull(vm.state.value.lastMonth)
        vm.viewModelScope.cancel()
    }

    private class Repo(
        var thisMonth: String = "2026-11",
        var lastMonth: ApiResult<PerfOverview>? = null,
        private val wait: CompletableDeferred<ApiResult<PerfOverview>>? = null,
    ) : PerformanceRepository by unused() {
        val lastMonthCalls = mutableListOf<String>()
        override fun cachedOverview(): PerfOverview? = null
        override suspend fun overview(): ApiResult<PerfOverview> = ApiResult.Success(month(thisMonth))
        override suspend fun overviewOf(yearMonth: String): ApiResult<PerfOverview> {
            lastMonthCalls += yearMonth
            wait?.let { return it.await() }
            return lastMonth ?: ApiResult.Success(month(yearMonth))
        }
    }

    private companion object {
        fun card(id: Long, recognized: Long, target: Long?, achieved: Boolean = false, noRecord: Boolean = false, thresholds: List<Long> = listOfNotNull(target)) = CardPerf(
            userCardId = id, brand = brandOf("우리카드"), name = "카드$id", target = target, targetLabel = null,
            recognized = recognized, remaining = target?.let { (it - recognized).coerceAtLeast(0) } ?: 0,
            achieved = achieved, noTarget = target == null, guide = null, decision = null,
            provisional = false, recentChange = null, recentAtIso = null, noRecord = noRecord, thresholds = thresholds,
        )

        fun nori(recognized: Long, target: Long, achieved: Boolean = false) =
            card(16, recognized, target, achieved = achieved, thresholds = listOf(200_000, 300_000, 400_000, 600_000, 800_000)).copy(name = "노리2")

        fun month(yearMonth: String) = listOf(card(1, 320_000, 300_000, achieved = true)).toOverview(yearMonth, null, null)

        fun unused(): PerformanceRepository = Proxy.newProxyInstance(
            PerformanceRepository::class.java.classLoader,
            arrayOf(PerformanceRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as PerformanceRepository
    }
}
