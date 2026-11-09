package com.yamu.app.feature.forecast

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import com.yamu.app.core.designsystem.component.CompactButton
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuMotion
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.SimAction
import com.yamu.app.feature.forecast.data.SimCardOption
import com.yamu.app.feature.forecast.data.SimProblem
import com.yamu.app.feature.forecast.data.SimSnapshot
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.SimulationQuery
import com.yamu.app.feature.forecast.data.canSavePlan
import com.yamu.app.feature.forecast.data.subjectParticle
import com.yamu.app.feature.forecast.data.toSimProblem
import com.yamu.app.navigation.BuyCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 경로 값 ↔ 조건 — 결과는 저장된 게 아니라 이 조건으로 부른 값이다 */
fun SimulationQuery.toRoute() = BuyCheckResult(
    amount = amount,
    title = productName,
    categoryCode = categoryCode,
    categoryName = categoryName,
    merchantId = merchantId,
    merchantName = merchantName,
    channel = channel.name,
    userCardId = userCardId,
    months = installmentMonths,
    purchaseDate = purchaseDate.toString(),
)

fun BuyCheckResult.toQuery() = SimulationQuery(
    amount = amount,
    productName = title,
    categoryCode = categoryCode,
    categoryName = categoryName,
    merchantId = merchantId,
    merchantName = merchantName,
    channel = PaymentChannel.entries.firstOrNull { it.name == channel } ?: PaymentChannel.OFFLINE,
    userCardId = userCardId,
    installmentMonths = months,
    purchaseDate = LocalDate.parse(purchaseDate),
)

// ───────────────────────── I-02 · I-03 사기 전후 비교 ─────────────────────────

data class SimState(
    val query: SimulationQuery,
    val loading: Boolean = true,
    val simulation: Simulation? = null,
    val problem: SimProblem? = null,
    val cards: List<SimCardOption>? = null,
    val cardsError: String? = null,
    val saving: Boolean = false,
    val savedPlanId: Long? = null,
    val saveUncertain: Boolean = false,     // 저장 응답을 못 받았다 — 재시도 키가 없어 다시 보내지 않는다
    val message: String? = null,
)

/**
 * 가상 구매 — 조건을 바꾸면 다시 부르고, 앞 요청의 늦은 결과는 버린다(연타 · 빠른 전환).
 * 시뮬레이션은 계산만 해서 다시 불러도 결제 · 청구가 생기지 않는다. 계획 저장만 서버에 남는다
 */
@HiltViewModel
class BuyCheckViewModel internal constructor(initial: SimulationQuery, private val repo: ForecastRepository) : ViewModel() {
    @Inject constructor(savedStateHandle: SavedStateHandle, repo: ForecastRepository) : this(savedStateHandle.toRoute<BuyCheckResult>().toQuery(), repo)

    private val _state = MutableStateFlow(SimState(query = initial))
    val state: StateFlow<SimState> = _state.asStateFlow()
    private var job: Job? = null

    /** 잔고예보에서 쓰는 안전 잔액 — 시뮬레이션 응답에는 없어 지난 예보 값을 쓴다 */
    val alertThreshold: Long? get() = repo.cached()?.alertThreshold

    init { load() }

    fun load() {
        job?.cancel()
        val query = _state.value.query
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, problem = null) }
            when (val r = repo.simulate(query)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, simulation = r.data, problem = null) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, simulation = null, problem = r.error.toSimProblem()) }
            }
        }
    }

    private fun change(transform: (SimulationQuery) -> SimulationQuery) {
        val next = transform(_state.value.query)
        if (next == _state.value.query) return
        _state.update { it.copy(query = next, saveUncertain = false) }
        load()
    }

    /** 카드를 바꾼다(null = 혜택이 가장 큰 카드). 체크카드면 할부를 푼다 */
    fun setCard(userCardId: Long?) = change { q ->
        val card = _state.value.cards?.firstOrNull { it.userCardId == userCardId }
        q.copy(userCardId = userCardId, installmentMonths = q.installmentMonths.takeIf { installmentAllowed(q.amount, card) })
    }

    fun setChannelAndMonths(channel: PaymentChannel, months: Int?) = change { it.copy(channel = channel, installmentMonths = months) }

    fun setMonths(months: Int?) = change { it.copy(installmentMonths = months) }

    fun setDate(date: LocalDate) = change { it.copy(purchaseDate = date) }

    fun loadCards() {
        if (_state.value.cards != null) return
        viewModelScope.launch {
            _state.update { it.copy(cardsError = null) }
            when (val r = repo.simulationCards()) {
                is ApiResult.Success -> _state.update { it.copy(cards = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(cardsError = r.error.userMessage()) }
            }
        }
    }

    fun reloadCards() {
        _state.update { it.copy(cards = null) }
        loadCards()
    }

    /** 일시불 결과만 저장. 응답을 못 받았으면 저장됐는지 모르므로 다시 보내지 않고 잔고예보에서 확인하게 한다 */
    fun save() {
        val s = _state.value
        val sim = s.simulation ?: return
        if (s.saving || s.loading || s.saveUncertain || !sim.canSavePlan) return
        _state.update { it.copy(saving = true) }   // 코루틴이 돌기 전에 막아야 연타가 두 번 나가지 않는다
        viewModelScope.launch {
            when (val r = repo.savePlan(sim)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, savedPlanId = r.data.id) }
                is ApiResult.Failure -> _state.update {
                    if (r.error.isOutcomeUnknown) it.copy(saving = false, saveUncertain = true, message = "저장됐는지 확인하지 못했어요 · 잔고예보에서 확인해 주세요")
                    else it.copy(saving = false, message = r.error.userMessage())
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun navigated() = _state.update { it.copy(savedPlanId = null) }
}

@Composable
fun BuyCheckResultRoute(
    onBack: () -> Unit,
    onEditConditions: () -> Unit,
    onEditAmount: () -> Unit,
    onForecast: () -> Unit,
    onPay: () -> Unit,
    onSaved: (planId: Long) -> Unit,
    viewModel: BuyCheckViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }   // card · how
    var picking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    LaunchedEffect(s.savedPlanId) {
        s.savedPlanId?.let {
            viewModel.navigated()
            onSaved(it)
        }
    }
    val openCards = {
        viewModel.loadCards()
        sheet = "card"
    }
    val sim = s.simulation
    val today = LocalDate.now(KST)
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onBack,
        cards = true,
        bottomBar = {
            when {
                sim == null -> Unit
                s.saveUncertain -> BottomCta { PrimaryButton("잔고예보에서 확인하기", onClick = onForecast) }
                sim.query.isInstallment -> BottomCta {
                    Text("할부 구매는 가계부에 할부 칸이 없어 아직 계획으로 저장할 수 없어요", style = YamuTheme.typography.bodyS, color = YamuTheme.colors.text3, modifier = Modifier.padding(bottom = 8.dp))
                    PrimaryButton("일시불로 다시 보기", onClick = { viewModel.setMonths(null) }, enabled = !s.loading)
                }
                sim.canSavePlan -> BottomCta(secondary = if (sim.query.purchaseDate == today) ({ TextButtonGrey("지금 결제하러 가기", onClick = onPay, modifier = Modifier.fillMaxWidth(), small = true) }) else null) {
                    PrimaryButton("${sim.query.purchaseDate.toKoreanDate()} 구매 계획으로 저장", onClick = viewModel::save, loading = s.saving, enabled = !s.loading)
                }
                else -> Unit
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(s.query, sim, onCard = openCards, onHow = { sheet = "how" }, onDate = { picking = true })
            val problem = s.problem
            when {
                problem != null -> ProblemCard(problem, onAction = { action ->
                    when (action) {
                        SimAction.PICK_CATEGORY -> onEditConditions()
                        SimAction.PICK_CARD, SimAction.OTHER_CARD -> {
                            viewModel.reloadCards()
                            sheet = "card"
                        }
                        SimAction.PICK_DATE -> picking = true
                        SimAction.LUMP_SUM -> viewModel.setMonths(null)
                        SimAction.RETRY -> viewModel.load()
                        SimAction.BACK -> onEditAmount()
                    }
                }, onRecommended = { viewModel.setCard(null) })
                sim == null -> YamuCard {
                    Text("야무가 계산 중이에요", style = YamuTheme.typography.titleM, color = YamuTheme.colors.text1)
                    Spacer(Modifier.height(YamuSpacing.x4))
                    SkeletonBlock(height = 28.dp, widthFraction = 0.5f)
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 140.dp)
                }
                else -> {
                    // 다시 계산하는 동안에는 지난 결과를 흐리게 둔다(자리 그대로, 모션 줄이기면 바로)
                    val dim by animateFloatAsState(
                        targetValue = if (s.loading) 0.4f else 1f,
                        animationSpec = if (YamuTheme.reduceMotion) snap() else tween(YamuMotion.SHORT_MS, easing = YamuMotion.standard),
                        label = "recalc",
                    )
                    Column(Modifier.alpha(dim)) {
                        HeroCard(sim, viewModel.alertThreshold)
                        ScheduleCard(sim)
                        BenefitCard(sim)
                        if (sim.alternatives.isNotEmpty()) AlternativesCard(sim, onPick = { if (!s.loading) viewModel.setCard(it) })
                        BasisCard(sim)
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    when (sheet) {
        "card" -> CardSheet(s.cards, s.cardsError, s.query.userCardId, onRetry = viewModel::reloadCards, onDismiss = { sheet = null }, onPick = {
            sheet = null
            viewModel.setCard(it)
        })
        "how" -> HowSheet(s.query, s.cards?.firstOrNull { it.userCardId == s.query.userCardId }, onDismiss = { sheet = null }, onApply = { channel, months ->
            sheet = null
            viewModel.setChannelAndMonths(channel, months)
        })
    }
    if (picking) PurchaseDatePicker(s.query.purchaseDate, onDismiss = { picking = false }, onPick = {
        picking = false
        viewModel.setDate(it)
    })
}

/** 무엇 · 얼마 + 바꿀 수 있는 칩 셋(카드 · 어디서 어떻게 · 날짜). 업종 · 브랜드는 앞 화면에서 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(q: SimulationQuery, sim: Simulation?, onCard: () -> Unit, onHow: () -> Unit, onDate: () -> Unit) {
    val c = YamuTheme.colors
    Column(Modifier.padding(horizontal = YamuSpacing.screen).padding(top = 4.dp, bottom = YamuSpacing.blockGap)) {
        Text(listOfNotNull(q.productName, "${q.amount.toWon()}원").joinToString(" · "), style = YamuTheme.typography.headlineS, color = c.text1)
        Spacer(Modifier.height(10.dp))
        val cardName = when {
            q.userCardId == null -> sim?.card?.name?.let { "$it · 추천" } ?: "혜택이 가장 큰 카드"
            else -> sim?.card?.name ?: "고른 카드"
        }
        val how = listOfNotNull(listOfNotNull(q.categoryName, q.merchantName).joinToString(" "), q.channel.label, q.installmentMonths?.let { "할부 ${it}개월" } ?: "일시불")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(cardName, "카드 $cardName, 바꾸기", onCard)
            PillButton(how.filter { it.isNotBlank() }.joinToString(" · "), "결제 방식 바꾸기", onHow)
            PillButton("${q.purchaseDate.toKoreanDate()} 구매", "구매일 바꾸기", onDate)
        }
    }
}

/** 사고 나면 가장 낮을 때 — 전(회색 취소선) → 후, 상태(사실만), BE 안내 문장, 전후 그래프 */
@Composable
private fun HeroCard(sim: Simulation, threshold: Long?) {
    val c = YamuTheme.colors
    val after = sim.after
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("사고 나면 가장 낮을 때", style = YamuTheme.typography.titleS, color = c.text2)
            if (sim.isEstimate) {
                Spacer(Modifier.width(6.dp))
                EstimateTag()
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            sim.before?.minBalance?.takeIf { it != after?.minBalance }?.let { b ->
                Text("${b.toWon()}원", style = YamuTheme.typography.titleM.copy(textDecoration = TextDecoration.LineThrough), color = c.text3)
                Icon(YamuIcons.ChevronRight, contentDescription = "에서", tint = c.text3, modifier = Modifier.padding(horizontal = 4.dp).size(18.dp))
            }
            AmountText(after?.minBalance ?: 0, shortage = (after?.minBalance ?: 0) < 0, color = c.primary)
        }
        afterTag(after, sim.afterRiskStart(threshold))?.let { (text, tone) ->
            Spacer(Modifier.height(8.dp))
            StatusTag(text, tone = tone)
        }
        listOfNotNull(sim.message, sim.outsideNote?.takeIf { sim.message == null }).forEach {
            Spacer(Modifier.height(10.dp))
            Text(it, style = YamuTheme.typography.bodyM, color = c.text2)
        }
        val days = sim.days
        if (days.size >= 2) {
            Spacer(Modifier.height(YamuSpacing.x5))
            val lines = listOf(
                ChartLine("사기 전", days.map { it.before }, c.text3, dashed = true, width = 1.8.dp),
                ChartLine("사기 후", days.map { it.after }, c.primary, width = 2.4.dp),
            )
            val below = threshold?.let { t -> days.indices.filter { (days[it].after ?: Long.MAX_VALUE) < t } }.orEmpty()
            BalanceChart(
                dates = days.map { it.date },
                lines = lines,
                description = "사기 전과 후 잔액 그래프, 사고 나면 가장 낮을 때 ${(after?.minBalance ?: 0).toWon()}원",
                threshold = threshold,
                riskRanges = below.toRanges(),
            )
            Spacer(Modifier.height(10.dp))
            ChartLegend(lines)
        }
    }
}

/** 이어진 날 번호 → 구간 */
private fun List<Int>.toRanges(): List<IntRange> = fold(mutableListOf<IntRange>()) { acc, i ->
    val last = acc.lastOrNull()
    if (last != null && last.last + 1 == i) acc[acc.lastIndex] = last.first..i else acc += i..i
    acc
}

/**
 * 사고 난 뒤 상태 — 권하거나 말리지 않고 잔액 사실만(FR-032 · BE 안내 문장과 같은 원칙).
 * 날짜는 가장 낮은 날이 아니라 안전 잔액 아래로 처음 내려가는 날([riskStart])이고, 문구도 잔고예보 첫 화면 태그와 같다(점검 B-08).
 * 그 날이 0원 아래로 내려가는 날보다 이를 수 있어 '잔액 부족'이라 쓰지 않는다. 0원 아래면 빨간색으로만 구분한다
 */
internal fun afterTag(after: SimSnapshot?, riskStart: LocalDate?): Pair<String, TagTone>? {
    after ?: return null
    val below = riskStart?.let { "${it.toKoreanDate()}부터 안전 잔액 아래" } ?: "안전 잔액 아래로 내려가요"
    return when {
        (after.minBalance ?: 0) < 0 || after.status == RiskLevel.DANGER -> below to TagTone.Error
        after.status == RiskLevel.WARNING -> below to TagTone.Warning
        after.status == RiskLevel.SAFE -> "가장 낮을 때도 안전 잔액 위" to TagTone.Success
        else -> null
    }
}

/** 빠져나가는 날 — 회차마다, 예보 기간 밖이면 태그 */
@Composable
private fun ScheduleCard(sim: Simulation) {
    if (sim.schedule.isEmpty()) return
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("빠져나가는 날", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            Text("${sim.card.name} 출금 계좌", style = YamuTheme.typography.bodyS, color = c.text3)
        }
        Spacer(Modifier.height(4.dp))
        sim.schedule.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            ListRow(
                title = w.label,
                sub = w.date?.toKoreanDateWeek(),
                value = "${w.amount.toWon()}원",
                valueColor = if (w.beyondPeriod) c.text2 else c.text1,
                titleTag = if (w.beyondPeriod) ({ StatusTag("기간 밖", tone = TagTone.Neutral) }) else null,
                dense = true,
            )
        }
    }
}

/** 이 카드로 사면 — 혜택 합계 · 줄(원화), 청구 금액. 계산하지 못했으면 이유 */
@Composable
private fun BenefitCard(sim: Simulation) {
    val c = YamuTheme.colors
    val b = sim.benefit
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${sim.card.name}${sim.card.name.directionParticle()} 사면", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            if (sim.card.recommendedTop) StatusTag("혜택 1순위", tone = TagTone.Primary)
        }
        Spacer(Modifier.height(8.dp))
        if (b?.net != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("받는 혜택", style = YamuTheme.typography.bodyM, color = c.text2)
                Text("${b.net.toWon()}원", style = YamuTheme.typography.titleL, color = c.primary)
                EstimateTag()
            }
            Spacer(Modifier.height(8.dp))
            b.lines.forEach { line -> StepLine(line.name, line.amount?.let { "${it.toWon()}원" } ?: "금액 미정") }
            b.charged?.let { StepLine("청구 금액", "${it.toWon()}원") }
        } else {
            Text("혜택은 계산하지 못했어요", style = YamuTheme.typography.titleS, color = c.text2)
        }
        sim.missing.forEach { Text(it, style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun StepLine(label: String, value: String) {
    val c = YamuTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = YamuTheme.typography.bodyM, color = c.text1)
    }
}

/** 다른 카드였다면 — 누르면 그 카드로 다시 계산. 고른 카드보다 1순위 카드가 크면 위에 안내 */
@Composable
private fun AlternativesCard(sim: Simulation, onPick: (Long) -> Unit) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Text("다른 카드였다면", style = YamuTheme.typography.cardTitle, color = c.text1)
        sim.betterCard?.let { better ->
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(YamuRadius.input))
                    .background(c.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text("${better.name}${better.name.subjectParticle()} 혜택이 더 커요", style = YamuTheme.typography.titleS, color = c.onPrimaryContainer)
                listOfNotNull(better.net?.let { "${it.toWon()}원" }, sim.betterBy?.let { "지금 카드보다 ${it.toWon()}원 더" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = YamuTheme.typography.bodyS, color = c.onPrimaryContainer, modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.height(10.dp))
                CompactButton("${better.name}${better.name.directionParticle()} 다시 보기", onClick = { onPick(better.userCardId) }, small = true)
            }
        }
        Spacer(Modifier.height(4.dp))
        sim.alternatives.forEachIndexed { i, alt ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            ListRow(
                title = alt.name,
                sub = listOfNotNull(alt.detail, alt.note?.let { "비교하지 못했어요 · $it" }).joinToString(" · "),
                value = alt.net?.let { "${it.toWon()}원" },
                valuePlain = if (alt.net == null) "—" else null,
                titleTag = if (alt.recommended) ({ StatusTag("1순위", tone = TagTone.Primary) }) else null,
                chevron = true,
                dense = true,
                onClick = { onPick(alt.userCardId) },
            )
        }
        Text("카드를 누르면 그 카드로 다시 계산해요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
    }
}

/** 계산 기준 — BE 가정 문장 그대로 + 신용한도 미확인 + 실제 결제와 무관 */
@Composable
private fun BasisCard(sim: Simulation) {
    val c = YamuTheme.colors
    val lines = sim.assumptions +
        listOfNotNull(if (sim.creditLimitSkipped) "신용한도는 확인하지 않았어요." else null) +
        "실제 결제나 실적에는 반영되지 않아요."
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text("계산 기준", style = YamuTheme.typography.titleS, color = c.text1)
        Spacer(Modifier.height(6.dp))
        lines.forEach { Text("· $it", style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

/** 계산하지 못한 이유 — 제목 · 설명 · 다음 행동(422 · 400 이유마다) */
@Composable
private fun ProblemCard(problem: SimProblem, onAction: (SimAction) -> Unit, onRecommended: () -> Unit) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CharacterImage(YamuPose.Alert, size = CharacterSize.S)
            Spacer(Modifier.height(12.dp))
            Text(problem.title, style = YamuTheme.typography.titleL, color = c.text1, textAlign = TextAlign.Center)
            problem.body?.let { Text(it, style = YamuTheme.typography.bodyM, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(18.dp))
            problem.action.label?.let { PrimaryButton(it, onClick = { onAction(problem.action) }) }
            if (problem.toRecommended) TextButtonGrey("혜택이 가장 큰 카드로 보기", onClick = onRecommended, modifier = Modifier.fillMaxWidth(), small = true)
        }
    }
}

/** 어디서 어떻게 — 매장/온라인 · 일시불/할부(신용카드 · 5만 원 이상) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HowSheet(query: SimulationQuery, card: SimCardOption?, onDismiss: () -> Unit, onApply: (PaymentChannel, Int?) -> Unit) {
    val c = YamuTheme.colors
    var channel by rememberSaveable { mutableStateOf(query.channel) }
    var months by rememberSaveable { mutableStateOf(query.installmentMonths) }
    val canInstallment = installmentAllowed(query.amount, card)
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("어떻게 사나요", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(14.dp))
        Segmented(listOf("매장에서", "온라인으로"), selectedIndex = channel.ordinal, onSelect = { channel = PaymentChannel.entries[it] })
        Spacer(Modifier.height(20.dp))
        Text("결제", style = YamuTheme.typography.bodyM, color = c.text3)
        Spacer(Modifier.height(8.dp))
        if (canInstallment) {
            Segmented(listOf("일시불", "할부"), selectedIndex = if (months == null) 0 else 1, onSelect = { months = if (it == 0) null else (months ?: 3) })
            if (months != null) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimulationQuery.INSTALLMENT_MONTHS.forEach { m ->
                        com.yamu.app.core.designsystem.component.GreyChip("${m}개월", selected = m == months, onClick = { months = m })
                    }
                }
            }
        } else {
            Text("일시불 · 할부는 신용카드로 5만 원 이상일 때만 볼 수 있어요", style = YamuTheme.typography.bodyM, color = c.text2)
        }
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("이대로 다시 보기", onClick = { onApply(channel, months.takeIf { canInstallment }) })
    }
}
