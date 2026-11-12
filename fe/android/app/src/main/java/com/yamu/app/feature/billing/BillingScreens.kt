package com.yamu.app.feature.billing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuTabs
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.billing.data.BillList
import com.yamu.app.feature.billing.data.BillState
import com.yamu.app.feature.billing.data.BillingRepository
import com.yamu.app.feature.billing.data.Refund
import com.yamu.app.feature.billing.data.RefundPage
import com.yamu.app.feature.billing.data.RefundState
import com.yamu.app.feature.billing.data.RefundSummary
import com.yamu.app.feature.billing.data.amountNote
import com.yamu.app.feature.billing.data.billingMonthLabel
import com.yamu.app.feature.billing.data.koreanDay
import com.yamu.app.feature.billing.data.line
import com.yamu.app.feature.billing.data.rowLine
import com.yamu.app.feature.billing.data.tag
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.brandOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// M-09 청구 · 환급 — 청구서 탭(GET /billings) · 환급 탭(GET /refunds). 카드 상세에서 오면(CardBills) 그 카드 청구서만, 환급 탭 없이.
// 프로토타입에 없는 화면이라 S-05 결제 내역 모양(회색 바탕 · 합계 카드 · 머리 · 흰 줄)을 따른다

data class BillingState(
    val cardFiltered: Boolean = false,
    val bills: BillList? = null,
    val billsLoading: Boolean = true,
    val billsError: String? = null,
    val cardGone: Boolean = false,                  // 카드 필터로 왔는데 없는 카드(404)
    val refunds: List<Refund> = emptyList(),
    val refundSummary: RefundSummary? = null,
    val refundsLoaded: Boolean = false,
    val refundsLoading: Boolean = false,
    val refundsError: String? = null,
    val refundPage: Int = 0,
    val refundsHasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    val messages: List<String> = emptyList(),       // 스낵바 차례 — 청구서 · 환급이 같이 실패해도 하나가 다른 하나를 덮지 않게
)

private fun BillingState.withMessage(text: String) = copy(messages = messages + text)

/**
 * 환급 탭 합계 카드에 쓸 값. 입금 전 환급이 남아 있으면 목록이 비어도 보인다 —
 * BE 합계는 기간과 상관없이 지금 PENDING인 환급을 모두 세고(RefundQueryReader.pending), 목록은 최근 3개월에 생긴 것만 준다
 */
internal val BillingState.pendingSummary: RefundSummary? get() = refundSummary?.takeIf { it.pendingCount > 0 }

@HiltViewModel
class BillingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BillingRepository,
    link: LinkRepository,
) : ViewModel() {
    /** CardBills(userCardId)로 왔을 때만 있다 */
    private val userCardId: Long? = savedStateHandle.get<Long>("userCardId")

    private val _state = MutableStateFlow(BillingState(cardFiltered = userCardId != null))
    val state: StateFlow<BillingState> = _state.asStateFlow()
    private var billsJob: Job? = null
    private var refundsJob: Job? = null

    /** 청구서 줄의 카드 그림 — 청구서 응답엔 별명만 있어 카드 목록에서 상품명 · 카드사를 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    /** 화면에 돌아올 때마다 — 출금 · 환급 정기 작업이나 결제 취소가 상태를 바꿨을 수 있다 */
    fun refresh() {
        artLoader.ensure(_state.value.bills?.bills.orEmpty().mapNotNull { it.userCardId })
        loadBills()
        if (userCardId == null) loadRefunds()
    }

    /** 앞 요청이 늦게 와서 새 값을 덮지 않게 취소하고 다시 */
    fun loadBills() {
        billsJob?.cancel()
        billsJob = viewModelScope.launch {
            _state.update { it.copy(billsLoading = true, billsError = null) }
            val r = repo.bills(userCardId)
            if (r is ApiResult.Success) artLoader.ensure(r.data.bills.mapNotNull { it.userCardId })
            _state.update { s ->
                when (r) {
                    is ApiResult.Success -> s.copy(billsLoading = false, bills = r.data, cardGone = false)
                    is ApiResult.Failure -> when {
                        r.error is ApiError.NotFound && userCardId != null -> s.copy(billsLoading = false, bills = null, cardGone = true)
                        s.bills != null -> s.copy(billsLoading = false).withMessage("청구서를 새로 불러오지 못했어요 · ${r.error.userMessage()}")
                        else -> s.copy(billsLoading = false, billsError = r.error.userMessage())
                    }
                }
            }
        }
    }

    /**
     * 더 보기로 읽은 페이지까지 1페이지부터 다시 읽는다 — 상세에 다녀와도 줄 수 · 스크롤 자리가 그대로 남게.
     * 다 받은 뒤에 한 번에 바꾸고, 중간에 실패하면 보던 목록을 둔다. 도는 더 보기는 취소한다(새 목록에 밀린 옛 페이지가 섞이지 않게)
     */
    fun loadRefunds() {
        refundsJob?.cancel()
        val pages = _state.value.refundPage.coerceAtLeast(1)
        refundsJob = viewModelScope.launch {
            _state.update { it.copy(refundsLoading = true, refundsError = null, loadingMore = false, moreError = null) }
            val rows = mutableListOf<Refund>()
            var read = 0
            var last: RefundPage? = null
            while (read < pages) {
                when (val r = repo.refunds(page = read + 1)) {
                    is ApiResult.Success -> {
                        rows += r.data.items
                        last = r.data
                        read++
                        if (!r.data.hasNext) break
                    }
                    is ApiResult.Failure -> {
                        _state.update { s ->
                            if (s.refundsLoaded) s.copy(refundsLoading = false).withMessage("환급을 새로 불러오지 못했어요 · ${r.error.userMessage()}")
                            else s.copy(refundsLoading = false, refundsError = r.error.userMessage())
                        }
                        return@launch
                    }
                }
            }
            _state.update {
                it.copy(
                    refundsLoading = false,
                    refundsLoaded = true,
                    refunds = rows.distinctBy { r -> r.key },
                    refundSummary = last?.summary,
                    refundPage = read,
                    refundsHasNext = last?.hasNext == true,
                )
            }
        }
    }

    /** 연타 · 첫 페이지를 받는 중에는 무시 */
    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.refundsLoading || !s.refundsHasNext) return
        val next = s.refundPage + 1
        _state.update { it.copy(loadingMore = true, moreError = null) }
        refundsJob = viewModelScope.launch {
            val r = repo.refunds(page = next)
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(
                        loadingMore = false,
                        refunds = (it.refunds + r.data.items).distinctBy { x -> x.key },
                        refundSummary = r.data.summary,
                        refundPage = next,
                        refundsHasNext = r.data.hasNext,
                    )
                    is ApiResult.Failure -> it.copy(loadingMore = false, moreError = r.error.userMessage())
                }
            }
        }
    }

    /** 맨 앞 스낵바를 띄웠다 — 다음 차례가 있으면 화면이 이어서 띄운다 */
    fun messageShown() = _state.update { it.copy(messages = it.messages.drop(1)) }
}

@Composable
fun BillingRoute(
    onBack: () -> Unit,
    onBill: (billId: Long) -> Unit,
    onPayment: (paymentId: Long) -> Unit,
    viewModel: BillingViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    // 차례대로 하나씩 — 띄우는 중이면 SnackbarHostState가 다음 것을 기다리게 한다
    LaunchedEffect(s.messages) {
        val message = s.messages.firstOrNull() ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    FlowScreen(title = if (s.cardFiltered) "카드 청구서" else "청구 · 환급", onBack = onBack, cards = true) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (!s.cardFiltered) YamuTabs(listOf("청구서", "환급"), selectedIndex = tab, onSelect = { tab = it })
            if (s.cardFiltered || tab == 0) {
                BillsTab(s, arts, onBack = onBack, onBill = onBill, onRetry = viewModel::loadBills)
            } else {
                RefundsTab(s, onBill = onBill, onPayment = onPayment, onRetry = viewModel::loadRefunds, onMore = viewModel::loadMore)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillsTab(s: BillingState, arts: Map<Long, CardArtRef>, onBack: () -> Unit, onBill: (Long) -> Unit, onRetry: () -> Unit) {
    val list = s.bills
    val today = remember { LocalDate.now(KST) }
    when {
        s.cardGone -> MessageCard { EmptyState("이미 해제했거나 찾을 수 없는 카드예요", actionText = "돌아가기", onAction = onBack) }
        list == null && s.billsLoading -> ListSkeleton()
        list == null -> MessageCard { ErrorState(s.billsError ?: "청구서를 불러오지 못했어요", onRetry = onRetry) }
        list.bills.isEmpty() -> MessageCard {
            EmptyState(if (s.cardFiltered) "이 카드는 아직 청구서가 없어요" else "아직 청구서가 없어요\n신용카드로 결제하면 이용기간마다 청구서가 생겨요")
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item(key = "top") { Spacer(Modifier.height(YamuSpacing.x2)) }
            if (list.next != null || list.unpaidCount > 0) item(key = "summary") { BillSummaryCard(list) }
            // BE가 청구 월 최신순으로 준다 — 같은 달 청구서를 한 머리 아래에
            list.bills.groupBy { it.billingMonth }.forEach { (month, bills) ->
                stickyHeader(key = "month-$month") { GroupHeader(billingMonthLabel(month, today)) }
                itemsIndexed(bills, key = { _, b -> b.billId }) { i, b ->
                    BillingRow(
                        title = b.cardName,
                        lead = { BillCardThumb(b.userCardId?.let { arts[it] }, b.cardName) },
                        line = b.rowLine(),
                        amount = "${b.amount.toWon()}원",
                        amountNote = b.amountNote(),
                        tag = b.state.label,
                        tagTone = b.state.tone,
                        divider = i > 0,
                        onClick = { onBill(b.billId) },
                    )
                }
            }
            item(key = "foot") { Footnote("끝난 청구서는 최근 3개월까지 보여요") }
        }
    }
}

@Composable
private fun BillSummaryCard(list: BillList) {
    val c = YamuTheme.colors
    YamuCard {
        list.next?.let { n ->
            Text("다음 출금 · ${n.date.koreanDay()}", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AmountText(n.amount)
                if (list.nextIsEstimate) EstimateTag(text = "예상")
            }
        }
        if (list.unpaidCount > 0) {
            if (list.next != null) Spacer(Modifier.height(YamuSpacing.x2))
            Text("미납 청구서 ${list.unpaidCount}건 · 다시 출금하지 않아요", style = YamuTheme.typography.bodyM, color = c.error)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RefundsTab(s: BillingState, onBill: (Long) -> Unit, onPayment: (Long) -> Unit, onRetry: () -> Unit, onMore: () -> Unit) {
    val c = YamuTheme.colors
    when {
        !s.refundsLoaded && s.refundsError != null -> MessageCard { ErrorState(s.refundsError, onRetry = onRetry) }
        !s.refundsLoaded -> ListSkeleton()
        s.refunds.isEmpty() -> Column(Modifier.padding(top = YamuSpacing.x2)) {
            val sum = s.pendingSummary
            sum?.let { PendingRefundCard(it) }
            YamuCard { EmptyState(if (sum != null) "최근 3개월 동안 생긴 환급은 없어요" else "최근 3개월 동안 환급이 없어요") }
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item(key = "top") { Spacer(Modifier.height(YamuSpacing.x2)) }
            s.pendingSummary?.let { sum -> item(key = "summary") { PendingRefundCard(sum) } }
            itemsIndexed(s.refunds, key = { _, r -> r.key }) { i, r ->
                // 신용카드 환급은 그 청구서로, 체크카드 환불은 결제 상세로
                val open: (() -> Unit)? = r.billId?.let { id -> { onBill(id) } } ?: r.paymentId?.let { id -> { onPayment(id) } }
                BillingRow(
                    title = r.merchant,
                    line = r.line(),
                    amount = "${r.amount.toWon()}원",
                    tag = r.tag(),
                    tagTone = r.tone,
                    dimmed = r.state == RefundState.CANCELED,
                    divider = i > 0,
                    onClick = open,
                )
            }
            item(key = "more") {
                Column(Modifier.fillMaxWidth().padding(horizontal = YamuSpacing.screen)) {
                    if (s.refundsHasNext) {
                        Spacer(Modifier.height(YamuSpacing.x2))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            LinkButton(if (s.loadingMore) "불러오는 중…" else "더 보기", onClick = onMore)
                        }
                    }
                    s.moreError?.let {
                        Text("$it · 다시 눌러 주세요", style = YamuTheme.typography.bodyS, color = c.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Footnote("최근 3개월 환급이에요")
                }
            }
        }
    }
}

/** 입금 전 환급 합계 — 기간과 상관없이 세서 목록에 없는 건도 들어 있을 수 있다(마지막 줄) */
@Composable
private fun PendingRefundCard(sum: RefundSummary) {
    val c = YamuTheme.colors
    YamuCard {
        Text("입금 전 환급 ${sum.pendingCount}건", style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(4.dp))
        AmountText(sum.pendingAmount)
        if (sum.estimatedAmount > 0) {
            Text("그중 ${sum.estimatedAmount.toWon()}원은 출금 뒤 금액이 정해져요", style = YamuTheme.typography.bodyS, color = c.text3)
        }
        Text("기간과 상관없이 입금 전인 환급을 모두 셌어요", style = YamuTheme.typography.bodyS, color = c.text3)
    }
}

// ───────────────────────── 같이 쓰는 조각 ─────────────────────────

internal val BillState.tone: TagTone
    get() = when (this) {
        BillState.ACCUMULATING, BillState.CONFIRMED -> TagTone.Primary
        BillState.PAID -> TagTone.Success
        BillState.UNPAID -> TagTone.Error
        BillState.CANCELED, BillState.UNKNOWN -> TagTone.Neutral
    }

internal val RefundState.tone: TagTone
    get() = when (this) {
        RefundState.PENDING -> TagTone.Primary
        RefundState.COMPLETED -> TagTone.Success
        RefundState.FAILED -> TagTone.Error
        RefundState.CANCELED, RefundState.UNKNOWN -> TagTone.Neutral
    }

/** 예상 금액인 환급은 아직 정해진 게 아니라 회색 */
private val Refund.tone: TagTone get() = if (state == RefundState.PENDING && estimated) TagTone.Neutral else state.tone

/**
 * 흰 줄 하나 — 제목 · 상태 태그 / 둘째 줄 · 금액 / 금액 아래 작은 줄. 줄 전체를 누른다(S-05 결제 줄과 같은 크기).
 * [dimmed] = 청구 제외 회차 · 무효 환급처럼 취소로 더 오갈 돈이 없는 줄(청구 제외라도 이용 금액에 남는 회차가 있다 — UsageState.EXCLUDED)
 */
@Composable
internal fun BillingRow(
    title: String,
    line: String?,
    amount: String,
    divider: Boolean,
    onClick: (() -> Unit)?,
    tag: String? = null,
    tagTone: TagTone = TagTone.Neutral,
    amountNote: String? = null,
    dimmed: Boolean = false,
    horizontalPadding: Dp = YamuSpacing.screen,     // 흰 카드 안(청구서 상세)에서는 카드 안쪽 여백 20에 맞춘다
    lead: (@Composable () -> Unit)? = null,         // 청구서 줄의 카드 그림
) {
    val c = YamuTheme.colors
    if (divider) HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, tag, line, amount, amountNote).joinToString(", ") }
            .padding(horizontal = horizontalPadding, vertical = YamuSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = YamuTheme.typography.titleS,
                    color = if (dimmed) c.text3 else c.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                tag?.let { StatusTag(it, tone = tagTone) }
            }
            if (!line.isNullOrEmpty()) Text(line, style = YamuTheme.typography.bodyS, color = c.text3)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(amount, style = YamuTheme.typography.titleS.copy(fontWeight = FontWeight.Bold), color = if (dimmed) c.text3 else c.text1, maxLines = 1)
            amountNote?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text3) }
        }
    }
}

/**
 * 청구서의 카드 그림 44dp — 상품명 · 카드사는 카드 목록에서 찾는다([art]). 목록에 없으면(해제한 카드 · 목록 실패)
 * 청구서의 카드 이름(별명이 없으면 상품명)으로 그림을 찾고, 그것도 없으면 회색 카드에 이름 첫 낱말
 */
@Composable
internal fun BillCardThumb(art: CardArtRef?, cardName: String) =
    CardListThumb(
        art?.brand ?: brandOf(cardName, shortName = cardName.substringBefore(' ')),
        art?.productName ?: cardName,
        width = 44.dp,
        dimmed = art?.dimmed == true,
    )

/** 회색 바탕 위 묶음 머리 — 스크롤해도 위에 붙어 있다 */
@Composable
private fun GroupHeader(label: String) {
    val c = YamuTheme.colors
    Text(
        label,
        style = YamuTheme.typography.titleS,
        color = c.text1,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.background)
            .padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x4, bottom = YamuSpacing.x2),
    )
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = YamuTheme.typography.bodyS,
        color = YamuTheme.colors.text3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = YamuSpacing.x4, bottom = YamuSpacing.bottomSafe),
    )
}

@Composable
internal fun MessageCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = YamuSpacing.x2)) { YamuCard(content = content) }
}

@Composable
internal fun ListSkeleton() {
    Column(Modifier.padding(top = YamuSpacing.x2)) {
        YamuCard {
            SkeletonBlock(height = 20.dp, widthFraction = 0.5f)
            Spacer(Modifier.height(YamuSpacing.x2))
            SkeletonBlock(height = 38.dp, widthFraction = 0.4f)
        }
        YamuCard {
            repeat(4) {
                SkeletonBlock(height = 52.dp)
                Spacer(Modifier.height(YamuSpacing.x3))
            }
        }
    }
}
