package com.yamu.app.feature.link

import com.yamu.app.core.designsystem.component.FlowStep
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.formatAsOf
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkDoneState(val loading: Boolean = true, val overview: LinkedOverview? = null, val failed: Boolean = false)

/** C-04 연결 완료 — 잔액은 금융망 값 그대로(#83), 카드 수(#7) */
@HiltViewModel
class LinkDoneViewModel @Inject constructor(private val link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(LinkDoneState())
    val state: StateFlow<LinkDoneState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val r = link.linkedOverview()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, overview = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}

/** 뒤로(← · 시스템) = 홈 — 연결이 끝났으니 목록 · 인증으로 돌아가지 않는다 */
@Composable
fun LinkDoneRoute(onNext: () -> Unit, onExitToHome: () -> Unit, viewModel: LinkDoneViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onExitToHome)
    val c = YamuTheme.colors
    val overview = s.overview
    FlowScreen(
        title = "계좌 · 카드 연결",
        onBack = onExitToHome,
        step = FlowStep(1, 2),
        bottomBar = { BottomCta { PrimaryButton("월급 · 고정 지출 확인하기", onClick = onNext) } },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            CharacterImage(YamuPose.Done, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(12.dp))
            val headline = when {
                overview != null -> "계좌 ${overview.accounts.size}개와 카드 ${overview.cards.size}장을\n연결했어요"
                else -> "연결을 마쳤어요"
            }
            Text(headline, style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(32.dp))
            val account = overview?.primaryAccount
            when {
                s.loading -> SkeletonBlock(height = 72.dp)
                // 은행에서 가져온 잔액이 이 화면의 핵심 숫자 — 계좌 이름 / 큰 숫자 / 기준 시각
                account != null -> Column(Modifier.fillMaxWidth()) {
                    Text("${account.brand.name} ${account.name} ·${account.last4}", style = YamuTheme.typography.bodyM, color = c.text2)
                    // 숫자 30sp + 4dp + '원' 16sp를 글자 바닥에 맞춘다(프로토타입 C-04 .bal — 다른 화면의 0.6배 단위와 다름)
                    if (account.balance != null) Row(Modifier.padding(top = 4.dp)) {
                        Text(account.balance.toWon(), style = YamuTheme.typography.amountL, color = c.text1, modifier = Modifier.alignByBaseline())
                        Spacer(Modifier.width(4.dp))
                        Text("원", style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.alignByBaseline())
                    }
                    formatAsOf(account.balanceAsOfIso)?.let { Text(it, style = YamuTheme.typography.sub, color = c.text3) }
                }
                else -> Text("잔액은 잠시 뒤 보여 드려요", style = YamuTheme.typography.bodyM, color = c.text3)
            }
        }
    }
}
