package com.yamu.app.core.designsystem.component

import androidx.compose.ui.geometry.Rect
import com.yamu.app.core.designsystem.theme.YamuMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

/** 버튼에서 커지는 확인 트레이 자리 · 박자(M-TRAY-app, S15P31E103-503) */
class GrowTrayTest {

    @Test fun `트레이는 좌우 여백 · 시스템 바 위에 내용 높이만큼 뜬다`() {
        val r = TrayGeometry.trayRect(hostW = 360f, hostH = 800f, contentH = 300f, margin = 12f, bottomInset = 24f, topInset = 40f)
        assertEquals(Rect(12f, 464f, 348f, 764f), r)
    }

    @Test fun `내용이 화면보다 크면 위도 시스템 바 아래 여백까지만`() {
        val r = TrayGeometry.trayRect(hostW = 360f, hostH = 800f, contentH = 2_000f, margin = 12f, bottomInset = 24f, topInset = 40f)
        assertEquals(52f, r.top)
        assertEquals(764f, r.bottom)
        assertEquals(712f, TrayGeometry.maxContentHeight(800f, 12f, 24f, 40f))
    }

    @Test fun `버튼 자리에서 트레이 자리까지 곧게 커진다`() {
        val from = Rect(140f, 500f, 220f, 540f)
        val to = Rect(12f, 464f, 348f, 764f)
        assertEquals(from, TrayGeometry.lerpRect(from, to, 0f))
        assertEquals(to, TrayGeometry.lerpRect(from, to, 1f))
        assertEquals(Rect(76f, 482f, 284f, 652f), TrayGeometry.lerpRect(from, to, 0.5f))
    }

    @Test fun `뒤 화면이 줄어든 채 잰 버튼 자리를 줄기 전 자리로 되돌린다`() {
        val w = 360f
        val h = 800f
        val s = YamuMotion.BACKDROP_SCALE
        val original = Rect(140f, 500f, 220f, 540f)
        val shrunk = Rect(w / 2 + (original.left - w / 2) * s, h / 2 + (original.top - h / 2) * s, w / 2 + (original.right - w / 2) * s, h / 2 + (original.bottom - h / 2) * s)
        val back = TrayGeometry.unscale(shrunk, w, h, s)
        assertEquals(original.left, back.left, 0.01f)
        assertEquals(original.top, back.top, 0.01f)
        assertEquals(original.right, back.right, 0.01f)
        assertEquals(original.bottom, back.bottom, 0.01f)
    }

    @Test fun `열림 spring은 넘침 없이 230ms에 99%`() {
        // 임계 감쇠(dampingRatio 1) · 질량 1: 남은 거리 = (1 + ωt)e^(-ωt), ω = √강성
        val w = sqrt(YamuMotion.TRAY_STIFFNESS.toDouble())
        fun done(ms: Int): Double { val t = ms / 1000.0; return 1 - (1 + w * t) * exp(-w * t) }
        assertEquals(0.99, done(YamuMotion.TRAY_OPEN_MS), 0.001)
        assertTrue("200ms에는 아직 99% 전", done(200) < 0.99)
        assertTrue("넘침이 없으니 늘 1 아래", (1..1000).all { done(it) < 1.0 })
    }

    @Test fun `박자는 명세대로 — 닫힘 160 · 색 120 · 내용 60부터 200 · 누름 막기 350 · 뒤 화면 92퍼센트`() {
        assertEquals(160, YamuMotion.TRAY_CLOSE_MS)
        assertEquals(120, YamuMotion.COLOR_SHIFT_MS)
        assertEquals(260, YamuMotion.TRAY_CONTENT_DELAY_MS + YamuMotion.TRAY_CONTENT_MS)
        assertEquals(350, YamuMotion.TRAY_BLOCK_MS)
        assertEquals(160, YamuMotion.TRAY_CLOSE_COLOR_DELAY_MS + YamuMotion.COLOR_SHIFT_MS)
        assertEquals(0.92f, YamuMotion.BACKDROP_SCALE)
    }

    @Test fun `처음엔 닫혀 있고 회전 뒤 열린 상태로 되살리면 끝 상태로 바로 보인다`() {
        val closed = GrowTrayState(false)
        assertFalse(closed.visible)
        assertFalse(closed.isOpen)
        val restored = GrowTrayState(true)
        assertTrue(restored.visible)
        assertTrue(restored.isOpen)
        assertTrue("다시 그려진 트레이는 바로 누를 수 있다", restored.interactive)
        assertEquals(1f, restored.progress.value)
        assertEquals(1f, restored.contentAlpha.value)
    }

    @Test fun `판에 붙기 전에는 열기 · 닫기를 무시한다`() {
        val s = GrowTrayState(false)
        s.open()
        assertFalse("코루틴 범위가 없으면 아무 일도 없다", s.isOpen)
    }
}
