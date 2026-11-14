package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.report.data.AssetFlow
import com.yamu.app.feature.report.data.AssetFlowRepository
import com.yamu.app.feature.report.data.DailyBalanceDto
import com.yamu.app.feature.report.data.assetFlowAccounts
import com.yamu.app.feature.report.data.toBalances
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 가짜 소비분석 자산 흐름 — 예보는 가짜 잔고예보 계산(FakeForecastRepository) 그대로라 잔고예보에서 기준 계좌 · 30일을 고른 화면과 숫자가 같다.
 * 지난 잔고는 디버그 메뉴 '소비분석 자산 흐름'에서 고른다. 기본은 지금 BE처럼 없음(지난날 빗금),
 * '있음'이면 BE가 API를 만든 뒤 모습 — 가짜 서버가 오늘부터 [HISTORY_DAYS]일 앞까지만 날마다 끝 잔고를 준다(그보다 앞은 '데이터 없음', 명세 '과거 잔고 부족')
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

    override suspend fun history(accountIds: List<Long>, month: YearMonth): ApiResult<Map<LocalDate, Long>> {
        if (!DevSession.pastBalances || DevSession.faultScenario != FaultScenario.NONE) return ApiResult.Success(emptyMap())
        delay(200)
        val today = LocalDate.now(KST)
        val start = forecasts.assetAccounts().filter { it.accountId in accountIds }.sumOf { it.balance ?: 0L }
        val rows = (1..month.lengthOfMonth()).map { month.atDay(it) }
            .filter { it.isBefore(today) && !it.isBefore(today.minusDays(HISTORY_DAYS)) }
            .map { DailyBalanceDto(it.toString(), pastBalance(start, it, today)) }
        return ApiResult.Success(rows.toBalances())
    }

    /** 지난 끝 잔고 — 날짜로만 정하는 그럴듯한 값(같은 날은 늘 같은 값). 25일 월급 뒤 오르고 그 뒤 조금씩 줄어든다 */
    private fun pastBalance(now: Long, date: LocalDate, today: LocalDate): Long {
        val sinceSalary = (date.dayOfMonth - SALARY_DAY).let { if (it >= 0) it else it + date.lengthOfMonth() }
        val wobble = abs((date.toEpochDay() * 7_919) % 9) * 11_000
        val drift = (today.toEpochDay() - date.toEpochDay()) * 1_500
        return (now + 380_000 - sinceSalary * 21_000 + wobble - drift).coerceAtLeast(12_000)
    }

    private companion object {
        const val HISTORY_DAYS = 45L
        const val SALARY_DAY = 25
    }
}
