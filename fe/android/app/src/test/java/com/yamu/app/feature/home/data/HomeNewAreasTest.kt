package com.yamu.app.feature.home.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 새 홈 칸(BE -490 benefitSummary · forecastPreview, S15P31E103-491). 옛 서버(칸 없음) · 비워 보냄(기준 계좌 없음) · 그 칸만 실패를
 * 섞지 않고, 어느 경우에도 0원을 지어내지 않는다(487~490 명세)
 */
class HomeNewAreasTest {

    private val today = LocalDate.of(2026, 11, 14)

    private fun decode(json: String): HomeSummary = YamuJson.decodeFromString(HomeDto.serializer(), json).toSummary(today)

    /** 명세 예시(487~490 '홈 요약') 그대로 — 기존 칸 몇 개 + 새 칸 둘 */
    private val newServer = """
        {"cards":{"count":2,"items":[]},"onboarding":{"step":"DONE"},
         "balanceForecast":{"accountId":1,"currentBalance":3000001,"minBalance":900000,"horizonDays":7},
         "monthSummary":{"yearMonth":"2026-11","totalSpent":100000,"totalBenefit":1700},
         "benefitSummary":{"yearMonth":"2026-11","source":"YAMU","status":"PARTIAL",
           "totalBenefit":1500,"confirmedBenefit":1000,"unconfirmedBenefit":500,"unconvertedBenefitCount":0,
           "byType":[
             {"type":"DISCOUNT","expectedBenefit":1000,"confirmedBenefit":1000,"unconfirmedBenefit":0,"unconvertedBenefitCount":0},
             {"type":"CASHBACK","expectedBenefit":500,"confirmedBenefit":0,"unconfirmedBenefit":500,"unconvertedBenefitCount":0},
             {"type":"POINT","expectedBenefit":0,"confirmedBenefit":0,"unconfirmedBenefit":0,"unconvertedBenefitCount":0},
             {"type":"GIFT","expectedBenefit":9,"confirmedBenefit":9,"unconfirmedBenefit":0,"unconvertedBenefitCount":0}]},
         "forecastPreview":{"accountId":1,"currentBalance":3000001,"from":"2026-11-15","to":"2026-12-14","horizonDays":30,
           "minBalance":920000,"minBalanceDate":"2026-11-25","nextRiskDate":null,"alertThreshold":100000,
           "balanceAsOf":"2026-11-14T03:00:00Z","calculatedAt":"2026-11-14T03:00:01Z","isEstimate":true,"isProvisional":true},
         "partialErrors":[],"dataSyncedAt":"2026-11-14T03:00:00Z"}
    """.trimIndent()

    @Test fun `새 서버 혜택은 확정 · 확정 전을 더하지 않고 그대로 쓴다`() {
        val b = (decode(newServer).yamuBenefit as NewHomeArea.Value).data
        assertEquals(YearMonth.of(2026, 11), b.month)
        assertEquals(1_000L, b.confirmed)
        assertEquals(500L, b.pending)   // 전체 1,500 − 확정 1,000(전체에 확정을 또 더하지 않는다)
        assertNull(b.hasPayments)        // 새 칸엔 결제 건수가 없다
        assertEquals(today, b.to)
    }

    @Test fun `종류는 할인 · 캐시백 · 적립 순서이고 모르는 종류는 건너뛴다`() {
        val b = (decode(newServer).yamuBenefit as NewHomeArea.Value).data
        assertEquals(listOf(HomeBenefitType.DISCOUNT, HomeBenefitType.CASHBACK, HomeBenefitType.POINT), b.byType.map { it.type })
        assertEquals(b.confirmed, b.byType.sumOf { it.confirmed })   // 세 칸 합 = 큰 숫자(확정)
    }

    @Test fun `새 서버 잔고 요약은 내일부터 30일 · 잔액 읽은 시각 · 잠정`() {
        val p = (decode(newServer).forecastPreview as NewHomeArea.Value).data
        assertEquals(1L, p.accountId)
        assertEquals(920_000L, p.minBalance)   // 옛 balanceForecast.minBalance(900,000, 7일)와 섞지 않는다
        assertEquals(LocalDate.of(2026, 11, 25), p.minDate)
        assertEquals("2026-11-14T03:00:00Z", p.asOfIso)   // calculatedAt이 아니라 balanceAsOf
        assertTrue(p.fromTomorrow)
        assertTrue(p.provisional)
        assertNull(p.accountLabel)
        assertEquals("내일부터 30일 중 가장 낮을 때", p.minLabel)
    }

    /** 지금 운영 서버(-490 전) 모양 — 두 칸이 없다 */
    private val oldServer = """
        {"cards":{"count":2,"items":[]},"onboarding":{"step":"DONE"},
         "balanceForecast":{"accountId":1,"currentBalance":3000001,"minBalance":900000},"partialErrors":[]}
    """.trimIndent()

    @Test fun `옛 서버면 두 칸 모두 옛 방식으로 돌아간다(0원으로 그리지 않음)`() {
        val h = decode(oldServer)
        assertEquals(NewHomeArea.OldServer, h.yamuBenefit)
        assertEquals(NewHomeArea.OldServer, h.forecastPreview)
        assertEquals(1L, h.baseAccountId)
    }

    @Test fun `옛 서버에서 잔고 영역만 실패해도 옛 서버로 본다`() {
        val h = decode("""{"cards":{"count":2},"onboarding":{"step":"DONE"},"partialErrors":[{"area":"balanceForecast","code":"FIN_API_UNAVAILABLE"}]}""")
        assertEquals(NewHomeArea.OldServer, h.forecastPreview)
    }

    @Test fun `새 서버가 기준 계좌 없이 비워 보내면 없음(실패 아님)`() {
        val h = decode("""{"cards":{"count":2},"onboarding":{"step":"DONE"},"balanceForecast":null,"forecastPreview":null,"benefitSummary":{"yearMonth":"2026-11","totalBenefit":0,"confirmedBenefit":0,"unconfirmedBenefit":0},"partialErrors":[]}""")
        assertEquals(NewHomeArea.None, h.forecastPreview)
        assertTrue(h.yamuBenefit is NewHomeArea.Value)
    }

    @Test fun `partialErrors에 적힌 칸만 실패`() {
        val h = decode(
            """{"cards":{"count":2},"onboarding":{"step":"DONE"},"balanceForecast":{"accountId":1,"currentBalance":10},
               "benefitSummary":null,"forecastPreview":null,
               "partialErrors":[{"area":"benefitSummary","code":"INTERNAL_ERROR"},{"area":"forecastPreview","code":"FIN_API_ERROR"}]}""",
        )
        assertEquals(NewHomeArea.Failed, h.yamuBenefit)
        assertEquals(NewHomeArea.Failed, h.forecastPreview)
        assertEquals(1L, h.baseAccountId)   // 버튼은 옛 칸의 기준 계좌로라도 연다
    }

    @Test fun `혜택 칸 숫자가 빠졌으면 0원 대신 실패`() {
        val h = decode("""{"cards":{"count":2},"onboarding":{"step":"DONE"},"benefitSummary":{"yearMonth":"2026-11","totalBenefit":1500},"forecastPreview":{"currentBalance":1}}""")
        assertEquals(NewHomeArea.Failed, h.yamuBenefit)
        assertEquals(NewHomeArea.Failed, h.forecastPreview)   // 기준 계좌를 모르는 요약도 실패
    }

    @Test fun `글자로 온 partialErrors도 같다`() {
        val dto = HomeDto(partialErrors = listOf(JsonPrimitive("forecastPreview")), balanceForecast = BalanceForecastDto(accountId = 1))
        assertEquals(NewHomeArea.Failed, dto.toSummary(today).forecastPreview)
        val obj = HomeDto(partialErrors = listOf(JsonObject(mapOf("area" to JsonPrimitive("benefitSummary")))))
        assertEquals(NewHomeArea.Failed, obj.toSummary(today).yamuBenefit)
    }

    // ── 화면 문구 ──

    private fun benefit(confirmed: Long, pending: Long, unconverted: Long = 0, hasPayments: Boolean? = null, types: List<BenefitByType> = emptyList()) =
        HomeBenefit(YearMonth.of(2026, 11), LocalDate.of(2026, 11, 1), today, confirmed, pending, hasPayments, types, unconverted)

    @Test fun `히어로 제목은 이용 혜택`() {
        assertEquals("11월 이용 혜택", benefitTitle(YearMonth.of(2026, 11)))
        assertEquals("이번 달 이용 혜택", benefitTitle(null))
    }

    @Test fun `0원 안내 — 결제를 모르면 둘을 아우르고 못 바꾼 혜택만 있으면 없다고 하지 않는다`() {
        assertEquals("이번 달 YAMU 결제로 생긴 혜택이 아직 없어요", benefit(0, 0).emptyNote())
        assertEquals("이번 달 YAMU 결제가 아직 없어요", benefit(0, 0, hasPayments = false).emptyNote())
        assertEquals("이번 달 결제로 생긴 혜택이 아직 없어요", benefit(0, 0, hasPayments = true).emptyNote())
        assertEquals("원화로 셀 수 있는 혜택은 아직 없어요", benefit(0, 0, unconverted = 1).emptyNote())
        assertNull(benefit(0, 500).emptyNote())
    }

    @Test fun `종류 칸은 확정만 — 확정 전 · 환산 전은 0원으로 쓰지 않는다`() {
        assertEquals("1,000원", BenefitByType(HomeBenefitType.DISCOUNT, 1_000, 0, 0).amountText())
        assertEquals("확정 전", BenefitByType(HomeBenefitType.CASHBACK, 0, 500, 0).amountText())
        assertEquals("환산 전", BenefitByType(HomeBenefitType.POINT, 0, 0, 1).amountText())
        assertEquals("0원", BenefitByType(HomeBenefitType.POINT, 0, 0, 0).amountText())
    }

    @Test fun `종류 칸은 셀 것이 있을 때만`() {
        val types = listOf(BenefitByType(HomeBenefitType.DISCOUNT, 0, 0, 0))
        assertFalse(benefit(0, 0, types = types).showsTypes)
        assertTrue(benefit(0, 0, unconverted = 1, types = types).showsTypes)
        assertTrue(benefit(1_000, 0, types = types).showsTypes)
        assertFalse(benefit(1_000, 0).showsTypes)   // 옛 서버는 종류가 없다
    }

    @Test fun `못 바꾼 혜택 안내`() {
        assertEquals("원화로 확인되는 혜택만 더했어요. 금액에 넣지 못한 혜택이 2건 있어요", benefit(1_000, 0, unconverted = 2).unconvertedNote())
        assertNull(benefit(1_000, 0).unconvertedNote())
    }

    // ── 카드별 받은 혜택(BE -489) ──

    @Test fun `카드별 혜택은 userCardId로 잇고 카드를 모르는 줄은 뺀다`() {
        val dto = YamuJson.decodeFromString(
            HomeBenefitReportDto.serializer(),
            """{"yearMonth":"2026-11","cards":[
                 {"userCardId":1,"cardName":"생활비 카드","expectedBenefit":1500,"confirmedBenefit":1000,"unconfirmedBenefit":500,"unconvertedBenefitCount":0},
                 {"userCardId":null,"expectedBenefit":300,"confirmedBenefit":300,"unconfirmedBenefit":0},
                 {"userCardId":2,"expectedBenefit":0,"confirmedBenefit":0,"unconfirmedBenefit":0,"unconvertedBenefitCount":1}]}""",
        )
        val map = dto.toCardBenefits()!!
        assertEquals(setOf(1L, 2L), map.keys)
        assertEquals(CardBenefit(1_000, 500, 0), map[1L])
    }

    @Test fun `옛 서버 혜택 리포트엔 cards가 없다`() {
        assertNull(YamuJson.decodeFromString(HomeBenefitReportDto.serializer(), """{"yearMonth":"2026-10","summary":{}}""").toCardBenefits())
    }

    /** 마디 안 줄바꿈 없는 공백을 보통 공백으로 바꿔 읽는다 */
    private fun CardBenefit?.shown() = lineText()?.replace(' ', ' ')

    @Test fun `카드 줄 문구 — 확정 전을 받은 혜택에 섞지 않고 목록에 없는 카드는 숨긴다`() {
        assertEquals("받은 혜택 1,000원 · 확정 전 +500원", CardBenefit(1_000, 500, 0).shown())
        assertEquals("받은 혜택 2,400원", CardBenefit(2_400, 0, 0).shown())
        assertEquals("받은 혜택 0원 · 원화로 못 바꾼 혜택 1건", CardBenefit(0, 0, 1).shown())
        assertNull((null as CardBenefit?).lineText())
    }

    @Test fun `카드 줄은 가운데 점에서만 줄이 바뀐다`() {
        val parts = CardBenefit(9_800, 6_000, 0).lineText()!!.split(" · ")
        assertEquals(listOf("받은 혜택 9,800원", "확정 전 +6,000원"), parts)
    }
}
