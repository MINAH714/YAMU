package com.yamu.app.feature.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.billing.data.BillDetail
import com.yamu.app.feature.billing.data.BillState
import com.yamu.app.feature.billing.data.BillingRepository
import com.yamu.app.feature.billing.data.UsageState
import com.yamu.app.feature.billing.data.koreanDay
import com.yamu.app.feature.billing.data.refundStateLabel
import com.yamu.app.feature.billing.data.statusLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// M-09b 청구서 상세 — GET /billings/{billId}. 머리(금액 · 상태) → 출금 → 금액 → 청구할인 → 이용 내역 → 환급.
// 이용 내역 줄은 결제 상세(P-08)로. 출금 실패 사유는 BE가 아직 남기지 않아 보여 주지 않는다

data class BillDetailState(
    val loading: Boolean = true,
    val detail: BillDetail? = null,
    val error: String? = null,
    val gone: Boolean = false,          // 없는 청구서 · 남의 청구서(BE는 둘 다 404)
    val message: String? = null,
)

@HiltViewModel
class BillDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BillingRepository,
) : ViewModel() {
    private val billId: Long = checkNotNull(savedStateHandle.get<Long>("billId"))

    private val _state = MutableStateFlow(BillDetailState())
    val state: StateFlow<BillDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /** 화면에 돌아올 때마다 — 결제 상세에서 취소하고 돌아오면 회차 · 환급이 바뀌어 있다 */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val r = repo.bill(billId)
            _state.update { s ->
                when (r) {
                    is ApiResult.Success -> s.copy(loading = false, detail = r.data, gone = false)
                    is ApiResult.Failure -> when {
                        // 남의 청구서와 없는 청구서를 가르지 않고, 앞서 본 값도 지운다
                        r.error is ApiError.NotFound -> s.copy(loading = false, detail = null, gone = true)
                        s.detail != null -> s.copy(loading = false, message = "새로 불러오지 못했어요 · ${r.error.userMessage()}")
                        else -> s.copy(loading = false, error = r.error.userMessage())
                    }
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun BillDetailRoute(
    onBack: () -> Unit,
    onPayment: (paymentId: Long) -> Unit,
    viewModel: BillDetailViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    val d = s.detail
    FlowScreen(title = d?.bill?.billingMonth?.let { "${it.monthValue}월 청구서" } ?: "청구서", onBack = onBack, cards = true) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = YamuSpacing.x2),
        ) {
            when {
                s.gone -> YamuCard { EmptyState("찾을 수 없는 청구서예요", actionText = "돌아가기", onAction = onBack) }
                d == null && s.loading -> {
                    YamuCard {
                        SkeletonBlock(height = 18.dp, widthFraction = 0.5f)
                        Spacer(Modifier.height(YamuSpacing.x2))
                        SkeletonBlock(height = 38.dp, widthFraction = 0.4f)
                    }
                    YamuCard { repeat(4) { SkeletonBlock(height = 40.dp); Spacer(Modifier.height(YamuSpacing.x2)) } }
                }
                d == null -> YamuCard { ErrorState(s.error ?: "청구서를 불러오지 못했어요", onRetry = viewModel::load) }
                else -> BillDetailBody(d, onPayment)
            }
            Spacer(Modifier.height(YamuSpacing.bottomSafe))
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun BillDetailBody(d: BillDetail, onPayment: (Long) -> Unit) {
    val c = YamuTheme.colors
    val b = d.bill

    YamuCard {
        Text(listOfNotNull(b.cardName, d.cardLast4?.let { "·$it" }).joinToString(" "), style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(YamuSpacing.x2))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AmountText(b.amount)
            if (b.isEstimate) EstimateTag(text = "예상")
        }
        b.originalAmount?.let { Text("원래 청구액 ${it.toWon()}원", style = YamuTheme.typography.bodyS, color = c.text3) }
        Spacer(Modifier.height(YamuSpacing.x3))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
            StatusTag(b.state.label, tone = b.state.tone)
            b.statusLine()?.let { Text(it, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.weight(1f)) }
        }
        if (b.state == BillState.UNPAID) {
            Spacer(Modifier.height(YamuSpacing.x2))
            Text("미납이 풀릴 때까지 이 카드는 결제 카드로 고르지 않아요", style = YamuTheme.typography.bodyM, color = c.warning)
        }
    }

    YamuCard {
        CardTitle("출금")
        // 출금을 시도한 뒤(완료 · 미납 · 정산 끝)에는 '예정'이 아니다
        val attempted = b.state == BillState.PAID || b.state == BillState.UNPAID || b.state == BillState.CANCELED
        KeyValueRow(if (attempted) "출금일" else "출금 예정일", b.withdrawalDate?.koreanDay() ?: "확인할 수 없어요", large = false)
        if (b.withdrawalShifted) {
            Text("결제일 ${b.paymentDate!!.koreanDay()}이 휴일이라 다음 영업일에 출금해요", style = YamuTheme.typography.bodyS, color = c.text3)
        }
        if (b.state == BillState.UNPAID) {
            d.withdrawalAttemptedAt?.let { KeyValueRow("출금 시도", "${it.monthValue}월 ${it.dayOfMonth}일 ${it.format(HM)}", large = false) }
        }
        KeyValueRow("출금 계좌", b.account ?: "연결된 계좌가 없어요", large = false)
        KeyValueRow(
            "이용기간",
            if (b.usageFrom != null && b.usageTo != null) "${b.usageFrom.toMonthDay()} ~ ${b.usageTo.toMonthDay()}" else "확인할 수 없어요",
            large = false,
        )
    }

    YamuCard {
        CardTitle("금액")
        KeyValueRow("이용 금액", "${b.itemAmount.toWon()}원", large = false)
        if (b.billingDiscount != 0L) {
            // 혜택이 하나면 이름을 이 줄 아래에, 둘 이상이면 아래 카드에 나눠 적는다
            KeyValueRow(
                if (d.discountsEstimated || b.isEstimate) "청구할인(예상)" else "청구할인",
                "−${b.billingDiscount.toWon()}원",
                large = false,
                keySub = d.discounts.singleOrNull()?.name,
            )
        }
        b.expectedAmount?.let { KeyValueRow("예상 청구액", "${it.toWon()}원", large = false) }
        b.finalAmount?.let { KeyValueRow("청구액", "${it.toWon()}원", large = false) }
        b.unpaidAmount?.let { KeyValueRow("남은 미납액", "${it.toWon()}원", large = false, valueColor = if (it > 0) c.error else c.text1) }
    }

    if (d.discounts.size > 1) {
        YamuCard {
            CardTitle(if (d.discountsEstimated) "청구할인(예상)" else "청구할인")
            d.discounts.forEach { KeyValueRow(it.name, "−${it.amount.toWon()}원", large = false) }
        }
    }

    // 줄을 카드 끝까지 긋는 목록 카드 — 좌우 0(H-03)
    YamuCard(contentPadding = PaddingValues(vertical = YamuSpacing.x3)) {
        CardTitle("이용 내역 ${d.usages.size}건", Modifier.padding(horizontal = YamuSpacing.x5))
        if (d.usages.isEmpty()) {
            Text("이용 내역이 없어요", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(horizontal = YamuSpacing.x5, vertical = YamuSpacing.x2))
        }
        d.usages.forEachIndexed { i, u ->
            BillingRow(
                title = u.merchant,
                line = listOfNotNull(u.approvedOn?.toMonthDay(), u.planLabel).joinToString(" · "),
                amount = "${u.amount.toWon()}원",
                tag = u.state.tag,
                dimmed = u.state == UsageState.EXCLUDED,
                divider = i > 0,
                horizontalPadding = YamuSpacing.x5,
                onClick = u.paymentId?.let { id -> { onPayment(id) } },
            )
        }
    }

    if (d.refunds.isNotEmpty()) {
        YamuCard(contentPadding = PaddingValues(vertical = YamuSpacing.x3)) {
            CardTitle("환급", Modifier.padding(horizontal = YamuSpacing.x5))
            d.refunds.forEachIndexed { i, r ->
                BillingRow(
                    title = r.merchant?.let { "$it 환급" } ?: "결제 취소 환급",
                    line = null,
                    amount = "${r.amount.toWon()}원",
                    tag = refundStateLabel(r.state),
                    tagTone = r.state.tone,
                    divider = i > 0,
                    horizontalPadding = YamuSpacing.x5,
                    onClick = null,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = YamuTheme.typography.titleS, color = YamuTheme.colors.text1, modifier = modifier.padding(bottom = YamuSpacing.x2))
}
