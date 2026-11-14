package com.yamu.app.feature.home

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.CardBenefit
import com.yamu.app.feature.home.data.ForecastSummary
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.NewHomeArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

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

    // ── 새 홈 칸 forecastPreview(BE -490, S15P31E103-491) ──

    private val brief = HomeForecastBrief(9, null, 500_000, 120_000, null, null, fromTomorrow = true)

    @Test fun `새 칸을 받았으면 옛 잔고 영역이 실패해도 요약`() {
        val h = home(accountId = null, failed = setOf("balanceForecast")).copy(forecastPreview = NewHomeArea.Value(brief))
        assertEquals(ForecastSlot.Brief, forecastSlot(h))
        assertEquals(9L, h.baseAccountId)   // '잔고예보' 버튼도 새 칸의 계좌로
    }

    @Test fun `새 칸만 실패면 요약 칸 안에서 실패 · 다시 시도`() {
        assertEquals(ForecastSlot.Brief, forecastSlot(home(accountId = 7).copy(forecastPreview = NewHomeArea.Failed)))
    }

    @Test fun `새 서버가 비워 보냈으면 계좌 연결 안내`() {
        assertEquals(ForecastSlot.NoAccount, forecastSlot(home(accountId = null).copy(forecastPreview = NewHomeArea.None)))
    }

    @Test fun `연결 전이면 새 칸이 있어도 숨긴다`() {
        assertEquals(ForecastSlot.Hidden, forecastSlot(home(accountId = null, needsLinking = true).copy(forecastPreview = NewHomeArea.Value(brief))))
    }

    // ── 기준 계좌를 바꾼 뒤(셀프 리뷰 P2) — 지난 계좌의 칩 · 금액을 새 계좌 것처럼 보이지 않는다 ──

    private fun briefOf(accountId: Long) = HomeForecastBrief(accountId, "KB국민은행 ·1234", 412_000, 15_000, null, null)

    @Test fun `같은 기준 계좌의 요약은 그대로`() {
        assertEquals(HomeSection.Ready(briefOf(1)), HomeSection.Ready(briefOf(1)).forAccount(1))
    }

    @Test fun `기준 계좌가 바뀌었으면 지난 계좌 요약은 불러오는 중으로`() {
        assertEquals(HomeSection.Loading, HomeSection.Ready(briefOf(1)).forAccount(2))
        assertEquals(HomeSection.Loading, HomeSection.Ready(briefOf(1)).forAccount(null))   // 기준 계좌를 모르게 됐을 때도
    }

    @Test fun `바뀐 계좌가 실패하면 지난 계좌 값이 아니라 실패`() {
        val after = HomeSection.Ready(briefOf(1)).forAccount(2).next(ApiResult.Failure(failed))
        assertEquals(HomeSection.Failed(failed), after)
    }

    @Test fun `불러오는 중 · 실패는 그대로`() {
        assertEquals(HomeSection.Loading, HomeSection.Loading.forAccount(2))
        assertEquals(HomeSection.Failed(failed), HomeSection.Failed(failed).forAccount(2))
    }

    // ── 카드별 받은 혜택의 달(셀프 리뷰 P2) — 달이 바뀐 뒤 지난달 금액을 이번 달 카드 줄에 붙이지 않는다 ──

    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)
    private val novBenefits = MonthCardBenefits(nov, mapOf(11L to CardBenefit(9_800, 6_000, 0)))

    @Test fun `같은 달 호출이 실패하면 그 달 값은 남긴다`() {
        assertEquals(HomeSection.Ready(novBenefits), HomeSection.Ready(novBenefits).nextFor(nov, ApiResult.Failure(failed)))
    }

    @Test fun `달이 바뀐 뒤 실패하면 지난달 값을 버리고 실패`() {
        assertEquals(HomeSection.Failed(failed), HomeSection.Ready(novBenefits).nextFor(dec, ApiResult.Failure(failed)))
    }

    @Test fun `받으면 받은 달과 함께 둔다`() {
        val map = mapOf(11L to CardBenefit(1_000, 0, 0))
        assertEquals(HomeSection.Ready(MonthCardBenefits(dec, map)), HomeSection.Ready(novBenefits).nextFor(dec, ApiResult.Success(map)))
    }

    private fun benefitOf(month: YearMonth) = HomeBenefit(month, month.atDay(1), month.atDay(1), 0, 0, null)

    @Test fun `카드 줄은 히어로와 같은 달의 혜택만`() {
        val home = home(accountId = 1)
        val dec1 = HomeUiState(content = home.copy(yamuBenefit = NewHomeArea.Value(benefitOf(dec))), cardBenefits = HomeSection.Ready(novBenefits))
        assertNull(dec1.cardBenefitsShown())
        val nov14 = dec1.copy(content = home.copy(yamuBenefit = NewHomeArea.Value(benefitOf(nov))))
        assertEquals(novBenefits.byCard, nov14.cardBenefitsShown())
    }

    @Test fun `새 홈 칸이 없으면 카드 줄에 혜택을 붙이지 않는다`() {
        val old = HomeUiState(content = home(accountId = 1), cardBenefits = HomeSection.Ready(novBenefits))
        assertNull(old.cardBenefitsShown())   // 옛 서버(OldServer)
        assertNull(old.copy(content = home(accountId = 1).copy(yamuBenefit = NewHomeArea.Failed)).cardBenefitsShown())
    }

    @Test fun `새 칸 요약도 새 칸의 기준 계좌로 맞춘다`() {
        // 새 서버 — 기준 계좌를 9로 바꿨는데 요약 칸엔 아직 지난 계좌(1)의 값이 있으면 그리지 않는다
        val h = home(accountId = 9).copy(forecastPreview = NewHomeArea.Failed)
        assertEquals(HomeSection.Loading, HomeSection.Ready(briefOf(1)).forAccount(h.baseAccountId))
        assertEquals(HomeSection.Ready(brief), HomeSection.Ready(brief).forAccount(home(accountId = 1).copy(forecastPreview = NewHomeArea.Value(brief)).baseAccountId))
    }
}
