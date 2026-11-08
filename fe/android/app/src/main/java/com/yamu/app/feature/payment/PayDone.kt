package com.yamu.app.feature.payment

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.CompactButton
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.icon.YamuIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.navigation.PayDone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ReceiptState(val loading: Boolean = true, val receipt: PaymentReceipt? = null, val error: String? = null)

/** '10월 26일 09:38' */
internal fun formatPaidAt(iso: String?): String? = iso?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST).format(DateTimeFormatter.ofPattern("M월 d일 HH:mm")) }.getOrNull()
}

/** '2026-11-16' → '11월 16일' */
internal fun formatDay(iso: String?): String? = parseDate(iso)?.let { "${it.monthValue}월 ${it.dayOfMonth}일" }

// ───────────────────────── P-07 결제 완료 ─────────────────────────

/** 방금 승인한 결과(메모리)를 그리고, 앱이 다시 켜졌으면 #3으로 다시 받는다 */
@HiltViewModel
class PayDoneViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val payments: PaymentRepository) : ViewModel() {
    private val paymentId = savedStateHandle.toRoute<PayDone>().paymentId
    private val _state = MutableStateFlow(payments.recentReceipt(paymentId)?.let { ReceiptState(loading = false, receipt = it) } ?: ReceiptState())
    val state: StateFlow<ReceiptState> = _state.asStateFlow()

    init { if (_state.value.receipt == null) load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = payments.detail(paymentId)) {
                is ApiResult.Success -> _state.update { ReceiptState(loading = false, receipt = r.data) }
                is ApiResult.Failure -> _state.update { ReceiptState(loading = false, error = r.error.userMessage()) }
            }
        }
    }
}

@Composable
fun PayDoneRoute(
    onHome: () -> Unit,
    onDetail: (paymentId: Long) -> Unit,
    onPerformance: (userCardId: Long) -> Unit,
    viewModel: PayDoneViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val r = s.receipt
    FlowScreen(
        title = "",
        onBack = onHome,
        closeIcon = true,
        actions = { MockPayTag() },
        bottomBar = { BottomCta { PrimaryButton("홈으로", onClick = onHome) } },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            // 결과 — 가운데 묶음(캐릭터 pay · 머리 · 금액 · 가맹점)
            Column(
                Modifier.fillMaxWidth().padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 16.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CharacterImage(YamuPose.Pay, size = CharacterSize.M, animate = true)
                Spacer(Modifier.height(6.dp))
                Text("결제를 마쳤어요", style = YamuTheme.typography.headlineS, color = c.text1)
                if (r != null) {
                    AmountText(r.amount)
                    Text(listOfNotNull(r.merchantName, formatPaidAt(r.paidAtIso)).joinToString(" · "), style = YamuTheme.typography.bodyM, color = c.text3)
                }
            }
            when {
                s.loading -> Column(Modifier.padding(horizontal = YamuSpacing.screen)) {
                    repeat(3) {
                        SkeletonBlock(height = 44.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                r == null -> ErrorState(s.error ?: "결제 내역을 불러오지 못했어요", onRetry = viewModel::load, modifier = Modifier.padding(horizontal = YamuSpacing.screen))
                else -> {
                    HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(horizontal = YamuSpacing.screen))
                    Column(Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 8.dp, bottom = 12.dp)) { ReceiptLines(r, onPerformance) }   // 선 아래 8(프로토타입 P-07 .rows)
                    // 거래 상세는 본문의 작은 회색 버튼(아래 고정 버튼은 '홈으로' 하나)
                    CompactButton(
                        "거래 상세",
                        onClick = { onDetail(r.paymentId) },
                        horizontalPadding = 24.dp,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/** 혜택 · 청구(또는 출금) · 실적 — 서버 값 그대로(앱이 빼고 더하지 않음) */
@Composable
private fun ReceiptLines(r: PaymentReceipt, onPerformance: ((Long) -> Unit)?) {
    val c = YamuTheme.colors
    val card = r.cardBrand?.name ?: r.cardName ?: "카드"
    val row = 48.dp   // 프로토타입 결제 완료 .kv.lg
    r.benefits.forEach { b -> KeyValueRow("$card ${b.name}", "−${b.amount.toWon()}원", minHeight = row, valueColor = c.primary) }
    if (r.isCheck) {
        r.chargedAmount?.let { KeyValueRow("계좌에서 출금", "${it.toWon()}원", minHeight = row) }
        r.balanceAfter?.let { KeyValueRow("남은 잔액", "${it.toWon()}원", minHeight = row) }
    } else if (r.billAmount != null) {
        val months = r.installmentMonths?.let { " · ${it}개월 할부" }.orEmpty()
        KeyValueRow(listOfNotNull(formatDay(r.billDateIso), "청구").joinToString(" ") + months, "${r.billAmount.toWon()}원", minHeight = row)
    }
    if (r.perfRecognized != null) {
        val month = r.perfMonth?.let { "${it}월 " }.orEmpty()
        if (!r.perfCounted) {
            KeyValueRow("${month}실적", "이 결제는 빠져요", minHeight = row)
        } else {
            val total = r.perfTotalAfter
            val target = r.perfTarget
            val openPerf = r.cardId?.let { id -> onPerformance?.let { { it(id) } } }
            // 다른 줄처럼 키는 회색 보통 — 아래 작은 줄에 기준 · 남은 금액, 눌러서 카드 실적으로
            KeyValueRow(
                "$card ${month}실적",
                total?.let { "${it.toWon()}원" } ?: "+${r.perfRecognized.toWon()}원",
                minHeight = 52.dp,   // 두 줄 + 위아래 6(.rw .k2)
                keyTag = { EstimateTag(text = "추정치") },
                keySub = if (total != null && target != null) {
                    "기준 ${target.toWon()}원 · " + if (total >= target) "달성" else "${(target - total).toWon()}원 남았어요"
                } else null,
                trailing = if (openPerf != null) ({ Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3) }) else null,
                onClick = openPerf,
            )
        }
    }
}

