package com.yamu.app.feature.debug

import com.yamu.app.core.network.HealthApi
import com.yamu.app.core.network.HealthResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** 디버그 메뉴 '서버 연결 확인' — 응답이 안 와도 '확인 중'에 묶이지 않는다 */
class DebugMenuViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(respond: suspend () -> Response<HealthResponse>) = DebugMenuViewModel(
        object : HealthApi {
            override suspend fun health(): Response<HealthResponse> = respond()
        },
    )

    @Test fun `응답이 없으면 시간 제한 뒤 실패로 바뀐다`() = runTest(dispatcher) {
        val vm = viewModel { awaitCancellation() }
        vm.checkServer()
        advanceTimeBy(DebugMenuViewModel.CHECK_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(ServerCheck.Checking, vm.check.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(vm.check.value is ServerCheck.Fail)
    }

    @Test fun `연결이 안 되면 실패로 바뀐다`() = runTest(dispatcher) {
        val vm = viewModel { throw IOException("연결 거부") }
        vm.checkServer()
        advanceUntilIdle()
        assertEquals(ServerCheck.Fail("연결 거부"), vm.check.value)
    }

    @Test fun `응답이 오면 상태를 보여 준다`() = runTest(dispatcher) {
        val vm = viewModel { Response.success(HealthResponse("ok")) }
        vm.checkServer()
        advanceUntilIdle()
        assertEquals("ok", (vm.check.value as ServerCheck.Ok).status)
    }
}
