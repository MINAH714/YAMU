package com.yamu.app.feature.payment

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.toCancelProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── P-08 '결제 취소' → 결제 코드 ─────────────────────────
//
// 11/10 QA 결정: 앱은 결제를 바로 취소하지 않는다. 결제 상세의 '결제 취소'는 결제할 때와 같은 코드를 띄우고,
// 매장 POS가 결제 내역에서 고른 결제를 그 코드로 취소한다(코드가 서버 주소를 넘겨주는 역할이라 취소 전용 코드는 없다).
// 임시: 코드 상태 응답(#12)에는 '취소에 쓰였다'는 칸이 없어 그 결제의 상세(#3)를 몇 초마다 다시 받아 취소를 알아챈다.
// 임시: POS가 코드를 찍어 취소하는 경로(BE -424 · POS -425)가 아직 없어 코드 아래에 '앱에서 바로 취소'를 남긴다(#31,
// 예전 결제 상세의 취소와 같다). -424 · -425가 develop에 머지되면 [cancelInApp] · [InAppCancelButton] · 확인 창을 지운다.

/** 코드 아래 안내에 쓰는 결제 — 금액은 실제 결제 금액(즉시할인 뒤) */
data class CancelTarget(val merchantName: String?, val amount: Long)

/** '야무마트 5,940원 결제를 취소하려면 매장에 이 코드를 보여 주세요'. 결제를 아직 못 받았으면 가맹점 · 금액 없이 */
internal fun cancelHint(target: CancelTarget?): String {
    val what = target?.let { listOfNotNull(it.merchantName, "${it.amount.toWon()}원").joinToString(" ", postfix = " ") }.orEmpty()
    return "${what}결제를 취소하려면 매장에 이 코드를 보여 주세요"
}

/** 임시 앱 내 취소의 확인 창 문장 — 전체 취소만 있다(POL-006) */
internal fun cancelNotice(receipt: PaymentReceipt): String {
    val whole = "${receipt.amount.toWon()}원 전체를 취소해요."
    val money = if (receipt.isCheck) {
        "출금된 금액은 연결 계좌로 바로 돌려드려요."
    } else {
        "아직 청구 전이면 청구에서 빠지고 이미 출금된 금액은 3영업일 안에 결제 계좌로 돌려드려요."
    }
    return "$whole $money 이 결제로 쌓인 실적과 혜택 한도도 되돌려요."
}

/** 임시 앱 내 취소 뒤 스낵바 — 체크카드는 입금 뒤 잔액, 신용카드는 돌려받을 금액이나 청구 제외 */
internal fun canceledMessage(receipt: PaymentReceipt, done: CancelResult): String = when {
    receipt.isCheck -> done.balanceAfter?.let { "결제를 취소했어요 · 계좌 잔액 ${it.toWon()}원" } ?: "결제를 취소하고 계좌로 돌려드렸어요"
    done.refunds.isNotEmpty() -> "결제를 취소했어요 · ${done.refunds.sumOf { it.amount }.toWon()}원을 돌려드려요"
    else -> "결제를 취소했어요 · 청구에서 빠져요"
}

/**
 * 취소하러 연 결제 코드 화면이 그 결제의 상태를 지켜본다. 화면이 보이는 동안만 [WATCH_INTERVAL_MS]마다 상세를 받고,
 * 취소가 확인되면 [canceled]가 true가 되고 더 받지 않는다. 받기에 실패하면 다음 차례에 다시 본다.
 * 상세를 받으면 저장소가 취소를 결제 내역 · 결제 완료에도 알린다(PaymentEdits)
 *
 * 임시 앱 내 취소([cancelInApp])의 멱등 키는 처음 누를 때 한 번 만들고 성공할 때까지 다시 쓴다(SavedStateHandle이라 화면이
 * 다시 만들어져도 같다). 그래서 결과를 모르는 실패(응답 끊김) 뒤 다시 눌러도 두 번 취소되지 않는다
 */
@HiltViewModel
class CancelCodeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val payments: PaymentRepository,
) : ViewModel() {
    private val paymentId: Long = checkNotNull(savedStateHandle.get<Long>("paymentId"))   // 경로 CancelCode.paymentId

    private val _receipt = MutableStateFlow<PaymentReceipt?>(null)
    /** 마지막으로 받은 상세 — 임시 앱 내 취소의 확인 창 · 버튼에 쓴다 */
    val receipt: StateFlow<PaymentReceipt?> = _receipt.asStateFlow()

    private val _target = MutableStateFlow<CancelTarget?>(null)
    val target: StateFlow<CancelTarget?> = _target.asStateFlow()

    private val _canceled = MutableStateFlow(false)
    val canceled: StateFlow<Boolean> = _canceled.asStateFlow()

    /** 취소가 확인돼 닫을 때 띄울 문장 — 앱에서 취소했으면 잔액 · 돌려받을 금액까지 */
    private val _closeMessage = MutableStateFlow(POS_CANCELED_MESSAGE)
    val closeMessage: StateFlow<String> = _closeMessage.asStateFlow()

    private val _canceling = MutableStateFlow(false)
    val canceling: StateFlow<Boolean> = _canceling.asStateFlow()

    /** 화면 안 스낵바(앱 내 취소 실패) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var watchJob: Job? = null
    /** 지켜보기와 [checkNow]가 겹쳐 같은 상세를 두 번 받지 않게(viewModelScope는 메인 스레드 하나) */
    private var checking = false

    init { viewModelScope.launch { check() } }   // 안내 문구의 가맹점 · 금액

    fun setVisible(visible: Boolean) {
        watchJob?.cancel()
        watchJob = null
        if (!visible || _canceled.value) return
        watchJob = viewModelScope.launch {
            while (isActive && !_canceled.value) {
                delay(WATCH_INTERVAL_MS)
                check()
            }
        }
    }

    /** 코드가 쓰였거나 시간이 지났을 때 — 다음 차례를 기다리지 않고 바로 본다 */
    fun checkNow() {
        viewModelScope.launch { check() }
    }

    /**
     * 임시(-424 · -425 머지 전까지) — 매장을 거치지 않고 앱이 바로 취소한다. 연타 · 두 번째 확인은 처리 중이면 보내지 않는다.
     * 결과를 모르는 실패면 상세를 다시 받아 취소됐는지 본다
     */
    fun cancelInApp() {
        val r = _receipt.value ?: return
        if (_canceling.value || _canceled.value || !r.cancelable) return
        val key = savedStateHandle.get<String>(CANCEL_KEY) ?: newIdempotencyKey().also { savedStateHandle[CANCEL_KEY] = it }
        _canceling.value = true
        viewModelScope.launch {
            when (val res = payments.cancel(paymentId, key)) {
                is ApiResult.Success -> {
                    _canceling.value = false
                    _closeMessage.value = canceledMessage(r, res.data)
                    _canceled.value = true
                }
                is ApiResult.Failure -> {
                    val problem = res.error.toCancelProblem()
                    _canceling.value = false
                    _message.value = problem.message
                    if (problem.recheck) check()   // 취소됐을 수도 있다 — 상태로 판단
                }
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    /** 앱 내 취소를 보내는 동안은 받지 않는다 — 먼저 취소를 알아채 닫으면 잔액 · 돌려받을 금액 문장을 못 띄운다 */
    private suspend fun check() {
        if (checking || _canceled.value || _canceling.value) return
        checking = true
        try {
            val r = payments.detail(paymentId)
            if (r is ApiResult.Success) {
                _receipt.value = r.data
                _target.value = CancelTarget(r.data.merchantName, r.data.paidAmount)
                if (r.data.canceled) _canceled.value = true
            }
        } finally {
            checking = false
        }
    }

    companion object {
        const val WATCH_INTERVAL_MS = 3_000L
        const val CANCEL_KEY = "cancelIdempotencyKey"
        const val POS_CANCELED_MESSAGE = "결제를 취소했어요"
    }
}

/**
 * 결제 코드 화면(PayCodeRoute)을 그대로 쓰고 제목 · 안내만 바꾼다. 취소가 확인되면 [onClose]로 닫고 '결제를 취소했어요'를 띄운다
 * (상세는 돌아오면 다시 받는다). 가맹점이 결제로 찍어 결제 요청 확인이 떠 있는 동안에는 닫지 않는다
 */
@Composable
fun CancelCodeRoute(
    onClose: (message: String?) -> Unit,
    onPaid: (paymentId: Long) -> Unit,
    onOpenPinPad: (purpose: String) -> Unit,
    onAddCard: () -> Unit,
    viewModel: CancelCodeViewModel = hiltViewModel(),
    payViewModel: PayCodeViewModel = hiltViewModel(),
) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val canceled by viewModel.canceled.collectAsStateWithLifecycle()
    val receipt by viewModel.receipt.collectAsStateWithLifecycle()
    val canceling by viewModel.canceling.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pay by payViewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(text) }
    }
    // 취소에 쓰인 코드는 USED가 되면 '시간이 지났어요'로 바뀐다 — 그 문구가 남지 않게 바로 확인한다
    val codeGone = pay.phase is PayPhase.CodeExpired
    LaunchedEffect(codeGone) { if (codeGone) viewModel.checkNow() }
    val confirming = pay.request != null
    // 닫는 문장은 취소를 알기 전에 정해 두므로 그때의 값을 바로 읽는다
    LaunchedEffect(canceled, confirming) { if (canceled && !confirming) onClose(viewModel.closeMessage.value) }
    PayCodeRoute(
        onClose = onClose,
        onPaid = onPaid,
        onOpenPinPad = onOpenPinPad,
        onAddCard = onAddCard,
        cancelHint = cancelHint(target),
        cancelFooter = {
            if (receipt?.cancelable == true) InAppCancelButton(canceling, onClick = { if (!canceling) confirmCancel = true })
        },
        viewModel = payViewModel,
    )
    val r = receipt
    if (confirmCancel && r != null) {
        YamuDialog(
            title = "결제를 취소할까요?",
            text = cancelNotice(r),
            confirmText = "결제 취소",
            onConfirm = {
                confirmCancel = false
                viewModel.cancelInApp()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

/** 임시 — 매장 POS 취소 경로(-424 · -425)가 생기기 전 QA · 시연용. 되돌리기 어려운 일이라 회색 글자 버튼 → 확인 창 */
@Composable
private fun InAppCancelButton(canceling: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    TextButtonGrey(if (canceling) "취소하는 중…" else "앱에서 바로 취소", onClick = onClick, small = true)
}
