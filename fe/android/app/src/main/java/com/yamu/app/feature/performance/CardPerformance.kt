package com.yamu.app.feature.performance

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.format.clipName
import androidx.compose.ui.graphics.Color
import com.yamu.app.feature.performance.data.manLabel
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfDecision
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.SectionBlock
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PerformanceGauge
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toManWon
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BrandCardArt
import com.yamu.app.feature.link.DIMMED_CARD_ALPHA
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.performance.data.AT_RISK_LABEL
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.BenefitLine
import com.yamu.app.feature.performance.data.PerfDetail
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.data.visibleTiers
import com.yamu.app.navigation.CardPerformance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CardPerfState(
    val loading: Boolean = true,
    val card: CardPerf? = null,
    val detail: PerfDetail? = null,
    val error: String? = null,
    /** 합친 화면의 '더 써야 할까요?' — 기준 전 카드만 받는다. 못 받으면 덩어리만 숨긴다 */
    val analysis: PerfAnalysis? = null,
    val analysisNoData: Boolean = false,
    val daysLeft: Int? = null,
    val acting: Boolean = false,
    val message: String? = null,
)

/** R-02 카드 실적 상세(#66) — 카드 이름 · 판정은 실적관리 목록(#65)에서 */
@HiltViewModel
class CardPerformanceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val perf: PerformanceRepository,
    link: LinkRepository,
) : ViewModel() {
    private val userCardId = savedStateHandle.toRoute<CardPerformance>().userCardId
    private val _state = MutableStateFlow(CardPerfState())
    val state: StateFlow<CardPerfState> = _state.asStateFlow()

    /** 별명 카드의 그림 — 실적 응답엔 상품명이 없어 카드 목록에서 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    init {
        artLoader.ensure(listOf(userCardId))
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val overview = perf.cachedOverview() ?: (perf.overview() as? ApiResult.Success)?.data
            val card = overview?.all?.firstOrNull { it.userCardId == userCardId }
            when (val r = perf.detail(userCardId)) {
                // 옮기기 뒤 다시 불러올 때 막 띄운 안내는 남긴다
                is ApiResult.Success -> _state.update { CardPerfState(loading = false, card = card, detail = r.data, daysLeft = overview?.daysLeft, message = it.message) }
                is ApiResult.Failure -> _state.update { CardPerfState(loading = false, card = card, error = r.error.userMessage(), daysLeft = overview?.daysLeft, message = it.message) }
            }
            if (card?.pending == true) loadAnalysis()
        }
    }

    private suspend fun loadAnalysis() {
        when (val r = perf.analysis(userCardId)) {
            is ApiResult.Success -> _state.update { it.copy(analysis = r.data, analysisNoData = false) }
            is ApiResult.Failure -> _state.update { it.copy(analysis = null, analysisNoData = r.error is ApiError.DataInsufficient) }
        }
    }

    /** [이번 달은 더 안 쓸게요] · [다시 판단하기](null) */
    fun decide(decision: PerfDecision?) {
        val a = _state.value.analysis ?: return
        if (_state.value.acting) return
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            when (val r = perf.decide(a.userCardId, decision, a.version)) {
                is ApiResult.Success -> _state.update {
                    it.copy(acting = false, analysis = a.copy(decision = decision), message = if (decision == null) "다시 알려 드릴게요" else "이번 달 판단을 기록했어요")
                }
                is ApiResult.Failure -> _state.update { it.copy(acting = false, message = r.error.userMessage()) }
            }
        }
    }

    /** [통신비 결제 카드 OO로 바꾸기] — 예정 지출 결제 카드 바꾸기(#8) + '채우기' 기록(#35) → 다시 불러오기 */
    fun moveAndFill() {
        val a = _state.value.analysis ?: return
        val item = a.movable ?: return
        if (_state.value.acting) return
        val cardName = _state.value.card?.name ?: "이 카드"
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            val moved = perf.moveCashflow(item.cashflowItemId, a.userCardId)
            if (moved is ApiResult.Failure) {
                _state.update { it.copy(acting = false, message = moved.error.userMessage()) }
                return@launch
            }
            perf.decide(a.userCardId, PerfDecision.FILL, a.version)
            val deadline = parseDate(item.deadlineIso)?.toMonthDay()?.let { "${it}까지 " }.orEmpty()
            _state.update {
                it.copy(acting = false, message = "${item.title} 결제 카드를 $cardName${cardName.directionParticle()} 바꿔 뒀어요 · $deadline${item.title}${item.title.objectParticle()} 내는 곳에서도 바꿔 주세요")
            }
            load()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun CardPerformanceRoute(
    onBack: () -> Unit,
    onAnalysis: (userCardId: Long) -> Unit,
    viewModel: CardPerformanceViewModel = hiltViewModel(),
    calcInitiallyOpen: Boolean = false,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    val c = YamuTheme.colors
    val card = s.card
    val d = s.detail
    val a = s.analysis
    val cardName = card?.name ?: "카드"
    // 카드 한 장의 모든 것 — 이번 달 실적 → 더 써야 할까요?(기준 전만, FR-031) → 다음 달 혜택.
    // 아래 버튼 없음 — 결제 수단을 바꿔 주는 서비스가 아니라 안내만 한다(예정 지출 이야기는 문장으로, FR-032)
    FlowScreen(
        title = "$cardName 실적",
        onBack = onBack,
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
          Column(Modifier.padding(horizontal = YamuSpacing.screen)) {
            Spacer(Modifier.height(YamuSpacing.x6))
            card?.let {
                PerfCardArt(it, art = arts[it.userCardId])
                Spacer(Modifier.height(YamuSpacing.x6))
            }
            when {
                s.loading -> repeat(3) {
                    SkeletonBlock(height = 48.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                }
                d == null -> ErrorState(s.error ?: "실적을 불러오지 못했어요", onRetry = viewModel::load)
                d.noTarget -> {
                    // 실적 조건이 없어도 위는 다른 카드와 같은 모양(n월 실적 · 큰 숫자) — 게이지 대신 '조건 없음' 한 줄
                    Text(d.month?.let { "${it}월 실적" } ?: "이번 달 실적", style = YamuTheme.typography.bodyM, color = c.text3)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    AmountText(d.recognized)
                    Spacer(Modifier.height(10.dp))
                    Text("실적 조건 없이 쓴 만큼 혜택을 받아요", style = YamuTheme.typography.bodyM, color = c.text2)
                    Spacer(Modifier.height(20.dp))
                }
                else -> {
                    // ① 이번 달 실적 — 목록 줄과 같은 말(실적 / 기준 · 달성 · 남은 돈). 게이지는 0에서 차오른다
                    Text(d.month?.let { "${it}월 실적" } ?: "이번 달 실적", style = YamuTheme.typography.bodyM, color = c.text3)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    val steps = d.tiers.mapNotNull { it.threshold }
                    val multi = steps.size > 1
                    // 큰 숫자와 '/ 400,000원'은 글자 바닥을 맞춘다(프로토타입 R-02 .big baseline). 여러 단계면 계단 게이지가 단계를 보여 주므로 '/ 기준'은 뺀다
                    Row {
                        AmountText(d.recognized, Modifier.alignByBaseline())
                        Spacer(Modifier.width(6.dp))
                        if (!multi) d.target?.let { Text("/ ${it.toWon()}원", style = YamuTheme.typography.bodyL, color = c.text3, modifier = Modifier.alignByBaseline()) }
                    }
                    d.target?.let { target ->
                        Spacer(Modifier.height(16.dp))
                        // 여러 단계 — 계단 게이지 + 점 아래 단계 금액(단계별 혜택 설명은 아래 '12월 혜택'). 한 단계 — 막대
                        if (multi) TierStepper(d.recognized, steps, showLabels = true)
                        else {
                            // 0에서 차오른다(목록과 같은 animatedFill) — 다 차면 실제 값 그대로 넘겨 기준에 닿은 색을 정확히
                            val shown = animatedFill(d.recognized.toFloat())
                            PerformanceGauge(if (shown == d.recognized.toFloat()) d.recognized else shown.toLong(), target, showCaption = false, height = 6.dp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 목록과 같게 퍼센트 없이 — 달성 · '20만원 달성'(여러 단계 중 일부) · 미달성
                            val reached = d.reachedThreshold()
                            Text(
                                when {
                                    d.achieved -> "달성"
                                    reached != null -> "${manLabel(reached)} 달성"
                                    else -> "미달성"
                                },
                                style = YamuTheme.typography.titleS,
                                color = if (d.achieved || reached != null) c.success else c.text2,
                                modifier = Modifier.weight(1f),
                            )
                            if (!d.achieved) Text(
                                if (reached != null) "다음 단계까지 ${d.remaining.toWon()}원 남음" else "${d.remaining.toWon()}원 남음",
                                style = YamuTheme.typography.bodyM,
                                color = c.text2,
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))   // 덩어리 아래 20(.section) — 다음 덩어리 위 24는 SectionBlock이
                }
            }
          }
            // ② 더 써야 할까요? — 기준 전 카드만. 답 한 줄 + 이유 한 문장 + '어떻게 계산했나요?'(근거, FR-031).
            // 추가 소비가 이득이라는 말 없이 — 예정 지출로 채워지면 그 안내, 아니면 중립(FR-032). 기록이 없으면 한 줄, 분석을 못 받으면 숨김
            if (d != null && !d.noTarget && card?.pending == true) {
                when {
                    a != null && a.projected != null && a.target != null ->
                        SectionBlock(title = "더 써야 할까요?") {
                            VerdictSection(a, cardName, secured = d.reachedThreshold(), expected = d.expectedThreshold(a.projected), calcInitiallyOpen = calcInitiallyOpen)
                        }
                    s.analysisNoData -> SectionBlock(title = "더 써야 할까요?") {
                        Text("쓴 기록이 적어서 아직 판단하기 일러요", style = YamuTheme.typography.bodyL, color = c.text2)
                        Text("한 달쯤 쓰면 알려 드릴게요", style = YamuTheme.typography.bodyM, color = c.text3)
                    }
                }
            }
            // ③' 실적 조건 없는 카드 — 받는 혜택(이름 · 값 · 결제 조건 · 한도 없음). 혜택을 못 받아 오면 숨김
            if (d != null && d.noTarget && d.benefits.isNotEmpty()) {
                SectionBlock(title = "받는 혜택") {
                    d.benefits.forEach { b -> KeyValueRow(b.name, b.value.orEmpty(), keySub = b.conditionText(firstStep = null)) }
                }
            }
            // ③ 다음 달 혜택 — 단계마다 '20만원 이상 쓰면 · 월 최대 15,000원'. 혜택 이름은 처음 생기는 단계에만(같은 이름을 줄마다 되풀이하지 않는다).
            // 여러 단계가 한 가지 혜택이면 이름을 제목 아래 한 번만
            val ladder = d?.takeIf { !it.noTarget }?.benefitLadder().orEmpty()
            // 월말 예상이 닿는 가장 높은 단계 — '더 써야 할까요?'를 보여 주는 기준 전 카드만. 그 단계에 '달성 예상', 그 사이 단계는 진하게
            val expected = if (card?.pending == true) d?.expectedThreshold(a?.projected) else null
            // 혜택 내용(아래 ④)을 받으면 단계 줄엔 이름을 빼고 금액만 — 50만 · 100만처럼 한도만 커지는 단계도 같은 모양
            val listed = d != null && d.benefits.isNotEmpty()
            if (d != null && ladder.isNotEmpty()) {
                val onlyBenefit = ladder.mapNotNull { it.newBenefit }.singleOrNull()?.takeIf { ladder.size > 1 && !listed }
                SectionBlock(title = d.month?.let { "${it % 12 + 1}월 혜택" } ?: "다음 달 혜택") {
                    onlyBenefit?.let { Text(it, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.padding(bottom = 4.dp)) }
                    ladder.forEach { r ->
                        KeyValueRow(
                            "${r.label} 쓰면",
                            r.amountText,
                            keySub = r.newBenefit.takeIf { onlyBenefit == null && !listed },
                            keyTag = when {
                                r.achieved -> ({ StatusTag("받아요", tone = TagTone.Success) })
                                r.threshold == expected -> ({ StatusTag("달성 예상", tone = TagTone.Primary) })
                                r.next && expected == null && ladder.size > 1 -> ({ StatusTag("다음 단계", tone = TagTone.Primary) })
                                else -> null
                            },
                            valueColor = if (r.achieved || r.next || (expected != null && r.threshold <= expected)) Color.Unspecified else c.text3,
                        )
                    }
                    if (listed && ladder.size > 1) Text(
                        "월 최대는 아래 혜택을 모두 합쳐 한 달에 받을 수 있는 금액이에요",
                        style = YamuTheme.typography.bodyS,
                        color = c.text3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // ④ 혜택 내용 — 혜택마다 한 번씩 이름 · 값 · 건당 최대 · 조건. 첫 단계보다 높은 실적이 필요한 혜택만 'n만원부터'
            if (d != null && !d.noTarget && listed) {
                val firstStep = d.tiers.mapNotNull { it.threshold }.minOrNull()
                SectionBlock(title = "혜택 내용") {
                    d.benefits.forEach { b -> KeyValueRow(b.name, b.value.orEmpty(), keySub = b.conditionText(firstStep)) }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/**
 * 게이지 아래 한 줄 — 서버 다음 달 문구('150,000원 더 쓰면 다음 달 50만원 이상 구간 혜택을 받아요')가 있으면 그대로,
 * 없으면 앱이 만든 남은 금액 문구. 실적 기준이 없으면 null
 */
/** 다음 달 혜택 한 줄 — '30만원 이상' · 이 단계에서 처음 생기는 혜택(없으면 null) · '월 최대 20,000원' · 넘김 · 지금 향하는 단계 */
/** 혜택 이름 아래 작은 줄 — '30만원부터 · 건당 최대 4,000원 · 월 1번 · 10,000원 이상 결제 · 한도 없음'. 실적 조건 없는 혜택이 단계 카드에 섞이면 '실적 조건 없음' */
internal fun BenefitLine.conditionText(firstStep: Long?): String? = listOfNotNull(
    when {
        firstStep == null -> null
        minPerformance <= 0 -> "실적 조건 없음"
        minPerformance > firstStep -> "${manLabel(minPerformance)}부터"
        else -> null
    },
    perTransaction?.let { "건당 최대 ${it.toWon()}원" },
    monthlyCount?.let { "월 ${it}번" },
    minPayment.takeIf { it > 0 }?.let { "${it.toWon()}원 이상 결제" },
    "한도 없음".takeIf { !limited },
).joinToString(" · ").takeIf { it.isNotEmpty() }

internal data class LadderRow(val label: String, val newBenefit: String?, val amountText: String, val achieved: Boolean, val next: Boolean, val threshold: Long)

/** 단계마다 한 줄. 혜택 이름은 그 이름이 처음 생기는 단계에만 둔다. 이름도 금액도 없는 단계는 뺀다(보여 줄 게 없어서) */
internal fun PerfDetail.benefitLadder(): List<LadderRow> {
    val seen = mutableSetOf<String>()
    return tiers.filter { it.threshold != null }.mapNotNull { t ->
        val added = t.benefit?.takeIf { it.isNotBlank() && seen.add(it) }
        if (added == null && t.benefitAmount == null) return@mapNotNull null
        LadderRow(
            label = t.label.removeSuffix(" 원"),
            newBenefit = added,
            amountText = t.amountText,
            achieved = t.achieved,
            next = !achieved && t.threshold == target,
            threshold = t.threshold ?: return@mapNotNull null,
        )
    }
}

/** 말로 읽는 금액 — 220,000 → '22만 원', 35,000 → '3만 5천 원', 5,000 → '5천 원'. 예상 금액은 천 원 아래를 버린다 */
internal fun spokenWon(amount: Long): String {
    val v = amount / 1_000 * 1_000
    val man = v / 10_000
    val chun = (v % 10_000) / 1_000
    return when {
        v <= 0 -> "${amount.toWon()}원"
        man == 0L -> "${chun}천 원"
        chun == 0L -> "${man.toWon()}만 원"
        else -> "${man.toWon()}만 ${chun}천 원"
    }
}

/** 예상 금액을 만원 단위로 — 763,954 → '76만원'(만원 아래 버림, 기준 이상인 예상을 기준보다 높게 말하지 않게). 1만원 아래는 원 단위 그대로 */
internal fun aboutManWon(amount: Long): String =
    if (amount < 10_000) "${amount.toWon()}원" else "${(amount / 10_000).toWon()}만원"

/**
 * 월말 예상이 닿는 가장 높은 단계(아직 못 넘긴 것) — '12월 혜택'의 '달성 예상'과 '혜택 조건' 줄. 예상이 다음 단계에 못 닿으면 null.
 * 노리(20만 넘김 · 예상 763,954원 · 20/30/50/100만) → 50만
 */
internal fun PerfDetail.expectedThreshold(projected: Long?): Long? {
    val p = projected ?: return null
    return tiers.filter { !it.achieved && it.threshold != null && it.threshold <= p }.mapNotNull { it.threshold }.maxOrNull()
}

/**
 * '더 써야 할까요?'의 답 한 줄 + 이유 한 문장. 판정 이름(달성 추천 등) 없이 월말 예상과 기준만으로 —
 * 넘을 것 같으면 평소대로(닿는 단계는 '12월 혜택'의 '달성 예상'으로), 예정 지출로 채워지면 그 안내,
 * 여러 단계에서 아래 단계는 지키면 그 말, 나머지는 중립(FR-032)
 */
internal fun PerfAnalysis.verdict(card: String, secured: Long?): Pair<String, String> {
    val projected = projected ?: 0
    val target = target ?: 0
    val thisMonth = month?.let { "${it}월" } ?: "이번 달"
    val next = month?.let { "${it % 12 + 1}월" } ?: "다음 달"
    val goal = manLabel(target)
    val m = movable
    return when {
        // 예상에 예정 지출이 들어 있으면 그것까지 말한다(평소 소비만으로 넘는 것처럼 읽히지 않게). 금액은 만원 아래를 버려 '약 76만원'.
        // '약'과 금액은 줄이 바뀌어도 붙어 있게 줄바꿈 없는 공백
        projected >= target -> "평소처럼만 쓰면 돼요" to
            if ((scheduled ?: 0) > 0) "평소 소비와 예정된 지출을 합하면, 이번 달 실적은 약 ${aboutManWon(projected)}으로 예상돼요."
            else "평소처럼 쓰면 이번 달 실적은 약 ${aboutManWon(projected)}으로 예상돼요."
        m != null && moveFills() -> {
            // 앱이 결제 수단을 바꿔 주지 않으므로 '내는 곳에서' 바꾸라는 안내만
            val deadline = parseDate(m.deadlineIso)?.toMonthDay()?.let { "${it}까지 " }.orEmpty()
            "돈을 더 안 써도 $next 혜택을 받을 수 있어요" to
                "${m.title}(${m.amount.toWon()}원) 결제 카드만 $card${card.directionParticle()} 바꾸면 ${thisMonth}에 ${goal}을 넘겨요. " +
                "$deadline${m.title}${m.title.objectParticle()} 내는 곳에서 바꿔야 해요."
        }
        secured != null -> "이번 달은 다음 단계까지 어려워요" to
            "평소처럼 쓰면 ${thisMonth}에 ${projected.toWon()}원쯤이라 ${goal}에 ${(target - projected).toWon()}원 모자라요. ${manLabel(secured)} 단계 혜택은 그대로 받아요."
        else -> "이번 달은 채우기 어려워요" to
            "평소처럼 쓰면 ${thisMonth}에 ${projected.toWon()}원쯤이라 ${goal}에 ${(target - projected).toWon()}원 모자라요. 필요한 결제가 아니면 억지로 채우지 않아도 돼요."
    }
}

@Composable
private fun VerdictSection(a: PerfAnalysis, cardName: String, secured: Long?, expected: Long?, calcInitiallyOpen: Boolean) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val (title, body) = a.verdict(cardName.clipName(), secured)
    Text(title, style = t.titleL, color = c.text1)
    Spacer(Modifier.height(6.dp))
    Text(body, style = t.bodyL, color = c.text2)
    Spacer(Modifier.height(8.dp))
    CalcDetails(initiallyExpanded = calcInitiallyOpen) { CalcRows(a, expected) }
}

/**
 * '어떻게 계산했나요?' — 분석이 실제로 쓰는 값 전부(BE PerformanceAnalyzer): 지금까지 + 남은 날 평소 소비(지난 달들 같은 기간 평균 · 하루 평균)
 * + 이 카드로 나갈 예정 지출 = 이번 달 예상 → 혜택 조건. 다른 결제 수단의 예정 지출은 옮길 수 있는 것 · 없는 것(사유)까지
 */
@Composable
private fun CalcRows(a: PerfAnalysis, expected: Long?) {
    val estimate: (@Composable () -> Unit)? = if (a.isEstimate) ({ EstimateTag() }) else null
    val thisMonth = a.month?.let { "${it}월" } ?: "이번 달"
    // 지금까지 + 남은 날 평소 소비(지난 달들 이맘때 막대 · 하루 평균) + 예정 지출 = 이번 달 예상 → 혜택 조건
    a.recognized?.let { KeyValueRow("지금까지 쓴 돈", "${it.toWon()}원", large = false) }
    a.natural?.let {
        KeyValueRow(
            a.daysLeft?.let { d -> "남은 ${d}일 평소 쓰는 만큼" } ?: "월말까지 평소 쓰는 만큼",
            "+${it.toWon()}원",
            large = false,
            keyTag = estimate,
            keySub = listOfNotNull("지난 ${a.history.size.takeIf { n -> n > 0 } ?: 3}개월 이맘때 평균", a.averageDaily?.let { d -> "하루 ${d.toWon()}원꼴" }).joinToString(" · "),
        )
    }
    if (a.history.isNotEmpty()) HistoryBars(a.history)
    a.scheduled?.takeIf { it > 0 }?.let { KeyValueRow("이 카드로 나갈 예정 지출", "+${it.toWon()}원", large = false) }
    a.projected?.let { KeyValueRow("$thisMonth 예상 합계", "${it.toWon()}원", large = false, keyTag = estimate) }
    // 예상이 닿는 단계가 있으면 그 단계(12월 혜택의 '달성 예상'과 같은 값), 없으면 다음 단계
    (expected ?: a.target)?.let { KeyValueRow("혜택 조건", "${manLabel(it)} 이상", large = false) }
}

/** 지난 달들 같은 기간 쓴 돈 — 달마다 짧은 막대(평소 소비 예측의 근거) */
@Composable
private fun HistoryBars(history: List<Pair<Int, Long>>) {
    val c = YamuTheme.colors
    val max = history.maxOf { it.second }.coerceAtLeast(1)
    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        history.forEach { (month, amount) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${month}월", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.width(28.dp))
                Box(Modifier.weight(1f).height(6.dp)) {
                    Box(Modifier.fillMaxWidth(amount.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.outlineVariant))
                }
                Text("${amount.toWon()}원", style = YamuTheme.typography.bodyS, color = c.text3, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 64.dp))
            }
        }
    }
}

/** 넘긴 가장 높은 단계(목표보다 낮은 것) — 여러 단계 카드의 '20만원 달성'. 없으면 null */
internal fun PerfDetail.reachedThreshold(): Long? =
    tiers.filter { it.achieved && it.threshold != null && (target == null || it.threshold < target) }.mapNotNull { it.threshold }.maxOrNull()

/** 예정 지출을 이 카드로 옮기면 월말에 기준을 넘나 — 지금 예상은 못 넘고, 옮긴 예상(what-if, 없으면 더해서)은 넘을 때만 */
internal fun PerfAnalysis.moveFills(): Boolean {
    val p = projected ?: return false
    val t = target ?: return false
    val m = movable ?: return false
    return p < t && (projectedIfMoved ?: (p + m.amount)) >= t
}

/**
 * 월말 예상(FR-031) — 예상 실적 · 달성 가능/어려움 · 근거(지금까지 + 평소 쓰는 만큼 + 예정 지출).
 * 못 채울 것 같으면 예정 지출로 채워질 때만 옮기기 안내(FR-032), 아니면 중립 한 줄. 추가 소비가 이득이라는 말은 하지 않는다
 */
@Composable
private fun ForecastBlock(a: PerfAnalysis, cardName: String, secured: Long? = null) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val projected = a.projected ?: return
    val target = a.target ?: return
    val reach = projected >= target
    // 여러 단계 카드 — 예상은 '다음 단계' 기준으로, 이미 넘긴 단계 혜택은 지켜진다고 함께
    val goal = if (secured != null) "다음 단계 ${manLabel(target)}" else "기준 ${target.toWon()}원"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${projected.toWon()}원", style = t.titleL, color = c.text1, modifier = Modifier.weight(1f))
        StatusTag(if (reach) "달성 가능" else "달성 어려움", tone = if (reach) TagTone.Success else TagTone.Neutral, guide = true)
    }
    Text(
        if (reach) "${goal}을 넘어요" else "${goal}보다 ${(target - projected).toWon()}원 적어요",
        style = t.bodyM,
        color = c.text2,
    )
    if (!reach && secured != null) Text("${manLabel(secured)} 단계 혜택은 그대로 받아요", style = t.bodyM, color = c.success)
    Spacer(Modifier.height(12.dp))
    // 근거 — 화면에 그대로(수용 기준 '판단의 근거가 표시된다')
    val estimate: (@Composable () -> Unit)? = if (a.isEstimate) ({ EstimateTag() }) else null
    a.recognized?.let { KeyValueRow("지금까지 쓴 돈", "${it.toWon()}원", large = false) }
    a.natural?.let { KeyValueRow("월말까지 평소 쓰는 만큼", "+${it.toWon()}원", large = false, keyTag = estimate, keySub = "지난 3개월 이맘때 평균") }
    a.scheduled?.takeIf { it > 0 }?.let { KeyValueRow("이 카드로 나갈 예정 지출", "+${it.toWon()}원", large = false) }
    if (!reach) {
        Spacer(Modifier.height(16.dp))
        val m = a.movable
        if (m != null && a.moveFills()) {
            val moved = a.projectedIfMoved ?: (projected + m.amount)
            Text("${m.title} ${m.amount.toWon()}원을 이 카드로 내면 월말 ${moved.toWon()}원으로 채워져요", style = t.bodyL, color = c.text1)
            Spacer(Modifier.height(8.dp))
            HowTo(m, cardName.clipName())
        } else {
            Text("필요한 결제가 아니면 억지로 채우지 않아도 돼요", style = t.bodyL, color = c.text2)
        }
    }
}

/** 게이지 아래 한 줄을 '얼마 더 쓰면 무엇을' 로 — 목표 구간 혜택 이름, 없으면 한도 변화, 그것도 없으면 서버 문구 */
internal fun PerfDetail.stakeLine(): String? {
    val target = target ?: return null
    val next = month?.let { "${it % 12 + 1}월" } ?: "다음 달"
    val goal = tiers.firstOrNull { it.threshold == target }
    val reached = tiers.filter { it.achieved && it.threshold != null && it.threshold < target }.maxByOrNull { it.threshold ?: 0 }
    val max = goal?.benefitAmount?.let { " (월 최대 ${it.toWon()}원)" }.orEmpty()
    return when {
        achieved -> goal?.benefit?.let { "${next}에 $it 받아요" } ?: "$next 혜택을 받아요"
        goal?.benefit != null -> "${remaining.toWon()}원 더 쓰면 ${next}에 ${goal.benefit}$max"
        goal?.benefitAmount != null && reached?.benefitAmount != null ->
            "${remaining.toWon()}원 더 쓰면 $next 혜택 한도가 월 ${reached.benefitAmount.toWon()} → ${goal.benefitAmount.toWon()}원으로 늘어요"
        else -> headline()
    }
}

internal fun PerfDetail.headline(): String? {
    val target = target ?: return null
    nextMonthMessage?.let { return it }
    val goal = targetLabel?.removeSuffix(" 이상") ?: target.toManWon()
    return if (achieved) "$goal 기준을 채웠어요" else "${goal}까지 ${remaining.toWon()}원 남았어요"
}

/**
 * 실적 화면 머리의 카드 그림 — 어떤 카드인지 실물 카드로(R-02 · R-03). 폭은 최대 280dp, 탭을 바꾸면 같이 바뀐다.
 * name은 카드사 이름이라 그림은 [art](카드 목록)의 상품명으로, 모르면 nickname(별명이 없으면 상품명)으로 찾는다.
 * 끝자리는 실적 응답에 없어 빼고 보인다. 정지 · 만료 카드는 흐리게
 */
@Composable
internal fun PerfCardArt(card: CardPerf, modifier: Modifier = Modifier, art: CardArtRef? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Crossfade(card, label = "perfCard") { c ->
            BrandCardArt(
                c.brand,
                c.nickname ?: c.name,
                last4 = null,
                modifier = Modifier.widthIn(max = 280.dp).alpha(if (art?.dimmed == true) DIMMED_CARD_ALPHA else 1f),
                productName = art?.productName ?: c.nickname ?: c.name,
            )
        }
    }
}
