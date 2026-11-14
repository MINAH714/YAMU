package com.yamu.app.feature.report.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastApi
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.primaryOrAll
import com.yamu.app.feature.forecast.data.shortName
import com.yamu.app.feature.forecast.data.toForecast
import com.yamu.app.feature.forecast.data.toOption
import com.yamu.app.feature.forecast.monthDayKinds
import com.yamu.app.feature.forecast.rideValue
import com.yamu.app.feature.link.data.LinkApi
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

// ───────────────── 소비분석 자산 흐름(v4 ANALYSIS-01~15, S15P31E103-483) ─────────────────

/**
 * 지난날 잔고 한 줄 — BE에 아직 없다. 금융망 거래내역에 거래 뒤 잔액이 있지만 그걸 쓰는 API가 없고,
 * 명세가 지출액으로 잔고를 만들지 말라고 해서(COMMON-03) 앱은 받은 날만 실선으로 그린다. API가 생기면 이 모양으로 받는다(날짜 · 그날 끝 잔고)
 */
@Serializable data class DailyBalanceDto(val date: String, val balance: Long? = null)

/** 받은 지난 잔고 → 날짜별 값(값이 없는 줄은 버린다 — '데이터 없음'으로 남는다) */
fun List<DailyBalanceDto>.toBalances(): Map<LocalDate, Long> =
    mapNotNull { d -> parseDate(d.date)?.let { date -> d.balance?.let { date to it } } }.toMap()

/** 예보 기간 — 자산 흐름은 오늘부터 30일을 한 번 받아 7 · 14 · 30일 뒤를 고른다(BE는 7 · 14 · 30만 받고 시작일을 못 정한다) */
val ASSET_FLOW_PERIOD = ForecastPeriod.DAYS_30

/** 7 · 14 · 30일 뒤 예보 — 보는 달과 상관없이 오늘부터 센다(ANALYSIS-12) */
val ASSET_FLOW_AFTER = listOf(7, 14, 30)

/**
 * 자산 흐름이 보는 계좌 — 기준 계좌 하나(11/13 결정). 잔고예보를 고르지 않고 열었을 때와 같은 규칙([primaryOrAll])이라
 * 홈 요약 · 잔고예보와 숫자가 맞는다(FORECAST-03 · COMMON-04). 기준 계좌가 없는 옛 자료면 연결 계좌 전체
 */
fun assetFlowAccounts(accounts: List<ForecastAccountOption>): List<ForecastAccountOption> = accounts.primaryOrAll()

/** 계좌 이름표 — 하나면 'KB국민 ··1234'(잔고예보 계좌 칩과 같은 [shortName]), 여럿이면 '계좌 2개 전체' */
fun accountTag(accounts: List<ForecastAccountOption>): String = when (accounts.size) {
    0 -> "계좌 없음"
    1 -> accounts[0].shortName()
    else -> "계좌 ${accounts.size}개 전체"
}

/**
 * 자산 흐름 칸이 쓰는 값. 계좌 목록은 받았는데 예보만 실패하면 [forecast]가 null이고 [forecastError]가 있다 —
 * 지금 잔고는 그대로 보이고 예보 자리에만 다시 시도를 둔다(명세 '예측 실패', COMMON-08)
 */
data class AssetFlow(
    val accounts: List<ForecastAccountOption>,
    val forecast: Forecast?,
    val forecastError: ApiError? = null,
    /** 잔고를 읽은 때(한국 시각) — '오늘 14:05 기준' */
    val loadedAt: ZonedDateTime,
) {
    /** 지금 잔고 — 예보 응답의 지금 잔액, 없으면 계좌 목록 잔액 합(하나라도 모르면 모름) */
    val currentBalance: Long?
        get() = forecast?.currentBalance ?: accounts.map { it.balance }.takeIf { b -> b.isNotEmpty() && b.all { it != null } }?.sumOf { it ?: 0L }

    /** 예보 마지막 날 — 그 뒤는 '예보 범위 밖' */
    val forecastEnd: LocalDate? get() = forecast?.days?.lastOrNull()?.date
}

/**
 * 예보만 실패한 새로고침 — 같은 계좌의 지난 예보가 있으면 그 값(그 예보를 받은 때 기준)을 남기고 이번 실패만 [AssetFlow.forecastError]에 둔다.
 * 지난 예보가 없거나 계좌가 바뀌었으면 새 응답 그대로(7 · 14 · 30일 칸과 앞날이 빈다). 돌려준 값이 [next]가 아니면 마지막 값을 보인 것
 */
fun keepLastForecast(prev: AssetFlow?, next: AssetFlow): AssetFlow {
    if (next.forecast != null || prev?.forecast == null) return next
    if (prev.accounts.map { it.accountId } != next.accounts.map { it.accountId }) return next
    return prev.copy(forecastError = next.forecastError)
}

/** 소비분석에서 다음 달을 열 수 있나 — 예보가 다음 달 1일에 닿을 때만('다음 달은 예보가 닿는 날까지'). 예보가 없거나 말일에서 끝나면 닫는다 */
fun reachesNextMonth(flow: AssetFlow?, today: LocalDate): Boolean {
    val end = flow?.forecastEnd ?: return false
    return !end.isBefore(YearMonth.from(today).plusMonths(1).atDay(1))
}

interface AssetFlowRepository {
    /** 기준 계좌(없으면 전체)와 그 계좌의 오늘부터 30일 예보. 계좌가 없으면 [ForecastRepository.NO_ACCOUNT] 실패 */
    suspend fun load(): ApiResult<AssetFlow>

    /** 그달 지난 잔고 — 지금 BE에는 없어 빈 값이다(API가 생기면 여기서 받는다) */
    suspend fun history(accountIds: List<Long>, month: YearMonth): ApiResult<Map<LocalDate, Long>>
}

/**
 * 잔고예보와 같은 API를 쓰지만 잔고예보 저장소의 고른 기간 · 계좌와 마지막 값은 건드리지 않는다 —
 * 소비분석을 연 뒤 잔고예보에 들어가도 그 화면은 자기 선택대로 연다
 */
@Singleton
class AssetFlowRepositoryImpl @Inject constructor(
    private val forecastApi: ForecastApi,
    private val linkApi: LinkApi,
) : AssetFlowRepository {
    override suspend fun load(): ApiResult<AssetFlow> {
        val all = when (val a = apiCall { linkApi.accounts(null) }.map { r -> r.accounts.map { it.toOption() } }) {
            is ApiResult.Failure -> return a
            is ApiResult.Success -> a.data
        }
        if (all.isEmpty()) return ApiResult.Failure(ApiError.InvalidRequest(ForecastRepository.NO_ACCOUNT))
        val accounts = assetFlowAccounts(all)
        val r = apiCall { forecastApi.forecast(accounts.map { it.accountId }, ASSET_FLOW_PERIOD.until, ASSET_FLOW_PERIOD.days) }.map { it.toForecast() }
        val now = ZonedDateTime.now(KST)
        return ApiResult.Success(
            when (r) {
                is ApiResult.Success -> AssetFlow(accounts, r.data, loadedAt = now)
                is ApiResult.Failure -> AssetFlow(accounts, null, r.error, loadedAt = now)
            },
        )
    }

    override suspend fun history(accountIds: List<Long>, month: YearMonth): ApiResult<Map<LocalDate, Long>> = ApiResult.Success(emptyMap())
}

// ── 한 달 날 계산 ──

/**
 * 한 달 그래프 · 날짜 띠 · 날짜 칸이 같이 보는 하루. [value]는 그래프 점(실제는 그날 끝 잔고, 오늘 · 예상은 예보의 그날 끝 잔고,
 * 'AI 생활비 반영' 선, 없으면 '제외' 선 — 잔고예보 손잡이와 같은 선). [events]는 예보의 그날 입출금(0원 제외)
 */
data class AssetDay(val date: LocalDate, val kind: DayKind, val value: Long?, val events: List<ForecastEvent>)

/** 그달 하루하루 — 지난날은 받은 지난 잔고([history])만, 오늘과 앞날은 예보만 쓴다. 지어 낸 값은 없다 */
fun assetDays(month: YearMonth, today: LocalDate, flow: AssetFlow?, history: Map<LocalDate, Long>): List<AssetDay> {
    val f = flow?.forecast
    val byDate = f?.days?.associateBy { it.date }.orEmpty()
    val kinds = monthDayKinds(month, today, history.keys, flow?.forecastEnd)
    return kinds.mapIndexed { i, kind ->
        val date = month.atDay(i + 1)
        val day = byDate[date]
        val value = when (kind) {
            DayKind.ACTUAL -> history[date]
            DayKind.TODAY, DayKind.EXPECTED -> day?.let { rideValue(it, f?.hasConservative == true) }
            DayKind.NO_DATA, DayKind.OUT_OF_RANGE -> null
        }
        AssetDay(date, kind, value, day?.events?.filter { it.amount != 0L }.orEmpty().takeIf { kind == DayKind.TODAY || kind == DayKind.EXPECTED }.orEmpty())
    }
}

/**
 * 달을 바꿨을 때 처음 고를 날(명세 8장 제안) — 이번 달은 오늘, 지난달은 마지막 날, 다음 달은 1일.
 * 날짜 띠를 끝까지 밀어도 달은 넘어가지 않는다(띠 끝의 '○월 보기'로만)
 */
fun defaultAssetDate(month: YearMonth, today: LocalDate): LocalDate = when {
    month == YearMonth.from(today) -> today
    month < YearMonth.from(today) -> month.atEndOfMonth()
    else -> month.atDay(1)
}

/** 고른 날이 그달 안이면 그대로, 아니면 그달 처음 고를 날 */
fun assetDateIn(month: YearMonth, today: LocalDate, picked: LocalDate?): LocalDate =
    picked?.takeIf { YearMonth.from(it) == month } ?: defaultAssetDate(month, today)

/** 오늘부터 N일 뒤 예상 잔고('AI 생활비 반영' 선, 없으면 '제외') — 예보에 그날이 없으면 null */
fun forecastAfter(f: Forecast, today: LocalDate, days: Int): Pair<LocalDate, Long?> {
    val date = today.plusDays(days.toLong())
    return date to f.days.firstOrNull { it.date == date }?.let { rideValue(it, f.hasConservative) }
}

/** 예보 범위 밖 큰 출금 중 그달에 빠지는 것(카드 대금 · 사용 추정, BE beyondHorizon) — 그래프 밑에 한 줄씩(FORECAST-06) */
fun beyondInMonth(f: Forecast?, month: YearMonth): List<ForecastEvent> =
    f?.beyondHorizon.orEmpty().filter { YearMonth.from(it.date) == month && it.amount < 0 }
