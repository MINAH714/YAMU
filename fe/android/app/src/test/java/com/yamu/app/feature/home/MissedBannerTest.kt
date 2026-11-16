package com.yamu.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** 홈 놓치기 아까운 혜택 배너(S15P31E103-575) — 화면 읽기 문장 */
class MissedBannerTest {

    @Test
    fun `여러 장이면 몇 번째인지 뒤에 붙인다`() {
        assertEquals(
            "신한카드 Mr.Life, TIME 서비스 10% 할인 6,700원 남음, 5개 중 1번째",
            missedBannerDescription("신한카드 Mr.Life, TIME 서비스 10% 할인 6,700원 남음", index = 0, count = 5),
        )
        assertEquals("KB국민, 다음 달 혜택 조건, 5개 중 5번째", missedBannerDescription("KB국민, 다음 달 혜택 조건", index = 4, count = 5))
    }

    @Test
    fun `한 장이면 순서를 말하지 않는다`() {
        assertEquals("삼성 iD ON, 생활 업종 공유 한도 2,600원 남음", missedBannerDescription("삼성 iD ON, 생활 업종 공유 한도 2,600원 남음", index = 0, count = 1))
    }
}
