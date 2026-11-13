package com.yamu.app.feature.payment

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpResults
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.auth.pinPadSub
import com.yamu.app.feature.auth.pinPadTitle
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.payment.data.PaymentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** 결제 취소에서 연 본인 확인은 취소라고 말한다(S15P31E103-464) — 결제 코드 본인 확인은 그대로 */
class PayCodeCancelPromptTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = StepUpResults()
    private val auth = object : AuthRepository by unused<AuthRepository>() {
        override suspend fun biometricReady() = false
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun open(handle: SavedStateHandle) = PayCodeViewModel(
        unused<PaymentRepository>(),
        auth,
        StepUpFactory(auth, DeviceKeyManager(object : ContextWrapper(null) {}), results),
        unused<LinkRepository>(),
        handle,
    )

    @Test fun `결제 코드로 열면 지금 문구 그대로`() {
        open(SavedStateHandle())
        assertNull(results.pinTitle)
        assertEquals("인증하면 결제 코드가 보여요", results.detail)
        assertEquals("간편 비밀번호로\n결제 코드를 열어요", pinPadTitle(StepUpPurpose.BARCODE, results.pinTitle))
        assertEquals("인증하면 결제 코드가 보여요", pinPadSub(StepUpPurpose.BARCODE, results.detail))
    }

    @Test fun `결제 취소(경로에 paymentId)로 열면 간편 비밀번호 화면이 취소라고 말한다`() {
        open(SavedStateHandle(mapOf("paymentId" to 7L)))
        assertEquals("간편 비밀번호로\n결제를 취소해요", pinPadTitle(StepUpPurpose.BARCODE, results.pinTitle))
        assertEquals("인증하면 취소 코드가 보여요", pinPadSub(StepUpPurpose.BARCODE, results.detail))
    }

    @Test fun `지문 창 문구도 취소와 결제가 다르다`() {
        assertEquals("결제 취소", payCodePrompt(forCancel = true).title)
        assertEquals("결제 코드 열기", payCodePrompt(forCancel = false).title)
        assertNull(payCodePrompt(forCancel = false).pinTitle)
    }

    @Test fun `다른 목적의 간편 비밀번호 화면은 그대로`() {
        assertEquals("간편 비밀번호로\n카드를 해제해요", pinPadTitle(StepUpPurpose.CARD_DELETE))
        assertEquals("앱을 열 때 쓰는 6자리예요", pinPadSub(StepUpPurpose.APP_UNLOCK, null))
        assertEquals("인증하면 결제 코드가 보여요", pinPadSub(StepUpPurpose.BARCODE, null))
    }
}

private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
