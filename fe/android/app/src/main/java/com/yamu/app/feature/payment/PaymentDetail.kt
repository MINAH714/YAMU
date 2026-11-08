package com.yamu.app.feature.payment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BrandMark
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.MemoRules
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.toCancelProblem
import com.yamu.app.feature.payment.data.toMemoProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── P-08 거래 상세 · 메모 · 결제 취소 ─────────────────────────

data class PaymentDetailState(
    val loading: Boolean = true,
    val receipt: PaymentReceipt? = null,
    val error: String? = null,
    val canceling: Boolean = false,
    /** 체크카드 환불 입금 뒤 잔액 — 취소 응답에만 있다 */
    val balanceAfter: Long? = null,
    val memoOpen: Boolean = false,
    val savingMemo: Boolean = false,
    val memoError: String? = null,
    /** 스낵바 한 번 */
    val message: String? = null,
)

/**
 * 결제 상세(#3) · 메모(#42) · 전체 취소(#31).
 *
 * 취소 멱등 키는 이 화면에서 처음 누를 때 한 번 만들고 성공할 때까지 다시 쓴다(SavedStateHandle이라 화면이 다시 만들어져도 같다).
 * BE는 끝난 요청의 응답만 기억하고 실패는 기억하지 않아서, 같은 키로 다시 보내면 처음 결과를 받거나 새로 처리된다.
 * 그래서 결과를 모르는 실패(응답 끊김) 뒤 다시 눌러도 두 번 취소되지 않는다.
 */
@HiltViewModel
class PaymentDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val payments: PaymentRepository,
) : ViewModel() {
    private val paymentId: Long = checkNotNull(savedStateHandle.get<Long>("paymentId"))   // 경로 PaymentDetail.paymentId
    private val _state = MutableStateFlow(PaymentDetailState())
    val state: StateFlow<PaymentDetailState> = _state.asStateFlow()

    init { load() }

    /** 다시 불러오는 동안에도 보던 내용은 그대로 둔다(실패해도 마지막 값) */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = payments.detail(paymentId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, receipt = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun cancel() {
        val s = _state.value
        val receipt = s.receipt ?: return
        if (s.canceling || !receipt.cancelable) return   // 연타 · 두 번째 확인 — 처리 중이면 보내지 않는다
        val key = savedStateHandle.get<String>(CANCEL_KEY) ?: newIdempotencyKey().also { savedStateHandle[CANCEL_KEY] = it }
        _state.update { it.copy(canceling = true) }
        viewModelScope.launch {
            when (val res = payments.cancel(paymentId, key)) {
                is ApiResult.Success -> {
                    val done = res.data
                    _state.update {
                        it.copy(
                            canceling = false,
                            balanceAfter = done.balanceAfter,
                            receipt = it.receipt?.let { r ->
                                r.copy(status = PayStatus.CANCELED, canceledAtIso = done.canceledAtIso ?: r.canceledAtIso, refunds = done.refunds)
                            },
                            message = canceledMessage(receipt, done),
                        )
                    }
                    load()   // 환급 · 청구 회차를 서버 값으로
                }
                is ApiResult.Failure -> {
                    val problem = res.error.toCancelProblem()
                    _state.update { it.copy(canceling = false, message = problem.message) }
                    if (problem.recheck) load()   // 취소됐을 수도 있다 — 상태로 판단
                }
            }
        }
    }

    fun openMemo() = _state.update { it.copy(memoOpen = true, memoError = null) }

    fun closeMemo() = _state.update { it.copy(memoOpen = false, memoError = null) }

    fun memoEdited() {
        if (_state.value.memoError != null) _state.update { it.copy(memoError = null) }
    }

    /** [raw]가 비었거나 공백뿐이면 지운다. 앱에서 먼저 BE와 같은 규칙으로 세서 넘치면 보내지 않는다 */
    fun saveMemo(raw: String) {
        if (_state.value.savingMemo) return
        MemoRules.problem(raw)?.let { problem ->
            _state.update { it.copy(memoError = problem) }
            return
        }
        val text = MemoRules.normalize(raw).ifEmpty { null }
        _state.update { it.copy(savingMemo = true, memoError = null) }
        viewModelScope.launch {
            when (val res = payments.saveMemo(paymentId, text)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        savingMemo = false,
                        memoOpen = false,
                        receipt = it.receipt?.copy(memo = res.data),
                        message = if (res.data == null) "메모를 지웠어요" else "메모를 남겼어요",
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(savingMemo = false, memoError = res.error.toMemoProblem()) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        const val CANCEL_KEY = "cancelIdempotencyKey"
    }
}

/** 취소 뒤 스낵바 — 체크카드는 입금 뒤 잔액, 신용카드는 돌려받을 금액이나 청구 제외 */
internal fun canceledMessage(receipt: PaymentReceipt, done: CancelResult): String = when {
    receipt.isCheck -> done.balanceAfter?.let { "결제를 취소했어요 · 계좌 잔액 ${it.toWon()}원" } ?: "결제를 취소하고 계좌로 돌려드렸어요"
    done.refunds.isNotEmpty() -> "결제를 취소했어요 · ${done.refunds.sumOf { it.amount }.toWon()}원을 돌려드려요"
    else -> "결제를 취소했어요 · 청구에서 빠져요"
}

/** 취소 확인 다이얼로그 문장 — 전체 취소만 있다(POL-006) */
internal fun cancelNotice(receipt: PaymentReceipt): String {
    val whole = "${receipt.amount.toWon()}원 전체를 취소해요."
    val money = if (receipt.isCheck) {
        "출금된 금액은 연결 계좌로 바로 돌려드려요."
    } else {
        "아직 청구 전이면 청구에서 빠지고 이미 출금된 금액은 3영업일 안에 결제 계좌로 돌려드려요."
    }
    return "$whole $money 이 결제로 쌓인 실적과 혜택 한도도 되돌려요."
}

@Composable
fun PaymentDetailRoute(onBack: () -> Unit, viewModel: PaymentDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val r = s.receipt
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }

    FlowScreen(title = "거래 상세", onBack = onBack, actions = { MockPayTag() }) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                r == null && s.loading -> Column(Modifier.padding(YamuSpacing.screen)) {
                    repeat(4) {
                        SkeletonBlock(height = 44.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                r == null -> ErrorState(s.error ?: "결제 내역을 불러오지 못했어요", onRetry = viewModel::load, modifier = Modifier.padding(YamuSpacing.screen))
                else -> {
                    // 가맹점 · 일시 → 금액 → 결과 한 줄('1,500원 할인받고 13,500원 청구 예정') — 위 12 · 사이 6(프로토타입 P-08 .hd)
                    Column(Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 12.dp, bottom = 24.dp)) {
                        Text(listOfNotNull(r.merchantName, formatPaidAt(r.paidAtIso)).joinToString(" · "), style = YamuTheme.typography.bodyL, color = c.text2)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AmountText(r.amount)
                            if (r.canceled) StatusTag("취소")
                        }
                        val benefit = r.benefits.sumOf { it.amount }
                        if (!r.canceled && benefit > 0 && r.billAmount != null && !r.isCheck) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                buildAnnotatedString {
                                    append("${benefit.toWon()}원 할인받고 ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.text1)) { append("${r.billAmount.toWon()}원") }
                                    append(" 청구 예정")
                                },
                                style = YamuTheme.typography.bodyM,
                                color = c.text2,
                            )
                        }
                    }
                    HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(horizontal = YamuSpacing.screen))
                    Column(Modifier.padding(horizontal = YamuSpacing.screen, vertical = 12.dp)) {
                        KeyValueRow(
                            "결제 카드",
                            listOfNotNull(r.cardName, r.cardLast4?.let { "•••• $it" }).joinToString(" "),
                            large = false,
                            valueLead = r.cardBrand?.let { b -> { BrandMark(b) } },
                        )
                        r.approvalNo?.let { KeyValueRow("승인번호", it, large = false) }
                        MemoRow(r.memo, onClick = viewModel::openMemo)
                    }
                    HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(horizontal = YamuSpacing.screen))
                    if (r.canceled) {
                        CanceledLines(r, s.balanceAfter)
                    } else {
                        BenefitLines(r)
                    }
                    if (r.cancelable) {
                        // 되돌리기 어려운 일이라 주 버튼 대신 아래 회색 글자 버튼 → 확인 다이얼로그
                        TextButtonGrey(
                            if (s.canceling) "취소하는 중…" else "결제 취소",
                            onClick = { if (!s.canceling) confirmCancel = true },
                            small = true,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }

    if (confirmCancel && r != null) {
        YamuDialog(
            title = "결제를 취소할까요?",
            text = cancelNotice(r),
            confirmText = "결제 취소",
            onConfirm = {
                confirmCancel = false
                viewModel.cancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
    if (s.memoOpen && r != null) {
        MemoSheet(
            initial = r.memo,
            saving = s.savingMemo,
            serverError = s.memoError,
            onEdit = viewModel::memoEdited,
            onSave = viewModel::saveMemo,
            onDismiss = viewModel::closeMemo,
        )
    }
}

/**
 * 메모 줄 — 나만 보는 한 줄이고 취소된 결제에도 남길 수 있다. 100자까지라 KeyValueRow 대신 값이 남은 폭을 쓰고 두 줄에서 자른다
 * (KeyValueRow는 값이 길면 키 칸이 눌린다). 높이 · 글자는 상세의 작은 키–값 줄(40dp · bodyM / titleS)과 같다
 */
@Composable
private fun MemoRow(memo: String?, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = if (memo == null) "메모 남기기" else "메모 고치기", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("메모", style = YamuTheme.typography.bodyM, color = c.text2)
        Text(
            memo ?: "메모 남기기",
            style = YamuTheme.typography.titleS,
            color = if (memo == null) c.primary else c.text1,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
    }
}

/** 혜택 · 실적(추정치) — 승인 상태의 결제 */
@Composable
private fun BenefitLines(r: PaymentReceipt) {
    val c = YamuTheme.colors
    Column(Modifier.padding(horizontal = YamuSpacing.screen, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Text("혜택 · 실적", style = YamuTheme.typography.titleM, color = c.text1)
            EstimateTag(text = "추정치")
        }
        r.benefits.forEach { b -> KeyValueRow(b.name, "−${b.amount.toWon()}원", large = false, valueColor = c.primary) }
        if (r.perfRecognized != null) {
            val card = r.cardBrand?.name ?: "카드"
            val month = r.perfMonth?.let { "${it}월 " }.orEmpty()
            val total = r.perfTotalAfter
            val target = r.perfTarget
            KeyValueRow(
                "$card ${month}실적",
                when {
                    !r.perfCounted -> "이 결제는 빠져요"
                    total != null && target != null -> "${total.toWon()} / ${target.toWon()}원"
                    else -> "+${r.perfRecognized.toWon()}원"
                },
                large = false,
            )
        }
    }
}

/** 취소된 결제 — 취소 시각 · 돌려받는 돈. 받지 않은 혜택 · 빠진 실적은 줄로 늘어놓지 않고 한 줄로 */
@Composable
private fun CanceledLines(r: PaymentReceipt, balanceAfter: Long?) {
    val c = YamuTheme.colors
    Column(Modifier.padding(horizontal = YamuSpacing.screen, vertical = 12.dp)) {
        Text("취소", style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.padding(vertical = 8.dp))
        formatPaidAt(r.canceledAtIso)?.let { KeyValueRow("취소한 때", it, large = false) }
        when {
            r.isCheck -> {
                r.chargedAmount?.let { KeyValueRow("계좌로 돌려받음", "${it.toWon()}원", large = false) }
                balanceAfter?.let { KeyValueRow("남은 잔액", "${it.toWon()}원", large = false) }
            }
            r.refunds.isEmpty() -> KeyValueRow("청구", "청구에서 빠져요", large = false)
            else -> r.refunds.forEach { f ->
                KeyValueRow(
                    if (f.completed) "돌려받음" else "돌려받을 금액",
                    "${f.amount.toWon()}원",
                    large = false,
                    keySub = if (f.completed) null else formatDay(f.dueDateIso)?.let { "${it}까지 결제 계좌로" } ?: "출금 뒤 3영업일 안에 결제 계좌로",
                )
            }
        }
        Text(
            "이 결제로 쌓인 실적과 혜택 한도는 되돌렸어요",
            style = YamuTheme.typography.bodyS,
            color = c.text3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 메모 입력 — 한 줄 100자(코드포인트). 저장할 수 없으면 칸 아래 빨간 줄 · 저장 버튼 막음 */
@Composable
private fun MemoSheet(
    initial: String?,
    saving: Boolean,
    serverError: String?,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = YamuTheme.colors
    var text by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    val count = MemoRules.length(MemoRules.normalize(text))
    val problem = MemoRules.problem(text)
    val changed = MemoRules.normalize(text) != initial.orEmpty()
    val canSave = problem == null && changed && !saving
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("메모", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(4.dp))
        Text("나만 볼 수 있고 소비분석 · 추천에는 쓰지 않아요", style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "메모",
            value = text,
            onValueChange = {
                text = it
                onEdit()
            },
            placeholder = "예: 팀 회식 1/N 정산 예정",
            supporting = "$count/${MemoRules.MAX_LENGTH}자",
            error = (problem ?: serverError)?.let { "$it · $count/${MemoRules.MAX_LENGTH}자" },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(text) }),
            enabled = !saving,
        )
        Spacer(Modifier.height(YamuSpacing.x5))
        Row(horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
            if (!initial.isNullOrEmpty()) {
                NeutralButton("지우기", onClick = { onSave("") }, enabled = !saving, modifier = Modifier.weight(1f))
            }
            PrimaryButton("저장", onClick = { onSave(text) }, enabled = canSave, loading = saving, modifier = Modifier.weight(1f))
        }
    }
}
