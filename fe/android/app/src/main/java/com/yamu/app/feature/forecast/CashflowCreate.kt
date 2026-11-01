package com.yamu.app.feature.forecast

import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.GreyChip
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CASHFLOW_TITLE_MAX
import com.yamu.app.feature.forecast.data.CashflowField
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowRepeat
import com.yamu.app.feature.forecast.data.ForecastRepository
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
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class CashflowCreateState(
    val form: CashflowForm,
    val accounts: List<LinkedAccount> = emptyList(),
    val cards: List<LinkedCard> = emptyList(),
    val loadingPayments: Boolean = true,
    val paymentsError: String? = null,
    val errors: Map<CashflowField, String> = emptyMap(),
    val showErrors: Boolean = false,              // 한 번 눌러 본 뒤부터 칸 아래 이유를 보인다(처음부터 빨갛지 않게)
    val movedTo: LocalDate? = null,               // 말일 반복이라 시작일을 옮겼으면 그 날짜 — '…로 옮겼어요'
    val saving: Boolean = false,
    val saveError: String? = null,
    val unknownResult: Boolean = false,           // 보냈는데 응답을 못 받음 — 등록됐는지 모른다
    val done: Boolean = false,
)

/** F-02a 예정 수입·지출 추가 — 입력 검사는 앱이 먼저, 회차 날짜는 서버가(#20). 출금 · 입금 수단은 연결한 계좌 · 카드(#16) */
@HiltViewModel
class CashflowCreateViewModel @Inject constructor(
    private val repo: ForecastRepository,
    private val link: LinkRepository,
) : ViewModel() {
    private val today get() = LocalDate.now(KST)
    private val _state = MutableStateFlow(CashflowCreateState(form = CashflowForm(date = LocalDate.now(KST))))
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
                    s.copy(loadingPayments = false, accounts = o.accounts, cards = o.cards, form = form)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingPayments = false, paymentsError = r.error.userMessage()) }
            }
        }
    }

    /** 칸 고치기 — 한 번 눌러 본 뒤면 바로 다시 검사해 이유를 바꾼다 */
    fun edit(change: (CashflowForm) -> CashflowForm) = _state.update { s ->
        val form = change(s.form)
        s.copy(form = form, errors = if (s.showErrors) form.validate(today) else emptyMap(), saveError = null, unknownResult = false)
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

    fun save() {
        val s = _state.value
        if (s.saving) return   // 연타 — 서버에 재시도 키가 없어 두 번 가면 두 건이 생긴다
        val errors = s.form.validate(today)
        if (errors.isNotEmpty()) return _state.update { it.copy(showErrors = true, errors = errors) }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null, unknownResult = false) }
            when (val r = repo.createItem(s.form)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, done = true) }
                is ApiResult.Failure -> {
                    val e = r.error
                    val fields = e.fieldErrors().mapNotNull { (field, reason) -> cashflowFieldOf(field)?.let { it to reason.ifBlank { "다시 확인해 주세요" } } }.toMap()
                    _state.update { cur ->
                        when {
                            // 실패가 아니라 결과 불명 — 등록됐을 수도 있어 자동으로 다시 보내지 않고 목록부터 보게 한다
                            e == ApiError.Network -> cur.copy(saving = false, unknownResult = true, saveError = "연결이 끊겨 등록됐는지 확인하지 못했어요 · 목록에서 먼저 확인해 주세요")
                            fields.isNotEmpty() -> cur.copy(saving = false, showErrors = true, errors = fields)
                            // 그사이 끊긴 계좌 · 카드 — 목록을 다시 받아 다른 걸 고르게
                            e is ApiError.NotFound -> cur.copy(saving = false, showErrors = true, errors = mapOf(CashflowField.PAYMENT to "고른 계좌나 카드를 찾을 수 없어요 · 다른 걸 골라 주세요"))
                            else -> cur.copy(saving = false, saveError = e.userMessage())
                        }
                    }
                    if (e is ApiError.NotFound) loadPayments()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashflowCreateRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: CashflowCreateViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onDone() }
    var picking by rememberSaveable { mutableStateOf(false) }
    val f = s.form
    val movedTo = s.movedTo
    val paymentsError = s.paymentsError
    val c = YamuTheme.colors
    fun errorOf(field: CashflowField) = s.errors[field].takeIf { s.showErrors }
    FlowScreen(
        title = "예정 수입·지출 추가",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        bottomBar = {
            BottomCta(secondary = if (s.unknownResult) ({ TextButtonGrey("목록에서 확인하기", onClick = onBack, modifier = Modifier.fillMaxWidth()) }) else null) {
                s.saveError?.let {
                    Text(it, style = YamuTheme.typography.bodyS, color = if (s.unknownResult) c.warning else c.error, modifier = Modifier.padding(bottom = YamuSpacing.x2))
                }
                PrimaryButton(if (f.isIncome) "예정 수입 추가" else "예정 지출 추가", onClick = viewModel::save, loading = s.saving)
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(YamuSpacing.x2))
            Segmented(listOf("지출", "수입"), selectedIndex = if (f.isIncome) 1 else 0, onSelect = { i -> viewModel.edit { it.withIncome(i == 1) } })
            Spacer(Modifier.height(YamuSpacing.x4))
            YamuTextField(
                label = "이름",
                value = f.title,
                onValueChange = { v -> viewModel.edit { it.copy(title = v.take(CASHFLOW_TITLE_MAX)) } },
                placeholder = if (f.isIncome) "월급" else "월세",
                error = errorOf(CashflowField.TITLE),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(YamuSpacing.x2))
            YamuTextField(
                label = "금액(원)",
                value = f.amount?.toString().orEmpty(),
                onValueChange = { v -> viewModel.edit { it.copy(amount = v.filter(Char::isDigit).take(12).toLongOrNull()) } },
                placeholder = "250000",
                supporting = f.amount?.takeIf { it > 0 }?.let { "${it.toWon()}원" },
                error = errorOf(CashflowField.AMOUNT),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )

            // 시작일 — 이름 · 값을 나눈 한 줄(이거 사도 될까? I-01의 날짜 줄과 같은 모양)
            Spacer(Modifier.height(YamuSpacing.x2))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (f.repeat == CashflowRepeat.NONE) "날짜" else "시작일", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.width(48.dp))
                Spacer(Modifier.width(YamuSpacing.x3))
                Text(
                    "${f.date.toKoreanDate()} (${f.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})${if (f.date == LocalDate.now(KST)) " · 오늘" else ""}",
                    style = YamuTheme.typography.titleS,
                    color = c.text1,
                    modifier = Modifier.weight(1f),
                )
                LinkButton("바꾸기", onClick = { picking = true }, modifier = Modifier.offset(x = 12.dp))
            }
            errorOf(CashflowField.DATE)?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.error) }

            // 반복 — 매월이면 'N일' · '말일'. 말일을 고르면 시작일을 그달 말일로 옮긴다(서버는 말일 시작이 아니면 31을 거절)
            Spacer(Modifier.height(YamuSpacing.x4))
            Text("반복", style = YamuTheme.typography.sub, color = c.text3)
            Spacer(Modifier.height(YamuSpacing.x2))
            Segmented(
                listOf("안 함", "매주", "매월"),
                selectedIndex = f.repeat.ordinal,
                onSelect = { i -> viewModel.edit { it.withRepeat(CashflowRepeat.entries[i]) } },
            )
            when (f.repeat) {
                CashflowRepeat.NONE -> Unit
                CashflowRepeat.WEEKLY -> {
                    Spacer(Modifier.height(YamuSpacing.x2))
                    Text("${f.repeatLabel()}마다 ${if (f.isIncome) "들어와요" else "나가요"}", style = YamuTheme.typography.bodyS, color = c.text3)
                }
                CashflowRepeat.MONTHLY -> {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    Row(horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
                        // 31일에 시작하면 'N일'이 곧 말일이라 칩은 하나
                        if (f.date.dayOfMonth < 31) GreyChip("매월 ${f.date.dayOfMonth}일", selected = !f.isMonthEnd, onClick = { viewModel.setMonthEnd(false) })
                        GreyChip("매월 말일", selected = f.isMonthEnd, onClick = { viewModel.setMonthEnd(true) })
                    }
                    Spacer(Modifier.height(YamuSpacing.x2))
                    val note = when {
                        movedTo != null -> "말일 반복이라 시작일을 ${movedTo.toKoreanDate()}로 옮겼어요"
                        f.isMonthEnd -> "31일이 없는 달은 그달 마지막 날에 ${if (f.isIncome) "들어와요" else "나가요"}"
                        else -> null
                    }
                    note?.let { Text(it, style = YamuTheme.typography.bodyS, color = if (movedTo != null) c.primary else c.text3) }
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
                        SelectRow(card.brand, card.productName, checked = f.userCardId == card.userCardId, onToggle = { viewModel.edit { it.withCard(card.userCardId) } }, suffix = "·${card.last4}")
                    }
                }
            }
            errorOf(CashflowField.PAYMENT)?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.error) }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }

    if (picking) {
        val todayUtc = LocalDate.now(KST).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = f.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc   // 오늘부터(지난 날짜는 서버가 거절)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { viewModel.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("고르기", style = YamuTheme.typography.labelL, color = c.primary) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("닫기", style = YamuTheme.typography.labelL, color = c.text2) } },
        ) { DatePicker(state = state) }
    }
}
