package com.yamu.app.feature.report.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import com.yamu.app.feature.forecast.DayKind
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.FlowType
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastApi
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastEventDto
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.enumOf
import com.yamu.app.feature.forecast.data.primaryOrAll
import com.yamu.app.feature.forecast.data.shortName
import com.yamu.app.feature.forecast.data.toForecast
import com.yamu.app.feature.forecast.data.toOption
import com.yamu.app.feature.forecast.monthDayKinds
import com.yamu.app.feature.forecast.rideValue
import com.yamu.app.feature.link.data.LinkApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

// ───────────────── 소비분석 자산 흐름(v4 ANALYSIS-01~15, S15P31E103-483 · -492) ─────────────────

// ── 서버 응답 — GET /api/asset-flows(BE !315, S15P31E103-488) ──
// 종류 · 상태는 문자열로 받는다. BE가 새 값을 더해도 읽기는 깨지지 않고, 모르는 값은 '표시 불가'로 둔다

/** 월별 자산 흐름 API — yearMonth는 이번 달 11개월 전~다음 달. accountIds를 빼면 기준 계좌 하나(늘 뺀다) */
interface AssetFlowApi {
    @GET("asset-flows")
    suspend fun assetFlows(
        @Query("yearMonth") yearMonth: String,
        @Query("accountIds") accountIds: List<Long>? = null,
    ): Response<ApiEnvelope<AssetFlowDto>>
}

@Serializable data class AssetFlowDto(
    val yearMonth: String? = null,
    val accountIds: List<Long> = emptyList(),
    val currentTotalBalance: Long? = null,
    val balanceAsOf: String? = null,
    val series: List<AssetFlowPointDto> = emptyList(),
    val forecastFrom: String? = null,
    val forecastTo: String? = null,
    val partialErrors: List<AssetFlowPartialErrorDto> = emptyList(),
    val calculatedAt: String? = null,
)

/** 하루 — kind ACTUAL(어제까지 일말 실제) · CURRENT(오늘 지금) · FORECAST(내일부터 예상), dataStatus AVAILABLE · NO_DATA · ERROR */
@Serializable data class AssetFlowPointDto(
    val date: String,
    val kind: String? = null,
    val dataStatus: String? = null,
    val displayBalance: Long? = null,
    val actualBalance: Long? = null,
    /** 예보 세 선. confirmed도 앞날 계산값이라 실제 선으로 그리지 않는다(명세) — 앱은 displayBalance만 그린다 */
    val forecast: AssetFlowForecastDto? = null,
    val events: List<ForecastEventDto> = emptyList(),
)

@Serializable data class AssetFlowForecastDto(val confirmed: Long? = null, val expected: Long? = null, val conservative: Long? = null)

/** 지난 잔고를 확인하지 못한 계좌 · 기간 · 사유 */
@Serializable data class AssetFlowPartialErrorDto(val accountId: Long? = null, val from: String? = null, val to: String? = null, val reason: String? = null)

// ── 앱 안에서 쓰는 값 ──

/** 날짜 하나의 점 — [value]는 dataStatus가 AVAILABLE일 때만(0원 · 음수도 그대로), 아니면 null */
data class FlowPoint(val date: LocalDate, val kind: DayKind, val value: Long?, val events: List<ForecastEvent>)

/** 지난 잔고를 확인하지 못한 구간 — [reason]은 서버 값 그대로 */
data class HistoryGap(val accountId: Long?, val from: LocalDate?, val to: LocalDate?, val reason: String?)

/** 한 달 자산 흐름(asset-flows 응답). [points]는 날짜순, 서버가 그달 모든 날짜를 준다 */
data class MonthFlow(
    val month: YearMonth,
    val accountIds: List<Long>,
    val currentBalance: Long?,
    val balanceAsOf: ZonedDateTime?,
    val points: List<FlowPoint>,
    val forecastFrom: LocalDate?,
    val forecastTo: LocalDate?,
    val gaps: List<HistoryGap>,
)

/**
 * 서버 날 → 앱 날 종류. 모르는 kind · dataStatus는 [DayKind.UNKNOWN](표시 불가).
 * CURRENT는 값이 없어도 오늘 기준선을 그려야 해서 늘 오늘이고, FORECAST의 NO_DATA는 예보 마지막 날 뒤면 범위 밖이다
 */
internal fun pointKind(kind: String?, status: String?, date: LocalDate, forecastTo: LocalDate?): DayKind {
    val k = kind?.trim()?.uppercase()
    if (k !in KNOWN_KINDS) return DayKind.UNKNOWN
    if (k == "CURRENT") return DayKind.TODAY
    return when (status?.trim()?.uppercase()) {
        "AVAILABLE" -> if (k == "ACTUAL") DayKind.ACTUAL else DayKind.EXPECTED
        "NO_DATA" -> if (k == "FORECAST" && forecastTo != null && date.isAfter(forecastTo)) DayKind.OUT_OF_RANGE else DayKind.NO_DATA
        "ERROR" -> DayKind.ERROR
        else -> DayKind.UNKNOWN
    }
}

private val KNOWN_KINDS = setOf("ACTUAL", "CURRENT", "FORECAST")

/** UTC 시각 → 한국 시각. 못 읽으면 null */
internal fun parseInstantKst(iso: String?): ZonedDateTime? =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST) }.getOrNull() }

fun AssetFlowDto.toMonthFlow(requested: YearMonth): MonthFlow {
    val to = parseDate(forecastTo)
    val points = series.mapNotNull { p ->
        val date = parseDate(p.date) ?: return@mapNotNull null
        val kind = pointKind(p.kind, p.dataStatus, date, to)
        val available = p.dataStatus.equals("AVAILABLE", ignoreCase = true)
        FlowPoint(
            date = date,
            kind = kind,
            value = p.displayBalance.takeIf { available && kind != DayKind.UNKNOWN },
            events = p.events.filter { it.amount != 0L }.map { e ->
                ForecastEvent(date, enumOf(e.type, FlowType.UNKNOWN), e.title?.takeIf { it.isNotBlank() } ?: "예정된 돈", e.amount, enumOf(e.confidence, FlowConfidence.UNKNOWN))
            },
        )
    }.sortedBy { it.date }
    return MonthFlow(
        month = yearMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: requested,
        accountIds = accountIds,
        currentBalance = currentTotalBalance,
        balanceAsOf = parseInstantKst(balanceAsOf),
        points = points,
        forecastFrom = parseDate(forecastFrom),
        forecastTo = to,
        gaps = partialErrors.map { HistoryGap(it.accountId, parseDate(it.from), parseDate(it.to), it.reason) },
    )
}

/** 지난 잔고를 확인하지 못한 까닭 — 한 줄 안내(BE asset-flows partialErrors.reason) */
fun gapReason(reason: String?): String = when (reason?.uppercase()) {
    "FIN_API_UNAVAILABLE", "FIN_API_ERROR" -> "은행 연결이 안 돼 지난 잔고를 불러오지 못했어요"
    "UPSTREAM_TIMEOUT" -> "은행 응답이 늦어 지난 잔고를 불러오지 못했어요"
    "HISTORY_INCOMPLETE" -> "거래 내역이 모두 오지 않아 지난 잔고를 확인하지 못했어요"
    "FILTERED_HISTORY" -> "입출금 전체가 아닌 내역이라 지난 잔고를 확인하지 못했어요"
    "NO_ANCHOR" -> "기준이 될 지난 잔고가 없어 확인하지 못했어요"
    "AMBIGUOUS_ORDER" -> "같은 시각 거래의 순서를 알 수 없어 지난 잔고를 확인하지 못했어요"
    "BALANCE_MISMATCH" -> "거래 금액과 잔액이 맞지 않아 지난 잔고를 확인하지 못했어요"
    else -> "지난 잔고를 확인하지 못했어요"
}

/** 조회 실패(은행 · 시간 초과)인지 — 다시 시도하면 될 수 있다. 나머지 사유는 자료 문제라 다시 해도 같다 */
fun HistoryGap.isFetchFailure(): Boolean = reason?.uppercase() in setOf("FIN_API_UNAVAILABLE", "FIN_API_ERROR", "UPSTREAM_TIMEOUT")

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
 * 자산 흐름 칸의 계좌 · 30일 예보. 계좌 목록은 받았는데 예보만 실패하면 [forecast]가 null이고 [forecastError]가 있다 —
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

    /** 이 계좌들을 본 한 달 응답인지 — 다르면(기준 계좌가 바뀐 사이 등) 섞지 않고 예보만으로 그린다(COMMON-04) */
    fun matches(month: MonthFlow): Boolean = month.accountIds.toSet() == accounts.map { it.accountId }.toSet()
}

/** 한 달 자산 흐름을 받은 결과 — 옛 서버는 asset-flows가 없어 404라 [Unsupported]로 지금 동작(예보 + 빈 과거)을 쓴다 */
sealed interface MonthFlowResult {
    data class Loaded(val flow: MonthFlow) : MonthFlowResult
    data object Unsupported : MonthFlowResult
    data class Failed(val error: ApiError) : MonthFlowResult
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
    /** 기준 계좌(없으면 전체)와 그 계좌의 오늘부터 30일 예보(7 · 14 · 30일 뒤용). 계좌가 없으면 [ForecastRepository.NO_ACCOUNT] 실패 */
    suspend fun load(): ApiResult<AssetFlow>

    /** 그달 자산 흐름(지난 실제 잔고 · 오늘 · 예상) — 기준 계좌. 서버에 API가 없으면 [MonthFlowResult.Unsupported] */
    suspend fun month(month: YearMonth): MonthFlowResult
}

/**
 * 잔고예보와 같은 API를 쓰지만 잔고예보 저장소의 고른 기간 · 계좌와 마지막 값은 건드리지 않는다 —
 * 소비분석을 연 뒤 잔고예보에 들어가도 그 화면은 자기 선택대로 연다
 */
@Singleton
class AssetFlowRepositoryImpl @Inject constructor(
    private val forecastApi: ForecastApi,
    private val linkApi: LinkApi,
    private val assetFlowApi: AssetFlowApi,
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

    override suspend fun month(month: YearMonth): MonthFlowResult = monthFlowResult(apiCall { assetFlowApi.assetFlows(month.toString()) }, month)
}

/**
 * asset-flows 응답을 화면 결과로(가짜 저장소도 이 함수를 거친다). 이 경로가 없는 옛 서버는 [MonthFlowResult.Unsupported]로
 * 오류 없이 예보만 그린다. 새 서버가 준 데이터 404(RESOURCE_NOT_FOUND — 그사이 기준 계좌 해제 등)는 옛 서버로 묻지 않고 실패로 알린다
 */
fun monthFlowResult(r: ApiResult<AssetFlowDto>, month: YearMonth): MonthFlowResult = when (r) {
    is ApiResult.Success -> MonthFlowResult.Loaded(r.data.toMonthFlow(month))
    is ApiResult.Failure -> if (isMissingApi(r.error)) MonthFlowResult.Unsupported else MonthFlowResult.Failed(r.error)
}

/**
 * 서버에 이 API가 없다 — BE가 매핑 없는 경로에 주는 404 API_NOT_FOUND(앱은 [ApiError.NotSupported], 실적 · 분석 화면과 같은 판단)와
 * 프록시가 JSON 없이 준 404(코드가 빈 [ApiError.NotFound]). 코드가 있는 404는 데이터 문제라 여기 들지 않는다
 */
internal fun isMissingApi(error: ApiError): Boolean = error is ApiError.NotSupported || (error is ApiError.NotFound && error.code.isBlank())

@Module
@InstallIn(SingletonComponent::class)
object AssetFlowApiModule {
    @Provides
    @Singleton
    fun assetFlowApi(retrofit: Retrofit): AssetFlowApi = retrofit.create(AssetFlowApi::class.java)
}

// ── 한 달 날 계산 ──

/**
 * 한 달 그래프 · 날짜 띠 · 날짜 칸이 같이 보는 하루. [value]는 그래프 점(실제는 그날 끝 잔고, 오늘은 지금 잔고 또는 오늘 끝 예상,
 * 예상은 'AI 생활비 반영' 선, 없으면 '제외' 선 — 잔고예보 손잡이와 같은 선). [events]는 예보의 그날 입출금(0원 제외).
 * [endOfDay]는 오늘 칸의 '오늘 끝 예상'(30일 예보의 오늘 값)
 */
data class AssetDay(val date: LocalDate, val kind: DayKind, val value: Long?, val events: List<ForecastEvent>, val endOfDay: Long? = null)

/**
 * 그달 하루하루. 한 달 응답([month], asset-flows)이 있고 같은 계좌면 그 점을 그대로 쓴다 — 지난 실제 잔고 · 오늘 지금 잔고 · 앞날 예상.
 * 없으면(옛 서버 · 실패) 지난날은 빈 날, 오늘과 앞날은 30일 예보로 그린다(-483 동작). 지어 낸 값은 없다
 */
fun assetDays(month: YearMonth, today: LocalDate, flow: AssetFlow?, monthFlow: MonthFlow?): List<AssetDay> {
    val f = flow?.forecast
    val byDate = f?.days?.associateBy { it.date }.orEmpty()
    val endOfToday = byDate[today]?.let { rideValue(it, f?.hasConservative == true) }
    val todayEvents = byDate[today]?.events?.filter { it.amount != 0L }.orEmpty()
    val usable = monthFlow?.takeIf { it.month == month && (flow == null || flow.matches(it)) }
    if (usable != null) {
        val points = usable.points.associateBy { it.date }
        return (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val p = points[date]
            when {
                p == null -> AssetDay(date, DayKind.NO_DATA, null, emptyList())   // 서버는 모든 날을 주지만 빠지면 빈 날로
                p.kind == DayKind.TODAY -> AssetDay(date, DayKind.TODAY, p.value, todayEvents, endOfDay = endOfToday)
                else -> AssetDay(date, p.kind, p.value, p.events.takeIf { p.kind == DayKind.EXPECTED }.orEmpty())
            }
        }
    }
    val kinds = monthDayKinds(month, today, emptySet(), flow?.forecastEnd)
    return kinds.mapIndexed { i, kind ->
        val date = month.atDay(i + 1)
        val day = byDate[date]
        val value = when (kind) {
            DayKind.TODAY, DayKind.EXPECTED -> day?.let { rideValue(it, f?.hasConservative == true) }
            else -> null
        }
        AssetDay(
            date, kind, value,
            day?.events?.filter { it.amount != 0L }.orEmpty().takeIf { kind == DayKind.TODAY || kind == DayKind.EXPECTED }.orEmpty(),
            endOfDay = endOfToday.takeIf { kind == DayKind.TODAY },
        )
    }
}

/** 칸 머리의 지금 잔고와 그 기준 시각 — 한 달 응답이 같은 계좌면 그 값(은행에서 읽은 시각), 아니면 30일 예보를 받은 때 */
fun currentBalanceOf(flow: AssetFlow, monthFlow: MonthFlow?): Pair<Long?, ZonedDateTime> {
    val m = monthFlow?.takeIf { flow.matches(it) && it.currentBalance != null }
    return if (m != null) m.currentBalance to (m.balanceAsOf ?: flow.loadedAt) else flow.currentBalance to flow.loadedAt
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
