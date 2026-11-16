package com.yamu.app.feature.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 홈 첫 화면에 '놓치기 아까운 혜택' 제목만 걸리게 히어로를 늘리는 높이(S15P31E103-596) */
class HomeHeroExtraTest {

    @Test
    fun `칸이 화면 위쪽에 있으면 제목만 보일 만큼 늘린다`() {
        // 스크롤 칸 780dp, 칸 위 680dp → 780 − 48 − 680 = 52dp
        assertEquals(52.dp, heroExtraFor(viewport = 780.dp, missedTop = 680.dp, current = 0.dp))
    }

    @Test
    fun `이미 늘린 뒤 다시 재도 같은 값이다`() {
        // 52dp 늘려 칸이 732dp로 내려온 상태
        assertEquals(52.dp, heroExtraFor(viewport = 780.dp, missedTop = 732.dp, current = 52.dp))
    }

    @Test
    fun `내용이 화면보다 길면 늘리지 않는다`() {
        assertEquals(0.dp, heroExtraFor(viewport = 640.dp, missedTop = 700.dp, current = 0.dp))
    }

    @Test
    fun `키 큰 화면에서도 200dp까지만`() {
        assertEquals(200.dp, heroExtraFor(viewport = 1280.dp, missedTop = 600.dp, current = 0.dp))
    }
}
