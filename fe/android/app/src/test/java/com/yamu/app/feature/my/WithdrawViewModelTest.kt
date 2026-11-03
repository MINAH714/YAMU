package com.yamu.app.feature.my

import android.content.ContextWrapper
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.PinPadResult
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpResults
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import com.yamu.app.feature.my.data.Device
import com.yamu.app.feature.my.data.MyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** 회원 탈퇴 — 성공했을 때만 이 폰을 비운다. 실패 · 응답 유실 때 기기 정보가 남아야 다시 들어올 수 있다 */
class WithdrawViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = StepUpResults()
    private var forgotten = 0
    private var withdrawals = 0
    private var lastToken: String? = null
    private var result: ApiResult<Unit> = ApiResult.Success(Unit)
    private var devicesResult: ApiResult<List<Device>> = ApiResult.Success(emptyList())
    private var deviceReads = 0

    private val auth = object : AuthRepository by unused<AuthRepository>() {
        override suspend fun biometricReady() = false
        override suspend fun forgetAfterWithdrawal() {
            forgotten++
        }
    }

    private val my = object : MyRepository by unused<MyRepository>() {
        override suspend fun withdraw(stepUpToken: String): ApiResult<Unit> {
            withdrawals++
            lastToken = stepUpToken
            return result
        }

        override suspend fun devices(): ApiResult<List<Device>> {
            deviceReads++
            return devicesResult
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = WithdrawViewModel(my, auth, StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results))

    private fun TestScope.passStepUp(vm: WithdrawViewModel) {
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Verified(StepUpPurpose.WITHDRAW_MEMBER, StepUpToken("su_withdraw", StepUpPurpose.WITHDRAW_MEMBER)))
        advanceUntilIdle()
    }

    @Test fun `확인 체크 전에는 시작하지 않는다`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.withdraw()
        advanceUntilIdle()
        assertFalse(vm.state.value.working)
        assertFalse(vm.stepUp.busy)
    }

    @Test fun `성공하면 이 폰을 비우고 화면을 끝낸다`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals("su_withdraw", lastToken)
        assertEquals(1, forgotten)
        assertTrue(vm.state.value.done)
    }

    @Test fun `응답을 못 받았는데 계정이 없으면 탈퇴가 끝난 것`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Network)
        devicesResult = ApiResult.Failure(ApiError.Unauthorized("UNAUTHORIZED"))
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(1, forgotten)
        assertTrue(vm.state.value.done)
    }

    @Test fun `계정 확인도 못 하면 기기 정보를 지우지 않는다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Network)
        devicesResult = ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(0, forgotten)
        assertFalse(vm.state.value.done)
        assertEquals("탈퇴가 끝났는지 확인하지 못했어요 · 다시 눌러 주세요", vm.state.value.error)
    }

    @Test fun `다시 누를 때 계정이 없으면 본인 인증 없이 끝낸다 — 끝난 계정은 간편 비밀번호 화면에서 막힌다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Network)
        devicesResult = ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        devicesResult = ApiResult.Failure(ApiError.Unauthorized("UNAUTHORIZED"))
        vm.withdraw()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals(1, forgotten)
        assertEquals(1, withdrawals)
        assertFalse(vm.stepUp.busy)
    }

    @Test fun `응답 유실 없이 401이면 탈퇴로 치지 않는다 — 다른 이유로 끊긴 세션일 수 있다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Unauthorized("UNAUTHORIZED"))
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(0, forgotten)
        assertEquals(0, deviceReads)
        assertEquals("탈퇴를 완료하지 못했어요 · 다시 로그인해 주세요", vm.state.value.error)
    }

    @Test fun `응답을 못 받으면 기기 정보를 지우지 않고 다시 누를 수 있다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(0, forgotten)
        assertFalse(vm.state.value.done)
        assertFalse(vm.state.value.working)
        assertEquals("탈퇴가 끝났는지 확인하지 못했어요 · 다시 눌러 주세요", vm.state.value.error)

        result = ApiResult.Success(Unit)
        vm.withdraw()
        passStepUp(vm)
        assertNull(vm.state.value.error)
        assertEquals(1, forgotten)
        assertTrue(vm.state.value.done)
    }

    @Test fun `서버가 거절하면 기기 정보를 지우지 않고 이유를 알린다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.StepUpRequired("STEP_UP_REQUIRED"))
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(0, forgotten)
        assertEquals("탈퇴를 완료하지 못했어요 · 본인 확인이 필요해요", vm.state.value.error)
    }

    @Test fun `출금 전 청구가 있으면 막고 이유를 알린다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Conflict("BILL_OUTSTANDING"))
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(0, forgotten)
        assertEquals("출금이 끝나지 않은 청구가 있어 탈퇴할 수 없어요", vm.state.value.error)
    }

    @Test fun `입금 전 환급이 있으면 막고 이유를 알린다`() = runTest(dispatcher) {
        result = ApiResult.Failure(ApiError.Conflict("REFUND_PENDING"))
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        passStepUp(vm)
        assertEquals(0, forgotten)
        assertEquals("입금되지 않은 환급이 있어 탈퇴할 수 없어요", vm.state.value.error)
    }

    @Test fun `연타해도 탈퇴 요청은 한 번`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        vm.withdraw()
        passStepUp(vm)
        assertEquals(1, withdrawals)
    }

    @Test fun `본인 인증을 취소하면 아무 일도 없고 다시 누를 수 있다`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setConfirmed(true)
        vm.withdraw()
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Cancelled(StepUpPurpose.WITHDRAW_MEMBER))
        advanceUntilIdle()
        vm.onStepUpIdle()
        assertFalse(vm.state.value.working)
        assertEquals(0, withdrawals)
        assertEquals(0, forgotten)
    }

    private companion object {
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
