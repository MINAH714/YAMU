package com.yamu.app.feature.home

import com.yamu.app.core.security.SessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import com.yamu.app.feature.report.data.ReportJump
import com.yamu.app.feature.report.data.ReportMonth
import com.yamu.app.feature.report.data.ReportSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.YearMonth
import javax.inject.Inject

/**
 * 홈 상태. [content]는 마지막으로 성공한 값 — 실패해도 지우지 않고 [error]로 위에 한 줄 알린다(07 문서 3-4).
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val content: HomeSummary? = null,
    val error: ApiError? = null,
    val userName: String? = null,        // 인사 머리 '○○님, 월급까지 D-25'
) {
    /** 잔고예보 · 예정 수입·지출로 가는 길은 예상 잔액 카드뿐이라 카드가 없으면 따로 보여 준다(연결 전은 연결이 먼저) */
    val showsEntries: Boolean
        get() = if (content != null) content.forecast == null && !content.needsLinking else !isLoading && error != null

    /**
     * 맨 아래 소비분석 줄(2차 QA 19). 예상 잔액 카드가 있어도 둔다 — [showsEntries]면 그 카드 안 세 번째 줄로.
     * 연결 전이면 결제가 없어 소비분석도 비어 있으니 연결이 먼저
     */
    val showsReportEntry: Boolean
        get() = if (content != null) !content.needsLinking else !isLoading && error != null
}

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

    /** 화면에 들어오거나 돌아올 때마다 — 이전 내용은 두고 새 값으로 바꾼다 */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.content == null) }
            val name = session.read().name
            val result = repository.loadHome()
            _state.update { it.applied(result).copy(userName = name) }
        }
    }

    /** 당겨서 새로고침 — 금융망 동기화(#76) 뒤 홈 다시 받기 */
    fun refresh() {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val sync = repository.sync()
            val home = repository.loadHome()
            _state.update { current ->
                val next = current.applied(home).copy(isRefreshing = false)
                // 홈은 받았지만 동기화가 실패했으면(금융망 지연 등) 위에 한 줄 알림 — 숫자는 마지막 반영 시각 기준
                if (home is ApiResult.Success && sync is ApiResult.Failure) next.copy(error = sync.error) else next
            }
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
     * 잔고예보 요약 '자산 흐름 보기'(HOME-15) — 소비분석 탭으로 바꾸기 전에 부른다. 소비분석이 이번 달로 돌아가 자산 흐름 칸까지 내려간다.
     * 버튼은 홈 아래쪽 개편(S15P31E103-481)에서 단다
     */
    fun openAssetFlow() = reportJump.request(ReportSection.ASSET_FLOW)

    private fun HomeUiState.applied(result: ApiResult<HomeSummary>): HomeUiState = when (result) {
        is ApiResult.Success -> copy(isLoading = false, content = result.data, error = null)
        is ApiResult.Failure -> copy(isLoading = false, error = result.error)
    }
}
