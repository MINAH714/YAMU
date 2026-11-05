package com.yamu.app.feature.home

import com.yamu.app.core.network.ApiError
import com.yamu.app.feature.home.data.ForecastSummary
import com.yamu.app.feature.home.data.HomeSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 잔고예보 · 예정 수입·지출 진입로를 언제 따로 보여 주나 */
class HomeUiStateTest {

    private val forecast = ForecastSummary("월급 전 예상 잔액", 350_000, isEstimate = true, isProvisional = false, incomeDate = null, daysToIncome = null, shortageFrom = null)
    private val failed = ApiError.NotFound("API_NOT_FOUND")

    private fun summary(forecast: ForecastSummary?, needsLinking: Boolean = false) =
        HomeSummary(forecast, recentPayment = null, todos = emptyList(), cardCount = 1, unreadCount = 0, needsLinking, syncedAtIso = null, failedAreas = emptySet())

    @Test fun `불러오는 중에는 보이지 않는다`() {
        assertFalse(HomeUiState(isLoading = true).showsEntries)
    }

    @Test fun `요약을 못 받으면 보인다`() {
        assertTrue(HomeUiState(isLoading = false, error = failed).showsEntries)
    }

    @Test fun `예상 잔액 카드가 있으면 보이지 않는다`() {
        assertFalse(HomeUiState(isLoading = false, content = summary(forecast)).showsEntries)
    }

    @Test fun `새로고침이 실패해도 마지막 예상 잔액 카드가 있으면 보이지 않는다`() {
        assertFalse(HomeUiState(isLoading = false, content = summary(forecast), error = failed).showsEntries)
    }

    @Test fun `요약은 받았는데 예상 잔액이 없으면 보인다`() {
        assertTrue(HomeUiState(isLoading = false, content = summary(forecast = null)).showsEntries)
    }

    @Test fun `연결 전이면 예상 잔액이 없어도 보이지 않는다`() {
        assertFalse(HomeUiState(isLoading = false, content = summary(forecast = null, needsLinking = true)).showsEntries)
    }
}
