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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.AccountShortfall
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.SimAction
import com.yamu.app.feature.forecast.data.SimBenefit
import com.yamu.app.feature.forecast.data.SimCardOption
import com.yamu.app.feature.forecast.data.SimProblem
import com.yamu.app.feature.forecast.data.SimSnapshot
import com.yamu.app.feature.forecast.data.SimWithdrawal
import com.yamu.app.feature.forecast.data.Simulation
import com.yamu.app.feature.forecast.data.shortName
import com.yamu.app.feature.forecast.data.SimulationQuery
import com.yamu.app.feature.forecast.data.canSavePlan
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
import kotlin.math.roundToInt

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
    val saveUncertain: Boolean = false,     // 저장 응답을 못 받았다 — 같은 키로만 다시 보낸다(서버가 한 번만 만든다)
    val savedElsewhere: Boolean = false,    // 같은 키의 앞 저장이 이미 들어갔다(409) — 다시 저장하지 않고 잔고예보에서 보게 한다
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

    /**
     * 계획 저장 재시도 키(BE -358 !202). 같은 조건에서 다시 누르면 같은 키 — 응답을 못 받았어도 한 번만 저장된다.
     * 조건을 바꾸면 다른 계획이라 새 키, 저장에 성공해도 다음 저장은 새 키
     */
    private var planKey = newIdempotencyKey()

    /** 잔고예보에서 쓰는 안전 잔액 — 시뮬레이션 응답에는 없어 지난 예보 값을 쓴다 */
    val alertThreshold: Long? get() = repo.cached()?.alertThreshold

    /** 지난 잔고예보 — 고른 날 칸에 그날 입출금을 빌려 쓴다('사기 전' 선과 같을 때만, [matchingForecast]) */
    val forecast: Forecast? get() = repo.cached()

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
        planKey = newIdempotencyKey()   // 조건이 바뀌면 다른 계획이다
        _state.update { it.copy(query = next, saveUncertain = false, savedElsewhere = false) }
        load()
    }

    /** 카드를 바꾼다(null = 혜택이 가장 큰 카드). 체크카드면 할부를 푼다. 할부일 때는 카드 시트가 체크카드를 보여 주지 않는다(-588) */
    fun setCard(userCardId: Long?) = change { q ->
        val cards = _state.value.cards
        val card = cards?.firstOrNull { it.userCardId == userCardId }
        q.copy(userCardId = userCardId, installmentMonths = q.installmentMonths.takeIf { installmentAllowed(q.amount, card, cards) })
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

    /**
     * 일시불 결과만 저장. 응답을 못 받았으면 같은 키로 다시 누를 수 있게 둔다(서버가 한 번만 만든다).
     * 같은 키에 다른 내용(409)이면 앞 저장이 이미 들어간 것이라 잔고예보에서 확인하게 한다
     */
    fun save() {
        val s = _state.value
        val sim = s.simulation ?: return
        if (s.saving || s.loading || s.savedElsewhere || !sim.canSavePlan) return
        _state.update { it.copy(saving = true) }   // 코루틴이 돌기 전에 막아야 연타가 두 번 나가지 않는다
        val key = planKey
        viewModelScope.launch {
            when (val r = repo.savePlan(sim, key)) {
                is ApiResult.Success -> {
                    planKey = newIdempotencyKey()
                    _state.update { it.copy(saving = false, saveUncertain = false, savedPlanId = r.data.id) }
                }
                is ApiResult.Failure -> _state.update {
                    when {
                        r.error.isOutcomeUnknown -> it.copy(saving = false, saveUncertain = true, message = "응답을 받지 못했어요 · 다시 눌러도 한 번만 저장돼요")
                        r.error.code == "IDEMPOTENCY_KEY_REUSED" -> it.copy(saving = false, savedElsewhere = true, message = "앞서 저장한 계획이 이미 들어갔어요 · 잔고예보에서 확인해 주세요")
                        else -> {
                            if (r.error is ApiError.NotFound) planKey = newIdempotencyKey()   // 이 키로 만든 계획을 지웠거나 카드 · 계좌가 없다
                            it.copy(saving = false, saveUncertain = false, message = r.error.userMessage())
                        }
                    }
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
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val reduce = YamuTheme.reduceMotion
    var heroTop by remember { mutableIntStateOf(0) }
    // 그래프에서 고른 날 — 결과(조건 · 기간)가 바뀌면 그 결과의 구매일로 다시. 한 번 만졌으면 첫 안내(손잡이 흔들기)는 다시 하지 않는다
    val simKey = sim?.let { "${it.query}|${it.days.firstOrNull()?.date}|${it.days.size}" }
    var simDay by rememberSaveable(simKey) { mutableIntStateOf(sim?.let(::simInitialDay) ?: 0) }
    var simTouched by rememberSaveable { mutableStateOf(false) }
    var flash by remember(simKey) { mutableIntStateOf(0) }
    // '빠져나가는 날' 줄 — 기간 안이면 그날로, 기간 밖이면 마지막 날로 가며 오른쪽 끝 표시를 깜빡인다. 그래프 위가 가려져 있으면 끌어올린다
    val onWithdrawal: (SimWithdrawal) -> Unit = onWithdrawal@{ w ->
        val days = sim?.days ?: return@onWithdrawal
        val i = days.indexOfFirst { it.date == w.date }
        if (!w.beyondPeriod && i < 0) return@onWithdrawal
        simTouched = true
        if (w.beyondPeriod) {
            simDay = days.lastIndex
            flash++
        } else simDay = i
        if (scroll.value > heroTop) scope.launch { if (reduce) scroll.scrollTo(heroTop) else scroll.animateScrollTo(heroTop) }
    }
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onBack,
        cards = true,
        bottomBar = {
            when {
                sim == null -> Unit
                // 응답을 못 받은 저장 — 같은 키라 다시 눌러도 한 번만 들어간다. 잔고예보에서 먼저 확인해도 된다
                s.saveUncertain && !s.savedElsewhere && sim.canSavePlan -> BottomCta(secondary = { TextButtonGrey("잔고예보에서 확인하기", onClick = onForecast, modifier = Modifier.fillMaxWidth(), small = true) }) {
                    PrimaryButton("다시 저장하기", onClick = viewModel::save, loading = s.saving, enabled = !s.loading)
                }
                s.saveUncertain || s.savedElsewhere -> BottomCta { PrimaryButton("잔고예보에서 확인하기", onClick = onForecast) }
                sim.query.isInstallment -> BottomCta {
                    Text("할부 구매는 가계부에 할부 칸이 없어 아직 계획으로 저장할 수 없어요", style = YamuTheme.typography.bodyS, color = YamuTheme.colors.text3, modifier = Modifier.padding(bottom = 8.dp))
                    PrimaryButton("일시불로 다시 보기", onClick = { viewModel.setMonths(null) }, enabled = !s.loading)
                }
                sim.canSavePlan -> BottomCta {
                    PrimaryButton("${sim.query.purchaseDate.toKoreanDate()} 구매 계획으로 저장", onClick = viewModel::save, loading = s.saving, enabled = !s.loading)
                }
                else -> Unit
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(scroll)) {
            // '어떻게' 시트도 카드 목록을 읽는다 — 쓸 수 있는 신용카드가 없으면 할부를 닫는다(-588)
            Header(s.query, sim, onCard = openCards, onHow = {
                viewModel.loadCards()
                sheet = "how"
            }, onDate = { picking = true })
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
                    Column(Modifier.alpha(dim).onPlaced { heroTop = it.positionInParent().y.roundToInt() }) {
                        HeroCard(
                            sim,
                            viewModel.alertThreshold,
                            forecast = viewModel.forecast,
                            selected = simDay,
                            onSelect = { simDay = it },
                            interacted = simTouched,
                            onInteract = { simTouched = true },
                            flash = flash,
                        )
                        ScheduleCard(sim, selectedDate = sim.days.getOrNull(simDay)?.date, onWithdrawal = onWithdrawal)
                        BenefitCard(sim)
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    when (sheet) {
        "card" -> CardSheet(s.cards, s.cardsError, s.query.userCardId, installment = s.query.isInstallment, onRetry = viewModel::reloadCards, onDismiss = { sheet = null }, onPick = {
            sheet = null
            viewModel.setCard(it)
        })
        "how" -> HowSheet(s.query, s.cards, onDismiss = { sheet = null }, onApply = { channel, months ->
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
            q.userCardId == null -> sim?.card?.name?.let { "$it (추천)" } ?: "혜택이 가장 큰 카드"
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

/** 사고 나면 가장 낮을 때 — 전(회색 취소선) → 후, 상태(사실만), BE 안내 문장, 전후 그래프(손잡이 · 구매일 · 출금일, [SimGraph]) */
@Composable
private fun HeroCard(
    sim: Simulation,
    threshold: Long?,
    forecast: Forecast?,
    selected: Int,
    onSelect: (Int) -> Unit,
    interacted: Boolean,
    onInteract: () -> Unit,
    flash: Int,
) {
    val c = YamuTheme.colors
    val after = sim.after
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("사고 나면 가장 낮을 때", style = YamuTheme.typography.titleS, color = c.text2)
            if (sim.isEstimate) {
                Spacer(Modifier.width(6.dp))
                EstimateTag()
            }
            // 어느 계좌 잔액인지 — 연결 계좌 전체의 합으로 계산한다(S15P31E103-589)
            sim.accountsLabel?.let {
                Spacer(Modifier.weight(1f))
                Text(it, style = YamuTheme.typography.bodyS, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            }
        }
        // 전 → 후. 같으면(출금이 예보 기간 밖 · 고르지 않은 계좌 · 전액 즉시할인) 숫자 하나에 '사기 전과 같아요'를 붙여
        // 구매 때문에 내려가는 값으로 읽히지 않게 한다(QA). 후 값이 없으면 0원으로 채우지 않고 숫자를 뺀다
        val minAfter = after?.minBalance
        val minBefore = sim.before?.minBalance
        if (minAfter != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (minBefore != null && minBefore != minAfter) {
                    Text("${minBefore.toWon()}원", style = YamuTheme.typography.titleM.copy(textDecoration = TextDecoration.LineThrough), color = c.text3, maxLines = 1)
                    Icon(YamuIcons.ChevronRight, contentDescription = "에서", tint = c.text3, modifier = Modifier.padding(horizontal = 4.dp).size(18.dp))
                }
                AmountText(minAfter, shortage = minAfter < 0, color = c.primary, fitOneLine = true, modifier = Modifier.weight(1f, fill = false))
                if (minBefore == minAfter) {
                    Spacer(Modifier.width(8.dp))
                    Text(SAME_AS_BEFORE, style = YamuTheme.typography.bodyM, color = c.text3, maxLines = 1)
                }
            }
        }
        afterTag(after)?.let { (text, tone) ->
            Spacer(Modifier.height(8.dp))
            StatusTag(text, tone = tone)
        }
        listOfNotNull(sim.message, sim.outsideNote?.takeIf { sim.message == null }).forEach {
            Spacer(Modifier.height(10.dp))
            Text(it, style = YamuTheme.typography.bodyM, color = c.text2)
        }
        sim.cardAccountShortfall?.let {
            Spacer(Modifier.height(12.dp))
            ShortfallNote(it)
        }
        if (sim.days.size >= 2) {
            Spacer(Modifier.height(YamuSpacing.x5))
            SimGraph(sim, threshold, forecast, selected, onSelect, interacted, onInteract, flash)
        }
    }
}

internal const val SAME_AS_BEFORE = "사기 전과 같아요"

/**
 * 사고 난 뒤 상태 — 권하거나 말리지 않고 잔액 사실만(FR-032 · BE 안내 문장과 같은 원칙).
 * 날짜는 가장 낮은 날이 아니라 BE가 준 안전 잔액 아래로 처음 내려가는 날(riskStartDate)이고, 문구도 잔고예보 첫 화면 태그와 같다(점검 B-08).
 * 그 날이 0원 아래로 내려가는 날보다 이를 수 있어 '잔액 부족'이라 쓰지 않는다. 0원 아래면 빨간색으로만 구분한다.
 * 그 칸이 없는 옛 응답이면 날짜를 빼고 보인다
 */
internal fun afterTag(after: SimSnapshot?): Pair<String, TagTone>? {
    after ?: return null
    val below = after.riskStartDate?.let { "${it.toKoreanDate()}부터 안전 잔액 아래" } ?: "안전 잔액 아래로 내려가요"
    return when {
        (after.minBalance ?: 0) < 0 || after.status == RiskLevel.DANGER -> below to TagTone.Error
        after.status == RiskLevel.WARNING -> below to TagTone.Warning
        after.status == RiskLevel.SAFE -> "가장 낮을 때도 안전 잔액 위" to TagTone.Success
        else -> null
    }
}

/**
 * 전체 계좌 합은 괜찮아도 카드 출금 계좌 하나만 보면 모자랄 때(S15P31E103-589) — 어느 계좌에 얼마, 가장 모자랄 때.
 * 0원 아래라 그래프 · 고른 날 칸의 '0원 아래'와 같은 빨간 계열이다
 */
@Composable
private fun ShortfallNote(shortfall: AccountShortfall) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.errorContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(shortfall.text, style = YamuTheme.typography.titleS, color = c.onErrorContainer)
        shortfall.date?.let {
            Text("가장 모자랄 때 ${it.toKoreanDateWeek()}", style = YamuTheme.typography.bodyS, color = c.onErrorContainer, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * 빠져나가는 날 — 회차마다, 예보 기간 밖이면 태그. 줄을 누르면 그래프가 그날로 가고(기간 밖이면 오른쪽 끝 표시가 깜빡인다)
 * 그래프에서 고른 날과 같은 날 줄은 연한 파랑 바탕이다
 */
@Composable
private fun ScheduleCard(sim: Simulation, selectedDate: LocalDate?, onWithdrawal: (SimWithdrawal) -> Unit) {
    if (sim.schedule.isEmpty()) return
    val c = YamuTheme.colors
    val chartDates = remember(sim) { sim.days.map { it.date }.toSet() }
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("빠져나가는 날", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            Text(
                sim.cardAccount?.shortName() ?: "${sim.card.name} 출금 계좌",
                style = YamuTheme.typography.bodyS,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(4.dp))
        sim.schedule.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            // 그래프로 갈 수 있는 줄만 누를 수 있다 — 기간 안이면 그날이 그래프에 있어야 하고, 기간 밖이면 그래프가 두 날 이상일 때
            val reachable = sim.days.size >= 2 && (w.beyondPeriod || w.date in chartDates)
            ListRow(
                title = w.label,
                sub = w.date?.toKoreanDateWeek(),
                value = "${w.amount.toWon()}원",
                valueColor = if (w.beyondPeriod) c.text2 else c.text1,
                titleTag = if (w.beyondPeriod) ({ StatusTag("기간 밖", tone = TagTone.Neutral) }) else null,
                dense = true,
                modifier = Modifier.dayRow(!w.beyondPeriod && w.date != null && w.date == selectedDate, c.primaryContainer),
                onClick = if (reachable) ({ onWithdrawal(w) }) else null,
            )
        }
    }
}

/** 이 카드로 사면 — 혜택 합계 · 줄(원화), 청구 금액. 받을 혜택이 없으면 '1순위' 태그 없이 [NO_BENEFIT], 계산하지 못했으면 이유 */
@Composable
private fun BenefitCard(sim: Simulation) {
    val c = YamuTheme.colors
    val b = sim.benefit
    val none = hasNoBenefit(b)
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${sim.card.name}${sim.card.name.directionParticle()} 사면", style = YamuTheme.typography.cardTitle, color = c.text1, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (sim.card.recommendedTop && !none) StatusTag("혜택 1순위", tone = TagTone.Primary)
        }
        Spacer(Modifier.height(8.dp))
        if (none) {
            Text(NO_BENEFIT, style = YamuTheme.typography.titleS, color = c.text2)
            b?.charged?.let {
                Spacer(Modifier.height(8.dp))
                StepLine("청구 금액", "${it.toWon()}원")
            }
        } else if (b?.net != null) {
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

internal const val NO_BENEFIT = "받을 수 있는 혜택이 없어요"

/**
 * 받을 혜택이 없는지 — 순혜택을 계산했고 0원이며, 0원이 아닌 줄이나 금액을 모르는 줄('금액 미정')도 없을 때.
 * 혜택이 모두 같으면(0원 포함) BE가 기본 카드 → 이번에 실적을 처음 채우는 카드 → 카드 순서로 고르는데 시뮬레이션 응답에는
 * 그 이유가 없다. 그래서 '혜택 1순위'나 '기본 카드로' 대신 이유 없는 문구를 쓴다(S15P31E103-591).
 * 계산하지 못했으면(benefit · net null) 여기 들지 않고 '혜택은 계산하지 못했어요'로 따로 보인다
 */
internal fun hasNoBenefit(benefit: SimBenefit?): Boolean {
    val net = benefit?.net ?: return false
    return net.signum() == 0 && benefit.lines.all { it.amount != null && it.amount.signum() == 0 }
}

@Composable
private fun StepLine(label: String, value: String) {
    val c = YamuTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = YamuTheme.typography.bodyM, color = c.text1)
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

/** 어디서 어떻게 — 매장/온라인 · 일시불/할부(신용카드 · 5만 원 이상, 카드를 안 골랐으면 쓸 수 있는 신용카드가 있을 때) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HowSheet(query: SimulationQuery, cards: List<SimCardOption>?, onDismiss: () -> Unit, onApply: (PaymentChannel, Int?) -> Unit) {
    val c = YamuTheme.colors
    var channel by rememberSaveable { mutableStateOf(query.channel) }
    var months by rememberSaveable { mutableStateOf(query.installmentMonths) }
    val canInstallment = installmentAllowed(query.amount, cards?.firstOrNull { it.userCardId == query.userCardId }, cards)
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
