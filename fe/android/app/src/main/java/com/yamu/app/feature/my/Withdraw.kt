package com.yamu.app.feature.my

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.theme.YamuSize
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.StepUpEffect
import com.yamu.app.feature.auth.StepUpFactory
import com.yamu.app.feature.auth.StepUpPrompt
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.StepUpPurpose
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.withdrawErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// M-07b 회원 탈퇴 — 프로토타입의 이유 묻기는 뺐다. 서버가 사유를 저장하지 않는다.
// 안내 문장은 BE WithdrawalService가 실제로 하는 일에 맞췄다(이력은 남고 재가입한 계정에는 보이지 않는다, 철회 기간 없음).

data class WithdrawState(
    val confirmed: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class WithdrawViewModel @Inject constructor(
    private val my: MyRepository,
    private val auth: AuthRepository,
    stepUpFactory: StepUpFactory,
) : ViewModel() {
    private val _state = MutableStateFlow(WithdrawState())
    val state: StateFlow<WithdrawState> = _state.asStateFlow()
    val stepUp = stepUpFactory.create(viewModelScope)

    fun setConfirmed(on: Boolean) = _state.update { it.copy(confirmed = on) }

    /**
     * 서버가 탈퇴를 끝냈을 때만 이 폰을 비운다. 실패 · 응답 유실 때 먼저 지우면 계정은 남았는데 기기만 비어 다시 들어갈 수 없다.
     * 응답을 못 받았는데 실제로는 끝났다면 다음 요청이 401이라 세션 만료 흐름으로 첫 화면에 간다
     */
    fun withdraw() {
        val s = _state.value
        if (!s.confirmed || s.working || stepUp.busy) return
        _state.update { it.copy(working = true, error = null) }
        stepUp.start(
            purpose = StepUpPurpose.WITHDRAW_MEMBER,
            prompt = StepUpPrompt("지문으로 탈퇴를 확인해요", "되돌릴 수 없어요"),
            onError = { e -> _state.update { it.copy(working = false, error = failed(e)) } },
        ) { token ->
            when (val r = my.withdraw(token.token)) {
                is ApiResult.Success -> {
                    auth.forgetAfterWithdrawal()
                    _state.update { it.copy(working = false, done = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(working = false, error = failed(r.error)) }
            }
        }
    }

    private fun failed(e: ApiError) = when {
        e.isOutcomeUnknown -> "탈퇴가 끝났는지 확인하지 못했어요 · 다시 눌러 주세요"
        else -> withdrawErrorMessage(e.code) ?: "탈퇴를 완료하지 못했어요 · ${e.userMessage()}"
    }

    /** 본인 확인을 취소하고 닫았을 때 */
    fun onStepUpIdle() {
        if (_state.value.working && !stepUp.busy) _state.update { it.copy(working = false) }
    }
}

@Composable
fun WithdrawRoute(
    onBack: () -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    onWithdrawn: () -> Unit,
    viewModel: WithdrawViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val stepUpUi by viewModel.stepUp.ui.collectAsStateWithLifecycle()
    StepUpEffect(viewModel.stepUp, onOpenPinPad)
    LaunchedEffect(stepUpUi) { viewModel.onStepUpIdle() }
    LaunchedEffect(s.done) { if (s.done) onWithdrawn() }
    val c = YamuTheme.colors
    FlowScreen(
        title = "회원 탈퇴",
        onBack = onBack,
        bottomBar = {
            BottomCta {
                PrimaryButton("탈퇴하기", onClick = viewModel::withdraw, enabled = s.confirmed, loading = s.working)
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(YamuSpacing.x2))
            Text("탈퇴하면 되돌릴 수 없어요", style = YamuTheme.typography.headlineS, color = c.text1)
            SectionLabel("탈퇴하면 이렇게 돼요")
            KeyValueRow("카드 · 계좌 연결", "모두 풀려요", large = false)
            KeyValueRow("이름 · 휴대폰 · 기기 정보", "지워져요", large = false)
            KeyValueRow("결제 · 청구 기록", "볼 수 없어요", large = false)
            KeyValueRow("카드사 카드 · 은행 계좌", "그대로예요", large = false)
            Spacer(Modifier.height(YamuSpacing.x4))
            Text("다시 가입할 수 있지만 지금 기록은 이어지지 않아요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(YamuSpacing.x1))
            Text("출금이 끝나지 않은 청구나 입금되지 않은 환급이 있으면 탈퇴할 수 없어요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(YamuSpacing.x4))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = YamuSize.touch)
                    .toggleable(value = s.confirmed, enabled = !s.working, role = Role.Checkbox, onValueChange = viewModel::setConfirmed),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
            ) {
                RoundCheckbox(checked = s.confirmed, onCheckedChange = null)
                Text("위 내용을 확인했어요", style = YamuTheme.typography.titleS, color = c.text1)
            }
            s.error?.let {
                Spacer(Modifier.height(YamuSpacing.x2))
                Text(it, style = YamuTheme.typography.bodyM, color = c.error)
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}
