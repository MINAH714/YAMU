package com.yamu.app.feature.home

import com.yamu.app.core.security.SessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.map
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.home.data.CardBenefit
import com.yamu.app.feature.home.data.HOME_AREA_FAILED
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeCardsData
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.NewHomeArea
import com.yamu.app.feature.home.data.PaySettings
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.report.data.ReportJump
import com.yamu.app.feature.report.data.ReportMonth
import com.yamu.app.feature.report.data.ReportSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.YearMonth
import javax.inject.Inject

/**
 * 홈 한 칸의 상태(v4 · COMMON-08). 한 칸이 실패해도 다른 칸은 그대로 그린다.
 * 다시 불러오다 실패하면 마지막으로 받은 값([Ready])을 그대로 둔다 — 홈 요약([HomeUiState.content])과 같은 규칙
 */
sealed interface HomeSection<out T> {
    data object Loading : HomeSection<Nothing>
    data class Ready<T>(val data: T) : HomeSection<T>
    data class Failed(val error: ApiError) : HomeSection<Nothing>
}

val <T> HomeSection<T>.dataOrNull: T? get() = (this as? HomeSection.Ready)?.data

/** 새 결과를 받는다 — 실패면 마지막 값을 남긴다 */
internal fun <T> HomeSection<T>.next(result: ApiResult<T>): HomeSection<T> = when (result) {
    is ApiResult.Success -> HomeSection.Ready(result.data)
    is ApiResult.Failure -> if (this is HomeSection.Ready) this else HomeSection.Failed(result.error)
}

/**
 * 홈 상태. [content]는 마지막으로 성공한 값 — 실패해도 지우지 않고 [error]로 위에 한 줄 알린다(07 문서 3-4).
 * [benefit] · [pay]는 v4 혜택 히어로 · 결제 칸(S15P31E103-480)이 따로 받는 값
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val content: HomeSummary? = null,
    val error: ApiError? = null,
    val userName: String? = null,        // 인사 '○○님, 11월 혜택을 모아 봤어요'
    val benefit: HomeSection<HomeBenefit> = HomeSection.Loading,
    val pay: HomeSection<PaySettings> = HomeSection.Loading,
    /** 내 카드 혜택 현황 · 놓치기 아까운 혜택(S15P31E103-481) */
    val cards: HomeSection<HomeCardsData> = HomeSection.Loading,
    /**
     * 잔고예보 요약 — 새 홈 칸(forecastPreview)이 있으면 그 값, 옛 서버면 홈 요약에 기준 계좌가 있을 때만 forecasts/balance로 부른다.
     * 어느 쪽도 아니면 Loading 그대로 두고 화면이 계좌 없음 · 실패를 홈 요약으로 가린다
     */
    val forecast: HomeSection<HomeForecastBrief> = HomeSection.Loading,
    /** 카드별 이번 달 받은 혜택(userCardId → 혜택) — 새 홈 칸(benefitSummary)이 있을 때만 부른다. 없거나 실패면 카드 줄에 붙이지 않는다 */
    val cardBenefits: HomeSection<MonthCardBenefits> = HomeSection.Loading,
)

/** 카드별 받은 혜택 한 달 치 — 받은 달을 같이 들고 있어야 달이 바뀐 뒤 지난달 금액을 이번 달 카드 줄에 붙이지 않는다(셀프 리뷰 P2) */
data class MonthCardBenefits(val month: YearMonth, val byCard: Map<Long, CardBenefit>)

/**
 * [month]의 카드별 혜택 결과를 받는다. 실패하면 같은 달 값만 남기고(COMMON-08), 다른 달 값이면 버리고 실패로 둔다 —
 * 12/1에 12월 호출만 실패했을 때 11월 금액이 12월 히어로 아래 카드 줄에 보이지 않게
 */
internal fun HomeSection<MonthCardBenefits>.nextFor(month: YearMonth, result: ApiResult<Map<Long, CardBenefit>>): HomeSection<MonthCardBenefits> {
    val kept = if (this is HomeSection.Ready && data.month != month) HomeSection.Loading else this
    return kept.next(result.map { MonthCardBenefits(month, it) })
}

/**
 * 카드 줄에 붙일 받은 혜택 — 히어로와 같은 달(새 홈 칸의 달)일 때만. 새 홈 칸이 없거나(옛 서버 · 그 칸만 실패) 달이 다르면 null(줄을 숨긴다)
 */
internal fun HomeUiState.cardBenefitsShown(): Map<Long, CardBenefit>? {
    val month = (content?.yamuBenefit as? NewHomeArea.Value)?.data?.month ?: return null
    return cardBenefits.dataOrNull?.takeIf { it.month == month }?.byCard
}

/** 잔고예보 요약 칸이 그릴 것 */
enum class ForecastSlot { Hidden, NoAccount, HomeFailed, Brief }

/**
 * 잔고예보 요약 칸 상태(HOME-14 · 사용자 상태별 처리). 연결 전은 위 안내가 맡아 숨기고, 기준 계좌가 없으면 계좌 연결 안내.
 * 새 홈 칸을 받았거나 그 칸만 실패했으면 요약(실패면 지금 잔고만 남기고 다시 시도). 옛 서버에서 홈 요약의 잔고 영역만 실패했으면
 * 어느 계좌인지 몰라 실패 · 다시 시도. 잔고 · 예보를 지어내지 않는다
 */
internal fun forecastSlot(home: HomeSummary): ForecastSlot = when {
    home.needsLinking -> ForecastSlot.Hidden
    home.forecastPreview is NewHomeArea.Value || home.forecastPreview == NewHomeArea.Failed -> ForecastSlot.Brief
    home.forecastPreview == NewHomeArea.None -> ForecastSlot.NoAccount
    home.forecast?.accountId != null -> ForecastSlot.Brief
    BALANCE_AREA in home.failedAreas -> ForecastSlot.HomeFailed
    else -> ForecastSlot.NoAccount
}

/** BE partialErrors의 잔고예보 영역 이름 */
private const val BALANCE_AREA = "balanceForecast"

/**
 * 지금 기준 계좌([accountId])의 요약만 남긴다. 기준 계좌를 A에서 B로 바꾼 뒤 B 응답 전이거나 B가 실패하면 A의 칩 · 금액이 그대로 보이면서
 * '잔고예보' 버튼은 B로 열리는 어긋남이 생긴다(셀프 리뷰 P2). 다른 계좌의 값이면 불러오는 중으로 되돌린다 —
 * 그 뒤 실패하면 [next]가 지난 값 없이 Failed로 남긴다. 화면도 그리기 전에 같은 걸 거친다
 */
internal fun HomeSection<HomeForecastBrief>.forAccount(accountId: Long?): HomeSection<HomeForecastBrief> =
    if (this is HomeSection.Ready && data.accountId != accountId) HomeSection.Loading else this

/** 결제 시각(서버 UTC)이 든 한국 기준 달 — 시각이 없거나 못 읽으면 null */
internal fun paymentMonthOf(paidAtIso: String?): YearMonth? =
    paidAtIso?.let { runCatching { YearMonth.from(OffsetDateTime.parse(it).atZoneSameInstant(KST)) }.getOrNull() }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val session: SessionStore,
    private val months: ReportMonth,
    private val reportJump: ReportJump,
    private val forecasts: ForecastRepository,
    link: LinkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** 카드 줄 그림 — 실적 응답엔 상품명이 없어 카드 목록에서 찾는다(실적관리와 같은 방법, -438) */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    private var loadJob: Job? = null

    /** 화면에 들어오거나 돌아올 때마다(결제 · 취소 뒤 반영, QA-03) — 이전 내용은 두고 새 값으로 바꾼다 */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.content == null) }
            val name = session.read().name
            loadAll { home -> _state.update { it.applied(home).copy(userName = name) } }
        }
    }

    /** 당겨서 새로고침 — 금융망 동기화(#76) 뒤 홈 다시 받기 */
    fun refresh() {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val sync = repository.sync()
            loadAll { home ->
                _state.update { current ->
                    val next = current.applied(home)
                    // 홈은 받았지만 동기화가 실패했으면(금융망 지연 등) 위에 한 줄 알림 — 숫자는 마지막 반영 시각 기준
                    if (home is ApiResult.Success && sync is ApiResult.Failure) next.copy(error = sync.error) else next
                }
            }
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * 홈 요약과 결제 설정 · 카드 현황을 같이 부른다. 홈 요약이 먼저 오면 먼저 그린다(가장 늦은 요청을 기다리지 않게).
     * 혜택 · 잔고예보 요약은 홈 응답의 새 칸(BE -490)을 쓰고, 옛 서버면 지금까지처럼 reports/monthly · forecasts/balance를 부른다.
     * 그래서 둘은 홈 요약을 받은 뒤에 정한다(마지막으로 받은 요약 기준 — 이번 홈 요청이 실패해도 지난 값으로).
     * 옛 서버의 혜택 달은 부르는 때의 이번 달(한국 기준) — 히어로를 누르면 같은 달 S-04로 간다([openBenefitReport])
     */
    private suspend fun loadAll(onHome: (ApiResult<HomeSummary>) -> Unit) = coroutineScope {
        val pay = async { repository.loadPaySettings() }
        val cards = async { repository.loadCards() }
        val result = repository.loadHome()
        onHome(result)
        val home = _state.value.content
        // 기준 계좌가 바뀌었으면 지난 계좌의 요약부터 버린다 — 새 계좌가 늦거나 실패해도 지난 계좌 칩 · 금액이 남지 않게(셀프 리뷰 P2)
        _state.update { it.copy(forecast = it.forecast.forAccount(it.content?.baseAccountId)) }
        val benefit = async { benefitOf(home, result) }
        // 카드별 받은 혜택은 새 홈 칸과 같은 달 · 출처(YAMU) — 옛 서버면 부르지 않는다(리포트에 cards가 없다)
        val cardBenefits = (home?.yamuBenefit as? NewHomeArea.Value)?.data?.month?.let { m -> async { m to repository.loadCardBenefits(m) } }
        val label = when (val preview = home?.forecastPreview) {
            is NewHomeArea.Value -> {
                showPreview(preview.data)
                async { preview.data.accountId to repository.loadAccountLabel(preview.data.accountId) }
            }
            NewHomeArea.Failed -> {
                _state.update { it.copy(forecast = it.forecast.next(ApiResult.Failure(ApiError.Unknown(HOME_AREA_FAILED)))) }
                null
            }
            else -> null
        }
        // 옛 서버 — 잔고예보 요약은 홈 요약의 기준 계좌로 forecasts/balance를 다시 부른다(-481 방식)
        val oldBrief = home?.takeIf { it.forecastPreview == NewHomeArea.OldServer && forecastSlot(it) == ForecastSlot.Brief }
            ?.forecast?.accountId?.let { async { repository.loadForecastBrief(it) } }
        val b = benefit.await()
        _state.update { it.copy(benefit = it.benefit.next(b)) }
        val p = pay.await()
        _state.update { it.copy(pay = it.pay.next(p)) }
        val c = cards.await()
        _state.update { it.copy(cards = it.cards.next(c)) }
        (c as? ApiResult.Success)?.data?.cards?.let { list -> artLoader.ensure(list.map { it.userCardId }) }
        cardBenefits?.await()?.let { (m, r) -> _state.update { it.copy(cardBenefits = it.cardBenefits.nextFor(m, r)) } }
        label?.await()?.let { (accountId, name) -> if (name != null) labelPreview(accountId, name) }
        oldBrief?.await()?.let { f -> _state.update { it.copy(forecast = it.forecast.next(f)) } }
    }

    /** 혜택 히어로 값 — 새 홈 칸이 있으면 그 값, 그 칸만 실패면 실패, 옛 서버면 reports/monthly. 홈 요약을 아직 한 번도 못 받았으면 그 실패 */
    private suspend fun benefitOf(home: HomeSummary?, result: ApiResult<HomeSummary>): ApiResult<HomeBenefit> = when (val area = home?.yamuBenefit) {
        null -> result as? ApiResult.Failure ?: ApiResult.Failure(ApiError.Unknown(HOME_AREA_FAILED))
        is NewHomeArea.Value -> ApiResult.Success(area.data)
        NewHomeArea.Failed -> ApiResult.Failure(ApiError.Unknown(HOME_AREA_FAILED))
        NewHomeArea.OldServer, NewHomeArea.None -> repository.loadBenefit(YearMonth.now(KST))
    }

    /** 새 홈 칸 요약을 그린다 — 계좌 칩은 계좌 목록을 받은 뒤 붙이므로 같은 계좌였던 지난 칩 이름을 그동안 남긴다 */
    private fun showPreview(brief: HomeForecastBrief) = _state.update { s ->
        val keep = s.forecast.dataOrNull?.takeIf { it.accountId == brief.accountId }?.accountLabel
        s.copy(forecast = HomeSection.Ready(brief.copy(accountLabel = keep)))
    }

    private fun labelPreview(accountId: Long, name: String) = _state.update { s ->
        val ready = s.forecast as? HomeSection.Ready ?: return@update s
        if (ready.data.accountId != accountId) s else s.copy(forecast = HomeSection.Ready(ready.data.copy(accountLabel = name)))
    }

    /** 혜택 칸만 다시(실패 칸의 '다시 시도') — 새 홈 칸은 홈 응답에 실려 오므로 홈부터 다시, 옛 서버면 reports/monthly만 */
    fun retryBenefit() {
        if (_state.value.content?.yamuBenefit != NewHomeArea.OldServer) return reload { it.copy(benefit = HomeSection.Loading) }
        viewModelScope.launch {
            _state.update { it.copy(benefit = HomeSection.Loading) }
            val b = repository.loadBenefit(YearMonth.now(KST))
            _state.update { it.copy(benefit = it.benefit.next(b)) }
        }
    }

    /** 홈부터 다시 — 실패 칸을 먼저 [loading] 모양으로 바꿔 누른 것이 보이게 */
    private fun reload(loading: (HomeUiState) -> HomeUiState) {
        if (loadJob?.isActive == true) return
        _state.update(loading)
        load()
    }

    /** 카드 현황 칸만 다시 */
    fun retryCards() {
        viewModelScope.launch {
            _state.update { it.copy(cards = HomeSection.Loading) }
            val c = repository.loadCards()
            _state.update { it.copy(cards = it.cards.next(c)) }
            // 처음 불러오기가 실패했으면 카드 그림 목록도 아직 없다 — loadAll처럼 받은 카드로 그림을 찾는다(없으면 대체 그림으로 남음)
            (c as? ApiResult.Success)?.data?.cards?.let { list -> artLoader.ensure(list.map { it.userCardId }) }
        }
    }

    /** 잔고예보 요약만 다시 — 새 홈 칸이거나 기준 계좌를 모르면(홈 요약의 잔고 영역 실패) 홈부터 다시 */
    fun retryForecast() {
        val accountId = _state.value.content?.takeIf { it.forecastPreview == NewHomeArea.OldServer }?.forecast?.accountId
            ?: return reload { it.copy(forecast = HomeSection.Loading) }
        viewModelScope.launch {
            _state.update { it.copy(forecast = HomeSection.Loading) }
            val f = repository.loadForecastBrief(accountId)
            _state.update { it.copy(forecast = it.forecast.next(f)) }
        }
    }

    /**
     * 잔고예보 요약 '잔고예보'(HOME-15) — F-01을 홈 요약과 같은 기준 계좌 · 30일로 연다(FORECAST-03, 11/13 허브 결정).
     * 그래서 사용자가 잔고예보에서 골라 둔 기간 · 계좌를 이 계좌 · 30일로 덮어쓴다. 기준 계좌를 모르면 고르지 않고 열기만 한다.
     * F-01 30일은 오늘을 포함하고 홈 새 칸(forecastPreview)은 내일부터라, 오늘이 가장 낮은 날이면 두 최저액이 다를 수 있다(487~490 명세)
     */
    fun openForecast() {
        val accountId = _state.value.content?.baseAccountId ?: return
        forecasts.select(ForecastSelection(ForecastPeriod.DAYS_30, setOf(accountId)))
    }

    /**
     * 최근 결제 '전체 보기' — 결제 내역(S-05)은 고른 달([ReportMonth])을 여니 최근 결제가 든 달을 먼저 고른다.
     * 홈 최근 결제는 기간 없이 가장 최근 한 건이라 월초엔 지난달 결제일 수 있다. 시각을 모르면 이번 달.
     * 소비분석 · 혜택 리포트와 같은 달을 쓰므로 소비분석 탭이 저장된 채 남아 있으면 그 탭도 이 달로 다시 받는다
     */
    fun openPayments() {
        val month = paymentMonthOf(_state.value.content?.recentPayment?.paidAtIso)
        if (month != null) months.select(month) else months.reset()
    }

    /**
     * 혜택 히어로 → 혜택 리포트(S-04, HOME-06). S-04는 고른 달([ReportMonth])을 열므로 히어로가 보인 달을 먼저 고른다.
     * 히어로를 받기 전이거나 달이 넘어갔으면 이번 달
     */
    fun openBenefitReport() {
        val month = _state.value.benefit.dataOrNull?.month
        if (month != null) months.select(month) else months.reset()
    }

    /** 잔고예보 요약 '자산 흐름 보기'(HOME-15) — 소비분석 탭으로 바꾸기 전에 부른다. 소비분석이 이번 달로 돌아가 자산 흐름 칸까지 내려간다 */
    fun openAssetFlow() = reportJump.request(ReportSection.ASSET_FLOW)

    private fun HomeUiState.applied(result: ApiResult<HomeSummary>): HomeUiState = when (result) {
        is ApiResult.Success -> copy(isLoading = false, content = result.data, error = null)
        is ApiResult.Failure -> copy(isLoading = false, error = result.error)
    }
}
