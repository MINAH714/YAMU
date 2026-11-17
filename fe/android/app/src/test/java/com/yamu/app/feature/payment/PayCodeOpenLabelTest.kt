package com.yamu.app.feature.payment

import android.content.ContextWrapper
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.StepUpResults
import com.yamu.app.feature.auth.StepUpUi
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpChallenge
import com.yamu.app.feature.auth.data.StepUpPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/** '결제 코드 열 때 인증 = 간편 비밀번호만'이면 지문이 있는 폰이어도 버튼에 '지문으로'를 붙이지 않는다 */
class PayCodeOpenLabelTest {

    @Test fun `서버가 지문을 허락했을 때만 지문으로`() {
        assertEquals("지문으로 결제 코드 열기", payCodeOpenLabel(biometricReady = true, biometricAllowed = true))
        assertEquals("결제 코드 열기", payCodeOpenLabel(biometricReady = true, biometricAllowed = false))
        assertEquals("묻기 전에는 어느 쪽인지 몰라 중립 문구", "결제 코드 열기", payCodeOpenLabel(biometricReady = true, biometricAllowed = null))
        assertEquals("결제 코드 열기", payCodeOpenLabel(biometricReady = false, biometricAllowed = true))
    }

    @Test fun `간편 비밀번호만이면 지문이 있어도 challenge가 지문을 빼고 간편 비밀번호 화면으로`() {
        val dispatcher = StandardTestDispatcher()
        val auth = object : AuthRepository by unused<AuthRepository>() {
            override suspend fun biometricReady() = true
            override suspend fun challenge(purpose: String): ApiResult<StepUpChallenge> =
                ApiResult.Success(StepUpChallenge("ch_1", "abc", allowsBiometric = false))
        }
        val stepUp = StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), StepUpResults())
            .create(CoroutineScope(dispatcher))
        assertNull(stepUp.biometricAllowed.value)

        stepUp.start(StepUpPurpose.BARCODE, StepUpPrompt("결제 코드 열기", null), onError = {}) { }
        dispatcher.scheduler.runCurrent()

        assertEquals(false, stepUp.biometricAllowed.value)
        assertEquals(StepUpUi.OpenPin(StepUpPurpose.BARCODE), stepUp.ui.value)
        assertEquals("결제 코드 열기", payCodeOpenLabel(biometricReady = true, biometricAllowed = stepUp.biometricAllowed.value))
    }
}

private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
