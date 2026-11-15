package com.yamu.app.feature.my

import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.brandOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** 해제한 카드가 목록에서 제자리에 사라질 때까지 줄을 남겨 두는 규칙(M-TRAY-app-unregister-close, S15P31E103-504) */
class CardManageRemovingTest {

    private fun card(id: Long) = LinkedCard(
        userCardId = id, brand = brandOf("신한카드"), productName = "카드$id", isCheck = false, last4 = "000$id",
        paymentDay = 14, recognizedAmount = null, yearMonth = null,
    )

    private val old = listOf(card(1), card(2), card(3))

    @Test fun `사라지는 카드가 새 목록에 없으면 옛 자리에 다시 끼운다`() {
        assertEquals(listOf(1L, 2L, 3L), keepRemoving(listOf(card(1), card(3)), old, removingId = 2).map { it.userCardId })
        assertEquals("맨 앞이었으면 맨 앞", listOf(1L, 2L, 3L), keepRemoving(listOf(card(2), card(3)), old, removingId = 1).map { it.userCardId })
        assertEquals("맨 뒤였으면 맨 뒤", listOf(1L, 2L, 3L), keepRemoving(listOf(card(1), card(2)), old, removingId = 3).map { it.userCardId })
    }

    @Test fun `앞 카드가 같이 빠졌으면 남은 앞 카드 뒤에 끼운다`() {
        // 다른 기기에서 1번도 지웠다 — 남은 앞 카드(2번) 뒤, 앞 카드가 하나도 안 남았으면 맨 앞
        assertEquals(listOf(2L, 3L), keepRemoving(listOf(card(2)), old, removingId = 3).map { it.userCardId })
        assertEquals(listOf(2L, 3L), keepRemoving(listOf(card(3)), old, removingId = 2).map { it.userCardId })
    }

    @Test fun `사라지는 카드가 없거나 새 목록에 아직 있으면 새 목록 그대로`() {
        val fresh = listOf(card(1), card(3))
        assertEquals(fresh, keepRemoving(fresh, old, removingId = null))
        assertEquals(old, keepRemoving(old, old, removingId = 2))
        assertEquals("옛 목록에도 없으면 끼울 자리가 없다", fresh, keepRemoving(fresh, old, removingId = 9))
    }
}
