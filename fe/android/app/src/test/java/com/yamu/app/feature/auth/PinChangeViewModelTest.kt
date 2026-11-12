package com.yamu.app.feature.auth

import android.content.ContextWrapper
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.data.StepUpToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** M-08 간편 비밀번호 바꾸기 — 본인 확인 → 새 6자리 두 번 → 저장, 토큰 만료 · 결과 모름 · 취소(QA 07 · P1-16) */
@OptIn(ExperimentalCoroutinesApi::class)
class PinChangeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = StepUpResults()
    private val calls = mutableListOf<Pair<String, String>>()   // (새 비밀번호, 본인 확인 토큰)
    private val answers = ArrayDeque<ApiResult<Unit>>()

    private val auth = object : AuthRepository by unused<AuthRepository>() {
        override suspend fun biometricReady() = false   // 지문 없이 — 간편 비밀번호 화면으로 본인 확인
        override suspend fun changePin(pin: String, stepUpToken: String): ApiResult<Unit> {
            calls += pin to stepUpToken
            return answers.removeFirstOrNull() ?: ApiResult.Success(Unit)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PinChangeViewModel(auth, StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results))

    /** 간편 비밀번호 화면이 열리고 [token]으로 본인 확인을 마친다 */
    private fun TestScope.passStepUp(vm: PinChangeViewModel, token: String) {
        advanceUntilIdle()
        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Verified(StepUpPurpose.SECURITY_SETTINGS, StepUpToken(token, StepUpPurpose.SECURITY_SETTINGS)))
        advanceUntilIdle()
        vm.onStepUpIdle()
    }

    private fun TestScope.typeTwice(vm: PinChangeViewModel, first: String, second: String = first) {
        first.forEach { vm.onKey(KeypadKey.Digit(it - '0')) }
        advanceUntilIdle()   // 점 6개를 보여 주는 시간
        second.forEach { vm.onKey(KeypadKey.Digit(it - '0')) }
        advanceUntilIdle()
    }

    @Test fun `본인 확인 전에는 키를 받지 않고, 확인하면 새 번호 두 번으로 바꾼다`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onKey(KeypadKey.Digit(4))
        assertEquals("", vm.pin.value.input)

        passStepUp(vm, "su_1")
        assertEquals(PinChangeStep.Create, vm.state.value.step)
        typeTwice(vm, "482913")

        assertEquals(listOf("482913" to "su_1"), calls)
        assertEquals(PinChangeEnd.Changed, vm.state.value.end)
    }

    @Test fun `본인 확인을 취소하면 아무것도 바꾸지 않고 닫는다`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onStepUpIdle()                              // 간편 비밀번호 화면이 열려 있는 동안은 닫지 않는다
        assertNull(vm.state.value.end)

        vm.stepUp.onPinOpened()
        results.deliver(PinPadResult.Cancelled(StepUpPurpose.SECURITY_SETTINGS))
        advanceUntilIdle()
        vm.onStepUpIdle()
        assertEquals(PinChangeEnd.Closed(), vm.state.value.end)
        assertTrue(calls.isEmpty())
    }

    @Test fun `확인 번호가 다르면 저장하지 않고 첫 입력부터`() = runTest(dispatcher) {
        val vm = viewModel()
        passStepUp(vm, "su_1")
        typeTwice(vm, "482913", "482914")
        assertTrue(calls.isEmpty())
        assertEquals(PinChangeStep.Create, vm.state.value.step)
        assertEquals("처음 입력과 달라요. 처음부터 다시 만들어 주세요", vm.pin.value.error)
    }

    @Test fun `본인 확인 시간이 지났으면 새 번호는 그대로 두고 본인 확인만 다시 해서 저장한다`() = runTest(dispatcher) {
        answers += ApiResult.Failure(ApiError.from(403, "STEP_UP_REQUIRED"))
        val vm = viewModel()
        passStepUp(vm, "su_old")
        typeTwice(vm, "482913")
        assertEquals(PinChangeStep.Verify, vm.state.value.step)
        assertTrue(vm.stepUp.busy)                     // 간편 비밀번호 화면을 다시 연다

        passStepUp(vm, "su_new")
        assertEquals(listOf("482913" to "su_old", "482913" to "su_new"), calls)
        assertEquals(PinChangeEnd.Changed, vm.state.value.end)
    }

    @Test fun `연결이 끊기면 바뀌었는지 모른다고 알리고 다시 시도는 같은 토큰으로 보낸다`() = runTest(dispatcher) {
        answers += ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        passStepUp(vm, "su_1")
        typeTwice(vm, "482913")

        val s = vm.state.value
        assertEquals(PinChangeStep.Retry, s.step)
        assertTrue(s.unknown)
        assertEquals(PinChangeViewModel.UNKNOWN_MESSAGE, s.message)

        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("482913" to "su_1", "482913" to "su_1"), calls)
        assertEquals(PinChangeEnd.Changed, vm.state.value.end)
    }

    @Test fun `결과를 모른 채 닫으면 새 번호가 안 될 때 전 번호를 넣으라고 알린다`() = runTest(dispatcher) {
        answers += ApiResult.Failure(ApiError.Network)
        val vm = viewModel()
        passStepUp(vm, "su_1")
        typeTwice(vm, "482913")
        vm.back()
        assertEquals(PinChangeEnd.Closed(PinChangeViewModel.UNKNOWN_CLOSE), vm.state.value.end)
    }

    @Test fun `서버가 쉬운 번호라고 하면 같은 본인 확인으로 새 번호를 다시 넣는다`() = runTest(dispatcher) {
        answers += ApiResult.Failure(ApiError.from(400, "INVALID_PIN_FORMAT"))
        val vm = viewModel()
        passStepUp(vm, "su_1")
        typeTwice(vm, "482913")
        assertEquals(PinChangeStep.Create, vm.state.value.step)
        assertEquals("쉬운 번호는 쓸 수 없어요", vm.pin.value.error)

        advanceUntilIdle()                             // 흔들림이 끝난 뒤
        typeTwice(vm, "570264")
        assertEquals(listOf("482913" to "su_1", "570264" to "su_1"), calls)   // 서버는 형식을 먼저 봐서 토큰이 살아 있다
        assertEquals(PinChangeEnd.Changed, vm.state.value.end)
    }

    @Test fun `확인 단계의 뒤로는 첫 입력으로, 첫 입력의 뒤로는 닫기`() = runTest(dispatcher) {
        val vm = viewModel()
        passStepUp(vm, "su_1")
        "482913".forEach { vm.onKey(KeypadKey.Digit(it - '0')) }
        advanceUntilIdle()
        assertTrue(vm.pin.value.confirming)
        vm.back()
        assertNull(vm.state.value.end)
        assertEquals(false, vm.pin.value.confirming)

        vm.back()                                      // 첫 입력에서 뒤로 = 닫기
        assertEquals(PinChangeEnd.Closed(), vm.state.value.end)
    }

    private companion object {
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("안 쓰는 메서드: ${method.name}")
        } as T
    }
}
