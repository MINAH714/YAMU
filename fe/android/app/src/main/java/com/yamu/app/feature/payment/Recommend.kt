package com.yamu.app.feature.payment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.NotificationBell
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.my.data.MyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecommendState(
    /** 앱바 종 배지 — GET notifications의 unreadCount(필터와 상관없는 안 읽은 수), 홈과 같은 값 */
    val unread: Int = 0,
)

/**
 * P-01 카드추천 탭 — 결제 코드를 여는 입구.
 * 결제 전 단일 추천(POST recommendations)은 명세 V2에서 빠져, 카드 추천은 매장이 코드를 찍은 뒤 결제 요청 화면(P-06c)에서만 보인다
 */
@HiltViewModel
class RecommendViewModel @Inject constructor(private val my: MyRepository) : ViewModel() {
    private val _state = MutableStateFlow(RecommendState())
    val state: StateFlow<RecommendState> = _state.asStateFlow()

    /** 탭에 돌아올 때마다(알림함을 보고 온 뒤 포함) — 실패하면 배지만 안 바뀐다 */
    fun refreshUnread() = viewModelScope.launch {
        (my.unreadNotificationCount() as? ApiResult.Success)?.let { r -> _state.value = RecommendState(unread = r.data) }
    }
}

/** [onAddCard]는 지금 쓰지 않는다. 카드가 없으면 결제 코드 화면이 '카드 추가'를 띄운다(내비게이션 호출부를 바꾸지 않으려고 남겨 둠) */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RecommendRoute(onOpenPayCode: () -> Unit, onAddCard: () -> Unit, onNotifications: () -> Unit = {}, viewModel: RecommendViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshUnread() }
    val c = YamuTheme.colors
    TabScreen(
        title = "카드추천",
        actions = { NotificationBell(unread = s.unread, onClick = onNotifications) },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            YamuCard {
                Text("결제 코드를 열어 매장에 보여 주세요", style = YamuTheme.typography.titleL, color = c.text1)
                Spacer(Modifier.height(6.dp))
                Text("매장에서 찍으면 가진 카드 중 혜택이 가장 큰 카드를 골라 드려요", style = YamuTheme.typography.bodyM, color = c.text3)
                Spacer(Modifier.height(YamuSpacing.x4))
                PrimaryButton("결제 코드 열기", onClick = onOpenPayCode)
            }
        }
    }
}
