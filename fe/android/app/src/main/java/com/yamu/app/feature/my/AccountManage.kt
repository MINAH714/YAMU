package com.yamu.app.feature.my

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BankMark
import com.yamu.app.feature.link.data.CardValidation
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// M-01b 계좌 관리 — 범위 간략화에서 뺀 '계좌 · 월급 · 고정 지출' 가운데 계좌만 되살렸다. 월급 · 고정 지출은 잔고예보에 있다.
// 기준 계좌를 푸는 동작은 두지 않는다. 다른 계좌를 기준으로 정해야 바뀌고, 서버도 그냥 풀면 PRIMARY_REQUIRED로 거절한다.

data class AccountManageState(
    val loading: Boolean = true,
    val accounts: List<LinkedAccount> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    val message: String? = null,                   // 스낵바 한 번
)

@HiltViewModel
class AccountManageViewModel @Inject constructor(private val link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(AccountManageState())
    val state: StateFlow<AccountManageState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun load() {
        if (_state.value.saving) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.accounts.isEmpty(), error = null) }
            when (val r = link.accounts()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, accounts = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    /** 앞뒤 공백을 빼고 비면 별칭 지우기(빈 문자열을 보내야 서버가 지운다) */
    fun rename(accountId: Long, value: String) = save { link.updateAccount(accountId, alias = CardValidation.nickname(value).orEmpty()) }

    fun makePrimary(accountId: Long) = save { link.updateAccount(accountId, makePrimary = true) }

    /** 같은 값을 다시 보내도 결과가 같아 응답을 못 받으면 그대로 다시 누르면 된다. 한 번에 하나만 */
    private fun save(call: suspend () -> ApiResult<LinkedAccount>) {
        if (_state.value.saving) return
        loadJob?.cancel()   // 목록은 금융망 잔액을 기다려 느리다 — 저장 전에 읽은 값이 늦게 와서 방금 저장한 값을 덮지 않게
        _state.update { it.copy(saving = true, loading = false) }
        viewModelScope.launch {
            when (val r = call()) {
                is ApiResult.Success -> _state.update { s ->
                    s.copy(saving = false, accounts = s.accounts.map { a ->
                        when {
                            a.accountId == r.data.accountId -> r.data.keepingBalanceOf(a)
                            r.data.isPrimary -> a.copy(isPrimary = false)   // 서버가 전에 있던 기준 계좌를 풀었다
                            else -> a
                        }
                    })
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(saving = false, message = "저장하지 못했어요 · ${r.error.userMessage()}") }
                    if (r.error is ApiError.NotFound || r.error.isOutcomeUnknown) load()   // 없는 계좌거나 서버가 끝냈을 수 있다 — 지금 목록으로 다시
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun AccountManageRoute(onBack: () -> Unit, viewModel: AccountManageViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    val closeSheet = {
        selectedId = null
        renaming = false
    }
    FlowScreen(title = "계좌 관리", onBack = onBack) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            when {
                s.loading -> repeat(3) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                s.accounts.isEmpty() && s.error != null -> ErrorState(s.error.orEmpty(), onRetry = viewModel::load)
                s.accounts.isEmpty() -> EmptyState("연결한 계좌가 없어요")
                else -> {
                    SectionLabel("연결 계좌 ${s.accounts.size}개", first = true)
                    s.accounts.forEach { a ->
                        ListRow(
                            title = a.name,
                            sub = accountRowSub(a),
                            lead = { BankMark(a.brand) },
                            titleTag = if (a.isPrimary) ({ StatusTag("기준") }) else null,
                            valuePlain = a.balance?.let { "${it.toWon()}원" } ?: "잔액 확인 불가",
                            chevron = true,
                            minHeight = 72.dp,
                            onClick = if (s.saving) null else ({ selectedId = a.accountId }),   // 저장 중에 고른 계좌가 조용히 무시되지 않게
                        )
                    }
                    Spacer(Modifier.height(YamuSpacing.x3))
                    Text(if (s.saving) "저장하는 중이에요" else "기준 계좌는 홈에 먼저 보여요", style = YamuTheme.typography.bodyS, color = c.text3)
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    val selected = s.accounts.firstOrNull { it.accountId == selectedId }
    if (selected != null) {
        if (renaming) {
            AccountNameSheet(
                account = selected,
                onSave = { value ->
                    closeSheet()
                    viewModel.rename(selected.accountId, value)
                },
                onDismiss = closeSheet,
            )
        } else {
            AccountActionSheet(
                account = selected,
                onRename = { renaming = true },
                onMakePrimary = {
                    closeSheet()
                    viewModel.makePrimary(selected.accountId)
                },
                onDismiss = closeSheet,
            )
        }
    }
}

/** 수정 응답도 잔액을 다시 읽어 만든다 — 금융망이 실패해 비어 있어도 별칭만 바꿨는데 잔액이 사라지지 않게 */
private fun LinkedAccount.keepingBalanceOf(before: LinkedAccount) =
    if (balance != null) this else copy(balance = before.balance, balanceAsOfIso = before.balanceAsOfIso)

/** '신한은행 ·1234 · 카드 2장' */
internal fun accountRowSub(account: LinkedAccount): String =
    listOfNotNull("${account.brand.name} ·${account.last4}", "카드 ${account.cardCount}장".takeIf { account.cardCount > 0 }).joinToString(" · ")

@Composable
private fun AccountActionSheet(account: LinkedAccount, onRename: () -> Unit, onMakePrimary: () -> Unit, onDismiss: () -> Unit) {
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text(account.name, style = YamuTheme.typography.titleL, color = c.text1)
        Text("${account.brand.name} ·${account.last4}", style = YamuTheme.typography.bodyM, color = c.text3)
        Spacer(Modifier.height(YamuSpacing.x2))
        ListRow(title = "별칭 바꾸기", valuePlain = account.alias ?: "없음", chevron = true, onClick = onRename)
        if (account.isPrimary) {
            ListRow(title = "기준 계좌", sub = "다른 계좌를 기준으로 정하면 바뀌어요", valuePlain = "지금 이 계좌")
        } else {
            ListRow(title = "기준 계좌로 정하기", sub = "홈에 이 계좌를 먼저 보여 줘요", chevron = true, onClick = onMakePrimary)
        }
        Spacer(Modifier.height(YamuSpacing.x2))
    }
}

@Composable
private fun AccountNameSheet(account: LinkedAccount, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(account.alias.orEmpty()) }
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("계좌 별칭", style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "별칭",
            value = text,
            onValueChange = { text = it.take(CardValidation.NICKNAME_MAX) },
            placeholder = "${account.brand.name} ·${account.last4}",
            supporting = "${CardValidation.NICKNAME_MAX}자까지 · 비우면 기본 이름으로 보여요",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("저장", onClick = { onSave(text) })
        Spacer(Modifier.height(YamuSpacing.x2))
    }
}
