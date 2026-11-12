package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

/** 홈 최근 결제 줄 시각(H-01c, S15P31E103-428) — 서버는 UTC(Z)로 주고 줄은 한국 시각으로 */
class PaidAtLabelTest {

    private val now = ZonedDateTime.of(2026, 11, 12, 3, 45, 0, 0, KST)   // UTC로는 11/11 18:45

    private fun label(iso: String?) = paidAtLabel(iso, now)

    @Test
    fun `1분이 안 됐으면 방금`() {
        assertEquals("방금", label("2026-11-11T18:44:01Z"))
        assertEquals("방금", label("2026-11-11T18:45:00Z"))
    }

    @Test
    fun `서버 시계가 조금 앞서 있어도 방금`() {
        assertEquals("방금", label("2026-11-11T18:47:00Z"))   // 2분 뒤
        assertEquals("방금", label("2026-11-11T18:50:00Z"))   // 5분 뒤까지
    }

    @Test
    fun `1시간이 안 됐으면 몇 분 전`() {
        assertEquals("1분 전", label("2026-11-11T18:44:00Z"))
        assertEquals("59분 전", label("2026-11-11T17:46:00Z"))
    }

    @Test
    fun `1시간이 넘으면 날짜에 따라 오늘 · 어제 · 월일`() {
        assertEquals("오늘 00:05", label("2026-11-11T15:05:00Z"))   // 한국 11/12 00:05
        assertEquals("어제 23:50", label("2026-11-11T14:50:00Z"))   // 한국 11/11 23:50
        assertEquals("11/10 17:05", label("2026-11-10T08:05:00Z")) // 에뮬레이터에서 '방금'으로 보이던 결제
    }

    @Test
    fun `한국 날짜로 오늘 · 어제를 가른다`() {
        val afternoon = ZonedDateTime.of(2026, 11, 12, 15, 0, 0, 0, KST)
        assertEquals("오늘 09:05", paidAtLabel("2026-11-12T00:05:00Z", afternoon))   // UTC로는 같은 날, 한국으로도 오늘
        assertEquals("어제 23:30", paidAtLabel("2026-11-11T14:30:00Z", afternoon))   // UTC 11/11, 한국 11/11 → 어제
        assertEquals("오늘 08:30", paidAtLabel("2026-11-11T23:30:00Z", afternoon))   // UTC 11/11이지만 한국은 11/12
    }

    @Test
    fun `서버 시각이 한참 뒤면 방금이 아니라 그 시각으로`() {
        assertEquals("오늘 05:00", label("2026-11-11T20:00:00Z"))
    }

    @Test
    fun `오프셋이 붙은 시각도 읽는다`() {
        assertEquals("11/10 17:05", label("2026-11-10T17:05:00+09:00"))
    }

    @Test
    fun `시각이 없거나 못 읽으면 null`() {
        assertNull(label(null))
        assertNull(label(""))
        assertNull(label("어제"))
    }
}
