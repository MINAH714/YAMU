package com.yamu.app.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

/** 하단 막대 칸 순서 — 가운데 버튼(null)은 탭 사이 한가운데, 탭 번호는 순서대로 */
class BottomBarSlotsTest {

    @Test
    fun `탭 넷이면 가운데 버튼이 세 번째 칸이다`() {
        assertEquals(listOf(0, 1, null, 2, 3), bottomBarSlots(tabCount = 4, hasCenter = true))
    }

    @Test
    fun `가운데 버튼이 없으면 탭만 순서대로`() {
        assertEquals(listOf(0, 1, 2, 3), bottomBarSlots(tabCount = 4, hasCenter = false))
    }

    @Test
    fun `탭이 홀수면 왼쪽에 하나 더 둔다`() {
        assertEquals(listOf(0, 1, null, 2), bottomBarSlots(tabCount = 3, hasCenter = true))
    }
}
