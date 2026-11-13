package com.yamu.app.navigation

import com.yamu.app.core.designsystem.component.bottomBarSlots
import org.junit.Assert.assertEquals
import org.junit.Test

/** 하단 탭 구성(S15P31E103-478) — 홈 · 소비분석 · [결제] · 실적관리 · 마이 */
class TopTabTest {

    @Test
    fun `탭은 홈 소비분석 실적관리 마이 순서다`() {
        assertEquals(listOf("홈", "소비분석", "실적관리", "마이"), TopTab.entries.map { it.label })
        assertEquals(TopTab.entries.map { it.label }, TopTab.items.map { it.label })
    }

    @Test
    fun `막대에서 결제는 소비분석과 실적관리 사이에 온다`() {
        val labels = bottomBarSlots(TopTab.entries.size, hasCenter = true).map { slot -> slot?.let { TopTab.entries[it].label } ?: "결제" }
        assertEquals(listOf("홈", "소비분석", "결제", "실적관리", "마이"), labels)
    }

    @Test
    fun `탭마다 그래프와 첫 화면이 다르다`() {
        assertEquals(TopTab.entries.size, TopTab.entries.map { it.graphClass }.toSet().size)
        assertEquals(TopTab.entries.size, TopTab.entries.map { it.routeClass }.toSet().size)
    }
}
