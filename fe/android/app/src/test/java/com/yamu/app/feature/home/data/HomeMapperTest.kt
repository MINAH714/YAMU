package com.yamu.app.feature.home.data

import com.yamu.app.core.network.YamuJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #58 응답 → 홈 화면 값(07 문서 3-5 표) — 지금 명세 모양 · 보완안 모양 둘 다 */
class HomeMapperTest {

    private val today = LocalDate.of(2026, 10, 16)
    private fun parse(raw: String) = YamuJson.decodeFromString<HomeDto>(raw).toSummary(today)

    @Test
    fun `지금 명세 예시 모양이면 30일 뒤 예상 잔액으로 그린다`() {
        val home = parse(
            """
            {"cards":{"count":4,"items":[{"userCardId":12,"nickname":"생활비 카드","issuerColor":"#0046FF","maskedNumber":"9410-****-****-5678","cardType":"CHECK"}]},
             "performanceAlerts":[{"userCardId":15,"nickname":"메인 신용카드","remainingAmount":120000,"achievementRate":60.0,"daysLeft":11}],
             "balanceForecast":{"accountId":3,"currentBalance":620000,"endBalancePredicted":2214500,"nextRiskDate":"2026-10-29","horizonDays":30},
             "monthSummary":{"yearMonth":"2026-10","totalSpent":1250000,"totalBenefit":28500},
             "unreadNotificationCount":3,"partialErrors":[],"dataSyncedAt":"2026-10-20T09:00:00+09:00"}
            """,
        )
        val forecast = home.forecast!!
        assertEquals("30일 뒤 예상 잔액", forecast.label)
        assertEquals(2_214_500L, forecast.amount)
        assertTrue(forecast.isEstimate)
        assertEquals(LocalDate.of(2026, 10, 29), forecast.shortageFrom)
        assertEquals("메인 신용카드 실적까지 120,000원 남았어요", home.todos.single().title)
        assertEquals(3, home.unreadCount)
        assertFalse(home.needsLinking)
    }

    @Test
    fun `보완안 필드가 있으면 월급 전 가장 낮을 때 · 챙길 것 · 방금 결제를 쓴다`() {
        val home = parse(
            """
            {"cards":{"count":6},
             "balanceForecast":{"currentBalance":950000,"minBalance":350000,"nextIncome":{"date":"2026-11-10","amount":2800000},"isEstimate":true,"isProvisional":true},
             "todos":[{"title":"통신비만 우리카드로","tag":"실적 달성 추천","tone":"PRIMARY","userCardId":1},
                      {"title":"NH농협은 채우지 않기","tag":"추가 소비 비추천","tone":"ERROR","userCardId":3},
                      {"title":"세 번째는 안 보임"}],
             "recentPayment":{"paymentId":1,"merchantName":"야무카페 역삼점","amount":15000,"cardName":"신한카드","benefitAmount":1500,"paidAt":"2026-10-16T05:30:00Z"},
             "onboarding":{"step":"DONE"}}
            """,
        )
        val forecast = home.forecast!!
        assertEquals("월급 전 가장 낮을 때", forecast.label)
        assertEquals(350_000L, forecast.amount)
        assertEquals(25L, forecast.daysToIncome)
        assertTrue(forecast.isProvisional)
        assertNull(forecast.shortageFrom)
        assertEquals(2, home.todos.size)                          // 최대 2줄
        assertEquals(TodoTone.Error, home.todos[1].tone)
        assertEquals("야무카페 역삼점", home.recentPayment!!.merchantName)
        assertEquals("2026-10-16T05:30:00Z", home.recentPayment!!.paidAtIso)   // 줄 시각은 paidAtLabel이 만든다
    }

    @Test
    fun `카드가 0장이면 연결 전`() {
        val home = parse("""{"cards":{"count":0}}""")
        assertTrue(home.needsLinking)
        assertNull(home.forecast)
    }

    @Test
    fun `부분 실패한 영역 이름을 모은다`() {
        val home = parse("""{"cards":{"count":2},"partialErrors":["balanceForecast",{"area":"monthSummary","code":"DATA_INSUFFICIENT"}]}""")
        assertEquals(setOf("balanceForecast", "monthSummary"), home.failedAreas)
    }
}
