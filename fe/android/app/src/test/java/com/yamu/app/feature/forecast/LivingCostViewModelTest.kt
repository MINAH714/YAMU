package com.yamu.app.feature.forecast

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.LivingCostDetail
import com.yamu.app.feature.forecast.data.LivingCostDetailDto
import com.yamu.app.feature.forecast.data.toDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/** F-01b — 422는 기록 없음(0원으로 단정하지 않음), 계좌가 없으면 빈 상태, 그 밖의 실패는 받은 값을 지우지 않는다 */
class LivingCostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var result: ApiResult<LivingCostDetail> = ApiResult.Success(DETAIL)

    private val repo = object : ForecastRepository by unused() {
        override suspend fun livingCost(): ApiResult<LivingCostDetail> = result
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `받으면 상세를 그린다`() = runTest(dispatcher) {
        val vm = LivingCostViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(DETAIL, vm.state.value.detail)
    }

    @Test fun `422면 기록 없음 상태`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.from(422, "DATA_INSUFFICIENT"))
        val vm = LivingCostViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.noRecord)
        assertNull(vm.state.value.detail)
        assertNull(vm.state.value.error)
    }

    @Test fun `연결한 계좌가 없으면 빈 상태`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.from(400, ForecastRepository.NO_ACCOUNT))
        val vm = LivingCostViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.noAccount)
        assertFalse(vm.state.value.noRecord)
    }

    @Test fun `다시 불러오다 실패하면 받은 상세는 그대로 두고 오류만 알린다`() = runTest(dispatcher) {
        val vm = LivingCostViewModel(repo)
        vm.refresh()
        advanceUntilIdle()

        result = ApiResult.Failure(ApiError.Network)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(DETAIL, vm.state.value.detail)
        assertEquals(ApiError.Network.userMessage(), vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    private companion object {
        val DETAIL = LivingCostDetailDto(accountIds = listOf(3), projectedAmount = 96_000, dailyAverage = 6_000, method = "ML_V1", confidence = "HIGH").toDetail()

        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
