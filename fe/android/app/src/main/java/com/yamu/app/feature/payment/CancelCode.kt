package com.yamu.app.feature.payment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.payment.data.PaymentRepository
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
// 매장 POS가 결제 내역에서 고른 결제를 그 코드로 취소한다(BE -424 · POS -425. 코드가 서버 주소를 넘겨주는 역할이라 취소 전용 코드는 없다).
// 임시: 코드 상태 응답(#12)에는 '취소에 쓰였다'는 칸이 없어 그 결제의 상세(#3)를 몇 초마다 다시 받아 취소를 알아챈다.
// POS 취소가 없던 동안 코드 아래에 두었던 '앱에서 바로 취소'는 -496에서 뺐다. 저장소의 취소 호출(PaymentRepository.cancel)은
// 배포 서버에 POS 취소가 늦게 올라가면 되살릴 수 있게 남겨 둔다.

/** 코드 아래 안내에 쓰는 결제 — 금액은 실제 결제 금액(즉시할인 뒤) */
data class CancelTarget(val merchantName: String?, val amount: Long)

/** '야무마트 5,940원 결제를 취소하려면 매장에 이 코드를 보여 주세요'. 결제를 아직 못 받았으면 가맹점 · 금액 없이 */
internal fun cancelHint(target: CancelTarget?): String {
    val what = target?.let { listOfNotNull(it.merchantName, "${it.amount.toWon()}원").joinToString(" ", postfix = " ") }.orEmpty()
    return "${what}결제를 취소하려면 매장에 이 코드를 보여 주세요"
}

/**
 * 취소하러 연 결제 코드 화면이 그 결제의 상태를 지켜본다. 화면이 보이는 동안만 [WATCH_INTERVAL_MS]마다 상세를 받고,
 * 취소가 확인되면 [canceled]가 true가 되고 더 받지 않는다. 받기에 실패하면 다음 차례에 다시 본다.
 * 상세를 받으면 저장소가 취소를 결제 내역 · 결제 완료에도 알린다(PaymentEdits)
 */
@HiltViewModel
class CancelCodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val payments: PaymentRepository,
) : ViewModel() {
    private val paymentId: Long = checkNotNull(savedStateHandle.get<Long>("paymentId"))   // 경로 CancelCode.paymentId

    private val _target = MutableStateFlow<CancelTarget?>(null)
    val target: StateFlow<CancelTarget?> = _target.asStateFlow()

    private val _canceled = MutableStateFlow(false)
    val canceled: StateFlow<Boolean> = _canceled.asStateFlow()

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

    private suspend fun check() {
        if (checking || _canceled.value) return
        checking = true
        try {
            val r = payments.detail(paymentId)
            if (r is ApiResult.Success) {
                _target.value = CancelTarget(r.data.merchantName, r.data.paidAmount)
                if (r.data.canceled) _canceled.value = true
            }
        } finally {
            checking = false
        }
    }

    companion object {
        const val WATCH_INTERVAL_MS = 3_000L
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
    val pay by payViewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    // 취소에 쓰인 코드는 USED가 되면 '시간이 지났어요'로 바뀐다 — 그 문구가 남지 않게 바로 확인한다
    val codeGone = pay.phase is PayPhase.CodeExpired
    LaunchedEffect(codeGone) { if (codeGone) viewModel.checkNow() }
    val confirming = pay.request != null
    LaunchedEffect(canceled, confirming) { if (canceled && !confirming) onClose(CancelCodeViewModel.POS_CANCELED_MESSAGE) }
    PayCodeRoute(
        onClose = onClose,
        onPaid = onPaid,
        onOpenPinPad = onOpenPinPad,
        onAddCard = onAddCard,
        cancelHint = cancelHint(target),
        viewModel = payViewModel,
    )
}
