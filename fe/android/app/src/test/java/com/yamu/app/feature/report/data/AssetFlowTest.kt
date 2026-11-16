package com.yamu.app.feature.report.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastEventDto
import com.yamu.app.feature.forecast.data.day
import com.yamu.app.feature.forecast.data.testForecast
import com.yamu.app.feature.forecast.splitByKind
import com.yamu.app.feature.report.assetDayLabel
import com.yamu.app.feature.report.eventSub
import com.yamu.app.feature.report.gapLines
import com.yamu.app.feature.report.nowLine
import com.yamu.app.feature.report.panelNote
import com.yamu.app.feature.report.panelValue
import com.yamu.app.feature.report.panelAmount
import com.yamu.app.feature.report.showsDayEvents
import com.yamu.app.feature.report.lineWithLiving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

/** 소비분석 자산 흐름(Task 8.2.100 · 8.2.103) — 계좌 범위 · asset-flows 읽기 · 옛 서버 동작 · 처음 고를 날 · 7·14·30일 뒤 · 날짜 칸 글자 */
class AssetFlowTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val nov = YearMonth.of(2026, 11)
    private val dec = YearMonth.of(2026, 12)
    private val kb = ForecastAccountOption(1, "KB 입출금", "KB국민", "1234", 412_000, isPrimary = false)
    private val shinhan = ForecastAccountOption(2, "생활비 통장", "신한", "5678", 268_000, isPrimary = true)

    /** 오늘부터 30일(BE days=30은 31개 점) — 날마다 'AI 생활비 반영' 선이 1,000원씩 준다. 11/20 월세, 오늘 구독료 */
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

    /** asset-flows 11월(신한 하나): 1~9일 지난 잔고 자료 없음, 10~12일 실제, 13일 지금, 14일부터 예상(14일은 conservative 없음) */
    private fun novDto(status: (LocalDate) -> String = { "AVAILABLE" }) = AssetFlowDto(
        yearMonth = "2026-11",
        accountIds = listOf(2),
        currentTotalBalance = 268_000,
        balanceAsOf = "2026-11-13T05:30:00Z",
        series = (1..30).map { d ->
            val date = nov.atDay(d)
            when {
                d < 10 -> AssetFlowPointDto(date.toString(), "ACTUAL", "NO_DATA")
                d < 13 -> status(date).let { s -> AssetFlowPointDto(date.toString(), "ACTUAL", s, displayBalance = (300_000L + d).takeIf { s == "AVAILABLE" }, actualBalance = 300_000L + d) }
                d == 13 -> AssetFlowPointDto(date.toString(), "CURRENT", "AVAILABLE", displayBalance = 268_000, actualBalance = 268_000)
                else -> AssetFlowPointDto(
                    date.toString(), "FORECAST", "AVAILABLE",
                    displayBalance = 250_000L - d,
                    forecast = AssetFlowForecastDto(confirmed = 999_999, expected = 260_000, conservative = if (d == 14) null else 250_000L - d),
                    events = if (d == 20) listOf(ForecastEventDto("SCHEDULED_OUTFLOW", "월세", -250_000, "CONFIRMED"), ForecastEventDto("SCHEDULED_OUTFLOW", "빈 줄", 0, "CONFIRMED")) else emptyList(),
                )
            }
        },
        forecastFrom = "2026-11-14",
        forecastTo = "2026-12-13",
        partialErrors = listOf(AssetFlowPartialErrorDto(2, "2026-11-01", "2026-11-09", "HISTORY_INCOMPLETE")),
    )

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

    // ── asset-flows 읽기 ──

    @Test fun `서버 날 종류 — 상태를 같이 본다, 모르는 값은 표시 불가`() {
        val d = LocalDate.of(2026, 11, 20)
        val to = LocalDate.of(2026, 12, 13)
        assertEquals(DayKind.ACTUAL, pointKind("ACTUAL", "AVAILABLE", d, to))
        assertEquals(DayKind.NO_DATA, pointKind("ACTUAL", "NO_DATA", d, to))
        assertEquals(DayKind.ERROR, pointKind("ACTUAL", "ERROR", d, to))
        assertEquals("값이 없어도 오늘 기준선은 그린다", DayKind.TODAY, pointKind("CURRENT", "ERROR", d, to))
        assertEquals(DayKind.EXPECTED, pointKind("forecast", "available", d, to))
        assertEquals("예보 마지막 날 뒤는 범위 밖", DayKind.OUT_OF_RANGE, pointKind("FORECAST", "NO_DATA", LocalDate.of(2026, 12, 14), to))
        assertEquals("예보를 못 받은 앞날은 자료 없음", DayKind.NO_DATA, pointKind("FORECAST", "NO_DATA", LocalDate.of(2026, 12, 14), null))
        assertEquals(DayKind.UNKNOWN, pointKind("SNAPSHOT", "AVAILABLE", d, to))
        assertEquals(DayKind.UNKNOWN, pointKind("ACTUAL", "STALE", d, to))
        assertEquals(DayKind.UNKNOWN, pointKind(null, null, d, to))
    }

    @Test fun `응답 읽기 — 값은 AVAILABLE일 때 displayBalance만, 예보 confirmed는 쓰지 않는다`() {
        val m = novDto().toMonthFlow(nov)
        assertEquals(30, m.points.size)
        assertNull("자료 없음은 0원이 아니라 비움", m.points[0].value)
        assertEquals(300_010L, m.points[9].value)
        assertEquals(DayKind.TODAY, m.points[12].kind)
        assertEquals(268_000L, m.points[12].value)
        assertEquals("conservative가 없으면 서버가 expected를 displayBalance로 준다 — 그 값 그대로", 249_986L, m.points[13].value)
        assertTrue(m.points.none { it.value == 999_999L })
        assertEquals("0원 이벤트는 뺀다", listOf("월세"), m.points[19].events.map { it.title })
        assertEquals(listOf(2L), m.accountIds)
        assertEquals(LocalDate.of(2026, 12, 13), m.forecastTo)
        assertEquals("UTC → 한국 시각", ZonedDateTime.of(2026, 11, 13, 14, 30, 0, 0, KST).toInstant(), m.balanceAsOf?.toInstant())
        assertEquals(listOf(HistoryGap(2, nov.atDay(1), nov.atDay(9), "HISTORY_INCOMPLETE")), m.gaps)
    }

    @Test fun `읽지 못하는 날짜 줄은 버리고, 시각을 못 읽으면 null`() {
        val m = AssetFlowDto(yearMonth = "2026-11", series = listOf(AssetFlowPointDto("11/1", "ACTUAL", "AVAILABLE", 1)), balanceAsOf = "어제").toMonthFlow(nov)
        assertTrue(m.points.isEmpty())
        assertNull(m.balanceAsOf)
    }

    // ── 한 달 날 계산 ──

    @Test fun `asset-flows가 있으면 지난 실제 · 오늘 지금 잔고 · 앞날 예상을 그대로`() {
        val m = novDto().toMonthFlow(nov)
        val days = assetDays(nov, today, flow, m)
        assertEquals(DayKind.NO_DATA, days[0].kind)
        assertEquals(DayKind.ACTUAL, days[9].kind)
        assertEquals(300_010L, days[9].value)
        assertEquals(DayKind.TODAY, days[12].kind)
        assertEquals("오늘 점은 지금 잔고(CURRENT)", 268_000L, days[12].value)
        assertEquals("오늘 끝 예상은 30일 예보의 오늘 값", 400_000L, days[12].endOfDay)
        assertEquals("오늘 입출금은 30일 예보에서", listOf("구독료"), days[12].events.map { it.title })
        assertEquals(DayKind.EXPECTED, days[19].kind)
        assertEquals(listOf("월세"), days[19].events.map { it.title })
    }

    @Test fun `빈 날 · 조회 실패 날은 실선 · 점선 어디에도 넣지 않는다(결측을 잇지 않음)`() {
        val m = novDto { d -> if (d.dayOfMonth == 11) "ERROR" else "AVAILABLE" }.toMonthFlow(nov)
        val days = assetDays(nov, today, flow, m)
        assertEquals(DayKind.ERROR, days[10].kind)
        val (actual, expected) = splitByKind(days.map { it.value }, days.map { it.kind })
        assertNull(actual[10])
        assertNull(expected[10])
        assertEquals(300_010L, actual[9])
        assertEquals(300_012L, actual[11])
    }

    @Test fun `계좌가 다르거나 다른 달 응답이면 섞지 않고 예보만으로(COMMON-04)`() {
        val other = novDto().copy(accountIds = listOf(1)).toMonthFlow(nov)
        assertFalse(flow.matches(other))
        assertEquals(DayKind.NO_DATA, assetDays(nov, today, flow, other)[9].kind)
        val decFlow = novDto().copy(yearMonth = "2026-12").toMonthFlow(dec)
        assertEquals(DayKind.NO_DATA, assetDays(nov, today, flow, decFlow)[9].kind)
    }

    @Test fun `옛 서버(asset-flows 없음) — 지난날은 빈 날, 오늘 · 앞날은 30일 예보`() {
        val days = assetDays(nov, today, flow, null)
        assertEquals(30, days.size)
        assertEquals(DayKind.NO_DATA, days[0].kind)
        assertNull("지출액으로 잔고를 만들지 않는다(COMMON-03)", days[0].value)
        assertEquals(DayKind.TODAY, days[12].kind)
        assertEquals(400_000L, days[12].value)
        assertEquals(400_000L, days[12].endOfDay)
        assertEquals(393_000L, days[19].value)
    }

    @Test fun `옛 서버 다음 달 — 예보가 닿는 12월 13일까지만, 그 뒤는 범위 밖`() {
        val days = assetDays(dec, today, flow, null)
        assertEquals(DayKind.EXPECTED, days[12].kind)
        assertEquals(370_000L, days[12].value)
        assertEquals(DayKind.OUT_OF_RANGE, days[13].kind)
        assertNull(days[13].value)
    }

    @Test fun `칸 머리 지금 잔고 — 같은 계좌의 asset-flows면 은행에서 읽은 값 · 시각, 아니면 30일 예보`() {
        val m = novDto().toMonthFlow(nov)
        val (balance, asOf) = currentBalanceOf(flow, m)
        assertEquals(268_000L, balance)
        assertEquals(14, asOf.hour)
        assertEquals(30, asOf.minute)
        assertEquals(950_000L to loadedAt, currentBalanceOf(flow, null))
        assertEquals(950_000L to loadedAt, currentBalanceOf(flow, m.copy(accountIds = listOf(1))))
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

    // ── 날짜 칸 · 말풍선 글자 ──

    @Test fun `지금 잔고 기준 시각 — 자정을 넘겨 다시 받기 전이면 그 날짜`() {
        assertEquals("오늘 14:05 기준", nowLine(loadedAt, today))
        assertEquals("11/13 14:05 기준", nowLine(loadedAt, today.plusDays(1)))
    }

    @Test fun `오늘 칸 — 지금 잔고, 오늘 끝 예상이 다르면 한 줄 더`() {
        val m = novDto().toMonthFlow(nov)
        val days = assetDays(nov, today, flow, m)
        val (current, asOf) = currentBalanceOf(flow, m)
        assertEquals("268,000원" to "오늘 14:30 기준", panelValue(days[12], flow, m, today, current, asOf))
        assertEquals("오늘 끝 예상 400,000원", panelNote(days[12], current))
        assertNull(panelNote(days[12], 400_000))
        assertEquals(null to "오늘 잔고를 확인하지 못했어요", panelValue(days[12], flow, m, today, null, asOf))
    }

    @Test fun `빈 날 칸 — 자료 없음은 그 구간의 까닭, 조회 실패는 다시 시도, 표시 불가 · 범위 밖은 따로`() {
        val m = novDto { d -> if (d.dayOfMonth == 11) "ERROR" else "AVAILABLE" }.toMonthFlow(nov)
            .let { it.copy(gaps = it.gaps + HistoryGap(2, nov.atDay(11), nov.atDay(11), "UPSTREAM_TIMEOUT")) }
        val days = assetDays(nov, today, flow, m)
        assertEquals(null to "거래 내역이 모두 오지 않아 지난 잔고를 확인하지 못했어요. 쓴 돈으로 잔고를 만들어 보여 드리지 않아요.", panelValue(days[1], flow, m, today, 268_000, loadedAt))
        assertEquals(null to "은행 응답이 늦어 지난 잔고를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.", panelValue(days[10], flow, m, today, 268_000, loadedAt))
        val unknown = AssetDay(nov.atDay(5), DayKind.UNKNOWN, null, emptyList())
        assertEquals(null to "이날 잔고는 지금 앱에서 표시할 수 없어요. 앱을 업데이트하면 보일 수 있어요.", panelValue(unknown, flow, m, today, 268_000, loadedAt))
        val decDays = assetDays(dec, today, flow, null)
        assertEquals(null to "예보는 오늘부터 30일(12/13)까지만 해요.", panelValue(decDays[19], flow, null, today, 950_000, loadedAt))
        assertEquals("옛 서버의 지난날", null to "이날 잔고는 아직 받을 수 없어요. 쓴 돈으로 잔고를 만들어 보여 드리지 않아요.", panelValue(assetDays(nov, today, flow, null)[1], flow, null, today, 950_000, loadedAt))
    }

    @Test fun `빠진 구간 안내 — 기간과 까닭 한 줄, 같은 줄은 한 번`() {
        val m = novDto().toMonthFlow(nov).let { it.copy(gaps = it.gaps + it.gaps) }
        assertEquals(listOf("11/1~11/9 · 거래 내역이 모두 오지 않아 지난 잔고를 확인하지 못했어요"), gapLines(m))
        assertTrue(HistoryGap(1, null, null, "FIN_API_ERROR").isFetchFailure())
        assertFalse(HistoryGap(1, null, null, "NO_ANCHOR").isFetchFailure())
        assertEquals("지난 잔고를 확인하지 못했어요", gapReason("NEW_REASON"))
    }

    @Test fun `말풍선 윗줄 — 입출금이 있으면 이름, 없으면 날 종류, 빈 날은 날짜만`() {
        val days = assetDays(nov, today, flow, null)
        assertEquals("11/20 (금) · 월세", assetDayLabel(days[19]))
        assertEquals("11월 2일 (월)", assetDayLabel(days[1]))
        assertEquals("11월 21일 (토) · 예상", assetDayLabel(days[20]))
    }

    @Test fun `입출금 보조 줄`() {
        val bill = ForecastEvent(today, FlowType.CARD_BILLING, "신한카드 청구", -150_000, FlowConfidence.EXPECTED)
        val pay = ForecastEvent(today, FlowType.SCHEDULED_INCOME, "월급", 2_800_000, FlowConfidence.CONFIRMED)
        assertEquals("예정 · 카드 청구 · 금액 확정 전", eventSub(bill, today = false))
        assertEquals("오늘 · 수입", eventSub(pay, today = true))
    }

    @Test fun `날짜 칸 입출금 줄 — 오늘 · 앞날만, 지난날은 거래 목록이 없어 '없어요'로 단정하지 않는다(!320 P1)`() {
        assertTrue(showsDayEvents(DayKind.TODAY))
        assertTrue(showsDayEvents(DayKind.EXPECTED))
        listOf(DayKind.ACTUAL, DayKind.NO_DATA, DayKind.ERROR, DayKind.UNKNOWN, DayKind.OUT_OF_RANGE).forEach { assertFalse(it.name, showsDayEvents(it)) }
    }

    @Test fun `없는 API — API_NOT_FOUND · METHOD_NOT_ALLOWED와 본문 없는 404만, 코드가 있는 데이터 404는 아니다(!320 P1)`() {
        assertTrue(isMissingApi(ApiError.from(404, "API_NOT_FOUND")))
        assertTrue(isMissingApi(ApiError.from(405, "METHOD_NOT_ALLOWED")))
        assertTrue("프록시가 JSON 없이 준 404", isMissingApi(ApiError.from(404, null)))
        assertFalse("그사이 기준 계좌 해제 등", isMissingApi(ApiError.from(404, "RESOURCE_NOT_FOUND")))
        assertFalse(isMissingApi(ApiError.from(502, "FIN_API_UNAVAILABLE")))
        assertFalse(isMissingApi(ApiError.Network))
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

    @Test fun `점선 이름 — 30일 예보만 실패해도 asset-flows의 conservative로 'AI 생활비 반영'(!320 리뷰)`() {
        fun dto(conservative: Long?) = AssetFlowDto(
            yearMonth = "2026-11", accountIds = listOf(2),
            series = listOf(
                AssetFlowPointDto("2026-11-13", "CURRENT", "AVAILABLE", displayBalance = 268_000),
                AssetFlowPointDto("2026-11-14", "FORECAST", "AVAILABLE", displayBalance = 250_000, forecast = AssetFlowForecastDto(260_000, 260_000, conservative)),
            ),
        )
        val withLiving = dto(240_000).toMonthFlow(YearMonth.of(2026, 11))
        val without = dto(null).toMonthFlow(YearMonth.of(2026, 11))
        val past = AssetFlowDto(yearMonth = "2026-10", accountIds = listOf(2), series = listOf(AssetFlowPointDto("2026-10-01", "ACTUAL", "AVAILABLE", displayBalance = 1))).toMonthFlow(YearMonth.of(2026, 10))
        assertEquals(true, withLiving.hasConservative)
        assertEquals(false, without.hasConservative)
        assertNull("예보 점이 없는 달", past.hasConservative)

        val forecastFailed = AssetFlow(keepAccounts, null, ApiError.Network, loadedAt = keepAt)
        assertTrue(lineWithLiving(forecastFailed, withLiving))
        assertFalse(lineWithLiving(forecastFailed, without))
        val forecastWithLiving = AssetFlow(keepAccounts, testForecast(days = listOf(day(LocalDate.of(2026, 11, 13)))), loadedAt = keepAt)
        assertTrue("지난달은 30일 예보로", lineWithLiving(forecastWithLiving, past))
        assertTrue(lineWithLiving(forecastWithLiving, null))
    }
}
