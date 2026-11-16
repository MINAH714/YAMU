package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import com.yamu.app.feature.home.manShort
import com.yamu.app.feature.link.data.AccountDto
import com.yamu.app.feature.link.data.BankDto
import com.yamu.app.feature.link.data.IssuerDto
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.LimitCardDto
import com.yamu.app.feature.performance.data.LimitDto
import com.yamu.app.feature.performance.data.PerfDecision
import com.yamu.app.feature.performance.data.PerfGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** v4 홈 아래쪽(S15P31E103-481) — 카드 줄 한도 · 실적 칩(HOME-10 · COMMON-07), 놓치기 아까운 혜택(HOME-12 · 13), 잔고예보 요약(HOME-14) */
class HomeCardsTest {

    private fun card(
        id: Long,
        nickname: String? = "카드 $id",
        target: Long? = 300_000,
        recognized: Long = 0,
        achieved: Boolean = false,
        guide: PerfGuide? = null,
        targetLabel: String? = target?.let { "${it / 10_000}만원 이상" },
        decision: PerfDecision? = null,
    ) = CardPerf(
        userCardId = id, brand = brandOf("신한카드"), name = "신한카드", target = target, targetLabel = targetLabel,
        recognized = recognized, remaining = target?.let { (it - recognized).coerceAtLeast(0) } ?: 0,
        achieved = achieved, noTarget = target == null, guide = guide, decision = decision, provisional = false,
        recentChange = null, recentAtIso = null, nickname = nickname,
    )

    private fun limit(name: String, remaining: Long?, status: String = "AVAILABLE") = LimitDto(name, remaining, status)
    private fun limits(id: Long, vararg l: LimitDto) = LimitCardDto(id, "카드 $id", limits = l.toList())

    // ── 카드 줄 한도 한 줄 ──

    @Test fun `한도 하나면 이름과 남은 금액`() {
        assertEquals("카페 10% 할인 남은 한도 3,000원", limitLine(limits(1, limit("카페 10% 할인", 3_000))))
    }

    @Test fun `한도가 여럿이어도 더하지 않고 남은 금액이 가장 큰 한도 하나 + 외 n곳`() {
        val line = limitLine(limits(1, limit("편의점 5%", 1_100, "NEAR_END"), limit("TIME 서비스", 6_700), limit("통신비(월 1회)", null)))
        assertEquals("TIME 서비스 남은 한도 6,700원 외 2곳", line)
    }

    @Test fun `금액을 모르는 한도는 0원이 아니라 확인할 수 없음`() {
        assertEquals("통신비(월 1회) 남은 한도 확인할 수 없음", limitLine(limits(1, limit("통신비(월 1회)", null))))
        // 다 쓴 원화 한도보다 모르는 한도를 먼저 말한다 — 다 썼다고 단정하지 않게
        assertEquals("통신비 남은 한도 확인할 수 없음 외 1곳", limitLine(limits(1, limit("카페", 0, "EXHAUSTED"), limit("통신비", null))))
    }

    @Test fun `한도 줄이 없으면 한도 없음 · 응답에 카드가 없으면 아무것도 쓰지 않는다`() {
        assertEquals("한도 없음", limitLine(limits(1)))
        assertNull(limitLine(null))
    }

    @Test fun `모두 잠겼거나 다 썼으면 그렇게 말한다`() {
        assertEquals("이번 달 한도 잠김", limitLine(limits(1, limit("생활", null, "LOCKED"), limit("카페", 0, "LOCKED"))))
        assertEquals("이번 달 한도를 다 썼어요", limitLine(limits(1, limit("쿠팡", 0, "EXHAUSTED"), limit("생활", null, "LOCKED"))))
    }

    // ── 내 카드 타일(S15P31E103-519) ──

    @Test fun `실적 막대 비율 — 조건 없음은 막대 없음, 달성은 꽉 참, 나머지는 인정 실적 나누기 목표`() {
        assertNull(card(1, target = null).perfProgress())
        assertEquals(1f, card(1, recognized = 320_000, achieved = true).perfProgress())
        assertEquals(0.85f, card(1, recognized = 255_000).perfProgress()!!, 0.0001f)
        assertEquals(1f, card(1, recognized = 400_000).perfProgress())   // 넘쳐도 막대는 1까지
    }

    @Test fun `타일 한 문장 — 카드별 혜택을 모르면 받은 혜택을 0원으로 말하지 않는다`() {
        val row = HomeCardsData(listOf(card(1, recognized = 255_000)), limits = listOf(limits(1, limit("카페 10% 할인", 3_000)))).rows().single()
        assertEquals("카드 1, 카페 10% 할인 남은 한도 3,000원, 실적까지 45,000원", cardTileDescription(row, null))
        assertEquals(
            "카드 1, 받은 혜택 9,800원, 6,000원 혜택 예정, 카페 10% 할인 남은 한도 3,000원, 실적까지 45,000원",
            cardTileDescription(row, CardBenefit(confirmed = 9_800, pending = 6_000, unconvertedCount = 0)),
        )
    }

    // ── 실적 칩 · 줄 ──

    @Test fun `실적 칩은 달성 · 실적까지 · 실적 조건 없음`() {
        assertEquals(PerfChip("실적 달성", PerfChipTone.Done), card(1, recognized = 320_000, achieved = true).perfChip())
        assertEquals(PerfChip("실적까지 45,000원", PerfChipTone.Todo), card(1, recognized = 255_000).perfChip())
        assertEquals(PerfChip("실적 조건 없음", PerfChipTone.None), card(1, target = null).perfChip())
    }

    @Test fun `카드 목록 순서로 3장 · 실적 조건 없는 카드는 열지 않는다 · 별명이 없으면 카드사 이름`() {
        val data = HomeCardsData(
            listOf(card(1), card(2, target = null), card(3, nickname = null), card(4)),
            listOf(limits(1, limit("카페", 3_000))),
        )
        val rows = data.rows()
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.userCardId })
        assertTrue(rows[0].opens)
        assertFalse(rows[1].opens)
        assertEquals("신한카드", rows[2].name)
        assertEquals("카페 남은 한도 3,000원", rows[0].limitLine)
        assertNull(rows[1].limitLine)   // 한도 응답에 없는 카드
    }

    // ── 받은 혜택 순 · 이번 달 혜택 없음(11/16 사용자 요청) ──

    private fun benefit(confirmed: Long, pending: Long = 0) = CardBenefit(confirmed, pending, unconvertedCount = 0)

    @Test fun `받은 혜택이 큰 카드부터 3장 · 같으면 혜택 예정 순 · 혜택 없는 카드는 목록 순서로 뒤에`() {
        val data = HomeCardsData(listOf(card(1), card(2), card(3), card(4), card(5)), limits = null)
        val rows = data.rows(mapOf(4L to benefit(9_800), 2L to benefit(1_000, pending = 500), 5L to benefit(1_000)))
        assertEquals(listOf(4L, 2L, 5L), rows.map { it.userCardId })
        // 혜택 받은 카드가 하나면 나머지는 카드 목록 순서
        assertEquals(listOf(3L, 1L, 2L), data.rows(mapOf(3L to benefit(500))).map { it.userCardId })
    }

    @Test fun `카드별 혜택을 모르면 카드 목록 순서 그대로`() {
        val data = HomeCardsData(listOf(card(1), card(2), card(3), card(4)), limits = null)
        assertEquals(listOf(1L, 2L, 3L), data.rows(null).map { it.userCardId })
    }

    @Test fun `이번 달 혜택 없음은 받아 온 혜택에 그 카드가 없거나 다 0원일 때만`() {
        val benefits = mapOf(1L to benefit(9_800), 2L to benefit(0, pending = 3_000), 3L to benefit(0))
        assertFalse(noBenefitThisMonth(benefits, 1L))
        assertFalse(noBenefitThisMonth(benefits, 2L))   // 확정 전 혜택은 있다
        assertTrue(noBenefitThisMonth(benefits, 3L))
        assertTrue(noBenefitThisMonth(benefits, 4L))    // 이번 달 YAMU 결제 없음
        assertFalse(noBenefitThisMonth(null, 4L))       // 불러오지 못했으면 없다고 말하지 않는다
    }

    @Test fun `혜택 없는 카드 타일 한 문장`() {
        val row = HomeCardsData(listOf(card(1, recognized = 255_000)), limits = null).rows().single()
        assertEquals("카드 1, 이번 달 받은 혜택 없음, 실적까지 45,000원", cardTileDescription(row, null, noBenefit = true))
    }

    @Test fun `한도 API만 실패하면 줄에 한도를 쓰지 않는다`() {
        val rows = HomeCardsData(listOf(card(1)), limits = null).rows()
        assertNull(rows.single().limitLine)
        assertEquals("실적까지 300,000원", rows.single().chip.text)
    }

    // ── 놓치기 아까운 혜택 ──

    @Test fun `남은 한도는 금액을 아는 이번 달 한도만 많은 순 3줄`() {
        val data = HomeCardsData(
            listOf(card(1, nickname = "노리"), card(2, nickname = "처음")),
            listOf(
                limits(1, limit("카페", 3_000), limit("통신비", null), limit("편의점", 1_100, "NEAR_END")),
                limits(2, limit("마트", 11_800), limit("쿠팡", 0, "EXHAUSTED"), limit("생활", null, "LOCKED"), limit("주유", 500)),
            ),
        )
        val r = data.missed().remaining
        assertEquals(listOf("처음 · 마트 11800", "노리 · 카페 3000", "노리 · 편의점 1100"), r.map { "${it.cardName} · ${it.limitName} ${it.amount}" })
        // 카드 그림을 찾을 재료 — 카드 번호 · 카드사 · 그림 이름(별명이 있으면 별명, 화면은 카드 목록 그림을 먼저 쓴다)
        assertEquals(listOf(2L, 1L, 1L), r.map { it.userCardId })
        assertEquals("처음", r.first().artName)
        assertEquals("신한", r.first().brand.shortName)
    }

    @Test fun `실적 응답에 없는 카드의 한도도 한도 응답의 카드사로 카드 그림을 찾는다`() {
        val data = HomeCardsData(
            emptyList(),
            listOf(LimitCardDto(9, "KB국민 노리", issuer = IssuerDto("KB", "KB국민카드", "#FFBC00"), limits = listOf(limit("카페", 2_000)))),
        )
        val r = data.missed().remaining.single()
        assertEquals("KB국민 노리", r.cardName)
        assertEquals("KB국민카드", r.brand.name)
        assertEquals("KB국민 노리", r.artName)
    }

    @Test fun `조건을 채우면은 못 채운 카드 중 추가 소비 비추천이 아닌 것 · 남은 금액 적은 순 2줄`() {
        val data = HomeCardsData(
            listOf(
                card(1, nickname = "A", recognized = 100_000),
                card(2, nickname = "B", recognized = 255_000),
                card(3, nickname = "C", recognized = 290_000, guide = PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED),
                card(4, nickname = "D", recognized = 320_000, achieved = true),
                card(5, nickname = "E", target = null),
                card(6, nickname = "F", recognized = 200_000),
            ),
            limits = null,
        )
        val c = data.missed().conditions
        assertEquals(listOf("B", "F"), c.map { it.cardName })
        assertEquals("이번 달 실적이 30만원 이상이면 다음 달 혜택 조건을 채워요 (지금 255,000원)", c.first().text)
        // 카드 막대 — 지금 실적 / 목표
        assertEquals(255_000L, c.first().recognized)
        assertEquals(300_000L, c.first().target)
        assertEquals(0.85f, c.first().progress!!, 0.001f)
        assertEquals("30만원 이상", c.first().targetText)
    }

    @Test fun `막대 아래 짧은 금액 — 만 단위 소수 한 자리(버림), 1만 원 아래는 그대로`() {
        assertEquals(listOf("25.5만", "30만", "123.4만", "1만", "8,000", "0"), listOf(255_000L, 300_000L, 1_234_567L, 10_999L, 8_000L, 0L).map { it.manShort() })
    }

    @Test fun `목표 금액을 모르면 막대 없이 목표 표시만`() {
        val line = ConditionLine(1, "A", brandOf("신한카드"), "A", recognized = 100_000, target = null, targetText = "30만원 이상", text = "")
        assertNull(line.progress)
        assertNull(line.copy(target = 0).progress)
        assertEquals(1f, line.copy(target = 50_000).progress!!, 0f)   // 넘쳐도 막대는 끝까지만
    }

    @Test fun `이번 달 채우지 않기(SKIP)를 고른 카드는 조건을 채우면에 넣지 않는다`() {
        val data = HomeCardsData(
            listOf(
                card(1, nickname = "A", recognized = 290_000, decision = PerfDecision.SKIP),
                card(2, nickname = "B", recognized = 255_000, decision = PerfDecision.FILL),
                card(3, nickname = "C", recognized = 200_000, decision = PerfDecision.KEEP),
            ),
            limits = null,
        )
        assertEquals(listOf("B", "C"), data.missed().conditions.map { it.cardName })
    }

    @Test fun `더 쓰라는 말을 쓰지 않는다`() {
        val text = conditionText(card(1, recognized = 200_000))
        listOf("더 쓰", "쓰세요", "이득", "추천").forEach { assertFalse("'$it'이 들어가면 안 된다: $text", it in text) }
    }

    @Test fun `목표 표시가 없으면 금액으로`() {
        assertEquals("이번 달 실적이 40만 원 이상이면 다음 달 혜택 조건을 채워요 (지금 0원)", conditionText(card(1, target = 400_000, targetLabel = null)))
    }

    @Test fun `확인된 것이 없으면 칸을 숨긴다`() {
        val data = HomeCardsData(listOf(card(1, recognized = 300_000, achieved = true)), listOf(limits(1, limit("통신비", null))))
        assertTrue(data.missed().isEmpty)
    }

    // ── 잔고예보 요약 ──

    @Test fun `계좌 칩은 은행 이름과 끝 네 자리 · 목록에 없으면 null`() {
        val accounts = listOf(AccountDto(7, BankDto(code = "004", name = "KB국민은행"), maskedAccountNo = "123-****-0275"))
        assertEquals("KB국민은행 ·0275", accounts.labelOf(7))
        assertNull(accounts.labelOf(8))
    }

    @Test fun `예보 응답에서 지금 잔고 · 최저 잔고 · 날짜를 그대로 옮긴다`() {
        val b = HomeForecastDto(412_000, HomeForecastSummaryDto(180_500, "2026-11-28"), "2026-11-13T05:05:00Z").toBrief(7, "KB국민은행 ·0275")
        assertEquals(412_000L, b.currentBalance)
        assertEquals(180_500L, b.minBalance)
        assertEquals(LocalDate.of(2026, 11, 28), b.minDate)
        assertEquals("KB국민은행 ·0275", b.accountLabel)
    }

    @Test fun `기준 시각은 한국 시각으로 · Z가 없으면 UTC로 본다`() {
        val now = ZonedDateTime.of(2026, 11, 13, 15, 0, 0, 0, KST)
        assertEquals("오늘 14:05 기준", asOfLabel("2026-11-13T05:05:00Z", now))
        assertEquals("오늘 14:05 기준", asOfLabel("2026-11-13T05:05:00", now))
        assertEquals("어제 21:10 기준", asOfLabel("2026-11-12T12:10:00Z", now))
        assertEquals("11/10 09:00 기준", asOfLabel("2026-11-10T00:00:00Z", now))
        assertNull(asOfLabel("어제", now))
        assertNull(asOfLabel(null, now))
    }
}
