package com.yamu.app.feature.report.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** 홈 '자산 흐름 보기' → 소비분석 자산 흐름 칸 요청(S15P31E103-479, 번호는 !312 리뷰) */
class ReportJumpTest {

    private val thisMonth = YearMonth.of(2026, 11)
    private val months = ReportMonth { thisMonth }
    private val jump = ReportJump(months)

    @Test
    fun `자산 흐름 요청은 고른 달을 이번 달로 되돌린다`() {
        months.select(YearMonth.of(2026, 8))
        jump.request(ReportSection.ASSET_FLOW)
        assertEquals(ReportSection.ASSET_FLOW, jump.pending.value?.section)
        assertEquals(thisMonth, months.current().month)
    }

    @Test
    fun `내려간 뒤에는 요청이 남지 않는다`() {
        jump.request(ReportSection.ASSET_FLOW)
        jump.consume(jump.pending.value!!)
        assertNull(jump.pending.value)
    }

    @Test
    fun `요청이 없을 때 consume해도 아무 일 없다`() {
        jump.consume(JumpRequest(ReportSection.ASSET_FLOW, 1))
        assertNull(jump.pending.value)
    }

    @Test
    fun `요청마다 번호가 커지고, 앞 요청을 버려도 새 요청은 남는다`() {
        jump.request(ReportSection.ASSET_FLOW)
        val first = jump.pending.value!!
        val loaded = jump.stamp()                     // 그 뒤에 끝난 불러오기
        jump.request(ReportSection.ASSET_FLOW)
        val second = jump.pending.value!!
        assertTrue(first.id < loaded && loaded < second.id)
        jump.consume(first)                           // 앞 화면이 늦게 버림
        assertEquals(second, jump.pending.value)
    }
}
