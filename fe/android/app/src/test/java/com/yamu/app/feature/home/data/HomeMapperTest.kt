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
    fun `지금 명세 예시 모양(월급 없음)이면 지금 잔액과 월급 안내로 그린다`() {
        val home = parse(
            """
            {"cards":{"count":4,"items":[{"userCardId":12,"nickname":"생활비 카드","issuerColor":"#0046FF","maskedNumber":"9410-****-****-5678","cardType":"CHECK"}]},
             "performanceAlerts":[{"userCardId":15,"nickname":"메인 신용카드","remainingAmount":120000,"achievementRate":60.0,"daysLeft":11}],
             "balanceForecast":{"accountId":3,"currentBalance":620000,"endBalancePredicted":2214500,"nextRiskDate":"2026-10-29","horizonDays":30},
             "monthSummary":{"yearMonth":"2026-10","totalSpent":1250000,"totalBenefit":28500},
             "unreadNotificationCount":3,"partialErrors":[],"dataSyncedAt":"2026-10-20T09:00:00+09:00"}
            """,
        )
        // nextIncome이 없으면 월급을 정하기 전이다 — 수입 없이 본 30일 숫자 대신 지금 잔액(S15P31E103-429)
        val forecast = home.forecast!!
        assertEquals("지금 잔액", forecast.label)
        assertEquals(620_000L, forecast.amount)
        assertFalse(forecast.isEstimate)
        assertNull(forecast.shortageFrom)
        assertTrue(forecast.needsIncome)
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
        assertFalse(forecast.needsIncome)                         // 월급이 있으면 안내 없음
        assertEquals(2, home.todos.size)                          // 최대 2줄
        assertEquals(TodoTone.Error, home.todos[1].tone)
        assertEquals("야무카페 역삼점", home.recentPayment!!.merchantName)
        assertEquals("2026-10-16T05:30:00Z", home.recentPayment!!.paidAtIso)   // 줄 시각은 paidAtLabel이 만든다
    }

    @Test
    fun `계좌만 연결하고 카드가 0장이면 카드 등록으로 이끈다`() {
        // 가입 1원 인증으로 계좌가 붙어 BE는 DONE을 준다(S15P31E103-430)
        val home = parse("""{"cards":{"count":0},"onboarding":{"step":"DONE"}}""")
        assertFalse(home.needsLinking)
        assertTrue(home.needsCard)
    }

    @Test
    fun `연결 전이면 카드 등록이 아니라 연결로`() {
        val home = parse("""{"cards":{"count":0},"onboarding":{"step":"LINK"}}""")
        assertTrue(home.needsLinking)
        assertFalse(home.needsCard)
    }

    @Test
    fun `카드 영역만 실패했으면 0장으로 보지 않는다`() {
        val home = parse("""{"onboarding":{"step":"DONE"},"partialErrors":[{"area":"cards","code":"INTERNAL_ERROR"}]}""")
        assertEquals(0, home.cardCount)
        assertFalse(home.needsCard)
    }

    @Test
    fun `카드가 있으면 카드 등록 안내 없음`() {
        val home = parse("""{"cards":{"count":2},"onboarding":{"step":"DONE"}}""")
        assertFalse(home.needsCard)
    }

    @Test
    fun `월급 없이 지금 BE 모양이면 음수 30일 대신 지금 잔액을 쓴다`() {
        // 11/12 실서버 김야무와 같은 모양 — 수입 없이 30일을 계산해 끝 · 최저 잔액이 음수
        val home = parse(
            """
            {"cards":{"count":3},
             "balanceForecast":{"accountId":3,"currentBalance":950000,"endBalancePredicted":-275622,"nextRiskDate":"2026-11-07","horizonDays":30,"minBalance":-275622,"isEstimate":true,"isProvisional":false},
             "onboarding":{"step":"DONE"}}
            """,
        )
        val forecast = home.forecast!!
        assertEquals("지금 잔액", forecast.label)
        assertEquals(950_000L, forecast.amount)
        assertFalse(forecast.isEstimate)
        assertNull(forecast.incomeDate)
        assertNull(forecast.daysToIncome)
        assertNull(forecast.shortageFrom)
        assertTrue(forecast.needsIncome)
    }

    @Test
    fun `월급 없고 지금 잔액도 0원 이하면 예전처럼 30일 뒤 카드 · 월급 안내는 둔다`() {
        val home = parse(
            """
            {"cards":{"count":3},
             "balanceForecast":{"currentBalance":0,"endBalancePredicted":-480000,"nextRiskDate":"2026-10-16","horizonDays":30,"minBalance":-480000,"isEstimate":true},
             "onboarding":{"step":"DONE"}}
            """,
        )
        val forecast = home.forecast!!
        assertEquals("30일 뒤 예상 잔액", forecast.label)
        assertEquals(-480_000L, forecast.amount)
        assertEquals(LocalDate.of(2026, 10, 16), forecast.shortageFrom)
        assertTrue(forecast.needsIncome)
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

    // ── 결제 칸 밖 최근 결제(셀프 리뷰 P2: 카드를 모두 지운 사람도 최근 결제 · 결제 내역 입구가 남는다) ──

    private val recent = """"recentPayment":{"paymentId":7,"merchantName":"스타벅스 역삼점","amount":15000,"paidAt":"2026-10-16T05:00:00Z"}"""

    @Test
    fun `카드를 모두 지웠어도 최근 결제는 안내 아래 따로 남는다`() {
        val home = parse("""{"cards":{"count":0},"onboarding":{"step":"DONE"},$recent}""")
        assertTrue(home.needsCard)
        assertEquals(7L, home.recentPaymentOutsidePayCard()?.paymentId)
    }

    @Test
    fun `연결 전이어도 최근 결제가 있으면 따로 남는다`() {
        val home = parse("""{"cards":{"count":0},"onboarding":{"step":"LINK"},$recent}""")
        assertEquals(7L, home.recentPaymentOutsidePayCard()?.paymentId)
    }

    @Test
    fun `카드가 있으면 최근 결제는 결제 칸 안에만(따로 그리지 않는다)`() {
        assertNull(parse("""{"cards":{"count":2},"onboarding":{"step":"DONE"},$recent}""").recentPaymentOutsidePayCard())
    }

    @Test
    fun `최근 결제가 없으면 따로 칸도 없다`() {
        assertNull(parse("""{"cards":{"count":0},"onboarding":{"step":"DONE"}}""").recentPaymentOutsidePayCard())
    }
}
