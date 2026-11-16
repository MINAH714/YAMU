package com.yamu.app.feature.forecast

import com.yamu.app.navigation.BuyCheckResult
import com.yamu.app.core.designsystem.component.TagTone
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

/** I-01b · I-02 — 조건을 바꾸면 다시 계산하고 늦은 옛 결과는 버린다, 계획 저장은 일시불만 · 결과 불명이면 같은 키로만 다시 보낸다 */
class BuyCheckViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pending = ArrayDeque<CompletableDeferred<ApiResult<Simulation>>>()
    private val queries = mutableListOf<SimulationQuery>()
    private var saveResult: ApiResult<SavedPlan> = ApiResult.Success(SavedPlan(77, "태블릿", 400_000, DATE, 1, 0, null))
    private var saveCalls = 0
    private val saveKeys = mutableListOf<String>()
    private var categoryList = listOf(
        MerchantCategory("HYPERMARKET", "대형마트"),
        MerchantCategory("ONLINE_MARKETPLACE", "온라인 쇼핑"),
        MerchantCategory("DELIVERY_APP", "배달앱"),
        MerchantCategory("ELECTRIC_UTILITY", "전기요금", false),
        MerchantCategory("UNIVERSITY", "대학", false),
        MerchantCategory("TAXI", "택시", false),
    )
    private var cardList = CARDS

    private val repo = object : ForecastRepository by unused() {
        override suspend fun simulate(query: SimulationQuery): ApiResult<Simulation> {
            queries += query
            return pending.removeFirstOrNull()?.await() ?: ApiResult.Success(sim(query))
        }
        override suspend fun simulationCards(): ApiResult<List<SimCardOption>> = ApiResult.Success(cardList)
        override suspend fun categories(): ApiResult<List<MerchantCategory>> = ApiResult.Success(categoryList)
        override suspend fun merchants(categoryCode: String): ApiResult<List<CatalogMerchant>> = ApiResult.Success(listOf(CatalogMerchant(201, "이마트")))
        override suspend fun savePlan(simulation: Simulation, idempotencyKey: String): ApiResult<SavedPlan> {
            saveCalls++
            saveKeys += idempotencyKey
            return saveResult
        }
        override fun cached(): Forecast? = null
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @Test fun `제외 업종은 목록과 선택에서 막고 재조회시 제외되면 선택도 비운다`() = runTest(dispatcher) {
        val vm = BuyConditionsViewModel(10_000, "모의 구매", repo)
        advanceUntilIdle()
        assertEquals(listOf("HYPERMARKET", "ONLINE_MARKETPLACE", "DELIVERY_APP"), vm.state.value.categories?.map { it.code })
        for (code in listOf("ELECTRIC_UTILITY", "UNIVERSITY", "TAXI")) {
            vm.pickCategory(MerchantCategory(code, code, false))
            assertNull(vm.query())
            vm.pickCategory(MerchantCategory(code, code, true)) // 목록 밖 업종을 직접 넘겨도 거절
            assertNull(vm.query())
        }
        vm.pickCategory(categoryList.first())
        advanceUntilIdle()
        assertEquals("HYPERMARKET", vm.query()?.categoryCode)
        categoryList = categoryList.map { it.copy(simulationSupported = false) }
        vm.loadCategories()
        advanceUntilIdle()
        assertNull(vm.query())
        assertNull(vm.state.value.merchant)
    }

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

    @Test fun `저장 결과를 모르면 같은 키로 다시 저장한다 — 서버가 한 번만 만든다(-358)`() = runTest(dispatcher) {
        saveResult = ApiResult.Failure(ApiError.Network)
        val vm = result()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saveUncertain)

        saveResult = ApiResult.Success(SavedPlan(77, "태블릿", 400_000, DATE, 1, 0, null))
        vm.save()
        advanceUntilIdle()
        assertEquals(2, saveCalls)
        assertEquals(saveKeys[0], saveKeys[1])
        assertEquals(77L, vm.state.value.savedPlanId)
        assertFalse(vm.state.value.saveUncertain)
    }

    @Test fun `조건을 바꾸거나 저장에 성공하면 다음 저장은 새 키`() = runTest(dispatcher) {
        val vm = result()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        vm.navigated()
        vm.save()   // I-05에서 돌아와 같은 조건으로 한 번 더 — 다른 계획이다
        advanceUntilIdle()
        vm.setDate(DATE.plusDays(1))
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertEquals(3, saveKeys.toSet().size)
    }

    @Test fun `같은 키의 앞 저장이 이미 들어갔으면(409) 다시 저장하지 않는다`() = runTest(dispatcher) {
        saveResult = ApiResult.Failure(ApiError.from(409, "IDEMPOTENCY_KEY_REUSED"))
        val vm = result()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.savedElsewhere)
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

    @Test fun `조건 화면 — 업종 없이 비교하기를 누르면 넘어가지 않고 고르라고 알린다(QA)`() = runTest(dispatcher) {
        val vm = BuyConditionsViewModel(400_000L, "태블릿", repo)
        advanceUntilIdle()
        assertNull(vm.compare())
        assertEquals(BuyConditionsViewModel.CATEGORY_REQUIRED, vm.state.value.message)
        // 안내가 떠 있는 동안 또 눌러도 같은 값 — 화면의 LaunchedEffect가 한 번만 띄운다
        assertNull(vm.compare())
        assertEquals(BuyConditionsViewModel.CATEGORY_REQUIRED, vm.state.value.message)
        vm.messageShown()
        assertNull(vm.state.value.message)

        vm.pickCategory(MerchantCategory("HYPERMARKET", "대형마트"))
        advanceUntilIdle()
        assertEquals("HYPERMARKET", vm.compare()?.categoryCode)
        assertNull(vm.state.value.message)
    }

    @Test fun `구매일 달력 — 오늘(한국 날짜)부터 고를 수 있다`() {
        val today = LocalDate.of(2026, 11, 16)
        val selectable = FromDateSelectable(today)
        fun utc(d: LocalDate) = d.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertFalse(selectable.isSelectableDate(utc(today.minusDays(1))))
        assertTrue(selectable.isSelectableDate(utc(today)))
        assertTrue(selectable.isSelectableDate(utc(today.plusMonths(2))))
    }

    @Test fun `조건 화면 — 업종을 고르면 결제 방식을 그 업종의 기본값으로, 그 뒤 바꾼 것은 그대로(점검 B-14)`() = runTest(dispatcher) {
        val vm = BuyConditionsViewModel(400_000L, "태블릿", repo)
        advanceUntilIdle()

        vm.pickCategory(MerchantCategory("ONLINE_MARKETPLACE", "온라인 쇼핑"))
        assertEquals(PaymentChannel.ONLINE, vm.query()?.channel)

        vm.setChannel(PaymentChannel.OFFLINE)
        vm.retryMerchants()   // 브랜드 목록만 다시 — 고른 결제 방식이 기본값으로 돌아가면 안 된다
        advanceUntilIdle()
        assertEquals(PaymentChannel.OFFLINE, vm.query()?.channel)
        assertEquals(listOf(CatalogMerchant(201, "이마트")), vm.state.value.merchants)

        vm.pickCategory(MerchantCategory("ONLINE_MARKETPLACE", "온라인 쇼핑"))   // 같은 업종을 다시 눌러도 그대로
        assertEquals(PaymentChannel.OFFLINE, vm.query()?.channel)

        vm.pickCategory(MerchantCategory("DELIVERY_APP", "배달앱"))   // 업종을 바꾸면 새 업종의 기본값
        assertEquals(PaymentChannel.ONLINE, vm.query()?.channel)
        vm.pickCategory(MerchantCategory("HYPERMARKET", "대형마트"))
        assertEquals(PaymentChannel.OFFLINE, vm.query()?.channel)
    }

    @Test fun `결과 태그 — 안전 잔액 아래로 처음 내려가는 날부터, 잔고예보 첫 화면과 같은 문구(점검 B-08)`() {
        val today = LocalDate.of(2026, 11, 8)
        // 오늘부터 모자라고 가장 낮은 날은 12/8 — 가장 낮은 날이 아니라 BE가 준 시작일을 쓴다
        val short = SimSnapshot(-9_999_018_095, LocalDate.of(2026, 12, 8), RiskLevel.DANGER, riskStartDate = today)
        assertEquals("11월 8일부터 안전 잔액 아래" to TagTone.Error, afterTag(short))
        // 0원 아래인데 'AI 생활비 반영' 선에서만이라 WARNING이어도 빨간색
        assertEquals("11월 20일부터 안전 잔액 아래" to TagTone.Error, afterTag(SimSnapshot(-1, DATE, RiskLevel.WARNING, riskStartDate = DATE)))
        assertEquals("11월 20일부터 안전 잔액 아래" to TagTone.Warning, afterTag(SimSnapshot(60_000, DATE, RiskLevel.WARNING, riskStartDate = DATE)))
        // 시작일이 없는 옛 응답이면 날짜를 빼고(가장 낮은 날을 대신 쓰지 않는다)
        assertEquals("안전 잔액 아래로 내려가요" to TagTone.Warning, afterTag(SimSnapshot(60_000, DATE, RiskLevel.WARNING)))
        assertEquals("가장 낮을 때도 안전 잔액 위" to TagTone.Success, afterTag(SimSnapshot(360_000, DATE, RiskLevel.SAFE)))
        assertNull(afterTag(SimSnapshot(360_000, DATE, RiskLevel.UNKNOWN)))
        assertNull(afterTag(null))
    }

    @Test fun `할부는 신용카드 · 5만 원 이상일 때만`() {
        assertTrue(installmentAllowed(50_000, null))
        assertFalse(installmentAllowed(49_999, null))
        assertFalse(installmentAllowed(400_000, CARDS.single { it.userCardId == 16L }))
        assertTrue(installmentAllowed(400_000, CARDS.first()))
    }

    @Test fun `할부 — 카드를 안 골랐으면 쓸 수 있는 신용카드가 있을 때만, 목록을 아직 못 받았으면 연다(-588)`() {
        val checkOnly = CARDS.filter { !it.isCredit }
        val stoppedCredit = listOf(CARDS.first().copy(unusableReason = "정지된 카드예요")) + checkOnly
        assertTrue(installmentAllowed(400_000, null, null))
        assertTrue(installmentAllowed(400_000, null, CARDS))
        assertFalse(installmentAllowed(400_000, null, checkOnly))
        assertFalse(installmentAllowed(400_000, null, stoppedCredit))
        // 카드를 골랐으면 그 카드만 본다
        assertTrue(installmentAllowed(400_000, CARDS.first(), checkOnly))
        assertFalse(installmentAllowed(400_000, checkOnly.single(), CARDS))
    }

    @Test fun `조건 화면 — 할부면 고를 카드와 '내 카드 N장'에서 체크카드를 빼고, 일시불로 돌리면 다시 보인다(-588)`() = runTest(dispatcher) {
        cardList = CARDS + SimCardOption(12, "정지카드", "정지카드", isCredit = true, unusableReason = "정지된 카드예요")
        val vm = BuyConditionsViewModel(400_000L, "태블릿", repo)
        advanceUntilIdle()
        assertEquals(listOf(11L, 16L, 12L), vm.state.value.pickableCards?.map { it.userCardId })
        assertEquals(2, vm.state.value.pickableCards?.count { it.unusableReason == null })

        vm.setMonths(3)
        assertEquals(3, vm.state.value.months)
        assertEquals(listOf(11L, 12L), vm.state.value.pickableCards?.map { it.userCardId })
        assertEquals(1, vm.state.value.pickableCards?.count { it.unusableReason == null })
        assertEquals(listOf(11L, 12L), cardList.forPlan(installment = true).map { it.userCardId })

        vm.setMonths(null)
        assertEquals(cardList, vm.state.value.pickableCards)
    }

    @Test fun `조건 화면 — 쓸 수 있는 신용카드가 없으면 할부를 고를 수 없다(-588)`() = runTest(dispatcher) {
        cardList = listOf(CARDS.first().copy(unusableReason = "정지된 카드예요")) + CARDS.filter { !it.isCredit }
        val vm = BuyConditionsViewModel(400_000L, "태블릿", repo)
        advanceUntilIdle()
        vm.setMonths(3)
        assertNull(vm.state.value.months)
    }

    @Test fun `조건 화면 — 카드 목록보다 할부를 먼저 골랐어도 목록에 신용카드가 없으면 할부를 푼다(-588)`() = runTest(dispatcher) {
        cardList = CARDS.filter { !it.isCredit }
        val vm = BuyConditionsViewModel(400_000L, "태블릿", repo)
        vm.setMonths(3)   // 카드 목록을 받기 전 — 추천 카드가 신용카드일 수 있어 연다
        assertEquals(3, vm.state.value.months)
        advanceUntilIdle()
        assertNull(vm.state.value.months)

        cardList = CARDS
        val withCredit = BuyConditionsViewModel(400_000L, "태블릿", repo)
        withCredit.setMonths(3)
        advanceUntilIdle()
        assertEquals(3, withCredit.state.value.months)
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
