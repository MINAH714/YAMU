package com.yamu.app.feature.my

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.LinkedOverview
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.my.data.MyInfo
import com.yamu.app.feature.my.data.MyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** 기본 카드 없이 자동 선택 끄기(S15P31E103-464) — 스위치를 바꾸지 않고 먼저 안내, 서버에는 보내지 않는다 */
class CardManageAutoSelectTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** 처음엔 자동 선택이 켜져 있다 — 막았을 때 스위치가 켜진 채인지 본다 */
    private class StubMy(private val saveResult: ApiResult<Unit> = ApiResult.Success(Unit)) : MyRepository by unused<MyRepository>() {
        val sent = mutableListOf<Boolean>()
        override suspend fun me(): ApiResult<MyInfo> = ApiResult.Success(
            MyInfo("야무", null, autoLockSeconds = 60, pinOnlyForCode = false, biometricEnabled = false, currentDeviceId = null, autoSelect = true),
        )
        override suspend fun setAutoSelect(on: Boolean): ApiResult<Unit> {
            sent += on
            return saveResult
        }
    }

    private class StubLink(private val overview: ApiResult<LinkedOverview>) : LinkRepository by unused<LinkRepository>() {
        override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> = overview
    }

    private fun card(id: Long, isDefault: Boolean) =
        LinkedCard(id, brandOf("신한카드"), "카드$id", false, "000$id", 14, null, null, isDefault = isDefault)

    private fun cards(vararg c: LinkedCard): ApiResult<LinkedOverview> = ApiResult.Success(LinkedOverview(emptyList(), c.toList(), null))

    @Test fun `기본 카드가 없으면 끄지 않고 안내만 — 서버에 보내지 않는다`() = runTest(dispatcher) {
        val my = StubMy()
        val vm = CardManageViewModel(my, StubLink(cards(card(1, false), card(2, false))))
        vm.load()
        advanceUntilIdle()
        var message: String? = "부르지 않음"
        vm.setAutoSelect(false) { message = it }
        advanceUntilIdle()
        assertEquals(NEEDS_DEFAULT_CARD, message)
        assertTrue(my.sent.isEmpty())
        assertEquals("켜져 있던 스위치가 꺼졌다 켜지지 않고 그대로", true, vm.state.value.autoSelect)
    }

    @Test fun `서버가 기본 카드 없음으로 거절하면 같은 문장 · 스위치는 원래대로`() = runTest(dispatcher) {
        // 앱이 받은 목록엔 기본 카드가 있었지만 그 사이 다른 기기에서 풀었다
        val my = StubMy(saveResult = ApiResult.Failure(ApiError.Conflict("DEFAULT_CARD_REQUIRED")))
        val vm = CardManageViewModel(my, StubLink(cards(card(1, true))))
        vm.load()
        advanceUntilIdle()
        var message: String? = "부르지 않음"
        vm.setAutoSelect(false) { message = it }
        advanceUntilIdle()
        assertEquals(listOf(false), my.sent)
        assertEquals(NEEDS_DEFAULT_CARD, message)
        assertEquals(true, vm.state.value.autoSelect)
    }

    @Test fun `기본 카드가 있으면 그대로 끈다`() = runTest(dispatcher) {
        val my = StubMy()
        val vm = CardManageViewModel(my, StubLink(cards(card(1, false), card(2, true))))
        vm.load()
        advanceUntilIdle()
        var message: String? = "부르지 않음"
        vm.setAutoSelect(false) { message = it }
        advanceUntilIdle()
        assertNull(message)
        assertEquals(listOf(false), my.sent)
        assertEquals(false, vm.state.value.autoSelect)
    }

    @Test fun `켜는 것은 기본 카드와 상관없다`() = runTest(dispatcher) {
        val my = StubMy()
        val vm = CardManageViewModel(my, StubLink(cards(card(1, false))))
        vm.load()
        advanceUntilIdle()
        vm.setAutoSelect(true) {}
        advanceUntilIdle()
        assertEquals(listOf(true), my.sent)
    }

    // 목록을 못 받으면 화면은 스위치 대신 오류(다시 시도)를 그린다 — 스위치가 보이는데 목록이 비었으면 등록 카드 0장
    @Test fun `카드가 0장이면 끄지 않고 카드 추가부터 안내 — 서버에 보내지 않는다`() = runTest(dispatcher) {
        val my = StubMy()
        val vm = CardManageViewModel(my, StubLink(cards()))
        vm.load()
        advanceUntilIdle()
        var message: String? = "부르지 않음"
        vm.setAutoSelect(false) { message = it }
        advanceUntilIdle()
        assertEquals(NEEDS_CARD_FIRST, message)
        assertTrue(my.sent.isEmpty())
        assertEquals(true, vm.state.value.autoSelect)
    }
}

private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
