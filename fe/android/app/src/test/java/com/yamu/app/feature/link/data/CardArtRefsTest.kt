package com.yamu.app.feature.link.data

import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardArtRefsTest {

    /** 별명 '점심 카드'를 지은 톡톡 */
    private val toktok = LinkedCard(
        userCardId = 1, brand = brandOf("KB국민카드", color = "#FFBC00"), productName = "KB국민 청춘대로 톡톡카드", isCheck = false,
        last4 = "0001", paymentDay = 14, recognizedAmount = null, yearMonth = null, nickname = "점심 카드",
    )

    /** 카드사가 정지한 Mr.Life */
    private val mrLife = LinkedCard(
        userCardId = 2, brand = brandOf("신한카드"), productName = "신한카드 Mr.Life", isCheck = false,
        last4 = "0002", paymentDay = 25, recognizedAmount = null, yearMonth = null, issuerBlocked = true,
    )

    @Test
    fun `카드 목록에서 userCardId로 상품명 · 카드사 · 정지 여부를 찾는다`() {
        val refs = listOf(toktok, mrLife).toArtRefs()

        assertEquals(CardArtRef(toktok.brand, "KB국민 청춘대로 톡톡카드", dimmed = false), refs[1])
        assertEquals("신한카드 Mr.Life", refs.getValue(2).productName)
        assertTrue(refs.getValue(2).dimmed)
        assertNull(refs[3])
    }

    @Test
    fun `별명 카드도 카드 목록의 상품명으로 그림을 찾는다`() {
        val refs = listOf(toktok, mrLife).toArtRefs()

        assertNull(CardImages.forProduct("점심 카드"))
        assertNotNull(CardImages.forProduct(refs.getValue(1).productName))
        assertNotNull(CardImages.forProduct(refs.getValue(2).productName))
    }

    @Test
    fun `카드 목록을 못 받으면 빈 표`() = runTest {
        assertTrue(CardsOnlyLink(ApiResult.Failure(ApiError.Network)).cardArtRefs().isEmpty())
    }

    @Test
    fun `처음 한 번 받고, 받는 중에는 겹쳐 보내지 않는다`() = runTest {
        val link = CardsOnlyLink(ApiResult.Success(listOf(toktok)))
        val loader = CardArtRefsLoader(link, this)

        loader.ensure()
        loader.ensure(listOf(1))
        advanceUntilIdle()

        assertEquals(1, link.calls)
        assertEquals("KB국민 청춘대로 톡톡카드", loader.refs.value.getValue(1).productName)
    }

    @Test
    fun `아는 카드만 보이면 다시 받지 않고, 모르는 카드가 보이면 다시 받는다`() = runTest {
        val link = CardsOnlyLink(ApiResult.Success(listOf(toktok)))
        val loader = CardArtRefsLoader(link, this)
        loader.ensure()
        advanceUntilIdle()

        loader.ensure(listOf(1))
        advanceUntilIdle()
        assertEquals(1, link.calls)

        // 그 사이 Mr.Life를 등록했다
        link.result = ApiResult.Success(listOf(toktok, mrLife))
        loader.ensure(listOf(1, 2))
        advanceUntilIdle()
        assertEquals(2, link.calls)
        assertTrue(loader.refs.value.getValue(2).dimmed)
    }

    @Test
    fun `다시 받다 실패하면 알던 값을 지우지 않는다`() = runTest {
        val link = CardsOnlyLink(ApiResult.Success(listOf(toktok)))
        val loader = CardArtRefsLoader(link, this)
        loader.ensure()
        advanceUntilIdle()

        link.result = ApiResult.Failure(ApiError.Network)
        loader.ensure(listOf(1, 9))
        advanceUntilIdle()

        assertEquals(2, link.calls)
        assertEquals("KB국민 청춘대로 톡톡카드", loader.refs.value.getValue(1).productName)
        assertFalse(9L in loader.refs.value)
    }
}
