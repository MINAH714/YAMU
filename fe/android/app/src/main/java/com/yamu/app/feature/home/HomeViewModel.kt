package com.yamu.app.feature.home

import com.yamu.app.core.security.SessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeCardsData
import com.yamu.app.feature.home.data.HomeForecastBrief
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
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
    /** 잔고예보 요약 — 홈 요약에 기준 계좌가 있을 때만 부른다. 없으면 Loading 그대로 두고 화면이 계좌 없음 · 실패를 홈 요약으로 가린다 */
    val forecast: HomeSection<HomeForecastBrief> = HomeSection.Loading,
)

/** 잔고예보 요약 칸이 그릴 것 */
enum class ForecastSlot { Hidden, NoAccount, HomeFailed, Brief }

/**
 * 잔고예보 요약 칸 상태(HOME-14 · 사용자 상태별 처리). 연결 전은 위 안내가 맡아 숨기고, 기준 계좌가 없으면 계좌 연결 안내,
 * 홈 요약의 잔고 영역만 실패했으면 어느 계좌인지 몰라 실패 · 다시 시도. 잔고 · 예보를 지어내지 않는다
 */
internal fun forecastSlot(home: HomeSummary): ForecastSlot = when {
    home.needsLinking -> ForecastSlot.Hidden
    home.forecast?.accountId != null -> ForecastSlot.Brief
    BALANCE_AREA in home.failedAreas -> ForecastSlot.HomeFailed
    else -> ForecastSlot.NoAccount
}

/** BE partialErrors의 잔고예보 영역 이름 */
private const val BALANCE_AREA = "balanceForecast"

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
     * 홈 요약과 혜택 · 결제 설정을 같이 부른다. 홈 요약이 먼저 오면 먼저 그린다(가장 늦은 요청을 기다리지 않게).
     * 혜택 달은 부르는 때의 이번 달(한국 기준) — 히어로를 누르면 같은 달 S-04로 간다([openBenefitReport])
     */
    private suspend fun loadAll(onHome: (ApiResult<HomeSummary>) -> Unit) = coroutineScope {
        val month = YearMonth.now(KST)
        val benefit = async { repository.loadBenefit(month) }
        val pay = async { repository.loadPaySettings() }
        val cards = async { repository.loadCards() }
        onHome(repository.loadHome())
        // 잔고예보 요약은 홈 요약의 기준 계좌로 — 홈 요약을 받은 뒤에 부른다(마지막으로 받은 요약의 계좌)
        val accountId = _state.value.content?.let { home -> home.forecast?.accountId?.takeIf { forecastSlot(home) == ForecastSlot.Brief } }
        val forecast = accountId?.let { async { repository.loadForecastBrief(it) } }
        val b = benefit.await()
        _state.update { it.copy(benefit = it.benefit.next(b)) }
        val p = pay.await()
        _state.update { it.copy(pay = it.pay.next(p)) }
        val c = cards.await()
        _state.update { it.copy(cards = it.cards.next(c)) }
        (c as? ApiResult.Success)?.data?.cards?.let { list -> artLoader.ensure(list.map { it.userCardId }) }
        forecast?.await()?.let { f -> _state.update { it.copy(forecast = it.forecast.next(f)) } }
    }

    /** 혜택 칸만 다시(실패 칸의 '다시 시도') */
    fun retryBenefit() {
        viewModelScope.launch {
            _state.update { it.copy(benefit = HomeSection.Loading) }
            val b = repository.loadBenefit(YearMonth.now(KST))
            _state.update { it.copy(benefit = it.benefit.next(b)) }
        }
    }

    /** 카드 현황 칸만 다시 */
    fun retryCards() {
        viewModelScope.launch {
            _state.update { it.copy(cards = HomeSection.Loading) }
            val c = repository.loadCards()
            _state.update { it.copy(cards = it.cards.next(c)) }
        }
    }

    /** 잔고예보 요약만 다시 — 기준 계좌를 모르면(홈 요약의 잔고 영역 실패) 홈부터 다시 */
    fun retryForecast() {
        val accountId = _state.value.content?.forecast?.accountId ?: return load()
        viewModelScope.launch {
            _state.update { it.copy(forecast = HomeSection.Loading) }
            val f = repository.loadForecastBrief(accountId)
            _state.update { it.copy(forecast = it.forecast.next(f)) }
        }
    }

    /**
     * 잔고예보 요약 '잔고예보'(HOME-15) — F-01을 홈 요약과 같은 기준 계좌 · 30일로 연다(FORECAST-03, 11/13 허브 결정).
     * 그래서 사용자가 잔고예보에서 골라 둔 기간 · 계좌를 이 계좌 · 30일로 덮어쓴다. 기준 계좌를 모르면 고르지 않고 열기만 한다
     */
    fun openForecast() {
        val accountId = _state.value.content?.forecast?.accountId ?: return
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
