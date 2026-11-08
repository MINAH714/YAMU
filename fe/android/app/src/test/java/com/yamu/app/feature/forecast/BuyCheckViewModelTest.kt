package com.yamu.app.feature.forecast

import com.yamu.app.navigation.BuyCheckResult
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CatalogMerchant
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.MerchantCategory
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.SavedPlan
import com.yamu.app.feature.forecast.data.SimAction
import com.yamu.app.feature.forecast.data.SimBenefit
import com.yamu.app.feature.forecast.data.SimCard
import com.yamu.app.feature.forecast.data.SimCardOption
import com.yamu.app.feature.forecast.data.SimSnapshot
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.SimulationQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

/** I-01b · I-02 — 조건을 바꾸면 다시 계산하고 늦은 옛 결과는 버린다, 계획 저장은 일시불만 · 결과 불명이면 다시 보내지 않는다 */
class BuyCheckViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pending = ArrayDeque<CompletableDeferred<ApiResult<Simulation>>>()
    private val queries = mutableListOf<SimulationQuery>()
    private var saveResult: ApiResult<SavedPlan> = ApiResult.Success(SavedPlan(77, "태블릿", 400_000, DATE, 1, 0, null))
    private var saveCalls = 0

    private val repo = object : ForecastRepository by unused() {
        override suspend fun simulate(query: SimulationQuery): ApiResult<Simulation> {
            queries += query
            return pending.removeFirstOrNull()?.await() ?: ApiResult.Success(sim(query))
        }
        override suspend fun simulationCards(): ApiResult<List<SimCardOption>> = ApiResult.Success(CARDS)
        override suspend fun categories(): ApiResult<List<MerchantCategory>> = ApiResult.Success(listOf(MerchantCategory("HYPERMARKET", "대형마트")))
        override suspend fun merchants(categoryCode: String): ApiResult<List<CatalogMerchant>> = ApiResult.Success(listOf(CatalogMerchant(201, "이마트")))
        override suspend fun savePlan(simulation: Simulation): ApiResult<SavedPlan> {
            saveCalls++
            return saveResult
        }
        override fun cached(): Forecast? = null
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun result(months: Int? = null, cardId: Long? = null) = BuyCheckViewModel(ROUTE.copy(months = months, userCardId = cardId).toQuery(), repo)

    @Test fun `경로 값 ↔ 조건 — 넘긴 그대로 돌아온다`() {
        val full = ROUTE.copy(merchantId = 201, merchantName = "이마트", userCardId = 11, months = 3, channel = "ONLINE")
        assertEquals(full, full.toQuery().toRoute())
        assertEquals(PaymentChannel.OFFLINE, ROUTE.copy(channel = "SOMETHING").toQuery().channel)   // 모르는 값은 기본값(매장)
    }

    @Test fun `경로 값으로 조건을 만들고 바로 계산한다`() = runTest(dispatcher) {
        val vm = result()
        advanceUntilIdle()
        val q = queries.single()
        assertEquals(400_000L, q.amount)
        assertEquals("HYPERMARKET", q.categoryCode)
        assertEquals(PaymentChannel.OFFLINE, q.channel)
        assertNull(q.userCardId)
        assertNull(q.installmentMonths)
        assertFalse(vm.state.value.loading)
        assertEquals(q, vm.state.value.simulation?.query)
    }

    @Test fun `카드를 바꾸면 다시 계산하고 앞 요청의 늦은 결과는 버린다`() = runTest(dispatcher) {
        val slow = CompletableDeferred<ApiResult<Simulation>>()
        pending += slow
        val vm = result()
        advanceUntilIdle()
        vm.loadCards()
        advanceUntilIdle()

        vm.setCard(11)
        advanceUntilIdle()
        slow.complete(ApiResult.Success(sim(queries.first())))   // 첫 요청(추천 카드)이 늦게 도착
        advanceUntilIdle()

        assertEquals(11L, vm.state.value.simulation?.query?.userCardId)
        assertEquals(2, queries.size)
    }

    @Test fun `체크카드로 바꾸면 할부를 푼다`() = runTest(dispatcher) {
        val vm = result(months = 3)
        advanceUntilIdle()
        vm.loadCards()
        advanceUntilIdle()
        vm.setCard(16)
        advanceUntilIdle()
        assertNull(queries.last().installmentMonths)
        assertEquals(16L, queries.last().userCardId)
    }

    @Test fun `422 이유는 화면 상태로 — 결과는 비우고 다음 행동을 준다`() = runTest(dispatcher) {
        pending += CompletableDeferred(ApiResult.Failure(ApiError.from(422, "DATA_INSUFFICIENT", buildJsonObject { put("reason", "NO_ELIGIBLE_CARD") })))
        val vm = result()
        advanceUntilIdle()
        assertNull(vm.state.value.simulation)
        assertEquals(SimAction.PICK_CARD, vm.state.value.problem?.action)
    }

    @Test fun `할부 결과는 계획으로 저장하지 않는다`() = runTest(dispatcher) {
        val vm = result(months = 3, cardId = 11)
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertEquals(0, saveCalls)
    }

    @Test fun `일시불은 저장하고, 연타해도 한 번만 보낸다`() = runTest(dispatcher) {
        val vm = result()
        advanceUntilIdle()
        vm.save()
        vm.save()
        advanceUntilIdle()
        assertEquals(1, saveCalls)
        assertEquals(77L, vm.state.value.savedPlanId)
    }

    @Test fun `저장 결과를 모르면 다시 보내지 않고 잔고예보에서 확인하게 한다`() = runTest(dispatcher) {
        saveResult = ApiResult.Failure(ApiError.Network)
        val vm = result()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saveUncertain)
        vm.save()
        advanceUntilIdle()
        assertEquals(1, saveCalls)
    }

    @Test fun `조건 화면 — 업종이 없으면 비교할 수 없고, 체크카드를 고르면 할부를 푼다`() = runTest(dispatcher) {
        val vm = BuyConditionsViewModel(400_000L, "태블릿", repo)
        advanceUntilIdle()
        assertNull(vm.query())

        vm.pickCategory(MerchantCategory("HYPERMARKET", "대형마트"))
        advanceUntilIdle()
        assertEquals(listOf(CatalogMerchant(201, "이마트")), vm.state.value.merchants)
        vm.setMonths(3)
        assertEquals(3, vm.query()?.installmentMonths)
        vm.pickCard(16)
        assertNull(vm.query()?.installmentMonths)
        assertEquals(PaymentChannel.OFFLINE, vm.query()?.channel)   // 기본값은 매장
    }

    @Test fun `할부는 신용카드 · 5만 원 이상일 때만`() {
        assertTrue(installmentAllowed(50_000, null))
        assertFalse(installmentAllowed(49_999, null))
        assertFalse(installmentAllowed(400_000, CARDS.single { it.userCardId == 16L }))
        assertTrue(installmentAllowed(400_000, CARDS.first()))
    }

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 11, 20)
        val ROUTE = BuyCheckResult(400_000, "태블릿", categoryCode = "HYPERMARKET", categoryName = "대형마트", channel = "OFFLINE", purchaseDate = DATE.toString())
        val CARDS = listOf(
            SimCardOption(11, "생활카드", "생활카드", isCredit = true, unusableReason = null),
            SimCardOption(16, "알뜰체크", "알뜰체크", isCredit = false, unusableReason = null),
        )

        fun sim(q: SimulationQuery) = Simulation(
            query = q,
            card = SimCard(q.userCardId ?: 11, "생활카드", isCredit = true, recommendedTop = q.userCardId == null),
            benefit = SimBenefit(null, emptyList(), q.amount),
            missing = emptyList(),
            schedule = emptyList(),
            before = SimSnapshot(360_000, DATE, RiskLevel.SAFE),
            after = SimSnapshot(60_000, DATE, RiskLevel.WARNING),
            beyondPeriodImpact = 0,
            days = emptyList(),
            alternatives = emptyList(),
            assumptions = emptyList(),
            creditLimitSkipped = true,
            outsideNote = null,
            message = null,
            isEstimate = true,
        )

        /** 안 쓰는 메서드를 부르면 테스트가 실패해야 하므로 Proxy는 예외를 던진다 */
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
