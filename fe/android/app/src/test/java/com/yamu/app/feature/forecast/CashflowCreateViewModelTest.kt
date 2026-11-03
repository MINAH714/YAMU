package com.yamu.app.feature.forecast

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedOverview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

/** F-02a 저장 실패 — 결과 불명(다시 못 보냄)과 실패(다시 보냄) */
class CashflowCreateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var createResult: ApiResult<Long> = ApiResult.Success(1)

    private val repo = object : ForecastRepository by unused() {
        override suspend fun createItem(form: CashflowForm) = createResult
    }
    private val link = object : LinkRepository by unused() {
        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> = ApiResult.Success(LinkedOverview(emptyList(), emptyList(), null))
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.adding(): CashflowCreateViewModel = CashflowCreateViewModel(repo, link).also {
        advanceUntilIdle()
        it.edit { f -> f.copy(title = "월세", amount = 500_000, date = LocalDate.now().plusDays(1)).withAccount(3) }
    }

    @Test fun `코드 없는 504 · 성공 응답 읽기 실패는 결과 불명이라 다시 보내지 못하게 한다`() = runTest(dispatcher) {
        listOf(ApiError.from(504, null), ApiError.from(502, null), ApiError.Unknown("PARSE_ERROR"), ApiError.Network).forEach { e ->
            createResult = ApiResult.Failure(e)
            val vm = adding()
            vm.save()
            advanceUntilIdle()
            assertTrue("$e", vm.state.value.unknownResult)
        }
    }

    @Test fun `서버 코드가 있는 오류는 실패라 다시 보낼 수 있다`() = runTest(dispatcher) {
        listOf(ApiError.from(502, "FIN_API_ERROR"), ApiError.from(500, "INTERNAL_ERROR"), ApiError.from(400, "INVALID_REQUEST")).forEach { e ->
            createResult = ApiResult.Failure(e)
            val vm = adding()
            vm.save()
            advanceUntilIdle()
            assertFalse("$e", vm.state.value.unknownResult)
        }
    }

    private companion object {
        /** 안 쓰는 메서드를 부르면 테스트가 실패해야 하므로 Proxy는 예외를 던진다 */
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
