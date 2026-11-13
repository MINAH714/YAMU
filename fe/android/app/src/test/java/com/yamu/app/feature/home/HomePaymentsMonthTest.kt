package com.yamu.app.feature.home

import com.yamu.app.feature.report.data.ReportMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/** 홈 최근 결제 '전체 보기' — 결제 내역을 최근 결제가 든 달(한국 기준)로 연다(!300 리뷰 중간 1) */
class HomePaymentsMonthTest {
    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)

    @Test fun `결제 시각(UTC)을 한국 기준 달로`() {
        assertEquals(nov, paymentMonthOf("2026-11-30T08:05:00Z"))   // 11/30 17:05 KST
        assertEquals(dec, paymentMonthOf("2026-11-30T15:30:00Z"))   // 12/1 00:30 KST — UTC로는 아직 11월
        assertEquals(nov, paymentMonthOf("2026-11-30T23:59:00+09:00"))
    }

    @Test fun `시각이 없거나 못 읽으면 null(이번 달로 연다)`() {
        assertNull(paymentMonthOf(null))
        assertNull(paymentMonthOf(""))
        assertNull(paymentMonthOf("어제 17:05"))
    }

    @Test fun `12월 1일에 11월 30일 결제를 보고 전체 보기 — 결제 내역은 11월`() {
        val months = ReportMonth { dec }
        months.select(paymentMonthOf("2026-11-30T08:05:00Z")!!)
        assertEquals(nov, months.current().month)
        months.select(paymentMonthOf("2026-12-01T01:00:00Z")!!)   // 이번 달 결제면 '이번 달 따라가기'로 돌아간다
        assertEquals(dec, months.current().month)
    }
}
