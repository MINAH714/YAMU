package com.yamu.app.core.designsystem.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.yamu.app.core.designsystem.theme.YamuMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 굴러가는 숫자 계획과 날아가는 칩 자리(M-DONE-pay, S15P31E103-501) */
class RollPlanTest {

    @Test fun `바뀐 자리만 고르고 아랫자리부터 차례로 굴린다`() {
        // 285,000 → 300,000: 십만 · 만 · 천 자리만 바뀐다
        val plan = RollPlan.plan(285_000, 300_000)
        assertEquals(listOf(4, 5, 6), plan.map { it.place }.sorted())
        assertEquals(mapOf(4 to 0, 5 to 1, 6 to 2), plan.associate { it.place to it.order })
    }

    @Test fun `커지면 위로 돌아 9 다음 0 · 작아지면 아래로 돈다`() {
        val up = RollPlan.plan(285_000, 300_000).associateBy { it.place }
        assertEquals("5 → 0은 위로 돌아 10에서 멈춘다", 10, up.getValue(4).end)
        assertEquals("8 → 0", 10, up.getValue(5).end)
        assertEquals("2 → 3", 3, up.getValue(6).end)
        val down = RollPlan.plan(300_000, 285_000).associateBy { it.place }
        assertEquals("0 → 5는 아래로 돌아 -5에서 멈춘다", -5, down.getValue(4).end)
        assertEquals("3 → 2", 2, down.getValue(6).end)
    }

    @Test fun `네 자리 넘게 바뀌면 위 네 자리만 굴리고 나머지는 바로 바꾼다`() {
        val plan = RollPlan.plan(123_456, 234_567).associateBy { it.place }
        assertEquals(6, plan.size)
        assertEquals(listOf(3, 4, 5, 6), plan.values.filter { it.order >= 0 }.map { it.place }.sorted())
        assertEquals(-1, plan.getValue(1).order)
        assertEquals(-1, plan.getValue(2).order)
        assertEquals("맨 윗자리가 마지막에 멈춘다", YamuMotion.ROLL_MAX_DIGITS - 1, plan.getValue(6).order)
    }

    @Test fun `새로 생긴 자리는 굴리지 않고 없어진 자리는 계획에 없다`() {
        val grow = RollPlan.plan(9_800, 10_200).associateBy { it.place }
        assertEquals(-1, grow.getValue(5).order)
        assertEquals(1, grow.getValue(5).to)
        assertTrue(grow.getValue(4).order >= 0)
        val shrink = RollPlan.plan(10_200, 9_800)
        assertTrue(shrink.none { it.place == 5 })
    }

    @Test fun `같은 값 · 음수는 굴리지 않는다`() {
        assertTrue(RollPlan.plan(15_000, 15_000).isEmpty())
        assertTrue(RollPlan.plan(-1_000, 2_000).isEmpty())
    }

    @Test fun `가장 긴 굴림은 620ms에 자리마다 45ms씩 — 네 자리면 명세 끝 2055ms`() {
        assertEquals(620 + 45 * 2, RollPlan.totalMs(RollPlan.plan(285_000, 300_000)))
        assertEquals(2_055, YamuMotion.ROLL_AT_MS + RollPlan.totalMs(RollPlan.plan(123_456, 234_567)))
        assertEquals(0, RollPlan.totalMs(emptyList()))
    }

    @Test fun `칩 박자는 명세대로 200ms에 나타나 350~1000ms에 날고 1300ms부터 굴린다`() {
        assertEquals(200, YamuMotion.CHIP_APPEAR_AT_MS)
        assertEquals(1_000, YamuMotion.CHIP_FLY_AT_MS + YamuMotion.CHIP_FLY_MS)
        assertTrue(YamuMotion.ROLL_AT_MS > YamuMotion.CHIP_FLY_AT_MS + YamuMotion.CHIP_FLY_MS)
    }

    @Test fun `칩은 출발 가운데에서 도착 가운데로 곧게 날고 진행은 0~1로 자른다`() {
        val from = Rect(0f, 0f, 100f, 40f)
        val to = Rect(200f, 400f, 300f, 440f)
        assertEquals(Offset(50f, 20f), ChipFlight.center(from, to, 0f))
        assertEquals(Offset(150f, 220f), ChipFlight.center(from, to, 0.5f))
        assertEquals(Offset(250f, 420f), ChipFlight.center(from, to, 1.5f))
    }

    @Test fun `칩 왼쪽 위는 가운데에서 칩 크기 절반과 층 자리를 뺀다`() {
        assertEquals(IntOffset(110, 205), ChipFlight.topLeft(Offset(150f, 220f), IntSize(60, 20), layer = Offset(10f, 5f)))
    }
}
