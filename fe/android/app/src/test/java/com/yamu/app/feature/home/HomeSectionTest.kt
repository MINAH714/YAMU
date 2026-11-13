package com.yamu.app.feature.home

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.ForecastSummary
import com.yamu.app.feature.home.data.HomeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 홈 칸마다 따로 받는 값(v4 · COMMON-08) — 다시 불러오다 실패해도 마지막 값을 지우지 않는다 */
class HomeSectionTest {

    private val failed = ApiError.Server("INTERNAL_ERROR")

    @Test fun `처음 받으면 Ready`() {
        assertEquals(HomeSection.Ready(3), HomeSection.Loading.next(ApiResult.Success(3)))
    }

    @Test fun `처음부터 실패면 Failed`() {
        assertEquals(HomeSection.Failed(failed), HomeSection.Loading.next(ApiResult.Failure(failed)))
    }

    @Test fun `받은 값이 있으면 실패해도 그 값을 둔다`() {
        assertEquals(HomeSection.Ready(3), HomeSection.Ready(3).next(ApiResult.Failure(failed)))
    }

    @Test fun `실패 뒤 다시 받으면 새 값`() {
        assertEquals(HomeSection.Ready(4), HomeSection.Failed(failed).next(ApiResult.Success(4)))
    }

    @Test fun `값은 Ready에만 있다`() {
        assertEquals(5, HomeSection.Ready(5).dataOrNull)
        assertNull(HomeSection.Loading.dataOrNull)
        assertNull(HomeSection.Failed(failed).dataOrNull)
    }

    // ── 잔고예보 요약 칸(HOME-14 · 사용자 상태별 처리) ──

    private fun home(accountId: Long?, needsLinking: Boolean = false, failed: Set<String> = emptySet()) = HomeSummary(
        forecast = accountId?.let { ForecastSummary("지금 잔액", 412_000, false, false, null, null, null, accountId = it, currentBalance = 412_000) },
        recentPayment = null, todos = emptyList(), cardCount = 6, unreadCount = 0, needsLinking = needsLinking, syncedAtIso = null, failedAreas = failed,
    )

    @Test fun `기준 계좌가 있으면 요약`() {
        assertEquals(ForecastSlot.Brief, forecastSlot(home(accountId = 7)))
    }

    @Test fun `연결 전이면 위 안내가 맡아 숨긴다`() {
        assertEquals(ForecastSlot.Hidden, forecastSlot(home(accountId = null, needsLinking = true)))
    }

    @Test fun `잔고 영역만 실패했으면 계좌를 몰라 실패 · 비어 있으면 계좌 연결 안내`() {
        assertEquals(ForecastSlot.HomeFailed, forecastSlot(home(accountId = null, failed = setOf("balanceForecast"))))
        assertEquals(ForecastSlot.NoAccount, forecastSlot(home(accountId = null)))
    }
}
