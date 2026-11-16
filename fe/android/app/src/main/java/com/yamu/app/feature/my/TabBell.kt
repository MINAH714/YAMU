package com.yamu.app.feature.my

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.NotificationBell
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.my.data.MyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 탭 첫 화면 앱바의 알림 종에 붙일 안 읽은 수 — 소비분석 · 실적관리처럼 따로 불러 올 데가 없는 탭이 쓴다(S15P31E103-521) */
@HiltViewModel
class UnreadBadgeViewModel @Inject constructor(private val my: MyRepository) : ViewModel() {
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** 못 불러오면 지난 수를 그대로 둔다 — 배지 하나 때문에 화면에 오류를 띄우지 않는다 */
    fun refresh() {
        viewModelScope.launch {
            (my.notifications(page = 1) as? ApiResult.Success)?.data?.unread?.let { _unread.value = it }
        }
    }
}

/**
 * 탭 첫 화면 오른쪽 알림 종(S15P31E103-521) — 홈 · 소비분석 · 실적관리 · 마이 넷이 같은 자리에 같은 모양. 화면에 돌아올 때마다 수를 다시 받는다.
 * 홈 · 마이는 자기 화면이 이미 받는 수를 쓰므로 [NotificationBell]을 바로 쓴다
 */
@Composable
fun TabBell(onClick: () -> Unit, viewModel: UnreadBadgeViewModel = hiltViewModel()) {
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    NotificationBell(unread, onClick)
}
