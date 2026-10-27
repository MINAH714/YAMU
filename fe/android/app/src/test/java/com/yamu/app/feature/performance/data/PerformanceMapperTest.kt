package com.yamu.app.feature.performance.data

import com.yamu.app.core.format.toManWon
import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실적 · 유불리 — 판정은 서버 값 그대로, 앱은 이름 · 순서 · 표시만(11 문서 7-3) */
class PerformanceMapperTest {
    private fun item(id: Long, status: String, remaining: Long, guide: String? = null, target: Long? = 300_000) =
        CardPerformanceItemDto(userCardId = id, nickname = "카드$id", targetAmount = target, recognizedAmount = 0, remainingAmount = remaining, status = status, guide = guide)

    @Test fun `기준 전은 판정 순 → 남은 금액 순, 그 밖은 달성 → 조건 없음`() {
        val overview = listOf(
            item(1, "AT_RISK", 120_000, "EXTRA_SPENDING_NOT_RECOMMENDED"),
            item(2, "NO_TARGET", 0, target = null),
            item(3, "ON_TRACK", 30_000, "KEEP_NATURAL"),
            item(4, "ACHIEVED", 0),
            item(5, "AT_RISK", 80_000, "ACHIEVE_RECOMMENDED"),
            item(6, "ON_TRACK", 10_000, "KEEP_NATURAL"),
        ).map { it.toCardPerf() }.toOverview("2026-10", 9, null)

        assertEquals(listOf(5L, 6L, 3L, 1L), overview.pending.map { it.userCardId })
        assertEquals(listOf(4L, 2L), overview.others.map { it.userCardId })
        assertEquals(PerfGuide.ALREADY_ACHIEVED, overview.others.first().guide)
        assertTrue(overview.others.last().noTarget)
    }

    @Test fun `결제 반영 — 서버가 준 변화량과 가장 최근 반영 시각`() {
        val json = """{"yearMonth":"2026-10","daysLeft":15,"cards":[
            {"userCardId":11,"issuer":{"name":"신한카드","color":"#0046FF"},"targetAmount":300000,"recognizedAmount":285000,"remainingAmount":15000,
             "status":"ON_TRACK","guide":"KEEP_NATURAL","recentChange":{"amount":15000,"approvedAt":"2026-10-16T09:38:00+09:00"}},
            {"userCardId":13,"nickname":"우리 통신","targetAmount":400000,"recognizedAmount":320000,"remainingAmount":80000,"status":"AT_RISK"}]}"""
        val dto = YamuJson.decodeFromString(CardPerformancesDto.serializer(), json)
        val overview = dto.cards.map { it.toCardPerf() }.toOverview(dto.yearMonth, dto.daysLeft, null)
        val shinhan = overview.pending.first { it.userCardId == 11L }
        assertEquals(15_000L, shinhan.recentChange)
        assertEquals("신한카드", shinhan.name)
        assertEquals("2026-10-16T09:38:00+09:00", overview.reflectedAtIso)
        val woori = overview.pending.first { it.userCardId == 13L }
        assertNull(woori.guide)                       // 보완 전 모양 — 저장소가 #18로 채운다
        assertEquals("우리", woori.brand.shortName)    // 카드사가 없으면 별명 첫 단어
    }

    @Test fun `판단 값 — 문자열 · 객체 · 없음`() {
        assertEquals(PerfDecision.SKIP, readDecision(JsonPrimitive("SKIP")))
        assertEquals(PerfDecision.FILL, readDecision(buildJsonObject { put("decision", "fill"); put("decidedAt", "2026-10-16T09:40:00+09:00") }))
        assertNull(readDecision(JsonNull))
        assertNull(readDecision(null))
    }

    @Test fun `되돌리기 본문엔 decision null이 꼭 들어간다`() {
        val revert = decisionBody(null, "2026-10-16T06:00:00+09:00")
        assertTrue(revert.containsKey("decision"))
        assertEquals(JsonNull, revert["decision"])
        assertEquals("\"KEEP\"", decisionBody(PerfDecision.KEEP, null)["decision"].toString())
    }

    @Test fun `분석 — 옮길 수 있는 첫 항목, 혜택 금액이 없으면 평소 혜택 합`() {
        val dto = PerformanceAnalysisDto(
            userCardId = 13, yearMonth = "2026-10", targetAmount = 400_000, remainingAmount = 80_000, guide = "ACHIEVE_RECOMMENDED",
            projection = ProjectionDto(projectedRecognizedAmount = 370_000),
            benefitsAtStake = listOf(BenefitAtStakeDto("통신비 할인", 5_000)),
            movableItems = listOf(MovableItemDto(302, "월세", amount = 250_000, eligible = false), MovableItemDto(318, "통신비", amount = 35_000, eligible = true, deadline = "2026-10-23")),
        )
        val a = dto.toAnalysis()
        assertEquals(PerfGuide.ACHIEVE_RECOMMENDED, a.guide)
        assertEquals(318L, a.movable?.cashflowItemId)
        assertEquals(5_000L, a.extraBenefit)
        assertEquals(10, a.month)
        assertEquals(PerfGuide.INSUFFICIENT_DATA, PerformanceAnalysisDto(userCardId = 1, remainingAmount = 10).toAnalysis().guide)
    }

    @Test fun `구간 달성은 서버 값, 없으면 인정 실적 ≥ 기준`() {
        val d = PerformanceDetailDto(
            userCardId = 13, yearMonth = "2026-10",
            summary = PerfSummaryDto(targetAmount = 400_000, recognizedAmount = 320_000, remainingAmount = 80_000, status = "AT_RISK"),
            tiers = listOf(TierDto(400_000, "40만 원 이상", "통신비 할인"), TierDto(300_000, "30만 원 이상", "정수기 할인", achieved = true)),
        ).toDetail()
        assertEquals(listOf(true, false), d.tiers.map { it.achieved })
        assertEquals("40만 원 이상", d.targetLabel)
        assertFalse(d.achieved)
    }

    @Test fun `한도 태그 — 임박 · 소진 · 잠김 · 한도 없음`() {
        fun card(vararg statuses: String) = LimitCardDto(1, "카드", limits = statuses.map { LimitDto("한도", 0, it, if (it == "LOCKED") "전월 실적 미달" else null) }).toLimitCard()
        assertEquals(LimitTag.NEAR_END, card("AVAILABLE", "NEAR_END").tag)
        assertEquals(LimitTag.EXHAUSTED, card("EXHAUSTED", "LOCKED").tag)
        assertEquals(LimitTag.LOCKED, card("LOCKED").tag)
        assertEquals("전월 실적 미달", card("LOCKED").lockedReason)
        assertNull(card("AVAILABLE").tag)
        val unlimited = card()
        assertEquals(LimitTag.UNLIMITED, unlimited.tag)
        assertNull(unlimited.remaining)
    }

    @Test fun `같은 카드사 두 장이면 별명으로 구분`() {
        val overview = listOf(
            CardPerformanceItemDto(16, nickname = "KB국민 체크카드", issuerName = "KB국민카드", targetAmount = 200_000, status = "ACHIEVED"),
            CardPerformanceItemDto(12, nickname = "KB국민 적립 신용카드", issuerName = "KB국민카드", targetAmount = null, status = "NO_TARGET"),
            CardPerformanceItemDto(11, nickname = "신한 생활", issuerName = "신한카드", targetAmount = 300_000, remainingAmount = 15_000),
        ).map { it.toCardPerf() }.toOverview(null, null, null)
        assertEquals(listOf("KB국민 체크카드", "KB국민 적립 신용카드"), overview.others.map { it.name })
        assertEquals("신한카드", overview.pending.single().name)
    }

    @Test fun `기준 금액 만 원 표기`() {
        assertEquals("40만 원", 400_000L.toManWon())
        assertEquals("125,000원", 125_000L.toManWon())
    }
}
