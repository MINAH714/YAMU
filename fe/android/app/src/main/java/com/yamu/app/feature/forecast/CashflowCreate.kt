package com.yamu.app.feature.forecast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.card.CardImages
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.GreyChip
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.AmountLimit
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.map
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CASHFLOW_TITLE_MAX
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.isMatchable
import com.yamu.app.feature.forecast.data.CashflowField
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.CashflowScope
import com.yamu.app.feature.forecast.data.CashflowUpdateRequest
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.PaymentMatch
import com.yamu.app.feature.forecast.data.cashflowFieldOf
import com.yamu.app.feature.link.SelectRow
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedAccount
import com.yamu.app.feature.link.data.LinkedCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** 범위를 묻는 시트 — 저장인지 삭제인지 */
enum class ScopeAsk { SAVE, DELETE }

data class CashflowCreateState(
    val form: CashflowForm,
    val editing: CashflowEntry? = null,           // 수정 모드면 고칠 회차(F-02b)
    val missing: Boolean = false,                 // 고칠 회차를 찾지 못함(앱이 다시 켜져 목록 기억이 비었을 때)
    val accounts: List<LinkedAccount> = emptyList(),
    val cards: List<LinkedCard> = emptyList(),
    val loadingPayments: Boolean = true,
    val paymentsError: String? = null,
    val errors: Map<CashflowField, String> = emptyMap(),
    val showErrors: Boolean = false,              // 한 번 눌러 본 뒤부터 칸 아래 이유를 보인다(처음부터 빨갛지 않게)
    val movedTo: LocalDate? = null,               // 말일 반복이라 시작일을 옮겼으면 그 날짜 — '…로 옮겼어요'
    val saving: Boolean = false,
    val saveError: String? = null,
    val unknownResult: Boolean = false,           // 보냈는데 응답을 못 받음 — 반영됐는지 모른다
    val gone: Boolean = false,                    // 그사이 바뀌었거나 지워진 항목(404) — 목록으로 돌아가 새로
    val scopeAsk: ScopeAsk? = null,               // 반복 항목 — '이번 회차만 / 이후 전부' 시트
    val confirmDelete: Boolean = false,           // 1회 항목 — 지울지 한 번 더
    val done: Boolean = false,
)

/**
 * F-02a 예정 수입·지출 추가 · F-02b 수정 · 삭제 — 같은 화면, 경로에 항목 ID · 원래 회차가 있으면 수정 모드.
 * 입력 검사는 앱이 먼저, 회차 날짜는 서버가(#20). 출금 · 입금 수단은 연결한 계좌 · 카드(#16)
 */
@HiltViewModel
class CashflowCreateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ForecastRepository,
    private val link: LinkRepository,
) : ViewModel() {
    private val today get() = LocalDate.now(KST)

    // 수정 경로(CashflowEdit)의 값 — 회차는 F-02가 방금 받은 목록에서 찾는다
    private val editKey: Pair<Long, LocalDate>? = savedStateHandle.get<Long>("itemId")?.let { id ->
        savedStateHandle.get<String>("occurrenceDate")?.let { id to LocalDate.parse(it) }
    }
    private val original: CashflowEntry? = editKey?.let { (id, occurrence) -> repo.cachedEntry(id, occurrence) }

    /**
     * 등록 재시도 키(BE -358 !202) — 화면에 들어올 때 하나 만들고 다시 보낼 때도 같은 값. 앱이 다시 켜져도 이 화면이면 같은 키다.
     * 서버가 거절한 실패는 기록이 남지 않아 고쳐서 다시 보내도 같은 키로 새로 만든다
     */
    private val savedState = savedStateHandle
    private var createKey: String
        get() = savedState.get<String>(CREATE_KEY) ?: newIdempotencyKey().also { savedState[CREATE_KEY] = it }
        set(value) { savedState[CREATE_KEY] = value }

    private val _state = MutableStateFlow(
        CashflowCreateState(
            form = original?.toForm() ?: CashflowForm(date = LocalDate.now(KST)),
            editing = original,
            missing = editKey != null && original == null,
        ),
    )
    val state: StateFlow<CashflowCreateState> = _state.asStateFlow()

    init { loadPayments() }

    fun loadPayments() {
        viewModelScope.launch {
            _state.update { it.copy(loadingPayments = true, paymentsError = null) }
            when (val r = link.linkedOverview()) {
                is ApiResult.Success -> _state.update { s ->
                    val o = r.data
                    // 아직 안 골랐으면 주 계좌로(지출 · 수입 모두 받을 수 있는 수단)
                    val picked = s.form.accountId != null || s.form.userCardId != null
                    val form = if (picked) s.form else o.primaryAccount?.let { s.form.withAccount(it.accountId) } ?: s.form
                    s.copy(loadingPayments = false, accounts = o.accounts, cards = o.cards.filterNot { it.issuerBlocked }, form = form)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingPayments = false, paymentsError = r.error.userMessage()) }
            }
        }
    }

    /** 칸 고치기 — 한 번 눌러 본 뒤면 바로 다시 검사해 이유를 바꾼다 */
    fun edit(change: (CashflowForm) -> CashflowForm) = _state.update { s ->
        val form = change(s.form)
        s.copy(form = form, errors = if (s.showErrors) form.validate(today) else emptyMap(), saveError = if (s.unknownResult) s.saveError else null)
    }

    fun setMonthEnd(on: Boolean) {
        val before = _state.value.form.date
        edit { it.withMonthEnd(on) }
        _state.update { s -> s.copy(movedTo = s.form.date.takeIf { on && it != before }) }
    }

    fun setDate(d: LocalDate) {
        edit { it.withDate(d) }
        _state.update { s -> s.copy(movedTo = s.form.date.takeIf { it != d }) }
    }

    fun setEndDate(d: LocalDate?) = edit { it.withEndDate(d) }

    /** 저장 — 추가는 바로, 반복 항목 수정은 범위부터 묻는다 */
    fun save() {
        val s = _state.value
        if (s.saving) return   // 연타 — 등록은 같은 키라 한 번만 들어가지만 수정 · 삭제는 키가 없다
        val errors = s.form.validate(today)
        if (errors.isNotEmpty()) return _state.update { it.copy(showErrors = true, errors = errors) }
        val editing = s.editing
        when {
            editing == null -> submit { repo.createItem(s.form, createKey).map { } }
            editing.isRecurring -> _state.update { it.copy(scopeAsk = ScopeAsk.SAVE) }
            else -> update(CashflowScope.THIS_AND_FUTURE)
        }
    }

    /** 수정 보내기 — 바꾼 칸만(바꾼 게 없으면 그냥 닫는다) */
    fun update(scope: CashflowScope) {
        val s = _state.value
        val editing = s.editing ?: return
        _state.update { it.copy(scopeAsk = null) }
        val body = editing.toPatch(s.form, scope)
        if (body.changesNothing()) return _state.update { it.copy(done = true) }
        submit(sentPayment = body.accountId != null || body.userCardId != null) { repo.updateItem(editing.itemId, body) }
    }

    /** 삭제 — 반복이면 범위를, 1회면 한 번 더 묻는다 */
    fun askDelete() {
        val editing = _state.value.editing ?: return
        _state.update { if (editing.isRecurring) it.copy(scopeAsk = ScopeAsk.DELETE) else it.copy(confirmDelete = true) }
    }

    fun delete(scope: CashflowDeleteScope) {
        val editing = _state.value.editing ?: return
        _state.update { it.copy(scopeAsk = null, confirmDelete = false) }
        submit { repo.deleteItem(editing.itemId, scope, editing.occurrenceDate.takeIf { scope == CashflowDeleteScope.THIS_ONLY }) }
    }

    fun dismissAsk() = _state.update { it.copy(scopeAsk = null, confirmDelete = false) }

    private fun submit(sentPayment: Boolean = false, call: suspend () -> ApiResult<Unit>) {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null, unknownResult = false, gone = false) }
            when (val r = call()) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, done = true) }
                is ApiResult.Failure -> fail(r.error, sentPayment)
            }
        }
    }

    private fun fail(e: ApiError, sentPayment: Boolean) {
        val editing = _state.value.editing != null
        val reasons = e.fieldErrors().mapValues { (_, reason) -> reason.ifBlank { "다시 확인해 주세요" } }
        val fields = reasons.mapNotNull { (field, reason) -> cashflowFieldOf(field)?.let { it to reason } }.toMap()
        val others = reasons.filterKeys { cashflowFieldOf(it) == null }.values   // 원래 회차처럼 화면에 칸이 없는 이유
        _state.update { cur ->
            when {
                // 등록 결과 불명 — 같은 키로 다시 보내면 서버가 한 번만 만든다(BE -358). 자동으로 보내지는 않고 사용자가 누르게 한다
                e.isOutcomeUnknown && !editing -> cur.copy(saving = false, unknownResult = true, saveError = "응답을 받지 못했어요 · 다시 눌러도 한 번만 들어가요")
                // 수정 · 삭제는 키가 없어 반영됐을 수도 있다 — 자동으로 다시 보내지 않고 목록부터 보게 한다
                e.isOutcomeUnknown -> cur.copy(saving = false, unknownResult = true, saveError = "응답을 받지 못해 반영됐는지 확인하지 못했어요 · 목록에서 먼저 확인해 주세요")
                // 결과를 모르는 사이 내용을 고쳐 같은 키로 보냈는데 앞 요청이 이미 들어갔다
                e.code == "IDEMPOTENCY_KEY_REUSED" && !editing -> cur.copy(saving = false, gone = true, saveError = "앞서 보낸 내용이 이미 저장됐어요 · 목록에서 확인해 주세요")
                e.code == "PAID_OCCURRENCE_LOCKED" -> cur.copy(saving = false, saveError = "이미 결제된 회차가 있어 바꿀 수 없어요")
                fields.isNotEmpty() -> cur.copy(saving = false, showErrors = true, errors = fields, saveError = others.firstOrNull())
                others.isNotEmpty() -> cur.copy(saving = false, saveError = others.joinToString(" · "))
                // 수정 중 404 — 서버는 보낸 계좌 · 카드만 확인한다. 안 보냈으면 항목이 그사이 바뀌었거나(이후 전부로 나뉨) 지워졌다
                e is ApiError.NotFound && editing && !sentPayment -> cur.copy(saving = false, gone = true, saveError = "그사이 바뀌었거나 지워진 항목이에요 · 목록에서 다시 골라 주세요")
                // 보냈으면 둘 중 어느 쪽인지 코드로 가릴 수 없어 저장을 막지 않는다
                e is ApiError.NotFound && editing -> cur.copy(saving = false, showErrors = true, errors = mapOf(CashflowField.PAYMENT to "고른 계좌나 카드를 찾을 수 없어요 · 다른 걸 고르거나 목록에서 다시 확인해 주세요"))
                e is ApiError.NotFound -> cur.copy(saving = false, showErrors = true, errors = mapOf(CashflowField.PAYMENT to "고른 계좌나 카드를 찾을 수 없어요 · 다른 걸 골라 주세요"))
                else -> cur.copy(saving = false, saveError = e.userMessage())
            }
        }
        if (e is ApiError.NotFound) {
            // 등록 404는 고른 계좌 · 카드가 없거나, 이 키로 만든 항목을 그사이 지운 것 — 키를 바꿔야 다시 만들 수 있다
            if (!editing) createKey = newIdempotencyKey()
            loadPayments()
        }
    }

    private companion object {
        const val CREATE_KEY = "createIdempotencyKey"
    }
}

/** 수정 화면을 잠그는 까닭 — 지난 회차와 결제와 연결된 회차는 BE requireChangeable이 수정 · 삭제를 거절한다 */
internal enum class EditLock { NONE, PAST, MATCHED }

/**
 * 연결은 결제가 취소된 REVIEW_REQUIRED여도 잠근다(BE는 연결 행이 있는지만 본다). 끊으면 다시 고칠 수 있다.
 * 연결 상태를 아직 못 불렀으면 잠그지 않는다 — 그 사이 저장해도 BE가 409 PAID_OCCURRENCE_LOCKED로 막는다
 */
internal fun editLockOf(editing: CashflowEntry?, today: LocalDate, match: PaymentMatch?): EditLock = when {
    editing == null -> EditLock.NONE
    editing.date.isBefore(today) -> EditLock.PAST
    match != null -> EditLock.MATCHED
    else -> EditLock.NONE
}

/** 날짜 줄 · 끝나는 날 줄의 이름 칸 — '처음 들어오는 날'이 한 줄에 들어가고 두 줄의 값이 같은 자리에서 시작한다 */
private val DATE_LABEL_WIDTH = 104.dp

/** 보낼 값이 하나도 없는 수정 */
private fun CashflowUpdateRequest.changesNothing() =
    title == null && amount == null && date == null && recurrence == null && accountId == null && userCardId == null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashflowCreateRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: CashflowCreateViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onDone() }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }   // 날짜 고르기 — START(시작일) · END(끝나는 날)
    val f = s.form
    val editing = s.editing
    val movedTo = s.movedTo
    val paymentsError = s.paymentsError
    val matchable = editing?.isMatchable == true
    // 결제 연결 상태 — 아래 연결 칸과 같은 ViewModel을 같이 쓴다(카드 예정 지출 회차만 만든다)
    val matchVm = if (matchable) hiltViewModel<PaymentMatchViewModel>() else null
    val match = matchVm?.state?.collectAsStateWithLifecycle()?.value?.match
    // 지난 회차와 결제와 연결된 회차는 서버가 수정 · 삭제를 거절한다 — 내용만 보이고 결제 연결만(지난 회차는 F-02 '지난 7일'에서 연다)
    val lock = editLockOf(editing, LocalDate.now(KST), match)
    val c = YamuTheme.colors
    fun errorOf(field: CashflowField) = s.errors[field].takeIf { s.showErrors }
    FlowScreen(
        title = if (editing != null || s.missing) "예정 수입·지출 수정" else "예정 수입·지출 추가",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        bottomBar = bottomBar@{
            if (s.missing || lock != EditLock.NONE) return@bottomBar
            // 결과 불명 · 사라진 항목이면 목록부터 보게, 수정 중이면 삭제
            val secondary: (@Composable () -> Unit)? = when {
                s.unknownResult || s.gone -> ({ TextButtonGrey("목록에서 확인하기", onClick = onBack, modifier = Modifier.fillMaxWidth()) })
                editing != null -> ({ TextButtonGrey("삭제", onClick = viewModel::askDelete, modifier = Modifier.fillMaxWidth()) })
                else -> null
            }
            BottomCta(secondary = secondary) {
                s.saveError?.let {
                    Text(it, style = YamuTheme.typography.bodyS, color = if (s.unknownResult) c.warning else c.error, modifier = Modifier.padding(bottom = YamuSpacing.x2))
                }
                val label = when {
                    editing != null -> "저장"
                    f.isIncome -> "예정 수입 추가"
                    else -> "예정 지출 추가"
                }
                // 응답을 못 받았으면 반영됐을 수 있다 — 등록은 같은 키라 다시 눌러도 되고 나뉜 규칙의 수정은 400이 나서 목록부터 보게 한다
                PrimaryButton(label, onClick = viewModel::save, loading = s.saving, enabled = !s.gone && !(s.unknownResult && editing != null))
            }
        },
    ) { inner ->
        if (s.missing) {
            // 앱이 다시 켜져 F-02 목록 기억이 비었을 때 — 목록에서 다시 고르게
            EmptyState("고칠 항목을 다시 골라 주세요", modifier = Modifier.padding(inner), actionText = "목록으로", onAction = onBack)
            return@FlowScreen
        }
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(YamuSpacing.x2))
            if (lock != EditLock.NONE && editing != null) {
                val cardName = s.cards.firstOrNull { it.userCardId == editing.userCardId }?.displayName
                if (lock == EditLock.PAST) {
                    PastOccurrenceSummary(editing, cardName)
                } else {
                    PastOccurrenceSummary(editing, cardName, heading = "예정 지출", note = "결제와 연결된 회차예요 · 연결을 끊으면 고칠 수 있어요")
                }
                matchVm?.let { PaymentMatchSection(past = lock == EditLock.PAST, viewModel = it) }
                Spacer(Modifier.height(YamuSpacing.x6))
                return@Column
            }
            if (editing == null) {
                Segmented(listOf("지출", "수입"), selectedIndex = if (f.isIncome) 1 else 0, onSelect = { i -> viewModel.edit { it.withIncome(i == 1) } })
            } else {
                // 수입 · 지출은 서버가 바꾸지 못하게 한다 — 바꾸려면 지우고 새로 추가
                Text(if (f.isIncome) "예정 수입" else "예정 지출", style = YamuTheme.typography.titleM, color = c.text1)
                Text("수입 · 지출은 바꿀 수 없어요 · 바꾸려면 지우고 새로 추가해 주세요", style = YamuTheme.typography.bodyS, color = c.text3)
            }
            Spacer(Modifier.height(YamuSpacing.x4))
            YamuTextField(
                label = "이름",
                value = f.title,
                onValueChange = { v -> viewModel.edit { it.copy(title = v.take(CASHFLOW_TITLE_MAX)) } },
                placeholder = if (f.isIncome) "예: 월급" else "예: 월세",   // '예:'가 없으면 이미 넣은 값처럼 보인다(11/10 QA)
                error = errorOf(CashflowField.TITLE),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(YamuSpacing.x2))
            YamuTextField(
                label = "금액(원)",
                value = f.amount?.toString().orEmpty(),
                onValueChange = { v -> viewModel.edit { it.copy(amount = AmountLimit.CASHFLOW.clampInput(v).toLongOrNull()) } },
                placeholder = "예: 250000",
                supporting = f.amount?.takeIf { it > 0 }?.let { "${it.toWon()}원" },
                error = errorOf(CashflowField.AMOUNT),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )

            // 반복 — 날짜 줄 위에 둔다. 반복을 고르면 바로 아래 날짜 줄이 '처음 나가는 날'이 되고 그 아래에 반복 규칙이 붙는다
            Spacer(Modifier.height(YamuSpacing.x4))
            Text("반복", style = YamuTheme.typography.sub, color = c.text3)
            Spacer(Modifier.height(YamuSpacing.x2))
            Segmented(
                listOf("안 함", "매주", "매월"),
                selectedIndex = f.repeat.ordinal,
                onSelect = { i -> viewModel.edit { it.withRepeat(CashflowRepeat.entries[i]) } },
            )

            // 날짜 — 이름 · 값을 나눈 한 줄(이거 사도 될까? I-01의 날짜 줄과 같은 모양). 날짜를 바꾸는 곳은 여기 한 곳
            Spacer(Modifier.height(YamuSpacing.x2))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.dateLabel(editing = editing != null), style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.width(DATE_LABEL_WIDTH))
                Spacer(Modifier.width(YamuSpacing.x3))
                Text(
                    f.date.toKoreanDateWeek(markToday = true),
                    style = YamuTheme.typography.titleS,
                    color = c.text1,
                    modifier = Modifier.weight(1f),
                )
                LinkButton("바꾸기", onClick = { picking = "START" }, modifier = Modifier.offset(x = 12.dp))
            }
            errorOf(CashflowField.DATE)?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.error) }

            // 반복 규칙 — 날짜 줄에서 정해진다. 매월은 '매월 N일' 칩을 두지 않는다: 날짜를 바꾸는 칩으로 알고 눌렀는데 선택만 됐다(11/10 QA).
            // 말일만 칩으로 따로 고른다. 켜면 날짜를 그달 말일로 옮긴다(서버는 말일 시작이 아니면 31을 거절)
            val flows = if (f.isIncome) "들어와요" else "나가요"
            when (f.repeat) {
                CashflowRepeat.NONE -> Unit
                CashflowRepeat.WEEKLY -> Text("${f.repeatLabel()}마다 $flows", style = YamuTheme.typography.bodyS, color = c.text3)
                CashflowRepeat.MONTHLY -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // 고치는 회차가 짧은 달(2/28)이어도 규칙의 기준일(30)로 보인다
                        Text("${f.repeatLabel(editing?.monthlyDayOn(f) ?: f.date.dayOfMonth)}마다 $flows", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(YamuSpacing.x2))
                        // 켜진 칩을 누르면 끈다. 31일은 끄더라도 말일과 같은 날이라 켜진 채로 보이지만 다음 날짜 고르기부터 말일에 붙지 않는다
                        GreyChip("매월 말일", selected = f.isMonthEnd, onClick = { viewModel.setMonthEnd(!f.isMonthEnd) })
                    }
                    val note = when {
                        movedTo != null -> "말일 반복이라 날짜를 ${movedTo.toKoreanDate()}로 옮겼어요"
                        f.isMonthEnd -> "31일이 없는 달은 그달 마지막 날에 $flows"
                        else -> null
                    }
                    note?.let {
                        Spacer(Modifier.height(YamuSpacing.x2))
                        Text(it, style = YamuTheme.typography.bodyS, color = if (movedTo != null) c.primary else c.text3)
                    }
                }
            }
            // 끝나는 날(선택) — 반복일 때만. 없으면 끝없이
            if (f.repeat != CashflowRepeat.NONE) {
                val end = f.endDate
                Spacer(Modifier.height(YamuSpacing.x2))
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("끝나는 날", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.width(DATE_LABEL_WIDTH))
                    Spacer(Modifier.width(YamuSpacing.x3))
                    Text(end?.toKoreanDate() ?: "없음 · 계속 반복", style = YamuTheme.typography.titleS, color = if (end != null) c.text1 else c.text3, modifier = Modifier.weight(1f))
                    if (end != null) LinkButton("없애기", onClick = { viewModel.setEndDate(null) })
                    LinkButton(if (end != null) "바꾸기" else "정하기", onClick = { picking = "END" }, modifier = Modifier.offset(x = 12.dp))
                }
            }
            errorOf(CashflowField.RECURRENCE)?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.error) }

            // 출금 · 입금 수단 — 계좌 · 카드 중 하나(수입은 계좌만)
            Spacer(Modifier.height(YamuSpacing.x6))
            Text(if (f.isIncome) "들어올 계좌" else "나갈 계좌 · 카드", style = YamuTheme.typography.sub, color = c.text3)
            when {
                s.loadingPayments -> repeat(2) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 48.dp)
                }
                paymentsError != null -> ErrorState(paymentsError, onRetry = viewModel::loadPayments)
                s.accounts.isEmpty() && (f.isIncome || s.cards.isEmpty()) -> EmptyState("연결한 계좌${if (f.isIncome) "" else " · 카드"}가 없어요")
                else -> {
                    s.accounts.forEach { a ->
                        SelectRow(a.brand, a.name, checked = f.accountId == a.accountId, onToggle = { viewModel.edit { it.withAccount(a.accountId) } }, suffix = "·${a.last4}")
                    }
                    if (!f.isIncome) s.cards.forEach { card ->
                        SelectRow(
                            card.brand, card.displayName, checked = f.userCardId == card.userCardId,
                            onToggle = { viewModel.edit { it.withCard(card.userCardId) } }, suffix = "·${card.last4}",
                            cardImage = CardImages.forProduct(card.productName),   // 그림이 없으면 카드사 칩
                        )
                    }
                }
            }
            errorOf(CashflowField.PAYMENT)?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.error) }
            // 카드 예정 지출 회차 — 실제 결제와 연결(-319). 저장된 회차 기준이라 고치는 중인 값과 상관없다
            matchVm?.let { PaymentMatchSection(past = false, viewModel = it) }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }

    picking?.let { target ->
        val forEnd = target == "END"
        // 시작일은 오늘부터(지난 날짜는 서버가 거절), 끝나는 날은 시작일부터
        val minUtc = (if (forEnd) f.date else LocalDate.now(KST)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ((if (forEnd) f.endDate else null) ?: f.date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= minUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (forEnd) viewModel.setEndDate(d) else viewModel.setDate(d)
                    }
                    picking = null
                }) { Text("고르기", style = YamuTheme.typography.labelL, color = c.primary) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("닫기", style = YamuTheme.typography.labelL, color = c.text2) } },
        ) { DatePicker(state = state) }
    }

    // 반복 항목 — 저장 · 삭제할 범위. 반복 규칙을 바꿨으면 서버가 '이번 회차만'을 받지 않아 '이후 전부'만,
    // 옮겨 둔 회차의 원래 날짜가 지났으면 '이후 전부'를 받지 않아 '이번 회차만'
    val ask = s.scopeAsk
    if (ask != null && editing != null) {
        YamuBottomSheet(onDismiss = viewModel::dismissAsk) {
            Text(if (ask == ScopeAsk.SAVE) "어디까지 바꿀까요?" else DELETE_SCOPE_TITLE, style = YamuTheme.typography.titleL, color = c.text1)
            Spacer(Modifier.height(YamuSpacing.x3))
            val day = editing.date.toKoreanDate()
            when (ask) {
                ScopeAsk.SAVE -> {
                    val thisOnly = editing.canEditThisOnly(f)
                    val future = editing.canEditFuture(LocalDate.now(KST))
                    val original = editing.occurrenceDate.toKoreanDate()
                    if (thisOnly) {
                        ListRow("이번 회차만", sub = "${day} 한 번만 바꿔요", chevron = true, onClick = { viewModel.update(CashflowScope.THIS_ONLY) })
                    }
                    if (future) {
                        val sub = if (editing.revertsToOccurrence(f)) "${original}부터 모든 회차를 바꿔요 · 이 회차도 그날로 돌아가요" else "${day}부터 모든 회차를 바꿔요"
                        ListRow("이후 전부", sub = sub, chevron = true, onClick = { viewModel.update(CashflowScope.THIS_AND_FUTURE) })
                    }
                    when {
                        !future -> Text("원래 날짜(${original})가 지나 이후 전부는 바꿀 수 없어요 · 다음 회차에서 바꿔 주세요", style = YamuTheme.typography.bodyS, color = c.text3)
                        !thisOnly -> Text("반복 설정을 바꿔서 이후 전부에만 적용할 수 있어요", style = YamuTheme.typography.bodyS, color = c.text3)
                    }
                }
                ScopeAsk.DELETE -> DeleteScopeRows(editing, onPick = viewModel::delete)   // F-02 휴지통과 같은 줄(CashflowDelete.kt)
            }
            Spacer(Modifier.height(YamuSpacing.x4))
        }
    }
    if (s.confirmDelete && editing != null) {
        DeleteOnceDialog(editing.title, onConfirm = { viewModel.delete(CashflowDeleteScope.ALL) }, onDismiss = viewModel::dismissAsk)
    }
}
