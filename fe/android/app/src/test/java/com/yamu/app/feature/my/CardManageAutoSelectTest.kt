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

    private class StubMy : MyRepository by unused<MyRepository>() {
        val sent = mutableListOf<Boolean>()
        override suspend fun me(): ApiResult<MyInfo> = ApiResult.Failure(ApiError.Network)
        override suspend fun setAutoSelect(on: Boolean): ApiResult<Unit> {
            sent += on
            return ApiResult.Success(Unit)
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
        assertNull(vm.state.value.autoSelect)   // 스위치 상태를 바꾸지 않았다(이 시험에선 me 실패로 처음부터 모름)
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

    @Test fun `카드 목록을 못 받았으면 서버 판단에 맡긴다`() = runTest(dispatcher) {
        val my = StubMy()
        val vm = CardManageViewModel(my, StubLink(ApiResult.Failure(ApiError.Network)))
        vm.load()
        advanceUntilIdle()
        vm.setAutoSelect(false) {}
        advanceUntilIdle()
        assertEquals(listOf(false), my.sent)
    }
}

private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as T
