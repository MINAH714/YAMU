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
     * 잔고예보 요약 — 연결 계좌 전체 합 · 30일(forecasts/balance, -599). 홈 요약으로 그릴 칸일 때([ForecastSlot.Brief])만 부르고,
     * 아니면 Loading 그대로 두고 화면이 계좌 없음 · 실패를 홈 요약으로 가린다
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
 * 새 홈 칸을 받았거나 그 칸만 실패했으면 요약(숫자는 연결 계좌 전체로 따로 받는다, -599). 옛 서버에서 홈 요약의 잔고 영역만 실패했으면
 * 계좌가 있는지 몰라 실패 · 다시 시도. 잔고 · 예보를 지어내지 않는다
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

/** 홈 '잔고예보 보기'가 여는 F-01 — 연결 계좌 전체 · 30일([HomeViewModel.openForecast]) */
internal val HOME_FORECAST_SELECTION = ForecastSelection(ForecastPeriod.DAYS_30, all = true)

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
     * 혜택은 홈 응답의 새 칸(BE -490)을 쓰고, 옛 서버면 지금까지처럼 reports/monthly를 부른다. 잔고예보 요약은 서버 새 칸이 기준 계좌
     * 하나라 칸을 그릴지만 보고, 숫자는 연결 계좌 전체로 forecasts/balance를 부른다(-599).
     * 그래서 둘은 홈 요약을 받은 뒤에 정한다(마지막으로 받은 요약 기준 — 이번 홈 요청이 실패해도 지난 값으로).
     * 옛 서버의 혜택 달은 부르는 때의 이번 달(한국 기준) — 히어로를 누르면 같은 달 S-04로 간다([openBenefitReport])
     */
    private suspend fun loadAll(onHome: (ApiResult<HomeSummary>) -> Unit) = coroutineScope {
        val pay = async { repository.loadPaySettings() }
        val cards = async { repository.loadCards() }
        val result = repository.loadHome()
        onHome(result)
        val home = _state.value.content
        val benefit = async { benefitOf(home, result) }
        // 카드별 받은 혜택은 새 홈 칸과 같은 달 · 출처(YAMU) — 옛 서버면 부르지 않는다(리포트에 cards가 없다)
        val cardBenefits = (home?.yamuBenefit as? NewHomeArea.Value)?.data?.month?.let { m -> async { m to repository.loadCardBenefits(m) } }
        // 잔고예보 요약 — 서버 새 칸 · 옛 서버 모두 연결 계좌 전체로 다시 부른다. '잔고예보 보기'가 여는 F-01과 같은 숫자(-599)
        val brief = home?.takeIf { forecastSlot(it) == ForecastSlot.Brief }?.let { async { repository.loadForecastBrief() } }
        val b = benefit.await()
        _state.update { it.copy(benefit = it.benefit.next(b)) }
        val p = pay.await()
        _state.update { it.copy(pay = it.pay.next(p)) }
        val c = cards.await()
        _state.update { it.copy(cards = it.cards.next(c)) }
        (c as? ApiResult.Success)?.data?.cards?.let { list -> artLoader.ensure(list.map { it.userCardId }) }
        cardBenefits?.await()?.let { (m, r) -> _state.update { it.copy(cardBenefits = it.cardBenefits.nextFor(m, r)) } }
        brief?.await()?.let { f -> _state.update { it.copy(forecast = it.forecast.next(f)) } }
    }

    /** 혜택 히어로 값 — 새 홈 칸이 있으면 그 값, 그 칸만 실패면 실패, 옛 서버면 reports/monthly. 홈 요약을 아직 한 번도 못 받았으면 그 실패 */
    private suspend fun benefitOf(home: HomeSummary?, result: ApiResult<HomeSummary>): ApiResult<HomeBenefit> = when (val area = home?.yamuBenefit) {
        null -> result as? ApiResult.Failure ?: ApiResult.Failure(ApiError.Unknown(HOME_AREA_FAILED))
        is NewHomeArea.Value -> ApiResult.Success(area.data)
        NewHomeArea.Failed -> ApiResult.Failure(ApiError.Unknown(HOME_AREA_FAILED))
        NewHomeArea.OldServer, NewHomeArea.None -> repository.loadBenefit(YearMonth.now(KST))
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

    /** 잔고예보 요약만 다시(연결 계좌 전체) — 홈 요약으로 그릴 칸인지부터 모르면(홈 요약의 잔고 영역 실패) 홈부터 다시 */
    fun retryForecast() {
        val home = _state.value.content
        if (home == null || forecastSlot(home) != ForecastSlot.Brief) return reload { it.copy(forecast = HomeSection.Loading) }
        viewModelScope.launch {
            _state.update { it.copy(forecast = HomeSection.Loading) }
            val f = repository.loadForecastBrief()
            _state.update { it.copy(forecast = it.forecast.next(f)) }
        }
    }

    /**
     * 잔고예보 요약 '잔고예보 보기'(HOME-15) — F-01을 연결 계좌 전체 · 30일로 연다(S15P31E103-599, 11/17 사용자 결정).
     * 사용자가 잔고예보에서 골라 둔 기간 · 계좌는 이 선택으로 덮어쓴다. 전체는 [ForecastSelection.all]로 둬 그 뒤 새로 연결한 계좌도 들어간다.
     * 11/13 허브 결정 · -548(11/16)까지는 홈 요약과 같은 기준 계좌 하나로 열었다. 홈 카드 숫자도 같은 전체 · 30일로 받아([loadAll]) 두 화면이 같다
     */
    fun openForecast() = forecasts.select(HOME_FORECAST_SELECTION)

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
