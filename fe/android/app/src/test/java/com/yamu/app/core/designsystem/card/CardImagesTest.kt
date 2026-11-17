package com.yamu.app.core.designsystem.card

import com.yamu.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardImagesTest {

    /** 기존 BE 시드 17종과 QA IBK나라사랑카드 — 상품명은 시드 그대로 */
    private val seed = mapOf(
        "하나카드 트래블로그 체크카드" to R.drawable.card_hana_travelog_check,
        "현대카드 M ME4" to R.drawable.card_hyundai_m,
        "현대카드ZERO Edition3(포인트형)" to R.drawable.card_hyundai_mzroe3,
        "현대카드 ZERO Edition3 (할인형)" to R.drawable.card_hyundai_zero_edition3_discount,
        "카카오뱅크 프렌즈 체크" to R.drawable.card_kakaobank_friends_check,
        "KB국민 노리 체크" to R.drawable.card_kb_01664,
        "KB국민 노리2 체크카드(KB Pay)" to R.drawable.card_kb_07964,
        "KB국민 청춘대로 톡톡카드" to R.drawable.card_kb_toktok,
        "LOCA LIKIT Eat" to R.drawable.card_lotte_p13943_a13943,
        "LOCA LIKIT Play" to R.drawable.card_lotte_p13949_a13949,
        "LOCA LIKIT Shop" to R.drawable.card_lotte_p13955_a13955,
        "LOCA LIKIT 2.0" to R.drawable.card_lotte_p15835_a15835,
        "삼성 iD SIMPLE" to R.drawable.card_samsung_aap1767,
        "삼성 iD ON 카드" to R.drawable.card_samsung_id_on,
        "삼성 taptap O" to R.drawable.card_samsung_taptap_o,
        "IBK나라사랑카드" to R.drawable.card_ibk_narasarang,
        "신한카드 처음" to R.drawable.card_shinhan_cheoeum,
        "신한카드 Mr.Life" to R.drawable.card_shinhan_mrlife,
    )

    @Test
    fun `시드 18종이 모두 각자 그림을 찾는다`() {
        seed.forEach { (name, res) -> assertEquals(name, res, CardImages.forProduct(name)) }
        assertEquals(18, CardImages.all.size)
        assertEquals(seed.values.toSet(), CardImages.all.toSet())
    }

    @Test
    fun `ZERO 할인형은 포인트형과 다른 그림 — 서버 image_key와 같은 원본`() {
        assertNotEquals(
            CardImages.forProduct("현대카드ZERO Edition3(포인트형)"),
            CardImages.forProduct("현대카드 ZERO Edition3 (할인형)"),
        )
    }

    @Test
    fun `시드 적재가 보류된 상품은 그림이 없다`() {
        assertNull(CardImages.forProduct("신한카드 Point Plan"))
        assertNull(CardImages.forProduct("신한카드 Deep Oil"))
        assertNull(CardImages.forProduct("하나카드 원더카드 2.0 DAILY"))
    }

    @Test
    fun `띄어쓰기 · 괄호 · 대소문자 · '카드' · 카드사 머리가 달라도 찾는다`() {
        assertEquals(R.drawable.card_ibk_narasarang, CardImages.forProduct("IBK 나라사랑 카드"))
        assertEquals(R.drawable.card_kb_07964, CardImages.forProduct("노리2 체크카드 KB Pay"))
        assertEquals(R.drawable.card_kb_07964, CardImages.forProduct("KB국민카드 노리2체크(KB PAY)"))
        assertEquals(R.drawable.card_hyundai_mzroe3, CardImages.forProduct("현대카드 ZERO Edition3 포인트형"))
        assertEquals(R.drawable.card_samsung_id_on, CardImages.forProduct("삼성카드 iD ON"))
        assertEquals(R.drawable.card_samsung_id_on, CardImages.forProduct("iD ON"))
        assertEquals(R.drawable.card_kb_toktok, CardImages.forProduct("  KB국민카드  청춘대로 톡톡 "))
        assertEquals(R.drawable.card_hana_travelog_check, CardImages.forProduct("하나 트래블로그 체크카드"))
        assertEquals(R.drawable.card_kb_toktok, CardImages.forProduct("청춘대로 톡톡"))
        assertEquals(R.drawable.card_lotte_p13955_a13955, CardImages.forProduct("롯데카드 LOCA LIKIT Shop"))
        assertEquals(R.drawable.card_shinhan_mrlife, CardImages.forProduct("Mr. Life"))
        assertEquals(R.drawable.card_kakaobank_friends_check, CardImages.forProduct("카카오뱅크 프렌즈 체크카드"))
        assertEquals(R.drawable.card_kakaobank_friends_check, CardImages.forProduct("프렌즈 체크"))
        assertEquals(R.drawable.card_samsung_taptap_o, CardImages.forProduct("삼성카드 taptap O"))
        assertEquals(R.drawable.card_lotte_p15835_a15835, CardImages.forProduct("롯데 LOCA LIKIT 2.0"))
    }

    @Test
    fun `이름이 비슷한 다른 상품은 찾지 않는다`() {
        assertNull(CardImages.forProduct("KB국민 나라사랑카드"))
        assertNull(CardImages.forProduct("하나 나라사랑카드"))
        assertNull(CardImages.forProduct("현대카드 ZERO Edition2(할인형)"))
        assertNull(CardImages.forProduct("현대카드 ZERO Edition3"))
        assertNull(CardImages.forProduct("LOCA LIKIT"))
        assertNull(CardImages.forProduct("하나카드 트래블로그 신용카드"))
        assertNull(CardImages.forProduct("KB국민 노리2 체크카드"))
        assertNull(CardImages.forProduct("원더카드"))
        assertNull(CardImages.forProduct("현대카드 M"))
        assertNull(CardImages.forProduct("삼성 iD"))
        assertNull(CardImages.forProduct("카카오뱅크 체크"))
    }

    @Test
    fun `그림이 없는 카드 · 빈 이름은 null — 화면은 카드사 칩으로 떨어진다`() {
        assertNull(CardImages.forProduct("우리 통신·렌탈 신용카드"))
        assertNull(CardImages.forProduct("NH농협 교통·쇼핑 신용카드"))
        assertNull(CardImages.forProduct(""))
        assertNull(CardImages.forProduct("카드"))
        assertNull(CardImages.forProduct("신한카드"))
        assertNull(CardImages.forProduct("카카오뱅크"))
    }
}
