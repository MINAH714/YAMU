package com.yamu.app.feature.link

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import com.yamu.app.core.designsystem.component.FlowStep
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.data.BillLine
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowDecision
import com.yamu.app.feature.link.data.CashflowDraft
import com.yamu.app.feature.link.data.CashflowPlan
import com.yamu.app.feature.link.data.CashflowRepository
import com.yamu.app.feature.link.data.monthlyDayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 고치는 시트 대상 — 후보 한 줄 또는 하루 생활비 */
sealed interface CashflowEdit {
    data class Item(val candidate: CashflowCandidate) : CashflowEdit
    data object DailyCost : CashflowEdit
}

data class CashflowConfirmState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val draft: CashflowDraft? = null,
    val amounts: Map<Long, Long> = emptyMap(),     // 고친 금액
    val excluded: Set<Long> = emptySet(),          // 뺀 항목
    val dailyCost: Long? = null,
    val editing: CashflowEdit? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
) {
    fun amountOf(c: CashflowCandidate): Long = amounts[c.id] ?: c.amount

    /** 머리 글은 상태마다 따로 — 찾은 게 없거나 못 불러왔는데 "채워 뒀어요"가 보이지 않게(B-10) */
    val headline: CashflowHeadline
        get() = when {
            loading -> CashflowHeadline("월급과 고정 지출을\n찾고 있어요", null)
            loadError != null || draft == null -> CashflowHeadline("월급과 고정 지출을\n불러오지 못했어요", null)
            draft.isEmpty -> CashflowHeadline("찾은 월급과 고정 지출이\n아직 없어요", "나중에 잔고예보에서 넣을 수 있어요")
            else -> CashflowHeadline("월급과 고정 지출을\n채워 뒀어요", "다르면 눌러서 고쳐요")
        }
}

data class CashflowHeadline(val title: String, val sub: String?)

/** B-06 — 금융망 거래내역에서 찾은 월급 · 고정 지출(#19)을 확인하고 한 번에 확정(#8 × n · #17) */
@HiltViewModel
class CashflowConfirmViewModel @Inject constructor(private val cashflow: CashflowRepository) : ViewModel() {
    private val _state = MutableStateFlow(CashflowConfirmState())
    val state: StateFlow<CashflowConfirmState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            when (val r = cashflow.draft()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, draft = r.data, dailyCost = r.data.dailyLivingCost) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = r.error.userMessage()) }
            }
        }
    }

    fun edit(target: CashflowEdit?) = _state.update { it.copy(editing = target) }

    fun saveAmount(amount: Long?) {
        _state.update { s ->
            when (val e = s.editing) {
                is CashflowEdit.Item -> s.copy(
                    amounts = if (amount != null && amount > 0) s.amounts + (e.candidate.id to amount) else s.amounts,
                    excluded = s.excluded - e.candidate.id,
                    editing = null,
                )
                CashflowEdit.DailyCost -> s.copy(dailyCost = amount?.takeIf { it > 0 }, editing = null)
                null -> s
            }
        }
    }

    fun toggleExclude(candidate: CashflowCandidate) = _state.update {
        val next = if (candidate.id in it.excluded) it.excluded - candidate.id else it.excluded + candidate.id
        it.copy(excluded = next, editing = null)
    }

    fun confirm() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.saving) return
        val candidates = listOfNotNull(draft.income) + draft.expenses
        if (candidates.isEmpty() && s.dailyCost == null) return _state.update { it.copy(saved = true) }
        val plan = CashflowPlan(
            decisions = candidates.map { c ->
                CashflowDecision(c.id, include = c.id !in s.excluded, amount = s.amounts[c.id])
            },
            incomeId = draft.income?.id,
            dailyLivingCost = s.dailyCost,
            currentIncomeId = draft.currentIncomeId,
        )
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null) }
            when (val r = cashflow.confirm(plan)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, saveError = r.error.userMessage() + " · 다시 눌러 주세요") }
            }
        }
    }

    fun navigated() = _state.update { it.copy(saved = false) }
}

/** 고정 지출 줄 — 오늘부터 다가오는 날짜순(이번 달 지난 날짜는 뒤로) */
private sealed interface FixedLine {
    val day: Int?
    data class Candidate(val candidate: CashflowCandidate) : FixedLine { override val day get() = candidate.dayOfMonth }
    data class Bill(val bill: BillLine) : FixedLine { override val day get() = bill.dayOfMonth }
}

private fun upcomingOrder(today: Int): (FixedLine) -> Int = { line ->
    val d = line.day ?: 99
    if (d >= today) d else d + 31
}

@Composable
fun CashflowConfirmRoute(onBack: () -> Unit, onSaved: () -> Unit, viewModel: CashflowConfirmViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.saved) {
        if (s.saved) {
            viewModel.navigated()
            onSaved()
        }
    }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    val draft = s.draft
    FlowScreen(
        title = "월급·고정 지출",
        onBack = onBack,
        step = FlowStep(2, 2),
        bottomBar = {
            if (draft != null) {
                BottomCta {
                    s.saveError?.let {
                        Text(it, style = YamuTheme.typography.bodyS, color = c.error, modifier = Modifier.padding(bottom = YamuSpacing.x2))
                    }
                    PrimaryButton(if (draft.isEmpty) "첫 분석 보기" else "등록하고 첫 분석 보기", onClick = viewModel::confirm, loading = s.saving)
                }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            // 단계 막대 아래 4 + 머리 위 20(프로토타입 B-06 .su · .hd) — 막대가 아래 8을 이미 두므로 16. 보조–첫 줄 12
            Spacer(Modifier.height(16.dp))
            val headline = s.headline
            Text(headline.title, style = YamuTheme.typography.headlineS, color = c.text1)
            headline.sub?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = YamuTheme.typography.bodyM, color = c.text3)
            }
            Spacer(Modifier.height(12.dp))
            when {
                s.loading -> repeat(4) {
                    SkeletonBlock(height = 48.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                }
                s.loadError != null -> ErrorState(s.loadError.orEmpty(), onRetry = viewModel::load)
                draft == null -> Unit
                // 찾은 게 없어도 하루 생활비 줄은 둔다 — 제안값이 화면에 없는 채로 저장되지 않게
                else -> {
                    draft.income?.let { income ->
                        CandidateRow(income, s, onClick = { viewModel.edit(CashflowEdit.Item(income)) })
                    }
                    val today = remember { LocalDate.now(KST).dayOfMonth }
                    val lines = (draft.expenses.map { FixedLine.Candidate(it) } + draft.bills.map { FixedLine.Bill(it) })
                        .sortedBy(upcomingOrder(today))
                    if (lines.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))   // 묶음 사이 8 + 라벨 위 12(.grp + .grp · .grp-lb)
                        Text("고정 지출", style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold), color = c.text3)
                        val shown = if (showAll) lines else lines.take(VISIBLE_LINES)
                        shown.forEachIndexed { i, line ->
                            if (i > 0) HorizontalDivider(color = c.outlineVariant)
                            when (line) {
                                is FixedLine.Candidate -> CandidateRow(line.candidate, s, onClick = { viewModel.edit(CashflowEdit.Item(line.candidate)) })
                                // 카드대금은 청구서에서 오는 값이라 고치지 않는다(설명은 읽어 주기에만)
                                is FixedLine.Bill -> ListRow(
                                    title = line.bill.title + (line.bill.dayOfMonth?.let { " · ${monthlyDayLabel(it)}" } ?: ""),
                                    value = (-line.bill.amount).toWon(),
                                    modifier = Modifier.semantics { contentDescription = "${line.bill.title}, 카드사 청구서에서 자동으로 넣어요" },
                                )
                            }
                        }
                        val hidden = lines.size - VISIBLE_LINES
                        if (hidden > 0) {
                            HorizontalDivider(color = c.outlineVariant)
                            FoldToggle("${hidden}건 더 보기", expanded = showAll, onToggle = { showAll = !showAll })
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("생활비", style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold), color = c.text3)
                    ListRow(
                        title = "하루 생활비",
                        value = s.dailyCost?.let { "${it.toWon()}원" } ?: "정하기",
                        valueColor = if (s.dailyCost == null) c.primary else c.text1,
                        onClick = { viewModel.edit(CashflowEdit.DailyCost) },
                    )
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }

    s.editing?.let { editing ->
        EditAmountSheet(
            editing = editing,
            state = s,
            onDismiss = { viewModel.edit(null) },
            onSave = viewModel::saveAmount,
            onToggleExclude = viewModel::toggleExclude,
        )
    }
}

@Composable
private fun CandidateRow(candidate: CashflowCandidate, s: CashflowConfirmState, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val excluded = candidate.id in s.excluded
    val amount = s.amountOf(candidate)
    ListRow(
        title = candidate.title + (candidate.dayOfMonth?.let { " · ${monthlyDayLabel(it)}" } ?: ""),
        sub = if (excluded) "빼 둠 · 눌러서 다시 넣기" else null,
        value = if (candidate.isIncome) amount.toWon(withPlusSign = true) else (-amount).toWon(),
        valueColor = when {
            excluded -> c.text3
            candidate.isIncome -> c.primary
            else -> c.text1
        },
        onClick = onClick,
    )
}

@Composable
private fun EditAmountSheet(
    editing: CashflowEdit,
    state: CashflowConfirmState,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit,
    onToggleExclude: (CashflowCandidate) -> Unit,
) {
    val (title, initial) = when (editing) {
        is CashflowEdit.Item -> editing.candidate.title to state.amountOf(editing.candidate)
        CashflowEdit.DailyCost -> "하루 생활비" to state.dailyCost
    }
    var text by remember(editing) { mutableStateOf(initial?.toString().orEmpty()) }
    YamuBottomSheet(onDismiss = onDismiss) {
        Text(title, style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "금액(원)",
            value = text,
            onValueChange = { v -> text = v.filter(Char::isDigit).take(10) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // 서버는 이 값을 하루 목표로 저장만 하고 잔고예보 예측에는 넣지 않는다(docs/be/forecast.md)
            supporting = if (editing is CashflowEdit.DailyCost) "하루에 쓸 목표 금액이에요" else null,
        )
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("저장", onClick = { onSave(text.toLongOrNull()) })
        if (editing is CashflowEdit.Item) {
            Spacer(Modifier.height(YamuSpacing.x2))
            val excluded = editing.candidate.id in state.excluded
            NeutralButton(
                if (excluded) "다시 넣기" else if (editing.candidate.isIncome) "월급이 아니에요" else "고정 지출이 아니에요",
                onClick = { onToggleExclude(editing.candidate) },
            )
        }
    }
}

private const val VISIBLE_LINES = 3
