package com.yamu.app.feature.auth

import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.SessionStart
import com.yamu.app.feature.auth.data.SignupRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** 첫 화면(A-01) '로그인' — 로그아웃 뒤처럼 이 폰에 기기가 남아 있으면 간편 비밀번호로, 없으면 본인인증으로(2차 QA 14) */
class WelcomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(var start: SessionStart) : AuthRepository by unused() {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun sessionStart(): SessionStart {
            calls++
            gate?.await()
            return start
        }
    }

    private val signup = object : SignupRepository by unused() {
        var cleared = 0
        override fun clear() { cleared++ }
    }

    @Test fun `기기가 남아 있으면 간편 비밀번호로 간다 - 로그아웃 뒤`() = runTest(dispatcher) {
        val vm = WelcomeViewModel(signup, FakeAuth(SessionStart.Login))
        vm.login()
        advanceUntilIdle()
        assertEquals(WelcomeLogin.PIN, vm.login.value)
    }

    @Test fun `기기가 없으면 본인인증으로 이 폰을 등록한다`() = runTest(dispatcher) {
        val vm = WelcomeViewModel(signup, FakeAuth(SessionStart.Welcome))
        vm.login()
        advanceUntilIdle()
        assertEquals(WelcomeLogin.VERIFY, vm.login.value)
    }

    @Test fun `연타해도 한 번만 정하고 옮긴 뒤 돌아오면 다시 옮기지 않는다`() = runTest(dispatcher) {
        val auth = FakeAuth(SessionStart.Login).apply { gate = CompletableDeferred() }
        val vm = WelcomeViewModel(signup, auth)
        vm.login()
        advanceUntilIdle()
        vm.login()                       // 확인 중에 또 누름
        auth.gate!!.complete(Unit)
        advanceUntilIdle()
        vm.login()                       // 정해진 뒤 화면이 옮기기 전에 또 누름
        advanceUntilIdle()
        assertEquals(1, auth.calls)
        assertEquals(WelcomeLogin.PIN, vm.login.value)

        vm.loginHandled()
        assertNull(vm.login.value)       // 간편 비밀번호에서 뒤로 와도 그대로 첫 화면
    }

    @Test fun `간편 비밀번호에서 돌아와 다시 누르면 그때 기기 상태로 다시 정한다`() = runTest(dispatcher) {
        val auth = FakeAuth(SessionStart.Login)
        val vm = WelcomeViewModel(signup, auth)
        vm.login()
        advanceUntilIdle()
        vm.loginHandled()

        auth.start = SessionStart.Welcome   // 그사이 기기 키를 잃어 저장소가 기기 정보를 지웠다
        vm.login()
        advanceUntilIdle()
        assertEquals(WelcomeLogin.VERIFY, vm.login.value)
        assertEquals(2, auth.calls)
    }

    @Test fun `첫 화면에 오면 이전 가입 진행을 버린다`() {
        WelcomeViewModel(signup, FakeAuth(SessionStart.Welcome)).reset()
        assertEquals(1, signup.cleared)
    }

    private companion object {
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
    }
}
