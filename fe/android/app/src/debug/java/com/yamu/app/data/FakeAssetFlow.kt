package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.AssetFlowScenario
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.feature.forecast.data.ForecastEventDto
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.report.data.AssetFlow
import com.yamu.app.feature.report.data.AssetFlowDto
import com.yamu.app.feature.report.data.AssetFlowForecastDto
import com.yamu.app.feature.report.data.AssetFlowPartialErrorDto
import com.yamu.app.feature.report.data.AssetFlowPointDto
import com.yamu.app.feature.report.data.AssetFlowRepository
import com.yamu.app.feature.report.data.MonthFlowResult
import com.yamu.app.feature.report.data.assetFlowAccounts
import com.yamu.app.feature.report.data.monthFlowResult
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 가짜 소비분석 자산 흐름 — 30일 예보는 가짜 잔고예보 계산(FakeForecastRepository) 그대로라 잔고예보에서 기준 계좌 · 30일을 고른 화면과 숫자가 같다.
 * 한 달 응답(asset-flows)은 디버그 메뉴 '소비분석 자산 흐름'에서 고른다(S15P31E103-492):
 * 새 서버는 지난 잔고를 오늘부터 [HISTORY_DAYS]일 앞까지만 주고 그 앞은 자료 없음(HISTORY_INCOMPLETE), 은행 조회 실패는 지난날이 모두 ERROR,
 * 옛 서버는 404라 앱이 예보만으로 그린다. BE asset-flows처럼 오늘은 지금 잔고(CURRENT), 내일부터 30일은 예보, 그 뒤는 자료 없음
 */
@Singleton
class FakeAssetFlowRepository @Inject constructor(private val forecasts: FakeForecastRepository) : AssetFlowRepository {

    override suspend fun load(): ApiResult<AssetFlow> = withFault(empty = { ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT)) }) {
        delay(400)
        val all = forecasts.assetAccounts()
        if (all.isEmpty()) return@withFault ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT))
        val accounts = assetFlowAccounts(all)
        ApiResult.Success(AssetFlow(accounts, forecasts.assetForecast(accounts.map { it.accountId }.toSet()), loadedAt = ZonedDateTime.now(KST)))
    }

    override suspend fun month(month: YearMonth): MonthFlowResult {
        val scenario = DevSession.assetFlowScenario
        when (DevSession.faultScenario) {
            FaultScenario.NETWORK_ERROR -> { delay(300); return monthFlowResult(ApiResult.Failure(ApiError.Network), month) }
            FaultScenario.BANK_ERROR -> { delay(300); return monthFlowResult(ApiResult.Failure(ApiError.from(502, "FIN_API_UNAVAILABLE")), month) }
            FaultScenario.SLOW -> delay(3_000)
            else -> Unit
        }
        // 옛 서버 — 실제 서버처럼 404 API_NOT_FOUND를 만들어 실제 저장소와 같은 매핑([monthFlowResult])을 거친다
        if (scenario == AssetFlowScenario.OLD_SERVER) { delay(150); return monthFlowResult(ApiResult.Failure(ApiError.from(404, "API_NOT_FOUND")), month) }
        delay(250)
        val all = forecasts.assetAccounts()
        val accounts = all.filter { it.isPrimary }.take(1)   // BE: accountIds를 빼면 기준 계좌 하나, 없으면 계좌 없이 모두 NO_DATA
        val ids = accounts.map { it.accountId }
        val today = LocalDate.now(KST)
        val forecast = ids.takeIf { it.isNotEmpty() }?.let { forecasts.assetForecast(it.toSet()) }
        val byDate = forecast?.days?.associateBy { it.date }.orEmpty()
        val forecastTo = today.plusDays(FORECAST_DAYS)
        val start = accounts.sumOf { it.balance ?: 0L }
        val past = mutableListOf<LocalDate>()
        val series = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            when {
                ids.isEmpty() -> point(date, if (date.isBefore(today)) "ACTUAL" else if (date == today) "CURRENT" else "FORECAST", "NO_DATA")
                date.isBefore(today) -> {
                    val reachable = !date.isBefore(today.minusDays(HISTORY_DAYS))
                    when {
                        scenario == AssetFlowScenario.HISTORY_ERROR -> point(date, "ACTUAL", "ERROR").also { past += date }
                        reachable -> pastBalance(start, date, today).let { v -> point(date, "ACTUAL", "AVAILABLE", display = v, actual = v) }
                        else -> point(date, "ACTUAL", "NO_DATA").also { past += date }
                    }
                }
                date == today -> point(date, "CURRENT", "AVAILABLE", display = forecast?.currentBalance, actual = forecast?.currentBalance)
                !date.isAfter(forecastTo) -> byDate[date]?.let { day ->
                    point(
                        date, "FORECAST", "AVAILABLE",
                        display = day.conservative ?: day.expected,
                        forecast = AssetFlowForecastDto(day.confirmed, day.expected, day.conservative),
                        events = day.events.map { ForecastEventDto(it.type.name, it.title, it.amount, it.confidence.name) },
                    )
                } ?: point(date, "FORECAST", "NO_DATA")
                else -> point(date, "FORECAST", "NO_DATA")
            }
        }
        val reason = if (scenario == AssetFlowScenario.HISTORY_ERROR) "FIN_API_UNAVAILABLE" else "HISTORY_INCOMPLETE"
        val dto = AssetFlowDto(
            yearMonth = month.toString(),
            accountIds = ids,
            currentTotalBalance = forecast?.currentBalance,
            balanceAsOf = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            series = series,
            forecastFrom = today.plusDays(1).toString().takeIf { month.atEndOfMonth().isAfter(today) && ids.isNotEmpty() },
            forecastTo = forecastTo.toString().takeIf { month.atEndOfMonth().isAfter(today) && ids.isNotEmpty() },
            partialErrors = if (past.isEmpty() || ids.isEmpty()) emptyList() else listOf(AssetFlowPartialErrorDto(ids.first(), past.min().toString(), past.max().toString(), reason)),
            calculatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        return monthFlowResult(ApiResult.Success(dto), month)
    }

    private fun point(
        date: LocalDate, kind: String, status: String,
        display: Long? = null, actual: Long? = null, forecast: AssetFlowForecastDto? = null, events: List<ForecastEventDto> = emptyList(),
    ) = AssetFlowPointDto(date.toString(), kind, status, display.takeIf { status == "AVAILABLE" }, actual.takeIf { status == "AVAILABLE" }, forecast, events)

    /** 지난 끝 잔고 — 날짜로만 정하는 그럴듯한 값(같은 날은 늘 같은 값). 25일 월급 뒤 오르고 그 뒤 조금씩 줄어든다 */
    private fun pastBalance(now: Long, date: LocalDate, today: LocalDate): Long {
        val sinceSalary = (date.dayOfMonth - SALARY_DAY).let { if (it >= 0) it else it + date.lengthOfMonth() }
        val wobble = abs((date.toEpochDay() * 7_919) % 9) * 11_000
        val drift = (today.toEpochDay() - date.toEpochDay()) * 1_500
        return (now + 380_000 - sinceSalary * 21_000 + wobble - drift).coerceAtLeast(12_000)
    }

    private companion object {
        const val HISTORY_DAYS = 45L
        const val FORECAST_DAYS = 30L
        const val SALARY_DAY = 25
    }
}
