package com.yamu.app.core.designsystem.card

import com.yamu.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardImagesTest {

    @Test
    fun `공식 상품명 10개가 각자 그림을 찾는다`() {
        val expected = mapOf(
            "현대카드ZERO Edition3(포인트형)" to R.drawable.card_hyundai_mzroe3,
            "신한카드 Point Plan" to R.drawable.card_shinhan_pointplan,
            "삼성 iD ON 카드" to R.drawable.card_samsung_id_on,
            "신한카드 Deep Oil" to R.drawable.card_shinhan_biabix,
            "KB국민 청춘대로 톡톡카드" to R.drawable.card_kb_toktok,
            "KB국민 노리2 체크카드(KB Pay)" to R.drawable.card_kb_07964,
            "하나카드 원더카드 2.0 DAILY" to R.drawable.card_hana_wonder2_daily,
            "하나카드 트래블로그 체크카드" to R.drawable.card_hana_travelog_check,
            "LOCA LIKIT Play" to R.drawable.card_lotte_p13949_a13949,
            "LOCA LIKIT Shop" to R.drawable.card_lotte_p13955_a13955,
        )
        expected.forEach { (name, res) -> assertEquals(name, res, CardImages.forProduct(name)) }
        assertEquals(10, CardImages.all.size)
        assertEquals(expected.values.toSet(), CardImages.all.toSet())
    }

    @Test
    fun `띄어쓰기 · 괄호 · 대소문자 · '카드' · 카드사 머리가 달라도 찾는다`() {
        assertEquals(R.drawable.card_kb_07964, CardImages.forProduct("노리2 체크카드 KB Pay"))
        assertEquals(R.drawable.card_kb_07964, CardImages.forProduct("KB국민카드 노리2체크(KB PAY)"))
        assertEquals(R.drawable.card_hyundai_mzroe3, CardImages.forProduct("현대카드 ZERO Edition3 포인트형"))
        assertEquals(R.drawable.card_samsung_id_on, CardImages.forProduct("삼성카드 iD ON"))
        assertEquals(R.drawable.card_samsung_id_on, CardImages.forProduct("iD ON"))
        assertEquals(R.drawable.card_shinhan_pointplan, CardImages.forProduct("Point Plan"))
        assertEquals(R.drawable.card_shinhan_biabix, CardImages.forProduct("  신한카드  deep oil "))
        assertEquals(R.drawable.card_hana_wonder2_daily, CardImages.forProduct("원더카드 2.0 DAILY"))
        assertEquals(R.drawable.card_hana_travelog_check, CardImages.forProduct("하나 트래블로그 체크카드"))
        assertEquals(R.drawable.card_kb_toktok, CardImages.forProduct("청춘대로 톡톡"))
        assertEquals(R.drawable.card_lotte_p13955_a13955, CardImages.forProduct("롯데카드 LOCA LIKIT Shop"))
    }

    @Test
    fun `시드 상품명 — ZERO 할인형은 포인트형과 같은 그림, Mr Life · 처음은 그림 없음`() {
        assertEquals(R.drawable.card_hyundai_mzroe3, CardImages.forProduct("현대카드 ZERO Edition3 (할인형)"))
        assertNull(CardImages.forProduct("신한카드 Mr.Life"))
        assertNull(CardImages.forProduct("신한카드 처음"))
    }

    @Test
    fun `이름이 비슷한 다른 상품은 찾지 않는다`() {
        assertNull(CardImages.forProduct("현대카드 ZERO Edition2(할인형)"))
        assertNull(CardImages.forProduct("현대카드 ZERO Edition3"))
        assertNull(CardImages.forProduct("LOCA LIKIT"))
        assertNull(CardImages.forProduct("하나카드 트래블로그 신용카드"))
        assertNull(CardImages.forProduct("KB국민 노리2 체크카드"))
        assertNull(CardImages.forProduct("원더카드"))
    }

    @Test
    fun `그림이 없는 카드 · 빈 이름은 null — 화면은 카드사 칩으로 떨어진다`() {
        assertNull(CardImages.forProduct("우리 통신·렌탈 신용카드"))
        assertNull(CardImages.forProduct("NH농협 교통·쇼핑 신용카드"))
        assertNull(CardImages.forProduct(""))
        assertNull(CardImages.forProduct("카드"))
        assertNull(CardImages.forProduct("신한카드"))
    }
}
