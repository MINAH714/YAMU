package com.yamu.app.feature.auth

import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.PIN_SHAKE_MS
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.SignupMode
import com.yamu.app.feature.auth.data.SignupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

/** A-06 간편 비밀번호 만들기 — 빠르게 누른 키 · 틀렸을 때 흔들기 · 뒤로 가기(QA 07 · P1-17) */
@OptIn(ExperimentalCoroutinesApi::class)
class PinSetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val saved = mutableListOf<String>()
    private var setPinResult: ApiResult<Unit> = ApiResult.Success(Unit)
    private var registered = 0

    private val auth = object : AuthRepository by unused<AuthRepository>() {
        override fun canUseBiometric() = true
    }

    private val signup = object : SignupRepository by unused<SignupRepository>() {
        override val mode: SignupMode = SignupMode.SIGNUP
        override suspend fun setPin(pin: String): ApiResult<Unit> {
            saved += pin
            return setPinResult
        }
        override suspend fun registerDevice(biometric: Boolean): ApiResult<Unit> {
            registered++
            return ApiResult.Success(Unit)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun PinSetupViewModel.type(pin: String) = pin.forEach { onKey(KeypadKey.Digit(it - '0')) }

    /** 첫 입력 6자리 → 점 6개를 보여 주는 시간이 지나 확인 단계로 */
    private fun TestScope.typeFirst(vm: PinSetupViewModel, pin: String) {
        vm.type(pin)
        advanceTimeBy(PIN_FILLED_HOLD_MS + 1)
        runCurrent()
    }

    @Test fun `6자리를 채우면 잠깐 점 6개를 보여 주고 그사이 누른 키는 확인 입력에 들어가지 않는다`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        vm.type("482913")
        assertEquals(6, vm.pin.value.input.length)   // 점 6개가 다 찬 모습
        assertFalse(vm.pin.value.confirming)

        vm.onKey(KeypadKey.Digit(7))                  // 빠르게 누른 7번째 탭
        advanceTimeBy(PIN_FILLED_HOLD_MS + 1)
        runCurrent()

        val s = vm.pin.value
        assertTrue(s.confirming)
        assertEquals("", s.input)                     // 7은 버려졌다
        assertFalse(s.holding)
    }

    @Test fun `확인 단계로 넘어갈 때 키패드는 다시 섞지 않는다`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        val before = vm.pin.value.shuffleKey
        typeFirst(vm, "482913")
        assertEquals(before, vm.pin.value.shuffleKey)
    }

    @Test fun `두 번 같으면 한 번만 저장하고 지문 묻기로`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        typeFirst(vm, "482913")
        vm.type("482913")
        vm.onKey(KeypadKey.Digit(1))                  // 저장하는 동안 더 눌러도
        advanceUntilIdle()
        assertEquals(listOf("482913"), saved)
        assertEquals(PinSetupStep.Biometric, vm.state.value.step)
    }

    @Test fun `처음과 다르면 흔들고 흔들리는 동안은 키를 버린 뒤 첫 입력부터 다시`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        typeFirst(vm, "482913")
        val shuffled = vm.pin.value.shuffleKey
        vm.type("482914")

        val s = vm.pin.value
        assertFalse(s.confirming)
        assertEquals("처음 입력과 달라요. 처음부터 다시 만들어 주세요", s.error)
        assertEquals(1, s.errorKey)                   // 점이 흔들리고 진동이 한 번
        assertEquals(shuffled + 1, s.shuffleKey)      // 처음부터 다시 할 때는 새로 섞는다
        vm.onKey(KeypadKey.Digit(3))                  // 흔들리는 중에 누른 키
        assertEquals("", vm.pin.value.input)

        advanceTimeBy(PIN_SHAKE_MS.toLong() + 1)
        runCurrent()
        vm.onKey(KeypadKey.Digit(3))
        assertEquals("3", vm.pin.value.input)
        assertNull(vm.pin.value.error)
        assertTrue(saved.isEmpty())
    }

    @Test fun `쉬운 번호도 흔들고 첫 입력에 남는다`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        vm.type("123456")
        assertEquals("쉬운 번호는 쓸 수 없어요", vm.pin.value.error)
        assertEquals(1, vm.pin.value.errorKey)
        assertFalse(vm.pin.value.confirming)
        advanceUntilIdle()
        assertFalse(vm.pin.value.confirming)
    }

    @Test fun `확인 단계에서 뒤로 가면 화면을 닫지 않고 첫 입력으로 돌아간다`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        typeFirst(vm, "482913")
        vm.type("48")

        assertTrue(vm.back())                         // 처리함 = 화면을 닫지 않는다
        val s = vm.pin.value
        assertFalse(s.confirming)
        assertEquals("", s.input)
        assertEquals(0, s.errorKey)                   // 틀린 게 아니라 흔들지 않는다

        assertFalse(vm.back())                        // 첫 입력에서 뒤로 = 화면을 닫는다
    }

    @Test fun `점 6개를 보여 주는 사이 뒤로 가도 첫 입력으로 돌아가고 확인 단계로 넘어가지 않는다`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        vm.type("482913")
        assertTrue(vm.back())
        advanceUntilIdle()
        assertFalse(vm.pin.value.confirming)
        assertEquals("", vm.pin.value.input)
    }

    @Test fun `PIN을 저장한 뒤 지문 묻기에서는 뒤로 가기를 막는다`() = runTest(dispatcher) {
        val vm = PinSetupViewModel(auth, signup)
        typeFirst(vm, "482913")
        vm.type("482913")
        advanceUntilIdle()
        assertEquals(PinSetupStep.Biometric, vm.state.value.step)
        assertTrue(vm.back())                         // 막음 — 본인인증까지 빠지지 않는다
        assertEquals(PinSetupStep.Biometric, vm.state.value.step)
    }

    @Test fun `저장이 실패하면 까닭을 띄우고 흔든 뒤 첫 입력부터`() = runTest(dispatcher) {
        setPinResult = ApiResult.Failure(ApiError.InvalidRequest("INVALID_PIN_FORMAT"))
        val vm = PinSetupViewModel(auth, signup)
        typeFirst(vm, "482913")
        vm.type("482913")
        advanceUntilIdle()
        val s = vm.pin.value
        assertEquals("쉬운 번호는 쓸 수 없어요", s.error)
        assertEquals(1, s.errorKey)
        assertFalse(s.confirming)
        assertFalse(s.holding)
        assertEquals(PinSetupStep.Create, vm.state.value.step)
        assertFalse(vm.state.value.busy)
    }

    private companion object {
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("안 쓰는 메서드: ${method.name}")
        } as T
    }
}
