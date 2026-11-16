package com.yamu.app.feature.forecast

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.*
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.*
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.*
import com.yamu.app.feature.link.IncomeSheet
import com.yamu.app.feature.link.incomeAmountError
import com.yamu.app.feature.link.incomeDayError
import com.yamu.app.feature.link.data.IncomeInput
import com.yamu.app.feature.link.data.monthlyDayLabel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import javax.inject.Inject

data class SalaryState(
    val loading: Boolean = true,
    val data: SalaryData? = null,
    val error: String? = null,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val locked: Boolean = false,
    val pendingInput: IncomeInput? = null,
    val revision: Int = 0,
    val mustReload: Boolean = false,
)

@HiltViewModel
class SalaryViewModel @Inject constructor(
    private val repo: SalaryRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val pending: CashflowCreateRequest? get() = saved.get<String>("salaryBody")?.let { YamuJson.decodeFromString(it) }
    private val _state = MutableStateFlow(SalaryState())
    val state = _state.asStateFlow()
    private var loadingJob: Job? = null

    fun load() {
        if (_state.value.saving) return
        loadingJob?.cancel()
        _state.update { it.copy(loading = true, error = null, editing = false) }
        loadingJob = viewModelScope.launch {
            when (val r = repo.load(LocalDate.now(KST))) {
                is ApiResult.Success -> {
                    val p = pending
                    _state.update { it.copy(loading = false, data = r.data, locked = p != null,
                        pendingInput = p?.let { b -> IncomeInput(b.amount, requireNotNull(b.recurrence?.dayOfMonth), requireNotNull(b.accountId)) },
                        mustReload = false) }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun open() {
        val s = _state.value
        if (!s.loading && !s.saving && !s.mustReload && s.data != null && s.error == null) _state.update { it.copy(editing = true) }
    }
    fun dismiss() { if (!_state.value.saving) _state.update { it.copy(editing = false) } }

    fun save(input: IncomeInput) {
        val s = _state.value
        val data = s.data ?: return
        if (s.saving || s.loading || s.mustReload) return
        if (incomeAmountError(input.amount.toString()) != null || incomeDayError(input.day.toString()) != null || (!s.locked && data.accounts.none { it.accountId == input.accountId })) {
            _state.update { it.copy(error = "금액·받는 날·계좌를 확인해 주세요") }; return
        }
        if (s.locked && input != s.pendingInput) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val today = LocalDate.now(KST)
            val result: ApiResult<Unit>
            if (data.income != null && pending == null) {
                val entry = data.next
                if (entry == null || !entry.occurrenceDate.isAfter(today)) {
                    _state.update { it.copy(saving = false, editing = false, mustReload = true, error = "다음 월급 회차를 다시 확인해 주세요") }; return@launch
                }
                // PATCH는 멱등성 키가 없다. 결과 불명 때 같은 분기 요청을 재전송하지 않고 서버를 다시 읽는다.
                result = repo.update(entry.itemId, salaryPatch(entry, input, today))
                if (result is ApiResult.Failure && result.error.isOutcomeUnknown) {
                    _state.update { it.copy(saving = false, editing = false, mustReload = true, error = "저장 결과를 확인하지 못했어요. 다시 불러와 월급을 확인해 주세요") }; return@launch
                }
            } else {
                val body = pending ?: input.toRequest(today)
                val key = saved.get<String>("salaryKey") ?: newIdempotencyKey().also { saved["salaryKey"] = it }
                // 요청 전에 기록하여 화면 재생성 후에도 같은 키·본문으로 이어 간다.
                saved["salaryBody"] = YamuJson.encodeToString(body)
                val id = saved.get<Long>("salaryCreatedId") ?: when (val r = repo.create(body, key)) {
                    is ApiResult.Success -> r.data.also { saved["salaryCreatedId"] = it }
                    is ApiResult.Failure -> {
                        if (!r.error.isOutcomeUnknown) clearPending()
                        _state.update { it.copy(saving = false, locked = r.error.isOutcomeUnknown,
                            pendingInput = input.takeIf { r.error.isOutcomeUnknown }, error = r.error.userMessage() + " · 다시 저장해 주세요") }
                        return@launch
                    }
                }
                _state.update { it.copy(locked = true, pendingInput = input) }
                result = repo.designate(id)
            }
            when (result) {
                is ApiResult.Success -> {
                    clearPending()
                    _state.update { it.copy(saving = false, editing = false, locked = false, pendingInput = null, revision = it.revision + 1) }
                    load()
                }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, error = result.error.userMessage() + " · 다시 저장해 주세요") }
            }
        }
    }

    private fun clearPending() {
        saved.remove<String>("salaryBody"); saved.remove<String>("salaryKey"); saved.remove<Long>("salaryCreatedId")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SalaryModule {
    @Binds abstract fun salaryRepository(impl: SalaryRepositoryImpl): SalaryRepository
}

@Composable
internal fun SalaryCard(onSaved: () -> Unit, viewModel: SalaryViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }
    LaunchedEffect(s.revision) { if (s.revision > 0) onSaved() }
    YamuCard {
        Text("월급", style = YamuTheme.typography.titleS, color = YamuTheme.colors.text1)
        when {
            s.loading -> SkeletonBlock()
            !s.editing && s.error != null -> ErrorState(requireNotNull(s.error), onRetry = viewModel::load)
            else -> s.data?.let { d ->
                val income = d.income
                ListRow(
                    title = income?.amount?.let { "${it.toWon()}원" } ?: "월급을 등록해 주세요",
                    sub = if (s.locked) "저장 중이던 월급이 있어요. 눌러서 이어서 저장해 주세요" else income?.dayOfMonth?.let { "매월 ${monthlyDayLabel(it)} · 다음 회차부터 이후 전부 수정" } ?: "금액·받는 날·계좌를 넣으면 예보에 반영해요",
                    value = if (income == null) "등록" else "수정",
                    onClick = if (s.locked || (d.accounts.isNotEmpty() && (income == null || d.next != null))) viewModel::open else null,
                )
                if (d.accounts.isEmpty()) Text("계좌를 연결하면 월급을 등록할 수 있어요")
                else if (income != null && d.next == null && !s.locked) Text("수정할 다음 월급 회차가 없어요")
            }
        }
    }
    if (s.editing) s.data?.let { d ->
        val initial = s.pendingInput ?: d.income?.let { i ->
            if (i.amount != null && i.dayOfMonth != null && i.accountId != null) IncomeInput(i.amount, i.dayOfMonth, i.accountId) else null
        }
        IncomeSheet(
            current = initial, accounts = d.accounts, defaultAccount = d.accounts.firstOrNull { it.isPrimary } ?: d.accounts.firstOrNull(),
            onDismiss = viewModel::dismiss, onSave = { it?.let(viewModel::save) }, busy = s.saving, locked = s.locked,
            error = s.error, allowRemove = false,
            note = if (d.income != null) "다음 회차부터 이후 월급에 적용해요. 따로 바꾼 이후 회차도 함께 바뀌어요." else "매월 반복 수입으로 저장하고 잔고예보에 반영해요.",
        )
    }
}
