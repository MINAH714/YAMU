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
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CASHFLOW_AMOUNT_MAX
import com.yamu.app.feature.forecast.data.CashflowCreateRequest
import com.yamu.app.feature.link.data.BillLine
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowDecision
import com.yamu.app.feature.link.data.CashflowDraft
import com.yamu.app.feature.link.data.CashflowPlan
import com.yamu.app.feature.link.data.CashflowRepository
import com.yamu.app.feature.link.data.IncomeAccount
import com.yamu.app.feature.link.data.IncomeInput
import com.yamu.app.feature.link.data.monthlyDayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 고치는 시트 대상 — 후보 한 줄 · 새로 넣는 월급 · 하루 생활비 */
sealed interface CashflowEdit {
    data class Item(val candidate: CashflowCandidate) : CashflowEdit
    data object Income : CashflowEdit
    data object DailyCost : CashflowEdit
}

data class CashflowConfirmState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val draft: CashflowDraft? = null,
    val amounts: Map<Long, Long> = emptyMap(),     // 고친 금액
    val excluded: Set<Long> = emptySet(),          // 뺀 항목
    val newIncome: IncomeInput? = null,            // 여기서 새로 넣은 월급
    val incomeLocked: Boolean = false,             // 월급을 이미 보냈다(등록됐거나 결과 불명) — 같은 값으로만 다시 보낸다
    val dailyCost: Long? = null,
    val editing: CashflowEdit? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
    val retryingAccounts: Boolean = false,         // 월급 받을 계좌를 다시 부르는 중
) {
    fun amountOf(c: CashflowCandidate): Long = amounts[c.id] ?: c.amount

    /** 보낼 것이 있는지 — 후보 · 새 월급 · 하루 생활비 중 하나라도 */
    val hasAnythingToSave: Boolean
        get() = draft != null && (!draft.isEmpty || newIncome != null || dailyCost != null)

    /**
     * 머리 글은 상태마다 따로 — 찾은 게 없거나 못 불러왔는데 "채워 뒀어요"가 보이지 않게(B-10).
     * 서버는 반복 지출만 찾고 월급은 찾지 않으므로(BE -349 !200) 수입 후보가 없으면 고정 지출만 '찾았다'고 쓴다
     */
    val headline: CashflowHeadline
        get() = when {
            loading -> CashflowHeadline("고정 지출을\n찾고 있어요", null)
            loadError != null || draft == null -> CashflowHeadline("고정 지출을\n불러오지 못했어요", null)
            draft.income != null -> CashflowHeadline("월급과 고정 지출을\n채워 뒀어요", "다르면 눌러서 고쳐요")
            draft.noFixed && draft.canEnterIncome && newIncome == null -> CashflowHeadline("찾은 고정 지출이\n아직 없어요", "월급을 넣어 두면 월급날까지 예보해 드려요")
            draft.noFixed -> CashflowHeadline("찾은 고정 지출이\n아직 없어요", "나중에 잔고예보에서 넣을 수 있어요")
            draft.canEnterIncome && newIncome == null -> CashflowHeadline("고정 지출을\n채워 뒀어요", "다르면 눌러서 고치고 월급은 직접 넣어 주세요")
            else -> CashflowHeadline("고정 지출을\n채워 뒀어요", "다르면 눌러서 고쳐요")
        }
}

data class CashflowHeadline(val title: String, val sub: String?)

/** B-06 — 금융망 거래내역에서 찾은 월급 · 고정 지출(#19)을 확인하고 한 번에 확정(#8 × n · #17) */
@HiltViewModel
class CashflowConfirmViewModel @Inject constructor(private val cashflow: CashflowRepository) : ViewModel() {
    private val _state = MutableStateFlow(CashflowConfirmState())
    val state: StateFlow<CashflowConfirmState> = _state.asStateFlow()

    /** 월급 등록 재시도 키 — 화면에 들어올 때 하나 만들고 다시 보낼 때도 같은 값(BE -358 !202, 계좌 연결과 같은 방식) */
    private val incomeKey = newIdempotencyKey()

    /** 보낸 월급 본문 — 결과를 모르면 이 본문 그대로 다시 보낸다(첫 회차 날짜가 하루 지나 바뀌어도 409가 나지 않게) */
    private var sentIncome: CashflowCreateRequest? = null

    /** 등록된 월급 ID — 뒤 단계(후보 확정 · 설정)가 실패해 다시 누르면 다시 만들지 않고 이 ID로 설정한다 */
    private var createdIncomeId: Long? = null

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

    /**
     * 월급 줄의 [다시 시도] — 받을 계좌만 다시 부른다. 전체를 다시 부르면 고친 금액 · 하루 생활비가 처음 값으로 돌아간다.
     * 연타는 부르는 중이면 무시
     */
    fun retryIncomeAccounts() {
        if (_state.value.retryingAccounts || _state.value.draft == null) return
        _state.update { it.copy(retryingAccounts = true) }
        viewModelScope.launch {
            val r = cashflow.incomeAccounts()
            _state.update { s ->
                val d = s.draft ?: return@update s.copy(retryingAccounts = false)
                when (r) {
                    is ApiResult.Success -> s.copy(retryingAccounts = false, draft = d.copy(incomeAccounts = r.data, incomeAccountsError = null))
                    is ApiResult.Failure -> s.copy(retryingAccounts = false, draft = d.copy(incomeAccountsError = r.error))
                }
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
                CashflowEdit.Income, null -> s
            }
        }
    }

    /** 월급 시트 저장 · 지우기(null). 이미 보낸 월급은 바꾸지 않는다 — 같은 키로 다른 값을 보내면 서버가 409로 거절한다 */
    fun saveIncome(input: IncomeInput?) = _state.update { s ->
        if (s.incomeLocked) s.copy(editing = null) else s.copy(newIncome = input, editing = null)
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
        val income = s.newIncome?.takeIf { draft.canEnterIncome }
        if (candidates.isEmpty() && s.dailyCost == null && income == null) return _state.update { it.copy(saved = true) }
        _state.update { it.copy(saving = true, saveError = null) }   // 코루틴이 돌기 전에 막아야 연타가 두 번 나가지 않는다
        viewModelScope.launch {
            val incomeId = if (income == null) null else createdIncomeId ?: when (val r = createIncome(income)) {
                is ApiResult.Success -> r.data
                is ApiResult.Failure -> return@launch _state.update { it.copy(saving = false, saveError = incomeError(r.error)) }
            }
            val plan = CashflowPlan(
                decisions = candidates.map { c ->
                    CashflowDecision(c.id, include = c.id !in s.excluded, amount = s.amounts[c.id])
                },
                incomeId = draft.income?.id,
                dailyLivingCost = s.dailyCost,
                createdIncomeId = incomeId,
            )
            when (val r = cashflow.confirm(plan)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, saveError = r.error.userMessage() + " · 다시 눌러 주세요") }
            }
        }
    }

    /**
     * 월급을 예정 수입(매월)으로 등록. 응답을 못 받았으면 들어갔을 수 있어 월급 칸을 잠그고 다시 누르면 같은 키 · 같은 본문으로 보낸다.
     * 서버가 거절한 실패(4xx)는 기록이 남지 않아 고쳐서 다시 보내도 된다
     */
    private suspend fun createIncome(income: IncomeInput): ApiResult<Long> {
        val body = sentIncome ?: income.toRequest(LocalDate.now(KST))
        val r = cashflow.createIncome(body, incomeKey)
        when {
            r is ApiResult.Success -> {
                createdIncomeId = r.data
                sentIncome = body
                _state.update { it.copy(incomeLocked = true) }
            }
            r is ApiResult.Failure && r.error.isOutcomeUnknown -> {
                sentIncome = body
                _state.update { it.copy(incomeLocked = true) }
            }
        }
        return r
    }

    private fun incomeError(e: ApiError): String = when {
        e.isOutcomeUnknown -> "응답을 받지 못했어요 · 다시 눌러도 월급은 한 번만 들어가요"
        // 같은 키에 다른 본문 — 칸을 잠가 생기지 않지만 생기면 앞서 보낸 월급이 이미 들어간 것이다
        e.code == "IDEMPOTENCY_KEY_REUSED" -> "앞서 보낸 월급이 이미 들어갔어요 · 잔고예보 예정 수입에서 확인해 주세요"
        else -> "월급을 넣지 못했어요 · " + e.userMessage()
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
fun CashflowConfirmRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    fromHome: Boolean = false,   // 홈 월급 안내에서 열었다 — 가입 단계가 아니라 단계 막대 · 첫 분석 문구를 뺀다(S15P31E103-429)
    viewModel: CashflowConfirmViewModel = hiltViewModel(),
) {
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
        step = if (fromHome) null else FlowStep(2, 2),
        bottomBar = {
            if (draft != null) {
                BottomCta {
                    s.saveError?.let {
                        Text(it, style = YamuTheme.typography.bodyS, color = c.error, modifier = Modifier.padding(bottom = YamuSpacing.x2))
                    }
                    val label = when {
                        fromHome -> "등록하기"
                        draft.isEmpty && s.newIncome == null -> "첫 분석 보기"
                        else -> "등록하고 첫 분석 보기"
                    }
                    PrimaryButton(label, onClick = viewModel::confirm, loading = s.saving)
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
                    IncomeSection(draft, s, onEdit = { viewModel.edit(it) }, onRetryAccounts = viewModel::retryIncomeAccounts)
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
                                    title = line.bill.title + (line.bill.dayOfMonth?.let { " (${monthlyDayLabel(it)})" } ?: ""),
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

    when (val editing = s.editing) {
        null -> Unit
        CashflowEdit.Income -> draft?.let { d ->
            IncomeSheet(
                current = s.newIncome,
                accounts = d.incomeAccounts,
                defaultAccount = d.defaultIncomeAccount,
                onDismiss = { viewModel.edit(null) },
                onSave = viewModel::saveIncome,
            )
        }
        else -> EditAmountSheet(
            editing = editing,
            state = s,
            onDismiss = { viewModel.edit(null) },
            onSave = viewModel::saveAmount,
            onToggleExclude = viewModel::toggleExclude,
        )
    }
}

/**
 * 월급 묶음 — 정해 둔 월급은 보여 주기만, 수입 후보(옛 서버)는 후보 줄, 그 밖에는 직접 넣는 줄.
 * 서버가 월급을 후보로 만들지 않아(BE -349 !200) 여기서 넣지 않으면 앱 어디서도 월급을 정할 곳이 없다.
 * 받을 계좌를 못 읽었으면 같은 자리에 다시 시도 줄(QA P1-04)
 */
@Composable
private fun IncomeSection(draft: CashflowDraft, s: CashflowConfirmState, onEdit: (CashflowEdit) -> Unit, onRetryAccounts: () -> Unit) {
    val c = YamuTheme.colors
    val saved = draft.savedIncome
    val candidate = draft.income
    if (saved == null && candidate == null && !draft.canEnterIncome && !draft.incomeAccountsFailed) return
    Text("월급", style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold), color = c.text3)
    when {
        saved != null -> ListRow(
            title = saved.title + (saved.dayOfMonth?.let { " (${monthlyDayLabel(it)})" } ?: ""),
            sub = "정해 둔 월급이에요",
            value = saved.amount?.toWon(withPlusSign = true) ?: "금액 미정",
            valueColor = c.primary,
        )
        candidate != null -> CandidateRow(candidate, s, onClick = { onEdit(CashflowEdit.Item(candidate)) })
        draft.incomeAccountsFailed -> ListRow(
            title = IncomeInput.INCOME_TITLE,
            sub = "계좌를 불러오지 못했어요",
            value = if (s.retryingAccounts) "불러오는 중" else "다시 시도",
            valueColor = if (s.retryingAccounts) c.text3 else c.primary,
            onClick = if (s.retryingAccounts) null else onRetryAccounts,
        )
        else -> {
            val input = s.newIncome
            val account = input?.let { i -> draft.incomeAccounts.firstOrNull { it.accountId == i.accountId } }
            ListRow(
                title = input?.let { "${IncomeInput.INCOME_TITLE} (${monthlyDayLabel(it.day)})" } ?: IncomeInput.INCOME_TITLE,
                sub = when {
                    input == null -> "넣어 두면 월급날까지 예보해요"
                    s.incomeLocked -> "보낸 값 그대로 저장해요"
                    draft.incomeAccounts.size > 1 -> account?.label
                    else -> null
                },
                value = input?.amount?.toWon(withPlusSign = true) ?: "정하기",
                valueColor = c.primary,
                onClick = if (s.incomeLocked) null else ({ onEdit(CashflowEdit.Income) }),
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: CashflowCandidate, s: CashflowConfirmState, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val excluded = candidate.id in s.excluded
    val amount = s.amountOf(candidate)
    ListRow(
        title = candidate.title + (candidate.dayOfMonth?.let { " (${monthlyDayLabel(it)})" } ?: ""),
        sub = if (excluded) "빼 둠 (눌러서 다시 넣기)" else candidate.basis,
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
        CashflowEdit.Income -> IncomeInput.INCOME_TITLE to state.newIncome?.amount   // 월급은 IncomeSheet가 맡는다
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

/** 월급 넣기 — 금액 · 매월 받는 날(31은 말일) · 받는 계좌(둘 이상일 때만 고른다). 저장은 화면 값만 바꾸고 보내는 건 아래 버튼 */
@Composable
private fun IncomeSheet(
    current: IncomeInput?,
    accounts: List<IncomeAccount>,
    defaultAccount: IncomeAccount?,
    onDismiss: () -> Unit,
    onSave: (IncomeInput?) -> Unit,
) {
    val c = YamuTheme.colors
    var amount by remember { mutableStateOf(current?.amount?.toString().orEmpty()) }
    var day by remember { mutableStateOf(current?.day?.toString().orEmpty()) }
    var accountId by remember { mutableStateOf(current?.accountId ?: defaultAccount?.accountId) }
    var tried by remember { mutableStateOf(false) }
    val amountError = incomeAmountError(amount)
    val dayError = incomeDayError(day)
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("월급", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "금액(원)",
            value = amount,
            onValueChange = { v -> amount = v.filter(Char::isDigit).take(12) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            error = amountError.takeIf { tried },
        )
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "매월 받는 날",
            value = day,
            onValueChange = { v -> day = v.filter(Char::isDigit).take(2) },
            placeholder = "예: 25",   // 숫자만 두면 들어간 값처럼 보여 비운 채 저장하다 막혔다(S15P31E103-431)
            supporting = "31을 넣으면 매월 말일이에요",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            error = dayError.takeIf { tried },
        )
        if (accounts.size > 1) {
            Spacer(Modifier.height(YamuSpacing.x4))
            Text("받는 계좌", style = YamuTheme.typography.bodyS, color = c.text2)
            accounts.forEach { a ->
                ListRow(
                    title = a.label,
                    value = if (a.accountId == accountId) "선택" else null,
                    valueColor = c.primary,
                    dense = true,
                    onClick = { accountId = a.accountId },
                    modifier = Modifier.semantics { contentDescription = a.label + if (a.accountId == accountId) ", 고름" else "" },
                )
            }
        }
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("저장", onClick = {
            tried = true
            val id = accountId
            if (amountError == null && dayError == null && id != null) onSave(IncomeInput(amount.toLong(), day.toInt(), id))
        })
        if (current != null) {
            Spacer(Modifier.height(YamuSpacing.x2))
            NeutralButton("월급 넣지 않기", onClick = { onSave(null) })
        }
    }
}

/** 월급 금액 — 1원 ~ 1,000억 원(서버 상한) */
internal fun incomeAmountError(text: String): String? {
    val v = text.toLongOrNull()
    return when {
        v == null -> "금액을 넣어 주세요"
        v <= 0 -> "1원 이상 넣어 주세요"
        v > CASHFLOW_AMOUNT_MAX -> "1,000억 원까지 넣을 수 있어요"
        else -> null
    }
}

/** 매월 받는 날 — 1~31(서버 recurrence.dayOfMonth 범위) */
internal fun incomeDayError(text: String): String? =
    if (text.toIntOrNull() in 1..31) null else "1~31 사이로 넣어 주세요"

private const val VISIBLE_LINES = 3
