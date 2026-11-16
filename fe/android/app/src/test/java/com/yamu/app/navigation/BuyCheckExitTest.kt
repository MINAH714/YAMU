package com.yamu.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.KClass

/** 이거 사도 될까?에서 잔고예보로 나가는 길(저장 · '잔고예보에서 확인하기') — 들어온 곳에 따라 뒤로 가는 곳이 달라진다(S15P31E103-586) */
class BuyCheckExitTest {

    private fun exitWith(vararg stack: KClass<*>) = buyCheckExit { it in stack }

    @Test
    fun `잔고예보에서 들어왔으면 그 잔고예보로 돌아간다`() {
        assertEquals(
            BuyCheckExit.ForecastBelow,
            exitWith(Home::class, Forecast::class, BuyCheckInput::class, BuyCheckConditions::class, BuyCheckResult::class),
        )
    }

    @Test
    fun `마이 '모든 기능'에서 들어왔으면 금액 입력부터 걷어 내고 그 자리에 잔고예보를 올린다`() {
        assertEquals(
            BuyCheckExit.ReplaceWithForecast(BuyCheckInput::class),
            exitWith(Home::class, My::class, BuyCheckInput::class, BuyCheckConditions::class, BuyCheckResult::class),
        )
    }

    @Test
    fun `마이에서 연 잔고예보를 거쳐 들어왔으면 그 잔고예보로 돌아간다`() {
        assertEquals(
            BuyCheckExit.ForecastBelow,
            exitWith(Home::class, My::class, Forecast::class, BuyCheckInput::class, BuyCheckConditions::class, BuyCheckResult::class),
        )
    }

    @Test
    fun `디버그 메뉴에서 결과부터 열었으면 결과부터 걷어 낸다`() {
        assertEquals(BuyCheckExit.ReplaceWithForecast(BuyCheckResult::class), exitWith(DebugMenu::class, BuyCheckResult::class))
    }

    @Test
    fun `이미 잔고예보로 나갔으면(버튼 연타) 다시 옮기지 않는다`() {
        assertEquals(BuyCheckExit.Left, exitWith(Home::class, Forecast::class))
        assertEquals(BuyCheckExit.Left, exitWith(Home::class, My::class, Forecast::class))
    }
}
