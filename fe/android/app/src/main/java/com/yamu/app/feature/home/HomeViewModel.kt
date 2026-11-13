package com.yamu.app.feature.home

import com.yamu.app.core.security.SessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.HomeBenefit
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.home.data.PaySettings
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
)

/** 결제 시각(서버 UTC)이 든 한국 기준 달 — 시각이 없거나 못 읽으면 null */
internal fun paymentMonthOf(paidAtIso: String?): YearMonth? =
    paidAtIso?.let { runCatching { YearMonth.from(OffsetDateTime.parse(it).atZoneSameInstant(KST)) }.getOrNull() }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val session: SessionStore,
    private val months: ReportMonth,
    private val reportJump: ReportJump,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

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
        onHome(repository.loadHome())
        val b = benefit.await()
        _state.update { it.copy(benefit = it.benefit.next(b)) }
        val p = pay.await()
        _state.update { it.copy(pay = it.pay.next(p)) }
    }

    /** 혜택 칸만 다시(실패 칸의 '다시 시도') */
    fun retryBenefit() {
        viewModelScope.launch {
            _state.update { it.copy(benefit = HomeSection.Loading) }
            val b = repository.loadBenefit(YearMonth.now(KST))
            _state.update { it.copy(benefit = it.benefit.next(b)) }
        }
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

    /**
     * 잔고예보 요약 '자산 흐름 보기'(HOME-15) — 소비분석 탭으로 바꾸기 전에 부른다. 소비분석이 이번 달로 돌아가 자산 흐름 칸까지 내려간다.
     * 버튼은 홈 아래쪽 개편(S15P31E103-481)에서 단다
     */
    fun openAssetFlow() = reportJump.request(ReportSection.ASSET_FLOW)

    private fun HomeUiState.applied(result: ApiResult<HomeSummary>): HomeUiState = when (result) {
        is ApiResult.Success -> copy(isLoading = false, content = result.data, error = null)
        is ApiResult.Failure -> copy(isLoading = false, error = result.error)
    }
}
