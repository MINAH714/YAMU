package com.yamu.app.feature.home

import com.yamu.app.core.security.SessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val session: SessionStore,
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

    private fun HomeUiState.applied(result: ApiResult<HomeSummary>): HomeUiState = when (result) {
        is ApiResult.Success -> copy(isLoading = false, content = result.data, error = null)
        is ApiResult.Failure -> copy(isLoading = false, error = result.error)
    }
}
