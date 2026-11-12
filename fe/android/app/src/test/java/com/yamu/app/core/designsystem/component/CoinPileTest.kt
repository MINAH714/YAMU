package com.yamu.app.core.designsystem.component

import com.yamu.app.core.designsystem.theme.YamuMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 동전 무더기 — 금액 → 개수, 무대 크기에 맞춘 모양, 떨어지는 순서, 금액 올림 곡선. 1dp = 1px로 잰다(홈 카드 안쪽 폭 339) */
class CoinPileTest {

    private val max = CoinPileSpec.FILL_ORDER.size
    private fun home(coins: Int = 14) = CoinLayout(width = 339f, height = 220f, dp = 1f, coins = coins)      // 지금 홈쯤
    private fun lowest(coins: Int = 14) = CoinLayout(width = 339f, height = CoinPileDefaults.minHeight.value, dp = 1f, coins = coins)

    @Test
    fun `0원 이하는 동전이 없고, 금액이 클수록 많아지며 500만 원부터 32개다`() {
        assertEquals(0, coinCountFor(-50_000))
        assertEquals(0, coinCountFor(0))
        assertEquals(1, coinCountFor(9_999))
        assertEquals(14, coinCountFor(350_000))
        assertEquals(29, coinCountFor(4_999_999))
        assertEquals(32, coinCountFor(5_000_000))
        assertEquals(max, coinCountFor(Long.MAX_VALUE))
        val steps = listOf(1L, 10_000, 50_000, 100_000, 300_000, 700_000, 1_000_000, 5_000_000).map(::coinCountFor)
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun `채우는 순서는 줄마다 정한 개수를 넘지 않고 모두 채운다`() {
        val counts = CoinPileSpec.FILL_ORDER.groupingBy { it }.eachCount()
        CoinPileSpec.STACKS.forEachIndexed { i, s -> assertEquals("줄 $i", s.cap, counts[i]) }
    }

    @Test
    fun `동전 크기는 무대에 맞추되 28~60dp 안이다`() {
        assertEquals(28f, CoinLayout(339f, 60f, 1f, 14).diameter, 0.01f)                  // 60 × 0.42 = 25.2 → 28
        assertEquals(120f * CoinPileSpec.HEIGHT_SHARE, CoinLayout(339f, 120f, 1f, 14).diameter, 0.01f)  // 아주 낮으면 높이 기준 50.4
        assertEquals(60f, home().diameter, 0.01f)                                          // 홈은 폭 기준 60.5 → 60
        assertEquals(300f / CoinPileSpec.WIDTH_IN_COINS, CoinLayout(300f, 250f, 1f, 14).diameter, 0.01f)  // 폭이 빠듯하면 폭 기준 53.6
        assertEquals(60f, CoinLayout(500f, 600f, 1f, 14).diameter, 0.01f)
    }

    @Test
    fun `결제 줄이 생겨 무대가 80dp 낮아져도 동전 크기는 거의 그대로다`() {
        val before = home().diameter
        val after = CoinLayout(339f, 140f, 1f, 14).diameter
        assertTrue("$before → $after", after >= before * 0.95f)
    }

    @Test
    fun `무더기는 오른쪽 끝이 무대 오른쪽 끝에 붙고, 가장 많아도 왼쪽 밖으로 나가지 않는다`() {
        listOf(1, 4, 14, 24, max).forEach { n ->
            listOf(home(n), lowest(n)).forEach { l ->
                val drops = restingDrops(l, n)
                val right = drops.maxOf { l.centerX(it.stack) + l.radius }
                val left = drops.minOf { l.centerX(it.stack) - l.radius }
                assertEquals("$n 개 오른쪽 끝", l.width, right, 0.5f)
                assertTrue("$n 개 왼쪽 끝 $left", left >= 0f)
            }
        }
        val l = home(14)
        val center = restingDrops(l, 14).map { l.centerX(it.stack) }.average()
        assertTrue("35만 원 무더기 가운데 $center 는 오른쪽에", center > l.width * 0.6)
    }

    @Test
    fun `가장 많을 때도 무더기 위에 떨어질 자리가 남는다`() {
        listOf(lowest(max), home(max)).forEach { l ->
            val top = restingDrops(l, max).minOf { l.restFaceY(it.stack, it.level) - l.faceRy }
            assertTrue("무더기 위끝 $top · 무대 ${l.height}", top >= l.height * 0.45f)
        }
    }

    @Test
    fun `몇 개에서 끊어도 가장 높은 곳은 가운데 근처이고, 많을수록 무더기가 높아진다`() {
        var lastTop = Float.MAX_VALUE
        listOf(1, 4, 10, 14, 24, max).forEach { n ->
            val l = home(n)
            val highest = restingDrops(l, n).minBy { l.restFaceY(it.stack, it.level) }      // y가 작을수록 높다
            assertTrue("$n 개: 가장 높은 줄 ${highest.stack}", abs(CoinPileSpec.STACKS[highest.stack].x) <= 0.6f)
            val top = l.restFaceY(highest.stack, highest.level)
            assertTrue("$n 개: $top ≤ $lastTop", top <= lastTop)
            lastTop = top
        }
    }

    @Test
    fun `비스듬히 놓이는 건 줄마다 맨 위 동전과 혼자 떨어진 동전뿐이다`() {
        listOf(14, max).forEach { n ->
            val drops = restingDrops(home(n), n)
            drops.groupBy { it.stack }.forEach { (stack, coins) ->
                coins.filter { it.restTilt != 0f }.forEach { assertEquals("줄 $stack", coins.size - 1, it.level) }
            }
        }
    }

    @Test
    fun `처음엔 촘촘하게, 끝으로 갈수록 드문드문 떨어지기 시작한다`() {
        listOf(14, max).forEach { n ->
            val starts = (0 until n).map { dropDelay(it, n) }.sorted()
            val gaps = starts.zipWithNext { a, b -> b - a }
            val quarter = gaps.size / 4
            val early = gaps.take(quarter).average()
            val late = gaps.takeLast(quarter).average()
            assertTrue("$n 개: 앞 간격 $early · 끝 간격 $late", late > early * 4)
        }
    }

    @Test
    fun `떨어지는 박자와 출발 높이가 고르지 않다`() {
        val n = 14
        val gaps = (1 until n).map { dropDelay(it, n) - dropDelay(it - 1, n) }
        // 고르게 커지기만 하면 앞 간격보다 늘 크다 — 흔들어서 줄어드는 곳이 있어야 한다
        assertTrue(gaps.zipWithNext().count { (a, b) -> b < a } >= 2)
        val starts = entranceDrops(home(n), n, reduce = false).map { it.startY }
        assertTrue(starts.distinct().size >= n / 2)
    }

    @Test
    fun `금액 글자는 0에서 시작해 처음 빠르고 끝에서 천천히 끝값에 닿는다`() {
        assertEquals(0f, rollEase(0f), 0f)
        assertEquals(1f, rollEase(1f), 0f)
        assertEquals(1f, rollEase(2f), 0f)
        assertTrue(rollEase(0.5f) > 0.8f)
        val curve = (0..20).map { rollEase(it / 20f) }
        assertEquals(curve.sorted(), curve)
    }

    @Test
    fun `같은 줄의 위 동전은 아래 동전이 닿은 뒤에 닿는다`() {
        val drops = entranceDrops(home(max), max, reduce = false)
        drops.groupBy { it.stack }.values.forEach { stack ->
            assertEquals((0 until stack.size).toList(), stack.map { it.level })
            stack.zipWithNext().forEach { (below, above) ->
                assertTrue(
                    "줄 ${below.stack}: ${below.level}층 ${below.landMs} → ${above.level}층 ${above.landMs}",
                    above.landMs >= below.landMs + YamuMotion.COIN_STACK_GAP_MS - 0.01f,
                )
            }
        }
    }

    @Test
    fun `들어오는 모션은 화면 교체 페이드 뒤에 시작해 가장 많을 때도 2점2초 안에 끝난다`() {
        val drops = entranceDrops(home(max), max, reduce = false)
        assertEquals(YamuMotion.COIN_START_DELAY_MS.toFloat(), drops.minOf { it.startMs })
        assertTrue("끝 ${drops.maxOf { it.landMs }}", drops.maxOf { it.landMs } < 2_200f)
        assertTrue("무대가 높을수록 오래 떨어진다", home().fallMs(0, 0) > lowest().fallMs(0, 0))
    }

    @Test
    fun `모션 줄이기면 떨어지지 않고 모두 처음부터 제자리다`() {
        val drops = entranceDrops(home(), 14, reduce = true)
        assertEquals(14, drops.size)
        assertTrue(drops.all { it.startMs == 0f && it.fallMs == 0f && it.drift == 0f && it.tilt == 0f })
    }

    @Test
    fun `금액이 늘면 늘어난 동전만 그 줄 맨 위 동전이 닿은 뒤에 떨어진다`() {
        val before = entranceDrops(home(14), 14, reduce = false)
        val added = addedDrops(home(20), before, 20, nowMs = 0f, reduce = false)
        assertEquals(CoinPileSpec.FILL_ORDER.subList(14, 20), added.map { it.stack })
        added.forEach { d ->
            val below = before.filter { it.stack == d.stack }
            assertEquals(below.size + added.takeWhile { it !== d }.count { it.stack == d.stack }, d.level)
            below.lastOrNull()?.let { assertTrue(d.landMs >= it.landMs + YamuMotion.COIN_STACK_GAP_MS - 0.01f) }
        }
        assertTrue(addedDrops(home(8), before, 8, nowMs = 0f, reduce = false).isEmpty())
    }

    /** 이미 다 쌓인 무더기(탭을 오가 다시 그릴 때처럼) */
    private fun rested(coins: Int, layout: CoinLayout = home(coins)): PileMotion =
        PileMotion(CoinPileState(entered = true)).apply { rest(layout, coins) }

    @Test
    fun `금액이 줄면 나중에 얹힌 동전부터 차례로 떠나고 남은 동전은 그대로다`() {
        val motion = rested(14)
        val kept = motion.drops.take(11)
        val gone = motion.drops.drop(11)
        motion.change(home(11), 11, reduce = false)
        assertEquals(kept, motion.drops.toList())
        assertEquals(gone.reversed(), motion.leaving.map { it.drop })
        val starts = motion.leaving.map { it.startMs }
        assertEquals(starts.sorted(), starts)
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
        // 떠나는 동전 위에 남는 동전은 없다 — 줄마다 위에서부터 빠진다
        gone.forEach { g -> assertTrue(kept.none { it.stack == g.stack && it.level > g.level }) }
    }

    @Test
    fun `자리 크기만 바뀌면 같은 동전을 새 크기로 그리고 떨어지거나 떠나는 동전이 없다`() {
        val motion = rested(14)
        val before = motion.drops.toList()
        val smaller = CoinLayout(width = 339f, height = 150f, dp = 1f, coins = 14)
        motion.change(smaller, 14, reduce = false)
        assertEquals(before, motion.drops.toList())
        assertTrue(motion.leaving.isEmpty())
        assertTrue(motion.layout === smaller)
        assertEquals(0f, motion.slidePx(smaller, 0f), 0f)
    }

    @Test
    fun `금액이 늘면 늘어난 동전만 채우는 순서대로 이어 붙는다`() {
        val motion = rested(14)
        val before = motion.drops.toList()
        motion.change(home(20), 20, reduce = false)
        assertEquals(before, motion.drops.take(14))
        assertEquals(CoinPileSpec.FILL_ORDER.subList(14, 20), motion.drops.drop(14).map { it.stack })
        assertTrue(motion.leaving.isEmpty())
    }

    @Test
    fun `개수가 바뀌어 오른쪽 끝이 달라지면 옛 자리에서 새 자리로 미끄러진다`() {
        val (from, to) = (1..max).flatMap { a -> (1..max).map { a to it } }.first { (a, b) -> pileRightEdge(a) != pileRightEdge(b) }
        val motion = rested(from)
        val next = home(to)
        motion.change(next, to, reduce = false)
        // 처음엔 옛 자리(차이만큼 옮겨 그림), SLIDE_MS 뒤엔 새 자리
        assertEquals((pileRightEdge(to) - pileRightEdge(from)) * next.diameter, motion.slidePx(next, 0f), 0.01f)
        assertEquals(0f, motion.slidePx(next, PileMotion.SLIDE_MS), 0.01f)
        // 모션 줄이기면 미끄러지지 않는다
        val reduced = rested(from).apply { change(next, to, reduce = true) }
        assertEquals(0f, reduced.slidePx(next, 0f), 0f)
    }
}
