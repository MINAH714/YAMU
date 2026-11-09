package com.yamu.app.feature.report

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import com.yamu.app.core.designsystem.component.LinkButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BrandMark
import com.yamu.app.feature.report.data.BenefitReport
import com.yamu.app.feature.report.data.CategoryShare
import com.yamu.app.feature.report.data.MissedBenefit
import com.yamu.app.feature.report.data.MonthlySpend
import com.yamu.app.feature.report.data.PaymentItem
import com.yamu.app.feature.report.data.ReportRepository
import com.yamu.app.feature.report.data.mergePayments
import com.yamu.app.feature.report.data.paymentDayLabel
import com.yamu.app.feature.report.data.periodLabel
import com.yamu.app.feature.report.data.spendDiffSentence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── S-01 소비분석(탭) ─────────────────────────

data class ReportState(val loading: Boolean = true, val spend: MonthlySpend? = null, val error: String? = null)

/** 탭에 돌아올 때마다 새로 받는다(방금 한 결제가 바로 보이게) — 실패하면 마지막 값을 그대로 */
@HiltViewModel
class ReportViewModel @Inject constructor(private val repo: ReportRepository) : ViewModel() {
    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.spend == null, error = null) }
            when (val r = repo.monthly()) {
                is ApiResult.Success -> _state.update { ReportState(loading = false, spend = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }
}

@Composable
fun ReportRoute(
    onBenefits: () -> Unit,
    onPayments: () -> Unit,
    onPay: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    TabScreen(title = "소비분석") { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val sp = s.spend
            when {
                sp == null && s.loading -> YamuCard {
                    SkeletonBlock(height = 20.dp, widthFraction = 0.5f)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    SkeletonBlock(height = 38.dp, widthFraction = 0.6f)
                    Spacer(Modifier.height(YamuSpacing.x5))
                    repeat(3) { SkeletonBlock(height = 40.dp); Spacer(Modifier.height(YamuSpacing.x2)) }
                }
                sp == null -> YamuCard { ErrorState(s.error ?: "소비분석을 불러오지 못했어요", onRetry = viewModel::refresh) }
                sp.paymentCount == 0 || (sp.spent == 0L && sp.categories.isEmpty()) ->
                    YamuCard { EmptyState("이번 달 결제가 아직 없어요", actionText = "QR로 결제하기", onAction = onPay) }
                else -> {
                    YamuCard {
                        Text("${sp.periodLabel} ${if (sp.isLiving) "생활 소비" else "카드 결제"}", style = YamuTheme.typography.bodyM, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        AmountText(sp.spent)
                        sp.diffFromPrev?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(boldAmount(spendDiffSentence(it)), style = YamuTheme.typography.bodyM, color = c.text2)
                        }
                        s.error?.let { Text("$it · 마지막 값을 보여 드려요", style = YamuTheme.typography.bodyS, color = c.warning) }
                    }
                    if (sp.categories.isNotEmpty()) {
                        YamuCard {
                            Text("많이 쓴 곳", style = YamuTheme.typography.cardTitle, color = c.text1)
                            Spacer(Modifier.height(YamuSpacing.x3))
                            ShareBar(sp.categories, total = sp.spent)
                            Spacer(Modifier.height(YamuSpacing.x2))
                            sp.categories.forEachIndexed { i, cat -> CategoryRow(cat, chartColor(i)) }
                        }
                    }
                    YamuCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {   // 링크 카드는 .card.tight(16)
                        sp.benefit?.let {
                            ListRow(
                                title = sp.month?.let { m -> "${m}월에 받은 혜택" } ?: "이번 달 받은 혜택",
                                value = "${it.toWon()}원",
                                valueColor = c.primary,
                                chevron = true,
                                onClick = onBenefits,
                            )
                        }
                        ListRow(title = "결제 내역", value = sp.paymentCount?.let { "${it}건" }, chevron = true, onClick = onPayments)
                    }
                }
            }
        }
    }
}

/** 문장 속 'n원 더 · 덜'만 굵게 */
private fun boldAmount(sentence: String): AnnotatedString {
    val m = Regex("[0-9,]+원( 더| 덜)?").find(sentence) ?: return AnnotatedString(sentence)
    return buildAnnotatedString {
        append(sentence.substring(0, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) }
        append(sentence.substring(m.range.last + 1))
    }
}

/** 막대 색 — 프로토타입 --chart-1 · --chart-5 · --chart-2(나머지는 회색) */
@Composable
private fun chartColor(index: Int): Color {
    val dark = YamuTheme.colors.isDark
    val light = listOf(0xFF3182F6, 0xFF64A8FF, 0xFF90C2FF)
    val night = listOf(0xFF4593FC, 0xFF90C2FF, 0xFF2B5A9E)
    return Color((if (dark) night else light)[index % 3])
}

/** 비중 막대 1개 — 상위 3곳 + 그 외(회색). 색만으로 전하지 않게 아래 줄에 이름 · 비율 · 금액 */
@Composable
private fun ShareBar(categories: List<CategoryShare>, total: Long) {
    val fractions = categories.map { cat -> cat.ratio?.let { it / 100f } ?: if (total > 0) cat.amount.toFloat() / total else 0f }
    val rest = (1f - fractions.sum()).coerceAtLeast(0f)
    val label = (categories.zip(fractions).map { (cat, f) -> "${cat.name} ${(f * 100).toInt()}%" } + "그 외 ${(rest * 100).toInt()}%").joinToString(", ")
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).semantics { contentDescription = "카테고리 비중: $label" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fractions.forEachIndexed { i, f ->
            if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(chartColor(i)))
        }
        if (rest > 0.001f) Box(Modifier.weight(rest).fillMaxHeight().background(YamuTheme.colors.outlineVariant))
    }
}

@Composable
private fun CategoryRow(cat: CategoryShare, color: Color) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(cat.name, style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f))
        cat.ratio?.let { Text("$it%", style = YamuTheme.typography.bodyM, color = c.text3) }
        Text("${cat.amount.toWon()}원", style = YamuTheme.typography.value, color = c.text1, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 92.dp))
    }
}

// ───────────────────────── S-04 혜택 리포트 ─────────────────────────

data class BenefitReportState(val loading: Boolean = true, val report: BenefitReport? = null, val error: String? = null)

@HiltViewModel
class BenefitReportViewModel @Inject constructor(private val repo: ReportRepository) : ViewModel() {
    private val _state = MutableStateFlow(BenefitReportState())
    val state: StateFlow<BenefitReportState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.benefits()) {
                is ApiResult.Success -> _state.update { BenefitReportState(loading = false, report = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }
}

@Composable
fun BenefitReportRoute(
    onBack: () -> Unit,
    onPayments: () -> Unit,
    onPay: () -> Unit,
    viewModel: BenefitReportViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val r = s.report
    val empty = r != null && r.received == 0L && r.missed.isEmpty() && r.byPlace.isEmpty()
    FlowScreen(
        title = "혜택 리포트",
        onBack = onBack,
        cards = true,
        bottomBar = { if (r != null && !empty) BottomCta { PrimaryButton("결제 내역 보기", onClick = onPayments) } },
    ) { inner ->
        // 회색 바탕 위 카드 3개: 받은 혜택 · 놓친 혜택 · 어디서 받았나요 — 프로토타입 S-04
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                r == null && s.loading -> {
                    YamuCard {
                        SkeletonBlock(height = 20.dp, widthFraction = 0.5f)
                        Spacer(Modifier.height(YamuSpacing.x2))
                        SkeletonBlock(height = 38.dp, widthFraction = 0.6f)
                    }
                    YamuCard { repeat(3) { SkeletonBlock(height = 44.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
                }
                r == null -> YamuCard { ErrorState(s.error ?: "혜택 리포트를 불러오지 못했어요", onRetry = viewModel::load) }
                empty -> YamuCard { EmptyState("이번 달 결제가 아직 없어요", actionText = "QR로 결제하기", onAction = onPay) }
                else -> {
                    YamuCard {
                        Text("${r.periodLabel} 받은 혜택", style = YamuTheme.typography.bodyM, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        AmountText(r.received)
                        r.yamuExtra?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildAnnotatedString {
                                    append("야무 추천으로 ")
                                    withStyle(SpanStyle(color = c.primary, fontWeight = FontWeight.Bold)) { append("${it.toWon()}원 더") }
                                    append(" 받았어요")
                                },
                                style = YamuTheme.typography.bodyM,
                                color = c.text2,
                            )
                        }
                    }
                    if (r.missedTotal > 0) {
                        YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("놓친 혜택", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
                                Text("${r.missedTotal.toWon()}원", style = YamuTheme.typography.cardTitle, color = c.error)
                            }
                            Spacer(Modifier.height(YamuSpacing.x1))   // 머리 아래 4(프로토타입 S-04 .card-hd)
                            r.missed.forEach { m -> MissedRow(m) }
                        }
                    }
                    if (r.byPlace.isNotEmpty()) {
                        YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
                            Text("어디서 받았나요", style = YamuTheme.typography.cardTitle, color = c.text1)
                            Spacer(Modifier.height(YamuSpacing.x1))
                            r.byPlace.forEach { (name, amount) -> ListRow(title = name, value = "${amount.toWon()}원", minHeight = 52.dp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/** 놓친 혜택 한 줄 — 가맹점 · 날짜 · 이유 / −금액(검정, 합만 빨강) */
@Composable
private fun MissedRow(m: MissedBenefit) {
    val c = YamuTheme.colors
    // 52dp · 위아래 6(프로토타입 .row3)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.merchant, style = YamuTheme.typography.titleM, color = c.text1)
            Text(listOfNotNull(m.date?.toMonthDay(), m.reason).joinToString(" · "), style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp))
        }
        Text("−${m.amount.toWon()}원", style = YamuTheme.typography.value, color = c.text1)
    }
}

// ───────────────────────── S-05 결제 내역 ─────────────────────────

data class PaymentsState(
    val loading: Boolean = true,
    val periodLabel: String = periodLabel(null, partial = true),
    val count: Int = 0,
    val amount: Long = 0,
    val items: List<PaymentItem> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
    val error: String? = null,
)

/** 쪽마다 20건. 돌아올 때마다 첫 쪽을 다시 받아 맨 위에 합친다(이미 펼친 쪽은 그대로) */
@HiltViewModel
class PaymentsViewModel @Inject constructor(private val repo: ReportRepository) : ViewModel() {
    private val _state = MutableStateFlow(PaymentsState())
    val state: StateFlow<PaymentsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            when (val r = repo.payments(page = 1)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        count = r.data.count,
                        amount = r.data.amount,
                        items = mergePayments(r.data.items, it.items),
                        page = maxOf(it.page, 1),
                        hasNext = if (it.page > 1) it.hasNext else r.data.hasNext,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || !s.hasNext) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, moreError = null) }
            when (val r = repo.payments(page = s.page + 1)) {
                is ApiResult.Success -> _state.update {
                    it.copy(loadingMore = false, items = mergePayments(it.items, r.data.items), page = r.data.page, hasNext = r.data.hasNext)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, moreError = r.error.userMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaymentsRoute(
    onBack: () -> Unit,
    onDetail: (paymentId: Long) -> Unit,
    onPay: () -> Unit,
    viewModel: PaymentsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    FlowScreen(title = "결제 내역", onBack = onBack, cards = true) { inner ->
        when {
            s.items.isEmpty() && s.loading -> Column(Modifier.padding(inner).padding(top = YamuSpacing.x2)) {
                YamuCard {
                    SkeletonBlock(height = 20.dp, widthFraction = 0.6f)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    SkeletonBlock(height = 38.dp, widthFraction = 0.5f)
                }
                YamuCard { repeat(6) { SkeletonBlock(height = 52.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
            }
            s.items.isEmpty() && s.error != null -> Column(Modifier.padding(inner).padding(top = YamuSpacing.x2)) {
                YamuCard { ErrorState(s.error.orEmpty(), onRetry = viewModel::refresh) }
            }
            s.items.isEmpty() -> Column(Modifier.padding(inner).padding(top = YamuSpacing.x2)) {
                YamuCard { EmptyState("이번 달 결제가 아직 없어요", actionText = "QR로 결제하기", onAction = onPay) }
            }
            else -> LazyColumn(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
                // 회색 바탕: 합계 카드 → 날짜 머리(회색) → 그날 결제(흰 줄 · 사이 선) — 프로토타입 S-05
                item(key = "head") {
                    YamuCard {
                        Text("${s.periodLabel} 카드 결제 · ${s.count}건", style = YamuTheme.typography.bodyM, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        AmountText(s.amount)
                    }
                }
                s.items.groupBy { it.date }.forEach { (date, rows) ->
                    stickyHeader(key = "day-$date") { DayHeader(paymentDayLabel(date), rows.filter { !it.canceled }.sumOf { it.amount }) }
                    itemsIndexed(rows, key = { _, p -> p.paymentId }) { i, p ->
                        PaymentRow(p, divider = i > 0, onClick = { onDetail(p.paymentId) })
                    }
                }
                item(key = "more") {
                    Column(Modifier.padding(YamuSpacing.screen)) {
                        if (s.hasNext) {
                            // 작은 파란 글자 버튼, 가운데(프로토타입 S-05)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                LinkButton(if (s.loadingMore) "불러오는 중…" else "이전 결제 더 보기", onClick = { if (!s.loadingMore) viewModel.loadMore() })
                            }
                        }
                        s.moreError?.let {
                            Spacer(Modifier.height(YamuSpacing.x2))
                            Text("$it · 다시 눌러 주세요", style = YamuTheme.typography.bodyS, color = c.error)
                        }
                        Spacer(Modifier.height(YamuSpacing.bottomSafe))
                    }
                }
            }
        }
    }
}

/** 날짜 · 그날 합(불러온 결제 기준) — 스크롤해도 위에 붙어 있다 */
@Composable
private fun DayHeader(label: String, total: Long) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.background).padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = YamuSpacing.x4, bottom = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
        Text("${total.toWon()}원", style = YamuTheme.typography.labelM, color = c.text2)
    }
}

/** 가맹점 · 카드사 · 시각 / 금액 · 할인 · 적립 — 줄 전체가 거래 상세(P-08)로 */
@Composable
private fun PaymentRow(p: PaymentItem, divider: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val benefitText = p.benefit?.let { "${p.benefitLabel} ${it.toWon()}" }
    if (divider) HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(
                    p.merchant, p.brand?.shortName, p.time, "${p.amount.toWon()}원", benefitText,
                    "실적 제외".takeIf { p.excluded }, "취소".takeIf { p.canceled },
                ).joinToString(", ")
            }
            .padding(horizontal = YamuSpacing.screen, vertical = YamuSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.merchant, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.excluded) StatusTag("실적 제외")
                if (p.canceled) StatusTag("취소")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.brand?.let { BrandMark(it) }
                p.time?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text3) }
            }
        }
        // 금액 / 혜택 두 줄 사이 4dp — 왼쪽 가맹점 / 칩 · 시각 줄과 높이를 맞춘다(프로토타입 .txr .end small)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${p.amount.toWon()}원",
                style = YamuTheme.typography.titleS.copy(fontWeight = FontWeight.Bold),
                color = if (p.canceled) c.text3 else c.text1,
                textDecoration = if (p.canceled) TextDecoration.LineThrough else null,
            )
            benefitText?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.primary) }
        }
    }
}
