package com.yamu.app.feature.home

import com.yamu.app.feature.home.data.ForecastSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 예상 잔액 카드 상태 알약 — 히어로(0원보다 많음)는 위험일을 '안전 잔액 아래'로, 흰 카드만 '부족 예상'(!251 리뷰) */
class ForecastStatusLineTest {

    private val payday = LocalDate.of(2026, 11, 25)
    private val risk = LocalDate.of(2026, 11, 21)

    private fun forecast(amount: Long, shortageFrom: LocalDate?, incomeDate: LocalDate? = payday) =
        ForecastSummary("월급 전 가장 낮을 때", amount, isEstimate = true, isProvisional = false, incomeDate, daysToIncome = 14, shortageFrom)

    @Test fun `히어로는 위험일을 잔고예보와 같은 안전 잔액 아래로 말한다`() {
        assertEquals("11/21부터 안전 잔액 아래", forecast(60_000, risk).statusLine(hero = true))
    }

    @Test fun `흰 카드는 위험일을 부족 예상으로 말한다`() {
        assertEquals("11/21부터 부족 예상", forecast(-50_000, risk).statusLine(hero = false))
    }

    @Test fun `위험일이 없고 월급날이 있으면 두 카드 모두 부족 없음`() {
        assertEquals("월급날(11/25)까지 부족 없음", forecast(350_000, shortageFrom = null).statusLine(hero = true))
        assertEquals("월급날(11/25)까지 부족 없음", forecast(0, shortageFrom = null).statusLine(hero = false))
    }

    @Test fun `위험일도 월급날도 없으면 알약이 없다`() {
        assertNull(forecast(350_000, shortageFrom = null, incomeDate = null).statusLine(hero = true))
    }
}
