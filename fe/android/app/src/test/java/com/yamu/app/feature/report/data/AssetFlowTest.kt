package com.yamu.app.feature.report.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import com.yamu.app.feature.report.assetDayLabel
import com.yamu.app.feature.report.eventSub
import com.yamu.app.feature.report.nowLine
import com.yamu.app.feature.report.panelNote
import com.yamu.app.feature.report.panelValue
import com.yamu.app.feature.report.panelAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

/** 소비분석 자산 흐름(Task 8.2.100) — 계좌 범위 · 날 계산 · 처음 고를 날 · 7·14·30일 뒤 · 범위 밖 출금 · 날짜 칸 글자 */
class AssetFlowTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)
    private val kb = ForecastAccountOption(1, "KB 입출금", "KB국민", "1234", 412_000, isPrimary = false)
    private val shinhan = ForecastAccountOption(2, "생활비 통장", "신한", "5678", 268_000, isPrimary = true)

    /** 오늘부터 30일(BE days=30은 31개 점) — 날마다 'AI 생활비 반영' 선이 1,000원씩 준다. 11/20 월세, 오늘 커피 */
    private val forecast = testForecast(
        days = (0..30L).map { i ->
            val d = today.plusDays(i)
            val events = when (d) {
                LocalDate.of(2026, 11, 20) -> arrayOf(ForecastEvent(d, FlowType.SCHEDULED_OUTFLOW, "월세", -250_000, FlowConfidence.CONFIRMED))
                today -> arrayOf(ForecastEvent(d, FlowType.SCHEDULED_OUTFLOW, "구독료", -15_000, FlowConfidence.CONFIRMED))
                else -> emptyArray()
            }
            day(d, *events, expected = 500_000 - i * 500, conservative = 400_000 - i * 1_000)
        },
    ).copy(
        beyondHorizon = listOf(
            ForecastEvent(LocalDate.of(2026, 12, 15), FlowType.CARD_BILLING, "생활카드 12월 청구", -320_000, FlowConfidence.EXPECTED),
            ForecastEvent(LocalDate.of(2027, 1, 15), FlowType.CARD_BILLING, "여행카드 1월 청구", -90_000, FlowConfidence.EXPECTED),
        ),
    )
    private val loadedAt = ZonedDateTime.of(2026, 11, 13, 14, 5, 0, 0, KST)
    private val flow = AssetFlow(listOf(shinhan), forecast, loadedAt = loadedAt)

    // ── 계좌 ──

    @Test fun `기준 계좌 하나만 본다 — 없으면 전체(옛 자료)`() {
        assertEquals(listOf(shinhan), assetFlowAccounts(listOf(kb, shinhan)))
        assertEquals(listOf(kb, shinhan.copy(isPrimary = false)), assetFlowAccounts(listOf(kb, shinhan.copy(isPrimary = false))))
        assertTrue(assetFlowAccounts(emptyList()).isEmpty())
    }

    @Test fun `계좌 이름표`() {
        assertEquals("신한 ··5678", accountTag(listOf(shinhan)))
        assertEquals("계좌 2개 전체", accountTag(listOf(kb, shinhan)))
    }

    @Test fun `지금 잔고는 예보 응답, 예보가 없으면 계좌 잔액 합(하나라도 모르면 모름)`() {
        assertEquals(950_000L, flow.currentBalance)
        assertEquals(680_000L, AssetFlow(listOf(kb, shinhan), null, loadedAt = loadedAt).currentBalance)
        assertNull(AssetFlow(listOf(kb, shinhan.copy(balance = null)), null, loadedAt = loadedAt).currentBalance)
    }

    // ── 날 계산 ──

    @Test fun `이번 달 — 지난날은 지난 잔고가 없으면 빈 날, 오늘 · 앞날은 생활비 반영 선`() {
        val days = assetDays(nov, today, flow, history = emptyMap())
        assertEquals(30, days.size)
        assertEquals(DayKind.NO_DATA, days[0].kind)
        assertNull("지출액으로 잔고를 만들지 않는다(COMMON-03)", days[0].value)
        assertEquals(DayKind.TODAY, days[12].kind)
        assertEquals(400_000L, days[12].value)
        assertEquals(listOf("구독료"), days[12].events.map { it.title })
        assertEquals(DayKind.EXPECTED, days[19].kind)
        assertEquals(393_000L, days[19].value)
        assertEquals(listOf("월세"), days[19].events.map { it.title })
    }

    @Test fun `받은 지난 잔고만 실선 — 입출금은 붙이지 않는다(가계부 회차를 '주요 입출금'으로 보이지 않음)`() {
        val days = assetDays(nov, today, flow, history = mapOf(LocalDate.of(2026, 11, 12) to 777_000L))
        assertEquals(DayKind.ACTUAL, days[11].kind)
        assertEquals(777_000L, days[11].value)
        assertTrue(days[11].events.isEmpty())
        assertEquals(DayKind.NO_DATA, days[10].kind)
    }

    @Test fun `다음 달 — 예보가 닿는 12월 13일까지만, 그 뒤는 범위 밖(값 없음)`() {
        val days = assetDays(dec, today, flow, emptyMap())
        assertEquals(DayKind.EXPECTED, days[12].kind)
        assertEquals(370_000L, days[12].value)
        assertEquals(DayKind.OUT_OF_RANGE, days[13].kind)
        assertNull(days[13].value)
    }

    @Test fun `예보가 실패하면 앞날은 빈 날 — 오늘 칸은 지금 잔고로 남는다`() {
        val failed = AssetFlow(listOf(shinhan), null, loadedAt = loadedAt)
        val days = assetDays(nov, today, failed, emptyMap())
        assertEquals(DayKind.TODAY, days[12].kind)
        assertNull(days[12].value)
        assertEquals(DayKind.NO_DATA, days[13].kind)
        assertEquals("268,000원" to "지금 잔고 · 오늘 14:05 기준", panelValue(days[12], failed, today))
        assertEquals(null to "예보를 불러오지 못해 비워 뒀어요.", panelValue(days[13], failed, today))
    }

    // ── 처음 고를 날 ──

    @Test fun `달을 바꾸면 이번 달은 오늘, 지난달은 말일, 다음 달은 1일`() {
        assertEquals(today, defaultAssetDate(nov, today))
        assertEquals(LocalDate.of(2026, 10, 31), defaultAssetDate(YearMonth.of(2026, 10), today))
        assertEquals(LocalDate.of(2026, 2, 28), defaultAssetDate(YearMonth.of(2026, 2), today))
        assertEquals(LocalDate.of(2026, 12, 1), defaultAssetDate(dec, today))
    }

    @Test fun `고른 날이 그달 안이면 그대로, 다른 달 날이면 그달 처음 날`() {
        assertEquals(LocalDate.of(2026, 11, 20), assetDateIn(nov, today, LocalDate.of(2026, 11, 20)))
        assertEquals(today, assetDateIn(nov, today, LocalDate.of(2026, 12, 3)))
        assertEquals(today, assetDateIn(nov, today, null))
    }

    // ── 7 · 14 · 30일 뒤 · 범위 밖 ──

    @Test fun `7 · 14 · 30일 뒤는 오늘부터 — 보는 달과 상관없다`() {
        assertEquals(LocalDate.of(2026, 11, 20) to 393_000L, forecastAfter(forecast, today, 7))
        assertEquals(LocalDate.of(2026, 11, 27) to 386_000L, forecastAfter(forecast, today, 14))
        assertEquals(LocalDate.of(2026, 12, 13) to 370_000L, forecastAfter(forecast, today, 30))
        assertNull("예보에 없는 날은 지어내지 않는다", forecastAfter(forecast, today, 31).second)
    }

    @Test fun `생활비 선이 없으면 생활비 제외 선 값`() {
        val noLiving = forecast.copy(days = forecast.days.map { it.copy(conservative = null) })
        assertEquals(496_500L, forecastAfter(noLiving, today, 7).second)
    }

    @Test fun `범위 밖 출금은 보는 달에 빠지는 것만`() {
        assertEquals(listOf("생활카드 12월 청구"), beyondInMonth(forecast, dec).map { it.title })
        assertTrue(beyondInMonth(forecast, nov).isEmpty())
        assertTrue(beyondInMonth(null, dec).isEmpty())
    }

    // ── 지난 잔고 받기 ──

    @Test fun `지난 잔고 응답에서 값 없는 줄 · 못 읽는 날짜는 버린다`() {
        val rows = listOf(DailyBalanceDto("2026-11-10", 500_000), DailyBalanceDto("2026-11-11", null), DailyBalanceDto("11/12", 1))
        assertEquals(mapOf(LocalDate.of(2026, 11, 10) to 500_000L), rows.toBalances())
    }

    // ── 날짜 칸 · 말풍선 글자 ──

    @Test fun `지금 잔고 기준 시각 — 자정을 넘겨 다시 받기 전이면 그 날짜`() {
        assertEquals("지금 잔고 · 오늘 14:05 기준", nowLine(loadedAt, today))
        assertEquals("지금 잔고 · 11/13 14:05 기준", nowLine(loadedAt, today.plusDays(1)))
    }

    @Test fun `오늘 칸 — 지금 잔고와 오늘 끝 예상이 다르면 한 줄 더`() {
        val days = assetDays(nov, today, flow, emptyMap())
        assertEquals("950,000원" to "지금 잔고 · 오늘 14:05 기준", panelValue(days[12], flow, today))
        assertEquals("오늘 끝 예상 400,000원", panelNote(days[12], flow))
        assertNull(panelNote(days[19], flow))
    }

    @Test fun `빈 날 · 범위 밖 칸 문장`() {
        val nov2 = assetDays(nov, today, flow, emptyMap())[1]
        assertEquals(null to "이날 잔고는 아직 받을 수 없어요. 쓴 돈으로 잔고를 만들어 보여 드리지 않아요.", panelValue(nov2, flow, today))
        val dec20 = assetDays(dec, today, flow, emptyMap())[19]
        assertEquals(null to "예보는 오늘부터 30일(12/13)까지만 해요.", panelValue(dec20, flow, today))
        assertEquals("393,000원" to "예상 잔고 · AI 생활비 반영", panelValue(assetDays(nov, today, flow, emptyMap())[19], flow, today))
    }

    @Test fun `말풍선 윗줄 — 입출금이 있으면 이름, 없으면 날 종류`() {
        val days = assetDays(nov, today, flow, emptyMap())
        assertEquals("11/20 (금) · 월세", assetDayLabel(days[19]))
        assertEquals("빈 날은 날짜만(아랫줄이 데이터 없음)", "11월 2일 (월)", assetDayLabel(days[1]))
        assertEquals("11월 21일 (토) · 예상", assetDayLabel(days[20]))
    }

    @Test fun `입출금 보조 줄`() {
        val bill = ForecastEvent(today, FlowType.CARD_BILLING, "신한카드 청구", -150_000, FlowConfidence.EXPECTED)
        val pay = ForecastEvent(today, FlowType.SCHEDULED_INCOME, "월급", 2_800_000, FlowConfidence.CONFIRMED)
        assertEquals("예정 · 카드 청구 · 금액 확정 전", eventSub(bill, today = false))
        assertEquals("오늘 · 수입", eventSub(pay, today = true))
    }

    private val keepAccounts = listOf(ForecastAccountOption(2, "생활비 통장", "신한", "5678", 268_000, isPrimary = true))
    private val keepAt = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, KST)

    @Test fun `예보만 실패 — 같은 계좌면 지난 예보를 남기고, 계좌가 바뀌었거나 지난 예보가 없으면 새 응답 그대로(!312 리뷰)`() {
        val prev = AssetFlow(keepAccounts, testForecast(days = listOf(day(LocalDate.of(2026, 11, 13)))), loadedAt = keepAt)
        val failed = AssetFlow(keepAccounts, null, ApiError.Network, loadedAt = keepAt.plusHours(1))
        val kept = keepLastForecast(prev, failed)
        assertEquals(prev.forecast, kept.forecast)
        assertEquals("기준 시각은 그 예보를 받은 때", keepAt, kept.loadedAt)
        assertEquals(ApiError.Network, kept.forecastError)
        assertSame(failed, keepLastForecast(null, failed))
        val other = failed.copy(accounts = listOf(keepAccounts[0].copy(accountId = 3)))
        assertSame("계좌가 바뀌면 남기지 않는다", other, keepLastForecast(prev, other))
        val fresh = prev.copy(loadedAt = keepAt.plusHours(2))
        assertSame(fresh, keepLastForecast(prev, fresh))
    }

    @Test fun `다음 달 열기 — 예보가 다음 달 1일에 닿을 때만(!312 리뷰)`() {
        val today = LocalDate.of(2026, 10, 1)   // 31일인 달의 1일 — 30일 예보가 그달 말일에서 끝난다
        fun until(end: LocalDate) = AssetFlow(keepAccounts, testForecast(days = listOf(day(today), day(end))), loadedAt = keepAt)
        assertFalse(reachesNextMonth(until(LocalDate.of(2026, 10, 31)), today))
        assertTrue(reachesNextMonth(until(LocalDate.of(2026, 11, 1)), today))
        assertFalse("예보 실패", reachesNextMonth(AssetFlow(keepAccounts, null, ApiError.Network, loadedAt = keepAt), today))
        assertFalse(reachesNextMonth(null, today))
    }

    @Test fun `날짜 칸 큰 금액 색 — 보이는 값의 부호로, 오늘은 지금 잔고(!312 리뷰)`() {
        val todayDay = AssetDay(LocalDate.of(2026, 11, 13), DayKind.TODAY, value = -20_000, events = emptyList())
        assertEquals("오늘 끝 예상이 0원 아래여도 지금 잔고가 보이면 그 부호", 15_000L, panelAmount(todayDay, current = 15_000))
        assertEquals(-20_000L, panelAmount(todayDay.copy(kind = DayKind.EXPECTED), current = 15_000))
        assertNull(panelAmount(todayDay, current = null))
    }
}
