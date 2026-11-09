package com.yamu.app.feature.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.LivingConfidence
import com.yamu.app.feature.forecast.data.LivingCostDetail
import com.yamu.app.feature.forecast.data.LivingMonth
import com.yamu.app.feature.forecast.data.PaceCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── F-01b 생활비 예측(GET forecasts/living-cost) ─────────────────────────

data class LivingCostState(
    val loading: Boolean = true,
    val detail: LivingCostDetail? = null,
    val noRecord: Boolean = false,    // 422 — 고른 어느 계좌에도 생활비 기록이 없다(0원으로 확정하지 않음)
    val noAccount: Boolean = false,
    val error: String? = null,
)

/** 잔고예보와 같은 기간 · 계좌로 부른다(저장소가 지금 고른 값을 쓴다). 조회만이라 다시 불러도 된다 */
@HiltViewModel
class LivingCostViewModel @Inject constructor(private val repo: ForecastRepository) : ViewModel() {
    private val _state = MutableStateFlow(LivingCostState())
    val state: StateFlow<LivingCostState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = it.detail == null, error = null) }
            when (val r = repo.livingCost()) {
                is ApiResult.Success -> _state.update { LivingCostState(loading = false, detail = r.data) }
                is ApiResult.Failure -> _state.update {
                    when {
                        r.error is ApiError.DataInsufficient -> LivingCostState(loading = false, noRecord = true)
                        r.error.code == ForecastRepository.NO_ACCOUNT -> LivingCostState(loading = false, noAccount = true)
                        else -> it.copy(loading = false, error = r.error.userMessage())
                    }
                }
            }
        }
    }
}

/** 잔고예보 화면의 입구 — 예상 생활비 한 줄(기록이 없으면 그 사실), 누르면 F-01b */
@Composable
internal fun LivingCostEntry(f: Forecast, onOpen: () -> Unit) {
    val living = f.livingCost ?: return
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        ListRow(
            title = "생활비는 어떻게 예측했나요?",
            sub = when {
                !living.insufficient && living.projectedAmount != null -> listOfNotNull("${cashflowPeriod(f)} ${living.projectedAmount.toWon()}원", living.method.label).joinToString(" · ")
                // 잔고예보 응답만으로는 일부만 없는지 전부 없는지 몰라(전부면 상세가 422) 계좌별 값을 약속하지 않는다
                f.accountIds.size > 1 -> "생활비 기록이 없는 계좌가 있어 합계를 내지 않았어요"
                else -> "생활비 기록이 아직 없어요"
            },
            chevron = true,
            onClick = onOpen,
        )
    }
}

@Composable
fun LivingCostRoute(onBack: () -> Unit, onLink: () -> Unit = {}, viewModel: LivingCostViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    FlowScreen(title = "생활비 예측", onBack = onBack, cards = true) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val d = s.detail
            when {
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 생활비를 예측해 드려요", actionText = "계좌 연결", onAction = onLink) }
                s.noRecord -> YamuCard { EmptyState("생활비 기록이 아직 없어요\n체크카드로 결제하면 다음 예보부터 들어가요") }
                d == null && s.loading -> YamuCard {
                    SkeletonBlock(height = 28.dp, widthFraction = 0.5f)
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 120.dp)
                }
                d == null -> YamuCard { ErrorState(s.error ?: "생활비 예측을 불러오지 못했어요", onRetry = viewModel::refresh) }
                else -> {
                    ProjectedCard(d)
                    if (d.months.isNotEmpty() || d.dailyAverage != null) HistoryCard(d)
                    d.pace?.let { PaceCard(it) }
                    if (d.accounts.size > 1 || d.accountsWithoutRecord.isNotEmpty()) AccountsCard(d)
                    if (d.excludedNotes.isNotEmpty()) ExcludedCard(d.excludedNotes)
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/** 예상 생활비 — 잔고예보 'AI 생활비 반영' 선(conservative)에서 날마다 뺀 값의 합. 평균 × 일수가 아니다(BE 11/5 안내) */
@Composable
private fun ProjectedCard(d: LivingCostDetail) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.periodLabel} 예상 생활비", style = YamuTheme.typography.titleS, color = c.text2)
            Spacer(Modifier.width(6.dp))
            EstimateTag()
        }
        Spacer(Modifier.height(4.dp))
        val projected = d.projectedAmount
        if (projected != null) AmountText(projected) else Text("계산하지 못했어요", style = YamuTheme.typography.headlineS, color = c.text2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            d.method.label?.let { StatusTag(it, tone = TagTone.Primary) }
            d.confidence.label.takeIf { it.isNotEmpty() }?.let { StatusTag(it, tone = if (d.confidence == LivingConfidence.HIGH) TagTone.Success else TagTone.Neutral) }
        }
        if (d.from != null && d.to != null) {
            Text("${d.from.toMonthDay()} ~ ${d.to.toMonthDay()} · 오늘 쓴 돈은 지금 잔액에 이미 들어 있어 내일부터 셉니다", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 8.dp))
        }
        if (projected == null) Text("생활비 기록이 없는 계좌가 있어 합계를 내지 않았어요 · 아래 계좌별 값을 봐 주세요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
        if (d.seeded) Text("모의 소비 기록이 섞여 있어요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
        d.dailyTarget?.let { Text("설정한 하루 목표 ${it.toWon()}원 · 예측에는 섞지 않아요", style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** 지난 3개월 실제 — 달별 합계 막대와 평균. 예측 값이 아니라 근거 */
@Composable
private fun HistoryCard(d: LivingCostDetail) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text("지난 3개월 실제 생활비", style = YamuTheme.typography.cardTitle, color = c.text1)
        listOfNotNull(d.dailyAverage?.let { "하루 평균 ${it.toWon()}원" }, d.monthlyAverage?.let { "한 달 평균 ${it.toWon()}원" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 4.dp))
        }
        val max = d.months.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1L
        d.months.forEach { m -> MonthBar(m, fraction = m.amount.toFloat() / max) }
    }
}

@Composable
private fun MonthBar(m: LivingMonth, fraction: Float) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) { contentDescription = "${m.month.monthValue}월 ${m.amount.toWon()}원" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${m.month.monthValue}월", style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.width(40.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(c.primary))
        }
        Text("${m.amount.toWon()}원", style = YamuTheme.typography.bodyM, color = c.text1, modifier = Modifier.padding(start = 10.dp))
    }
}

/** 이번 달 소비 속도 — 평소의 1.3배를 넘으면 주의(사실만, 권하거나 말리지 않음) */
@Composable
private fun PaceCard(p: PaceCheck) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이번 달 소비 속도", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            StatusTag(if (p.flagged) "평소보다 빨라요" else "평소 속도", tone = if (p.flagged) TagTone.Warning else TagTone.Success)
        }
        val ratio = p.overPercent?.takeIf { it > 0 }?.let { "평소보다 ${it}% 더 쓰고 있어요" }
        listOfNotNull(
            p.thisMonthDaily?.let { "이번 달 하루 평균 ${it.toWon()}원" },
            p.averageDaily?.let { "평소 ${it.toWon()}원" },
        ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 6.dp)) }
        ratio?.let { Text(it, style = YamuTheme.typography.bodyS, color = if (p.flagged) c.onWarningContainer else c.text3, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** 계좌별 — 기록이 없는 계좌가 있으면 잔고예보의 'AI 생활비 반영' 선이 빠진다 */
@Composable
private fun AccountsCard(d: LivingCostDetail) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Text("계좌별", style = YamuTheme.typography.cardTitle, color = c.text1)
        if (d.accountsWithoutRecord.isNotEmpty()) {
            Text("기록이 없는 계좌가 있으면 잔고예보의 'AI 생활비 반영' 선은 빼고 그려요 · 기록이 있는 계좌만 고르면 생활비까지 넣어 볼 수 있어요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
        }
        d.accounts.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            ListRow(
                title = a.name ?: "계좌",
                sub = if (a.hasRecord) listOfNotNull(a.dailyAverage?.let { "하루 평균 ${it.toWon()}원" }, a.method.label).joinToString(" · ") else "생활비 기록 없음",
                value = a.projectedAmount?.let { "${it.toWon()}원" },
                valuePlain = if (a.projectedAmount == null) "—" else null,
                dense = true,
            )
        }
    }
}

/** 생활비에서 빼는 소비 — 예정 지출 · 카드 청구와 두 번 세지 않으려고(BE 문장 그대로) */
@Composable
private fun ExcludedCard(notes: List<String>) {
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text("생활비에서 빼는 것", style = YamuTheme.typography.titleS, color = c.text1)
        Spacer(Modifier.height(6.dp))
        notes.forEach { Text("· $it", style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}
