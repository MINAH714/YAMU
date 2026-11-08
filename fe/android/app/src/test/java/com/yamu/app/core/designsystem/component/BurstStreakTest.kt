package com.yamu.app.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

/** 축하 팝이 연달아 터질 때 줄이는 규칙 — 쉬는 시간 400ms, 이어진 것 3번까지 고리 */
class BurstStreakTest {

    @Test
    fun `처음 팝은 고리와 파티클을 모두 그린다`() {
        assertEquals(BurstParts.ALL, BurstStreak().next(now = 0))
    }

    @Test
    fun `앞 버스트가 끝나고 400ms 안에 오면 파티클을 빼고, 이어진 것이 3번을 넘으면 고리도 뺀다`() {
        val streak = BurstStreak()
        assertEquals(BurstParts.ALL, streak.next(now = 0))             // 끝 600
        assertEquals(BurstParts.RIPPLE_ONLY, streak.next(now = 500))   // 600 + 400 = 1000 전 → 2번째
        assertEquals(BurstParts.RIPPLE_ONLY, streak.next(now = 1000))  // 1100 + 400 = 1500 전 → 3번째
        assertEquals(BurstParts.NONE, streak.next(now = 1500))         // 1600 + 400 = 2000 전 → 4번째
    }

    @Test
    fun `쉬는 시간이 지나면 다시 처음처럼 모두 그린다`() {
        val streak = BurstStreak()
        streak.next(now = 0)
        streak.next(now = 500)
        assertEquals(BurstParts.RIPPLE_ONLY, streak.next(now = 1499))  // 500 + 600 + 400 = 1500 직전
        assertEquals(BurstParts.ALL, streak.next(now = 1499 + 600 + 400))  // 끝 2099 + 400 = 2499부터는 새로
    }
}
