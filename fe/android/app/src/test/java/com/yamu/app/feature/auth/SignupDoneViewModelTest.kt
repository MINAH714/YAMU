package com.yamu.app.feature.auth

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.security.SessionInfo
import com.yamu.app.feature.auth.data.AuthRepository
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

/** 가입 완료 축하 팝 — 한 번 돌면 저장된 표시로 회전 · 프로세스 재시작 뒤에도 돌지 않는다 */
class SignupDoneViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val auth = object : AuthRepository by unusedAuth() {
        override suspend fun session() = SessionInfo(1L, "김야무", null, onboardingCompleted = false, userDeviceId = 3L, biometricEnabled = false)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `처음 들어오면 한 번 축하하고 시작하면 키를 비운다`() = runTest(dispatcher) {
        val vm = SignupDoneViewModel(auth, SavedStateHandle())
        assertEquals("signup", vm.celebrateKey.value)
        vm.celebrated()
        advanceUntilIdle()
        assertNull(vm.celebrateKey.value)
        assertEquals("김야무", vm.name.value)
    }

    @Test fun `저장된 표시가 있으면 다시 만들어져도 축하하지 않는다`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        SignupDoneViewModel(auth, saved).celebrated()
        val restored = SignupDoneViewModel(auth, SavedStateHandle(mapOf("signupCelebrated" to saved.get<Boolean>("signupCelebrated"))))
        advanceUntilIdle()
        assertNull(restored.celebrateKey.value)
    }

    private companion object {
        fun unusedAuth(): AuthRepository = Proxy.newProxyInstance(
            AuthRepository::class.java.classLoader,
            arrayOf(AuthRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as AuthRepository
    }
}
