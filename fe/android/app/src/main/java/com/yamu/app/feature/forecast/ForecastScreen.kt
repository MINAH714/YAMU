package com.yamu.app.feature.forecast

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.core.snap
import androidx.compose.animation.Crossfade
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.yamu.app.core.designsystem.theme.YamuMotion
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.yamu.app.core.designsystem.component.overflowTouch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.YamuDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.staleLine
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowRow
import com.yamu.app.feature.forecast.data.FlowConfidence
import com.yamu.app.feature.forecast.data.Forecast
import com.yamu.app.feature.forecast.data.ForecastAccountOption
import com.yamu.app.feature.forecast.data.ForecastDay
import com.yamu.app.feature.forecast.data.ForecastEvent
import com.yamu.app.feature.forecast.data.ForecastPeriod
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastSelection
import com.yamu.app.feature.forecast.data.LineGroup
import com.yamu.app.feature.forecast.data.LineStep
import com.yamu.app.feature.forecast.data.RiskLevel
import com.yamu.app.feature.forecast.data.RiskWindow
import com.yamu.app.feature.forecast.data.cashflowRows
import com.yamu.app.feature.forecast.data.forecastOnlyRows
import com.yamu.app.feature.link.data.CashflowCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import com.yamu.app.core.ui.ReloadOnReconnect   // 여기 둔 까닭: 위 core.ui 줄은 열린 MR(-448)이 같은 자리에 줄을 더한다
import javax.inject.Inject

data class ForecastState(
    val loading: Boolean = true,
    val forecast: Forecast? = null,
    val error: String? = null,
    val noAccount: Boolean = false,
    val selection: ForecastSelection = ForecastSelection(),
    val accounts: List<ForecastAccountOption>? = null,  // 계좌 고르기 시트 — 열 때 부른다
    val accountsError: String? = null,
    val entries: List<CashflowEntry>? = null,     // F-02 가계부 회차(고칠 수 있는 줄) — 못 받으면 예보 줄만(읽기 전용)
    val pastEntries: List<CashflowEntry> = emptyList(),   // F-02 지난 7일 카드 예정 지출 — 결제와 연결만
    val entriesLoading: Boolean = false,
    val entriesError: String? = null,
    val candidates: List<CashflowCandidate>? = null,   // F-02 확인 전 후보 규칙 — 못 받으면 후보 줄은 읽기 전용
    val busy: Set<Long> = emptySet(),                   // 지우는 중 · 넣거나 빼는 중인 항목 ID — 줄마다 연타를 막는다
    val ask: CashflowAsk? = null,                       // F-02 확인 시트 · 대화상자
    val message: String? = null,                        // F-02 결과 한 줄(스낵바)
) {
    /**
     * F-02 — 회차 응답이 올 때까지 기다린다. 받아 둔 예보가 있어도 그 예보 줄(읽기 전용)을 먼저 그리면 회차가 온 뒤
     * 반복 표시 · 휴지통이 붙은 다른 모양으로 바뀌어, 예보 줄만 그리는 것은 회차를 못 받았을 때로 한정한다
     */
    val cashflowLoading: Boolean
        get() = !noAccount && entries == null && (entriesLoading || entriesError == null)

    /** F-02 — 이 화면은 회차를 보여 주므로 둘 다 실패하면 회차 쪽 오류가 먼저 */
    val cashflowError: String?
        get() = entriesError ?: error
}

/** F-02에서 한 번 더 묻는 것 — 가계부 줄 지우기 · 후보 시트 · 후보 빼기. [itemId]로 보내는 중인 줄을 가린다 */
sealed interface CashflowAsk {
    val itemId: Long

    /** 반복이면 범위 시트, 1회면 '○○을 지울까요?' */
    data class Delete(val entry: CashflowEntry) : CashflowAsk { override val itemId get() = entry.itemId }

    /** 맞춘 후보 줄 — [date] · [amount]는 그 줄(예보의 출금일 · 부호 있는 금액) 그대로 */
    data class Candidate(val candidate: CashflowCandidate, val date: LocalDate?, val amount: Long) : CashflowAsk { override val itemId get() = candidate.id }

    /** 후보 줄 휴지통 — 시트의 '빼기'와 같게 한 번 묻고 IGNORED */
    data class Ignore(val candidate: CashflowCandidate) : CashflowAsk { override val itemId get() = candidate.id }
}

/** 후보를 넣으면 무엇이 되는지 — 서버는 지금 반복 지출만 후보로 만든다(BE -349). 옛 서버의 수입 후보는 예정 수입 */
internal fun candidateKindTo(c: CashflowCandidate): String = if (c.isIncome) "예정 수입으로" else "고정 지출로"

/** F-01 · F-02 — 돌아올 때마다 새로(결제 · 계획 저장 · 지우기 · 추가 · 수정 뒤 바뀌므로). 새로 부르면 앞 요청은 버린다 */
@HiltViewModel
class ForecastViewModel @Inject constructor(private val repo: ForecastRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForecastState(forecast = repo.cached(), loading = repo.cached() == null, selection = repo.selection()))
    val state: StateFlow<ForecastState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { refreshForecast() }
    }

    /** F-02 — 예보(합계 · 카드 청구)를 받은 뒤 같은 기간(오늘 ~ 다음 월급날)의 가계부 회차. 예보가 없으면 오늘부터 30일 */
    fun refreshWithEntries() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(entriesLoading = true) }
            refreshForecast()
            if (_state.value.noAccount) return@launch
            // 예보가 실패하면 메모리에 지난 값이 남아 있다 — 그 from을 쓰면 자정을 넘겼을 때 어제 회차를 읽는다
            val f = _state.value.forecast.takeIf { _state.value.error == null }
            val from = f?.from ?: LocalDate.now(KST)
            val to = f?.incomeDate ?: f?.to ?: from.plusDays(29)   // 월급날 줄까지 보이게 하루 더
            when (val r = repo.cashflowEntries(from, to)) {
                is ApiResult.Success -> _state.update { it.copy(entries = r.data, entriesLoading = false, entriesError = null) }
                is ApiResult.Failure -> _state.update { it.copy(entriesLoading = false, entriesError = r.error.userMessage()) }
            }
            // 지난 7일 카드 예정 지출 — 결제와 연결할 회차. 못 받아도 위 목록은 그대로라 조용히 비운다
            val today = LocalDate.now(KST)
            when (val r = repo.pastCardEntries(today.minusDays(PAST_MATCH_DAYS), today.minusDays(1))) {
                is ApiResult.Success -> _state.update { it.copy(pastEntries = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(pastEntries = emptyList()) }
            }
            // 확인 전 후보 규칙 — 예보의 후보 줄과 맞춰 넣기 · 빼기를 연다. 못 받으면 후보 줄은 전처럼 읽기 전용
            when (val r = repo.cashflowCandidates(from, to)) {   // 회차와 같은 기간 — 안 보내면 서버 기본 30일이라 그 뒤 월급날까지의 후보가 빠진다
                is ApiResult.Success -> _state.update { it.copy(candidates = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(candidates = null) }
            }
        }
    }

    // ── F-02 줄 끝 휴지통 · 후보 시트(Task 8.2.73) ──

    /** 휴지통 — 가계부 줄은 지울 범위(반복) · 한 번 더(1회)를, 후보 줄은 빼기를 한 번 묻는다. 보내는 중인 줄은 무시 */
    fun askDelete(row: CashflowRow) {
        val ask = row.entry?.let { CashflowAsk.Delete(it) } ?: row.pending?.let { CashflowAsk.Ignore(it) } ?: return
        if (ask.itemId in _state.value.busy) return
        _state.update { it.copy(ask = ask) }
    }

    /** 맞춘 후보 줄 누르기 — 금액 · 날짜 · 근거와 넣기 · 빼기 */
    fun openCandidate(row: CashflowRow) {
        val c = row.pending ?: return
        _state.update { it.copy(ask = CashflowAsk.Candidate(c, row.date, row.amount)) }
    }

    fun dismissAsk() = _state.update { it.copy(ask = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /**
     * 지우기. 삭제에는 재시도 키가 없어 응답이 끊기면 다시 보내지 않고 목록을 새로 받아 알린다(수정 화면과 같은 원칙).
     * 결제와 연결된 회차는 목록의 연결 상태로 휴지통을 숨긴다([CashflowRow.deletable]). 그래도 409 PAID_OCCURRENCE_LOCKED가 오는 건
     * 앞으로 전부에 연결된 다른 회차가 있거나 목록이 오래된 경우라, 어느 줄인지 집지 않고 까닭만 보이고 새로 받는다
     */
    fun delete(entry: CashflowEntry, scope: CashflowDeleteScope) {
        if (!claim(entry.itemId)) return
        viewModelScope.launch {
            val r = repo.deleteItem(entry.itemId, scope, entry.occurrenceDate.takeIf { scope == CashflowDeleteScope.THIS_ONLY })
            val quoted = "'${entry.title}'${entry.title.objectParticle()}"
            val (message, reload) = when (r) {
                is ApiResult.Success -> {
                    // 새 목록이 오기 전에 지운 줄을 먼저 뺀다 — 남아 있으면 다시 눌러 404를 받는다. 앞으로 전부는 그 항목의 오늘부터 회차 모두
                    _state.update { s ->
                        s.copy(entries = s.entries?.filterNot { it.itemId == entry.itemId && (scope == CashflowDeleteScope.ALL || it.occurrenceDate == entry.occurrenceDate) })
                    }
                    when {
                        scope == CashflowDeleteScope.THIS_ONLY -> "'${entry.title}' ${entry.date.toKoreanDate()} 회차를 지웠어요"
                        entry.isRecurring -> "$quoted 앞으로 전부 지웠어요"
                        else -> "$quoted 지웠어요"
                    } to true
                }
                is ApiResult.Failure -> deleteFailure(r.error)
            }
            finish(entry.itemId, message, reload)
        }
    }

    /** 후보 넣기(CONFIRMED) · 빼기(IGNORED). 그사이 바뀐 후보(404 · 409)와 결과 불명은 목록을 새로 받는다 */
    fun decide(candidate: CashflowCandidate, confirm: Boolean) {
        if (!claim(candidate.id)) return
        viewModelScope.launch {
            val r = repo.decideCandidate(candidate.id, confirm)
            val quoted = "'${candidate.title}'${candidate.title.objectParticle()}"
            val (message, reload) = when (r) {
                is ApiResult.Success -> {
                    // 이 후보는 더 이상 후보가 아니다 — 새 예보가 올 때까지 그 줄은 읽기 전용으로
                    _state.update { s -> s.copy(candidates = s.candidates?.filterNot { it.id == candidate.id }) }
                    (if (confirm) "$quoted ${candidateKindTo(candidate)} 넣었어요" else "$quoted 뺐어요") to true
                }
                is ApiResult.Failure -> when {
                    r.error.isOutcomeUnknown -> "응답을 받지 못해 반영됐는지 확인하지 못했어요 · 목록을 새로 받았어요" to true
                    r.error is ApiError.NotFound || r.error is ApiError.Conflict -> "그사이 바뀐 후보예요 · 목록을 새로 받았어요" to true
                    else -> r.error.userMessage() to false
                }
            }
            finish(candidate.id, message, reload)
        }
    }

    /** 보내는 중으로 표시 — 이미 보내는 중이면 거절(같은 줄 연타 · 시트와 휴지통을 겹쳐 누름) */
    private fun claim(id: Long): Boolean {
        if (id in _state.value.busy) return false
        _state.update { it.copy(ask = null, busy = it.busy + id) }
        return true
    }

    private fun finish(id: Long, message: String?, reload: Boolean) {
        _state.update { it.copy(busy = it.busy - id, message = message) }
        if (reload) refreshWithEntries()
    }

    /**
     * 400도 새로 받는다 — 그사이 회차가 지났거나, '이후 전부' 수정으로 나뉜 규칙의 다른 줄이 앞으로 전부 지우기 뒤 화면에 남아
     * 그 줄을 다시 지운 경우다(먼저 빼기는 같은 항목 ID 줄만 한다, !291 리뷰)
     */
    private fun deleteFailure(e: ApiError): Pair<String, Boolean> = when {
        e.isOutcomeUnknown -> "응답을 받지 못해 지워졌는지 확인하지 못했어요 · 목록을 새로 받았어요" to true
        e.code == "PAID_OCCURRENCE_LOCKED" -> "결제와 연결된 회차가 있어 지울 수 없어요 · 연결된 회차를 눌러 결제 연결을 먼저 끊어 주세요" to true
        e is ApiError.NotFound || e is ApiError.InvalidRequest -> "그사이 바뀌었거나 지워진 항목이에요 · 목록을 새로 받았어요" to true
        else -> e.userMessage() to false
    }

    /** 계좌 고르기 시트를 열 때 */
    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(accountsError = null) }
            when (val r = repo.accounts()) {
                is ApiResult.Success -> _state.update { it.copy(accounts = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(accountsError = r.error.userMessage()) }
            }
        }
    }

    /**
     * 시트에서 고른 기간 · 계좌 — 계좌를 모두 골랐으면 '전체'(all)로 둬 새로 연결한 계좌도 들어가게 한다.
     * 고르지 않은 기본은 기준 계좌 하나다(S15P31E103-484) — 전에는 기본이 전체라 모두 고르면 null로 돌렸다
     */
    fun apply(period: ForecastPeriod?, accountIds: Set<Long>?) {
        val all = _state.value.accounts?.map { it.accountId }?.toSet()
        val pickedAll = accountIds != null && accountIds == all
        // 계좌 목록이 오기 전(불러오는 중 · 실패)에 누르면 계좌는 고르지 않은 것 — 지금 선택(전체 · 일부)을 그대로 두고 기간만 바꾼다(!313 리뷰)
        val selection = if (accountIds == null) _state.value.selection.copy(period = period)
        else ForecastSelection(period, accountIds.takeIf { it.isNotEmpty() && !pickedAll }, all = pickedAll)
        repo.select(selection)
        _state.update { it.copy(selection = selection) }
        refresh()
    }

    private suspend fun refreshForecast() {
        _state.update { it.copy(loading = it.forecast == null, error = null) }
        when (val r = repo.forecast()) {
            is ApiResult.Success -> _state.update { it.copy(loading = false, forecast = r.data, noAccount = false) }
            is ApiResult.Failure -> _state.update {
                if (r.error.code in NO_ACCOUNT_CODES) ForecastState(loading = false, noAccount = true, selection = it.selection)
                else it.copy(loading = false, error = r.error.userMessage())
            }
        }
    }

    private companion object {
        /** 계좌를 잇지 않았다 — 앱이 먼저 알아챈 경우 · 서버가 accountIds 없음으로 거절한 경우 · 옛 서버 코드 */
        val NO_ACCOUNT_CODES = setOf(ForecastRepository.NO_ACCOUNT, "BASE_ACCOUNT_NOT_SET")
    }
}

private fun LocalDate.daysFromToday(): Long = ChronoUnit.DAYS.between(LocalDate.now(KST), this)

/** '11월 20일 (금)'. [markToday]면 오늘은 '11월 20일 (금, 오늘)' — 전에는 '(금) · 오늘'이었다(가운데 점 대신 괄호, 11/10 QA) */
internal fun LocalDate.toKoreanDateWeek(markToday: Boolean = false, today: LocalDate = LocalDate.now(KST)): String {
    val week = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    return if (markToday && this == today) "${toKoreanDate()} ($week, 오늘)" else "${toKoreanDate()} ($week)"
}

/** '11월 20일 ~ 24일' · 달이 다르면 '11월 28일 ~ 12월 2일' · 하루면 '11월 20일' */
internal fun dateRange(from: LocalDate, to: LocalDate): String = when {
    from == to -> from.toKoreanDate()
    from.month == to.month && from.year == to.year -> "${from.toKoreanDate()} ~ ${to.dayOfMonth}일"
    else -> "${from.toKoreanDate()} ~ ${to.toKoreanDate()}"
}

/** 기간 칩 · 문구 — 고른 값이 없으면 서버가 실제로 계산한 기간으로 */
internal fun periodLabel(f: Forecast?, selection: ForecastSelection): String =
    selection.period?.label ?: when {
        f == null -> "기간"
        f.isNextIncome -> ForecastPeriod.NEXT_INCOME.label
        else -> "${f.horizonDays ?: 30}일"
    }

/**
 * F-02 머리의 '나갈 돈 · 출금 N건' — 금액과 건수를 같은 기준으로. 예보가 있으면 기간 안에 실제로 빠지는 날(카드는 청구 출금일) 기준,
 * 없으면 가계부 줄 기준. 카드 예정 지출은 사용일 줄로 보이지만 출금은 청구일이라 기간 밖이면 둘 다에서 빠진다
 */
internal fun cashflowOutflow(f: Forecast?, rows: List<CashflowRow>): Pair<Long, Int> =
    if (f != null) {
        f.scheduledOutflow to f.scheduledOutflows.size   // 합계와 건수가 같은 목록(Forecast.scheduledOutflows)
    } else {
        rows.filter { it.amount < 0 }.let { out -> -out.sumOf { it.amount } to out.size }
    }

/** F-01 · F-02 — 예정된 돈의 기간. 월급날 기준이 아니면 실제 일수(예보가 없으면 서버 기본값처럼 30일) */
internal fun cashflowPeriod(f: Forecast?): String =
    if (f != null && f.incomeDate != null) "월급 전까지" else "${f?.horizonDays ?: 30}일 동안"

// ───────────────────────── F-01 잔고예보 ─────────────────────────

@Composable
fun ForecastRoute(
    onBack: () -> Unit,
    onBuyCheck: () -> Unit,
    onCashflowList: () -> Unit,
    onLink: () -> Unit,
    onLivingCost: () -> Unit = {},
    onRegisterIncome: () -> Unit = {},   // 월급을 못 찾았다는 안내의 '월급 등록하기' → B-06(Task 8.2.74)
    viewModel: ForecastViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    // 마지막 값을 보이는 동안 끊겼던 연결이 돌아오면 저절로 다시 불러온다 — 연결과 상관없는 실패면 부르지 않는다(S15P31E103-464)
    ReloadOnReconnect(waiting = s.error != null, onReconnect = viewModel::refresh)
    var filtering by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    // 회색 바탕 + 카드: 고르기 칩 · 예상 잔액 · 낮아지는 때 · 선 설명 · 예정된 돈 · 기간 뒤 카드값. 가상 구매 입구는 아래 버튼(2026-11-08 개편)
    FlowScreen(
        title = "잔고예보",
        onBack = onBack,
        cards = true,
        // 앱바 오른쪽 잔액 알림 톱니는 뺐다 — 알림 설정은 마이에서만(S15P31E103-521)
        bottomBar = {
            if (!s.noAccount) BottomCta { PrimaryButton("이거 사면 잔고는? 비교해 보기", onClick = onBuyCheck) }
        },
    ) { inner ->
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val reduce = YamuTheme.reduceMotion
        var heroTop by remember { mutableIntStateOf(0) }
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(scroll)) {
            val f = s.forecast
            when {
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 예보를 보여 드려요", actionText = "계좌 연결", onAction = onLink) }
                f == null && s.loading -> YamuCard {
                    SkeletonBlock(height = 28.dp, widthFraction = 0.5f)
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 140.dp)
                }
                f == null -> YamuCard { ErrorState(s.error ?: "예보를 불러오지 못했어요", onRetry = viewModel::refresh) }
                else -> {
                    FilterChips(f, s.selection, onOpen = {
                        filtering = true
                        viewModel.loadAccounts()
                    })
                    // 고른 날은 화면에 하나 — 그래프 · 띠 · 고른 날 칸 · 목록이 같이 본다(Task 8.2.70)
                    val day = rememberDaySelection(f)
                    var chartDragging by remember { mutableStateOf(false) }
                    var stripScrolling by remember { mutableStateOf(false) }
                    var panelDragging by remember { mutableStateOf(false) }
                    val select: (Int, DaySource) -> Unit = { i, from -> if (accepts(from, chartDragging)) day.select(i, from) }
                    val selectedDate = f.days.getOrNull(day.day)?.date
                    // 목록 줄 → 그날로. 그래프가 보이게 화면을 카드 위로 올린다
                    val jump: (LocalDate?) -> Unit = { date ->
                        val i = f.days.indexOfFirst { it.date == date }
                        if (i >= 0) {
                            select(i, DaySource.LIST)
                            scope.launch { if (reduce) scroll.scrollTo(heroTop) else scroll.animateScrollTo(heroTop) }
                        }
                    }
                    Box(Modifier.onPlaced { heroTop = it.positionInParent().y.roundToInt() }) {
                        HeroCard(
                            f, s.error, s.selection, day,
                            interacting = chartDragging || stripScrolling || panelDragging,
                            select = select,
                            onChartDrag = { chartDragging = it },
                            onStripScroll = { stripScrolling = it },
                            onPanelDrag = { panelDragging = it },
                            onRegisterIncome = onRegisterIncome,
                        )
                    }
                    if (f.riskWindows.isNotEmpty()) RiskCard(f, selectedDate, onJump = jump)
                    LinesCard(f)
                    LivingCostEntry(f, onLivingCost)   // F-01b 생활비 예측 입구
                    UpcomingCard(f, onCashflowList, selectedDate, onJump = jump)
                    BeyondHorizonCard(f)
                    // 맨 아래 '빼고 계산한 것' 카드는 QA 요청으로 뺐다(S15P31E103-554)
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    if (filtering) {
        FilterSheet(
            forecast = s.forecast,
            selection = s.selection,
            accounts = s.accounts,
            accountsError = s.accountsError,
            onRetry = viewModel::loadAccounts,
            onDismiss = { filtering = false },
            onApply = { period, ids ->
                filtering = false
                viewModel.apply(period, ids)
            },
        )
    }
}

/** 기간 · 계좌 칩 — 회색 바탕 위 흰 알약, 누르면 시트. 계좌 칩은 계좌 이름표('KB국민은행 ··1234' · '계좌 2개 전체', COMMON-04) */
@Composable
private fun FilterChips(f: Forecast, selection: ForecastSelection, onOpen: () -> Unit) {
    val account = f.accountLabel(selection.all)
    Row(Modifier.padding(horizontal = YamuSpacing.screen).padding(bottom = YamuSpacing.blockGap), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(periodLabel(f, selection), contentDescription = "예보 기간 ${periodLabel(f, selection)}, 바꾸기", onClick = onOpen)
        FilterChip(account, contentDescription = "보는 계좌 $account, 바꾸기", onClick = onOpen)
    }
}

@Composable
private fun FilterChip(text: String, contentDescription: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            // 보이는 알약은 36dp, 터치 칸은 위아래로 6dp씩 넘쳐 48dp(S15P31E103-513)
            .overflowTouch(6.dp, Modifier.clickable(interaction, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick))
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .indication(interaction, LocalIndication.current)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = YamuTheme.typography.labelL, color = c.text1)
        Icon(YamuIcons.ExpandMore, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

/** 고른 날 상태 — 기간 · 계좌가 바뀌면 처음 날로 새로 만든다. 화면을 돌려도 고른 날과 첫 안내 여부는 지킨다 */
@Composable
private fun rememberDaySelection(f: Forecast): DaySelection {
    val n = f.days.size
    return rememberSaveable(listOf(f.from, f.to, f.accountIds).toString(), n, saver = DaySelection.saver(n)) { DaySelection(initialChartDay(f), n) }
}

/**
 * 예상 잔액 — 가장 나쁜 선의 최저 잔액과 그 선 이름, 상태, 두 선 그래프, 날짜 띠, 고른 날.
 * 그래프 · 띠 · 칸을 미는 동안에는 제목이 '11월 27일 (금) 잔액'으로 바뀌고 금액이 그날 잔액으로 굴러가며, 손을 떼고 1.5초 뒤 가장 낮을 때로 돌아온다(Task 8.2.70)
 */
@Composable
private fun HeroCard(
    f: Forecast,
    error: String?,
    selection: ForecastSelection,
    day: DaySelection,
    interacting: Boolean,
    select: (Int, DaySource) -> Unit,
    onChartDrag: (Boolean) -> Unit,
    onStripScroll: (Boolean) -> Unit,
    onPanelDrag: (Boolean) -> Unit,
    onRegisterIncome: () -> Unit,
) {
    val c = YamuTheme.colors
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(day.version, interacting) {
        if (day.version == 0) return@LaunchedEffect   // 사람이 바꾼 적이 없으면 가장 낮을 때 그대로
        live = true
        if (interacting) return@LaunchedEffect        // 손을 떼면 이 효과가 다시 돌며 1.5초를 센다
        delay(HERO_HOLD_MS)
        live = false
    }
    val liveDay = f.days.getOrNull(day.day)?.takeIf { live }
    val amount = liveDay?.let { rideValue(it, f.hasConservative) } ?: f.minBalance ?: 0
    // 그래프 · 달력 면 — 화면을 돌려도 지킨다(Task 8.2.71)
    var view by rememberSaveable { mutableStateOf(ChartView.GRAPH) }
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                liveDay?.let { heroLiveTitle(it.date) } ?: if (f.isNextIncome) "월급 전 가장 낮을 때" else "${f.horizonDays ?: 30}일 동안 가장 낮을 때",
                style = YamuTheme.typography.titleS,
                color = if (liveDay != null) c.text1 else c.text2,
            )
            if (f.isEstimate) {
                Spacer(Modifier.width(6.dp))
                EstimateTag()
            }
            if (f.days.size >= 2) {
                Spacer(Modifier.weight(1f))
                ViewToggle(view, onChange = { view = it })
            }
        }
        Spacer(Modifier.height(4.dp))
        RollingAmountText(amount, color = if (amount < 0) c.error else c.primary)
        // 어느 선의 최저인지 — 금액 바로 밑 한 줄(범례와 같은 선 견본). 날짜 줄과 나눠 둬 큰 글씨에서도 남은 폭에 눌려 꺾이지 않는다
        minBalanceBasis(f)?.let { basis ->
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                LineGlyph(dashed = f.hasConservative)
                Spacer(Modifier.width(6.dp))
                Text(basis, style = YamuTheme.typography.bodyS, color = c.text3)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            statusTag(f)?.let { (text, tone) -> StatusTag(text, tone = tone) }
            Text(
                listOfNotNull(f.minBalanceDate?.toKoreanDate(), f.incomeDate?.let { "월급 D-${it.daysFromToday()}" }).joinToString(" · "),
                style = YamuTheme.typography.bodyS,
                color = c.text3,
            )
        }
        error?.let { Text(staleLine(it, f.calculatedAtIso), style = YamuTheme.typography.bodyS, color = c.warning, modifier = Modifier.padding(top = 4.dp)) }
        // 월급을 못 찾아 N일로 계산했다는 이유(P1-08)와 월급 등록(B-06, 홈 월급 안내와 같은 모드). 등록하고 돌아오면 이 화면이 다시 부른다
        incomeMissingNote(f, selection)?.let {
            Column(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(YamuRadius.s))
                    .background(c.surfaceVariant)
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp),
            ) {
                Text(it, style = YamuTheme.typography.bodyS, color = c.text2)
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(YamuRadius.s))
                        .clickable(role = Role.Button, onClick = onRegisterIncome),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("월급 등록하기", style = YamuTheme.typography.labelL, color = c.primary)
                    Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(YamuSpacing.x5))
        ForecastLines(f, day, view, select, onChartDrag, onStripScroll, onPanelDrag)
    }
}

private const val HERO_HOLD_MS = 1_500L

/**
 * 맨 위 '가장 낮을 때' 금액이 어느 선 기준인지(!223 답, 서버는 그대로). summary.minBalance는 'AI 생활비 반영' 선, 그 선이 없으면 'AI 생활비 제외' 선의 최저다.
 * 날마다 값이 없으면 어느 선인지 몰라 null
 */
internal fun minBalanceBasis(f: Forecast): String? =
    if (f.days.isEmpty()) null else "${if (f.hasConservative) LINE_WITH_LIVING else LINE_WITHOUT_LIVING} 선 기준"

/** 선 모양 견본 — 범례와 같은 파란 실선('AI 생활비 제외') · 점선('AI 생활비 반영') */
@Composable
private fun LineGlyph(dashed: Boolean, modifier: Modifier = Modifier) {
    val color = YamuTheme.colors.primary
    Canvas(modifier.size(width = 18.dp, height = 4.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.6.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
    }
}

/** 상태 태그 — 사실만(권하거나 말리지 않음). 모르는 수준이면 태그 없음 */
internal fun statusTag(f: Forecast): Pair<String, TagTone>? {
    val whole = if (f.isNextIncome) "월급날까지" else "${f.horizonDays ?: 30}일 동안"
    val below = f.riskFrom?.let { "${it.toKoreanDate()}부터 안전 잔액 아래" }
        ?: if (f.hasAccountRisk) "계좌 ${f.atRiskAccountCount}개가 안전 잔액 아래" else "안전 잔액 아래로 내려가요"
    return when (f.status) {
        RiskLevel.SAFE -> "$whole 안전 잔액 위" to TagTone.Success
        RiskLevel.WARNING -> below to TagTone.Warning
        RiskLevel.DANGER -> below to TagTone.Error
        RiskLevel.UNKNOWN -> null
    }
}

/**
 * 그래프의 두 선 이름(점검 B-05, BE -356). 서버 expected는 생활비를 빼기 전, conservative는 AI 예상 생활비와 앞으로 쓸 신용카드 금액까지 뺀 선이다.
 * confirmed는 응답에 남아 있어도 그리지 않는다. 범례 · 날짜 칸 · 선 설명 · 위험 문장 · 접근성 설명이 이 이름을 같이 쓴다
 */
internal const val LINE_WITHOUT_LIVING = "AI 생활비 제외"
internal const val LINE_WITH_LIVING = "AI 생활비 반영"

/** 이번에 그리지 못한 선 — 생활비 선(conservative)이 없으면 범례에 취소선으로 남긴다 */
internal fun missingLines(f: Forecast): List<String> = if (f.hasConservative) emptyList() else listOf(LINE_WITH_LIVING)

/**
 * 그래프 접근성 설명 — 어떤 선이 있는지와 가장 낮을 때. 최저 잔액(summary.minBalance)은 가장 낮은 선 기준이라 그 선 이름을 붙인다.
 * 전에는 '예상 잔액 그래프'라 어느 선을 말하는지 드러나지 않았다(점검 B-05)
 */
internal fun chartDescription(f: Forecast): String {
    val min = "${(f.minBalance ?: 0).toWon()}원"
    return listOfNotNull(
        "잔액 그래프",
        if (f.hasConservative) "$LINE_WITHOUT_LIVING 선과 $LINE_WITH_LIVING 선" else "$LINE_WITHOUT_LIVING 선만(생활비 기록이 없어 $LINE_WITH_LIVING 선은 그리지 않았어요)",
        if (f.hasConservative) "$LINE_WITH_LIVING 선이 가장 낮을 때 $min" else "가장 낮을 때 $min",
        f.riskFrom?.let { "${it.toKoreanDate()}부터 안전 잔액 아래" },
    ).joinToString(", ")
}

/**
 * 처음 고른 날 — 안전 잔액 아래로 처음 내려가는 날(가장 이른 위험 구간의 시작), 없으면 가장 낮은 날, 그것도 모르면 마지막 날.
 * 전에는 가장 낮은 날이라 월급 전날(맨 끝)에 손잡이가 붙어 밀 수 있다는 게 잘 안 보였다(Task 8.2.65)
 */
internal fun initialChartDay(f: Forecast): Int {
    val days = f.days
    f.riskFrom?.let { from -> days.indexOfFirst { !it.date.isBefore(from) }.takeIf { it >= 0 }?.let { return it } }
    return days.indexOfFirst { it.date == f.minBalanceDate }.takeIf { it >= 0 } ?: days.lastIndex
}

/** 돈이 오가는 날(0원이 아닌 입출금이 있는 날) — 그래프에 동전을 얹는다 */
internal fun eventDayIndices(days: List<ForecastDay>): Set<Int> =
    days.indices.filter { i -> days[i].events.any { it.amount != 0L } }.toSet()

/** 말풍선 윗줄 — 돈이 오가는 날은 '11/21 (토) · 월세'(여러 건이면 '외 N건'), 아니면 '11월 21일 (토)' */
internal fun chartDayLabel(day: ForecastDay, today: LocalDate = LocalDate.now(KST)): String {
    val events = day.events.filter { it.amount != 0L }
    if (events.isEmpty()) return day.date.toKoreanDateWeek(markToday = true, today = today)
    val more = if (events.size > 1) " 외 ${events.size - 1}건" else ""
    return "${day.date.toMonthDayWeek()} · ${events.first().label}$more"
}

/**
 * TalkBack이 고른 날을 바꾼 뒤 읽는 문장(P2-28) — 날짜, 선마다 끝 잔액, 그날 입출금.
 * 전에는 '하루 뒤' 동작 뒤 아무것도 읽지 않아 몇 일 · 얼마인지 몰랐다
 */
internal fun chartDayState(day: ForecastDay, hasConservative: Boolean): String = listOfNotNull(
    day.date.toKoreanDateWeek(),
    day.conservative?.takeIf { hasConservative }?.let { "$LINE_WITH_LIVING ${it.toWon()}원" },
    day.expected?.let { "$LINE_WITHOUT_LIVING ${it.toWon()}원" },
    day.events.filter { it.amount != 0L }.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${dayEventLabel(it)} ${it.amount.toWon(withPlusSign = true)}원" },
).joinToString(", ")

/**
 * 두 선 + 범례 + 날짜 띠 + 고른 날 칸. AI 생활비 반영 선이 없으면 그 선만 빼고 범례에 취소선.
 * 예보할 날이 하루뿐이면(월급 전날) 그래프 대신 그날 칸과 안내 한 줄(P2-24)
 */
@Composable
private fun ForecastLines(
    f: Forecast,
    day: DaySelection,
    view: ChartView,
    select: (Int, DaySource) -> Unit,
    onChartDrag: (Boolean) -> Unit,
    onStripScroll: (Boolean) -> Unit,
    onPanelDrag: (Boolean) -> Unit,
) {
    val c = YamuTheme.colors
    val days = f.days
    if (days.size == 1) {
        Text(singleDayNote(f), style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(12.dp))
        DayPanel(days[0], hasConservative = f.hasConservative, threshold = f.alertThreshold)
        return
    }
    if (days.size < 2) return
    // 마지막 선이 손잡이가 타는 선 — 'AI 생활비 반영'(없으면 'AI 생활비 제외')
    val lines = buildList {
        add(ChartLine(LINE_WITHOUT_LIVING, days.map { it.expected }, c.primary, width = 2.6.dp))
        if (f.hasConservative) add(ChartLine(LINE_WITH_LIVING, days.map { it.conservative }, c.primary, dashed = true, width = 2.2.dp))
    }
    val eventDays = remember(days) { eventDayIndices(days) }
    val strip = remember(days, f.alertThreshold) { stripDays(days, f.hasConservative, f.alertThreshold) }
    // 면을 바꿔도 각 면의 저장 상태(그래프 들어오기 · 첫 안내를 했는지)는 지킨다 — 다시 그래프로 와도 들어오기를 되풀이하지 않게
    val saved = rememberSaveableStateHolder()
    Crossfade(
        targetState = view,
        animationSpec = if (YamuTheme.reduceMotion) snap() else tween(YamuMotion.BASE_MS, easing = YamuMotion.standard),
        label = "chartView",
    ) { v ->
        saved.SaveableStateProvider(v.name) {
            when (v) {
                ChartView.GRAPH -> Column {
                    BalanceChart(
                        dates = days.map { it.date },
                        lines = lines,
                        description = chartDescription(f),
                        threshold = f.alertThreshold,
                        eventDays = eventDays,
                        selected = day.day,
                        onSelect = { select(it, DaySource.CHART) },
                        dayLabel = { chartDayLabel(days[it]) },
                        dayState = { chartDayState(days[it], f.hasConservative) },
                        incomeArrow = f.incomeDate != null,
                        interacted = day.touched,
                        onInteract = day::touch,
                        onDragChange = onChartDrag,
                    )
                    Spacer(Modifier.height(12.dp))
                    ChartLegend(lines, missing = missingLines(f))
                    Spacer(Modifier.height(8.dp))
                    DateStrip(
                        days = strip,
                        selected = day.day,
                        source = day.source,
                        onSelect = select,
                        income = f.incomeDate,
                        hint = !day.touched,
                        bleed = 20.dp,   // HeroCard 안쪽 여백만큼 카드 끝까지
                        onScrollingChange = onStripScroll,
                    )
                }
                ChartView.CALENDAR -> ForecastCalendar(f, strip, selected = day.day, source = day.source, onSelect = select)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    DayPager(f, day, select, onPanelDrag)
}

/**
 * 그래프 · 달력 바꾸기 알약(Task 8.2.71) — 카드 오른쪽 위. 그래프를 가로로 끄는 것과 달력을 넘기는 것이 부딪히지 않게 면은 이 알약으로만 바꾼다
 */
@Composable
private fun ViewToggle(view: ChartView, onChange: (ChartView) -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            // 자르지 않고 바탕만 그린다 — 칸의 터치(48dp)가 알약 위아래로 넘쳐야 해서
            .background(c.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(3.dp)
            .semantics { contentDescription = "보기" },
    ) {
        listOf(ChartView.GRAPH to "그래프", ChartView.CALENDAR to "달력").forEach { (v, label) ->
            val on = v == view
            val interaction = remember { MutableInteractionSource() }
            Text(
                label,
                style = YamuTheme.typography.labelM,
                color = if (on) c.text1 else c.text3,
                modifier = Modifier
                    // 보이는 칸은 26dp 그대로, 터치 칸만 위아래로 11dp씩 넘쳐 48dp(S15P31E103-513)
                    .overflowTouch(11.dp, Modifier.selectable(selected = on, interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onChange(v) }))
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .indication(interaction, LocalIndication.current)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 고른 날 칸(Task 8.2.70) — 옆으로 밀거나 머리의 ‹ ›를 누르면 하루씩 넘어간다([DaySource.PANEL]).
 * 손가락을 따라 칸이 움직이고 48dp 넘게 밀고 놓으면 그 쪽으로 빠져나가며 다음 날이 반대편에서 들어온다. 끝 날에서는 덜 밀린다.
 * 다른 곳(그래프 · 띠 · 목록)에서 날을 바꾸면 밀지 않고 바로 바꾼다 — 그래프를 빠르게 끌 때 칸이 쌓여 밀리지 않게. 모션 줄이기면 늘 바로
 */
@Composable
private fun DayPager(f: Forecast, day: DaySelection, select: (Int, DaySource) -> Unit, onDragChange: (Boolean) -> Unit) {
    val days = f.days
    val n = days.size
    val reduce = YamuTheme.reduceMotion
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val latestDay by rememberUpdatedState(day.day)
    // 한쪽으로 빠져나가고(dir 쪽 반대로) 새 날이 반대편에서 들어온다.
    // 넘기는 중에 누르거나 민 것은 버린다 — 전에는 그때 '제자리로' 움직임을 새로 걸어 넘기던 움직임이 끊겼고, busy가 풀리지 않아 ‹ ›가 더는 듣지 않을 수 있었다(QA)
    fun go(dir: Int) {
        if (busy) return
        val target = latestDay + dir
        if (target !in 0 until n) {
            scope.launch { offset.animateTo(0f, tween(YamuMotion.BASE_MS, easing = YamuMotion.standard)) }
            return
        }
        if (reduce || width == 0) { scope.launch { offset.snapTo(0f) }; select(target, DaySource.PANEL); return }
        busy = true
        scope.launch {
            try {
                offset.animateTo(-dir * width.toFloat(), tween(PAGER_OUT_MS, easing = YamuMotion.standard))
                select(target, DaySource.PANEL)
                offset.snapTo(dir * width.toFloat())
                offset.animateTo(0f, tween(PAGER_IN_MS, easing = YamuMotion.standard))
            } finally {
                busy = false
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .clip(RoundedCornerShape(YamuRadius.input))
            .pointerInput(n) {
                detectHorizontalDragGestures(
                    onDragStart = { onDragChange(true); day.touch() },
                    onDragEnd = {
                        onDragChange(false)
                        val swipe = with(density) { PAGER_SWIPE.toPx() }
                        when {
                            offset.value < -swipe -> go(1)
                            offset.value > swipe -> go(-1)
                            else -> go(0)
                        }
                    },
                    onDragCancel = { onDragChange(false); go(0) },
                ) { change, dx ->
                    change.consume()
                    if (busy) return@detectHorizontalDragGestures
                    val next = offset.value + dx
                    val atEdge = (next > 0 && latestDay == 0) || (next < 0 && latestDay == n - 1)
                    scope.launch { offset.snapTo(offset.value + dx * if (atEdge) 0.3f else 1f) }
                }
            },
    ) {
        DayPanel(
            days[day.day.coerceIn(0, n - 1)],
            hasConservative = f.hasConservative,
            threshold = f.alertThreshold,
            modifier = Modifier.graphicsLayer { translationX = offset.value },
            onPrev = if (day.day > 0) ({ go(-1) }) else null,
            onNext = if (day.day < n - 1) ({ go(1) }) else null,
        )
    }
}

private val PAGER_SWIPE = 48.dp
private const val PAGER_OUT_MS = 140
private const val PAGER_IN_MS = 220

/** 고른 날 — 그날 끝 잔액(선마다), 안전 잔액 · 0원 아래면 태그, 그날 입출금. 칸 넘기기 화면에서는 머리에 ‹ › */
@Composable
private fun DayPanel(
    day: ForecastDay,
    hasConservative: Boolean,
    threshold: Long?,
    modifier: Modifier = Modifier,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    val c = YamuTheme.colors
    val lowest = listOfNotNull(day.expected, day.conservative.takeIf { hasConservative }).minOrNull()
    val paged = onPrev != null || onNext != null
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.surfaceVariant)
            .padding(start = 14.dp, end = 14.dp, top = if (paged) 2.dp else 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.date.toKoreanDateWeek(markToday = true), style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
            when (lowest?.let { zoneOf(it, threshold) }) {
                BalanceZone.BELOW_ZERO -> StatusTag("0원 아래", tone = TagTone.Error)
                BalanceZone.BELOW_SAFE -> StatusTag("안전 잔액 아래", tone = TagTone.Warning)
                else -> Unit
            }
            if (paged) {
                Spacer(Modifier.width(4.dp))
                PagerArrow(back = true, enabled = onPrev != null, onClick = { onPrev?.invoke() })
                PagerArrow(back = false, enabled = onNext != null, onClick = { onNext?.invoke() })
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayValue(LINE_WITHOUT_LIVING, day.expected, c.onPrimaryContainer, Modifier.weight(1f))
            if (hasConservative) DayValue(LINE_WITH_LIVING, day.conservative, c.onWarningContainer, Modifier.weight(1f))
        }
        // 오가는 돈이 없는 날은 두 금액만 — '이날은 오가는 돈이 없어요' 문장은 군더더기라 뺐다(QA)
        val events = day.events.filter { it.amount != 0L }
        if (events.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = c.outlineVariant)
            Spacer(Modifier.height(6.dp))
            events.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayEventLabel(e), style = YamuTheme.typography.bodyM, color = c.text1, modifier = Modifier.weight(1f))
                    Text("${e.amount.toWon(withPlusSign = true)}원", style = YamuTheme.typography.bodyM, color = if (e.amount > 0) c.primary else c.text1)
                }
            }
        }
    }
}

/** 고른 날 칸 머리의 ‹ › — 보이는 크기는 작게, 누르는 칸은 48dp */
@Composable
private fun PagerArrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(YamuRadius.s))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (back) "하루 앞" else "하루 뒤", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(YamuRadius.s)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(
                YamuIcons.ChevronRight,
                contentDescription = if (back) "하루 앞" else "하루 뒤",
                tint = if (enabled) c.text2 else c.text3.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp).graphicsLayer { if (back) scaleX = -1f },
            )
        }
    }
}

/** 날짜 칸 입출금 이름 — '넷플릭스 (예상)' · '카드 사용 예상 (추정)'. 가운데 점은 어색하다는 의견으로 괄호로 바꿨다(11/10 QA) */
internal fun dayEventLabel(e: ForecastEvent): String = when {
    e.isEstimate -> "${e.label} (추정)"
    e.isExpected -> "${e.label} (예상)"
    else -> e.label
}

/** 계산 카드 한 줄 — '예상 생활비 (AI 예측, 기록 있는 계좌만)'. 덧붙임은 괄호에 넣는다 */
internal fun stepLabel(s: LineStep): String = s.note?.let { "${s.label} ($it)" } ?: s.label

@Composable
internal fun DayValue(label: String, value: Long?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = YamuTheme.typography.bodyS, color = YamuTheme.colors.text3)
        // 칸이 좁아 아주 큰 금액이 숫자 중간에서 줄이 바뀌었다 — 한 줄로 두고 글자를 줄이며, 그래도 넘치면 말줄임(점검 2026-11-08)
        val style = YamuTheme.typography.titleS
        Text(
            value?.let { "${it.toWon()}원" } ?: "—",
            style = style,
            color = if ((value ?: 0) < 0) YamuTheme.colors.error else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        )
    }
}

/**
 * 목록 줄의 고른 날 표시 — 그래프에서 고른 날과 같은 날 줄은 연한 파랑 바탕(Task 8.2.70). 누르면 그날로 가는 줄만 [onClick]이 있다
 */
internal fun Modifier.dayRow(on: Boolean, highlight: androidx.compose.ui.graphics.Color): Modifier = this
    // 바탕이 글자에 딱 붙지 않게 양옆 8dp씩 넓혀 칠하고 글자 자리는 그대로 둔다
    .layout { m, cons ->
        val e = 8.dp.roundToPx()
        val w = cons.maxWidth + 2 * e
        val p = m.measure(cons.copy(minWidth = w, maxWidth = w))
        layout(cons.maxWidth, p.height) { p.place(-e, 0) }
    }
    .clip(RoundedCornerShape(YamuRadius.s))
    .background(if (on) highlight else androidx.compose.ui.graphics.Color.Transparent)
    .padding(horizontal = 8.dp)

/** 잔액이 낮아지는 때 — 위험 구간마다 선 · 부족액 · 원인(서버 causes 그대로). 원인 줄을 누르면 그래프가 그날로(날짜를 아는 줄만) */
@Composable
private fun RiskCard(f: Forecast, selectedDate: LocalDate?, onJump: (LocalDate?) -> Unit) {
    val c = YamuTheme.colors
    val worst = if (f.riskWindows.any { it.level == RiskLevel.DANGER }) RiskLevel.DANGER else RiskLevel.WARNING
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("잔액이 낮아지는 때", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            StatusTag(if (worst == RiskLevel.DANGER) "위험" else "주의", tone = if (worst == RiskLevel.DANGER) TagTone.Error else TagTone.Warning)
        }
        f.riskWindows.take(3).forEachIndexed { i, w ->
            Spacer(Modifier.height(if (i == 0) 10.dp else 16.dp))
            Text(dateRange(w.from, w.to), style = YamuTheme.typography.titleM, color = c.text1)
            Text(riskLine(w), style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(4.dp))
            w.causes.forEachIndexed { j, cause ->
                HorizontalDivider(color = c.outlineVariant)
                val inChart = cause.date != null && f.days.any { it.date == cause.date }
                ListRow(
                    title = cause.label,
                    sub = cause.sub(f.livingCost?.method),
                    value = "${cause.amount.toWon(withPlusSign = true)}원",
                    valueColor = if (cause.amount > 0) c.primary else c.text1,
                    dense = true,
                    modifier = Modifier.dayRow(inChart && cause.date == selectedDate, c.primaryContainer),
                    onClick = if (inChart) ({ onJump(cause.date) }) else null,
                )
            }
        }
    }
}

/**
 * 'AI 생활비를 반영하면 안전 잔액보다 최대 70,000원 모자라요'. 구간의 선은 BE -356부터 EXPECTED · CONSERVATIVE만 온다.
 * 옛 서버의 CONFIRMED(그리지 않는 확정 선)나 모르는 값이면 선을 말하지 않는다 — 앱과 서버 배포 순서가 어긋나도 없는 선 이름을 대지 않게
 */
internal fun riskLine(w: RiskWindow): String {
    val lead = when (w.line) {
        FlowConfidence.EXPECTED -> "AI 생활비를 빼지 않아도"
        FlowConfidence.CONSERVATIVE -> "AI 생활비를 반영하면"
        FlowConfidence.CONFIRMED, FlowConfidence.UNKNOWN -> null
    }
    val gap = w.shortfall?.takeIf { it > 0 }?.let { "안전 잔액보다 최대 ${it.toWon()}원 모자라요" } ?: "안전 잔액 아래로 내려가요"
    return listOfNotNull(lead, gap).joinToString(" ")
}

/**
 * 두 선 계산 — 제목 없이 계산식만 둔다(QA: 문구가 많아 안 읽힌다). 지금 잔액 → 더하고 빼는 돈 → 'AI 생활비 제외' 선 끝 → 더 빼는 생활비 → 'AI 생활비 반영' 선 끝.
 * 서버 conservative는 expected에서 예상 생활비와 앞으로 쓸 신용카드 금액을 더 뺀 값이라 두 번째 묶음은 첫 선 끝에서 이어 읽힌다(FR-047 확정 · 예측 구분)
 */
@Composable
private fun LinesCard(f: Forecast) {
    val c = YamuTheme.colors
    val (without, with) = f.lineGroups()
    YamuCard(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
        f.currentBalance?.let { StepRow("지금 잔액", "${it.toWon()}원") }
        // 들어오는 돈을 먼저 — 위에서 아래로 더하고 빼는 순서로 읽힌다(같은 쪽 안에서는 서버 순서 그대로)
        without.steps.sortedByDescending { it.amount > 0 }.forEach { StepRow(stepLabel(it), "${it.amount.toWon(withPlusSign = true)}원") }
        LineTotal(without, dashed = false)
        if (f.hasConservative) {
            Spacer(Modifier.height(8.dp))
            with.steps.forEach { StepRow(stepLabel(it), "${it.amount.toWon(withPlusSign = true)}원") }
            LineTotal(with, dashed = true)
            with.note?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp)) }
        } else {
            Text("$LINE_WITH_LIVING 선은 생활비 기록이 없어 그리지 않았어요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 계산식의 결과 줄 — 구분선 밑에 선 견본 · 선 이름(범례와 같은 이름) · 기간 끝 잔액을 굵게 */
@Composable
private fun LineTotal(g: LineGroup, dashed: Boolean) {
    val c = YamuTheme.colors
    val end = g.end
    HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LineGlyph(dashed = dashed)
        Spacer(Modifier.width(8.dp))
        Text(if (dashed) LINE_WITH_LIVING else LINE_WITHOUT_LIVING, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
        Text(end?.let { "${it.toWon()}원" } ?: "—", style = YamuTheme.typography.titleS, color = if ((end ?: 0) < 0) c.error else c.text1)
    }
}

@Composable
private fun StepRow(label: String, value: String) {
    val c = YamuTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = YamuTheme.typography.bodyM, color = c.text1)
    }
}

/** 예정된 돈 — 위 5줄. 카드 사용 예상은 '추정' 태그와 근거 문장(서버 title 그대로). 줄을 누르면 그래프가 그날로 */
@Composable
private fun UpcomingCard(f: Forecast, onCashflowList: () -> Unit, selectedDate: LocalDate?, onJump: (LocalDate?) -> Unit) {
    val c = YamuTheme.colors
    // 목록 카드는 안쪽 16(프로토타입 .card.tight) · 머리 줄 24dp. '전체 보기' 터치 칸(48)은 머리 줄 위아래로 넘친다(.card-hd .more 음수 여백)
    YamuCard(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${cashflowPeriod(f)} 예정된 돈", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .overflowHeight(24.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(YamuRadius.s))
                    .clickable(role = Role.Button, onClick = onCashflowList)
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("전체 보기", style = YamuTheme.typography.link, color = c.text3)
                Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
            }
        }
        val rows = f.upcoming
        if (rows.isEmpty()) {
            Text("${cashflowPeriod(f)} 예정된 돈이 없어요", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(vertical = 12.dp))
        }
        rows.take(5).forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            EventRow(e, on = e.date == selectedDate, onClick = { onJump(e.date) })
        }
    }
}

/**
 * 기간 뒤에 빠질 카드값(BE beyondHorizon) — 30일 예보인데 카드값이 31일째에 빠지면 아무 안내 없이 빠져 있던 것(점검 2026-11-08).
 * 선 · 위험에는 넣지 않은 돈이라 '예정된 돈'과 따로 두고 없으면 카드를 그리지 않는다
 */
@Composable
private fun BeyondHorizonCard(f: Forecast) {
    val items = f.beyondHorizon
    if (items.isEmpty()) return
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("기간 뒤에 빠질 카드값", style = YamuTheme.typography.cardTitle, color = c.text1, modifier = Modifier.weight(1f))
            Text("${(items.sumOf { it.amount }).toWon(withPlusSign = true)}원", style = YamuTheme.typography.titleS, color = c.text1)
        }
        Text(beyondHorizonNote(f), style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        items.take(BEYOND_VISIBLE).forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            EventRow(e)
        }
        val hidden = items.size - BEYOND_VISIBLE
        if (hidden > 0) Text("외 ${hidden}건", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 4.dp))
    }
}

/** '11월 30일 뒤라 위 그래프에는 넣지 않았어요' — 기간 끝을 모르면 날짜 없이 */
internal fun beyondHorizonNote(f: Forecast): String =
    (f.to?.let { "${it.toKoreanDate()} 뒤에 빠져서" } ?: "예보 기간 뒤에 빠져서") + " 위 그래프에는 넣지 않았어요"

private const val BEYOND_VISIBLE = 5

@Composable
private fun EventRow(e: ForecastEvent, on: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = YamuTheme.colors
    ListRow(
        modifier = Modifier.dayRow(on, c.primaryContainer),
        onClick = onClick,
        title = e.label,
        sub = listOfNotNull(e.date.toMonthDayWeek(), e.basis).joinToString(" · "),
        value = "${e.amount.toWon(withPlusSign = true)}원",
        valueColor = when {
            e.amount > 0 -> c.primary
            e.isEstimate -> c.text2
            else -> c.text1
        },
        titleTag = when {
            e.isEstimate -> ({ StatusTag("추정", tone = TagTone.Neutral) })
            e.isExpected -> ({ StatusTag("예상", tone = TagTone.Neutral) })
            else -> null
        },
        dense = true,
    )
}

/** 기간 · 계좌 고르기 — 고른 값은 앱을 켜 둔 동안만 기억(서버 설정은 그대로) */
@Composable
private fun FilterSheet(
    forecast: Forecast?,
    selection: ForecastSelection,
    accounts: List<ForecastAccountOption>?,
    accountsError: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (ForecastPeriod?, Set<Long>?) -> Unit,
) {
    val c = YamuTheme.colors
    val periods = ForecastPeriod.entries
    val shown = selection.period ?: when {
        forecast == null -> null
        forecast.isNextIncome -> ForecastPeriod.NEXT_INCOME
        else -> periods.firstOrNull { it.days == forecast.horizonDays }
    }
    var period by rememberSaveable { mutableIntStateOf(shown?.ordinal ?: -1) }
    var checked by remember(accounts) { mutableStateOf(selection.accountIds ?: forecast?.accountIds?.toSet() ?: accounts.orEmpty().map { it.accountId }.toSet()) }
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("예보 기간", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(14.dp))
        Segmented(periods.map { it.label }, selectedIndex = period, onSelect = { period = it })
        Spacer(Modifier.height(24.dp))
        Text("계좌", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(6.dp))
        when {
            accounts == null && accountsError != null -> ErrorState(accountsError, onRetry = onRetry)
            accounts == null -> repeat(2) {
                SkeletonBlock(height = 48.dp)
                Spacer(Modifier.height(8.dp))
            }
            else -> accounts.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                val on = a.accountId in checked
                ListRow(
                    title = a.name,
                    sub = listOfNotNull(a.bankName, a.last4.takeIf { it.isNotEmpty() }?.let { "··$it" }, a.balance?.let { "${it.toWon()}원" }).joinToString(" · "),
                    titleTag = if (a.isPrimary) ({ StatusTag("기준", tone = TagTone.Neutral) }) else null,
                    lead = { RoundCheckbox(checked = on, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = on, role = Role.Checkbox, onValueChange = { checked = if (it) checked + a.accountId else checked - a.accountId }),
                )
            }
        }
        Spacer(Modifier.height(YamuSpacing.x4))
        PrimaryButton(
            "이대로 보기",
            onClick = { onApply(periods.getOrNull(period), checked.takeIf { accounts != null }) },
            enabled = accounts == null || checked.isNotEmpty(),
        )
        if (accounts != null && checked.isEmpty()) {
            Text("계좌를 하나 이상 골라 주세요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ───────────────────────── F-02 예정 수입 · 지출 ─────────────────────────

/**
 * F-02 한 줄 — 가계부 항목이면 눌러서 고치고(F-02b), 맞춘 확인 전 후보는 눌러서 넣거나 빼고, 카드 청구와 못 맞춘 후보는 읽기 전용.
 * 반복이면 날짜 옆에 '매월 말일'처럼. 확인 전 후보는 F-01 '예정된 돈'과 같은 '예상' 태그.
 * [onDelete]가 있으면 줄 끝에 휴지통(터치 48dp). 보내는 중([busy])이면 휴지통도 줄도 눌리지 않는다(지우는 중에 수정 화면으로 들어가지 않게)
 */
@Composable
private fun CashflowRowLine(row: CashflowRow, busy: Boolean, onClick: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val c = YamuTheme.colors
    ListRow(
        title = row.title,
        sub = cashflowRowSub(row),
        value = "${row.amount.toWon(withPlusSign = true)}원",
        valueColor = if (row.amount > 0) c.primary else c.text1,
        titleTag = if (row.candidate) ({ StatusTag("예상", tone = TagTone.Neutral) }) else null,
        trailing = onDelete?.let { delete ->
            {
                IconButton(onClick = delete, enabled = !busy) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = deleteLabel(row),
                        tint = if (busy) c.text3.copy(alpha = 0.4f) else c.text3,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        dense = true,
        onClick = onClick?.takeIf { !busy },
    )
}

/** 휴지통 읽기 이름 — 가계부 줄은 '○○ 지우기', 후보 줄은 시트와 같은 '○○ 빼기' */
internal fun deleteLabel(row: CashflowRow): String = if (row.entry == null && row.pending != null) "${row.title} 빼기" else "${row.title} 지우기"

/** 맞춘 확인 전 후보 — 금액 · 날짜(출금일 기준) · 찾은 근거와 넣기 · 빼기 */
@Composable
private fun CandidateSheet(ask: CashflowAsk.Candidate, busy: Boolean, onDecide: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = YamuTheme.colors
    val candidate = ask.candidate
    YamuBottomSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(candidate.title, style = YamuTheme.typography.titleL, color = c.text1, modifier = Modifier.weight(1f, fill = false))
            StatusTag("예상", tone = TagTone.Neutral)
        }
        Spacer(Modifier.height(4.dp))
        Text(candidateSheetNote(candidate), style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(YamuSpacing.x3))
        KeyValueRow("금액", "${ask.amount.toWon(withPlusSign = true)}원", large = false)
        ask.date?.let { KeyValueRow("날짜", "${it.toKoreanDateWeek()} · 출금일 기준", large = false) }
        candidate.basis?.let { KeyValueRow("찾은 근거", it, large = false) }
        Spacer(Modifier.height(YamuSpacing.x5))
        PrimaryButton("${candidateKindTo(candidate)} 넣기", onClick = { onDecide(true) }, enabled = !busy)
        Spacer(Modifier.height(YamuSpacing.x2))
        NeutralButton("빼기", onClick = { onDecide(false) }, enabled = !busy)
    }
}

/** 후보 시트 설명 — 넣으면 예정 목록에서 고칠 수 있고, 빼면 잔고예보에서도 빠진다 */
internal fun candidateSheetNote(c: CashflowCandidate): String =
    "기록에서 찾은 반복 ${if (c.isIncome) "수입" else "지출"}이라 아직 확인 전이에요. 넣으면 예정 목록에서 고칠 수 있고, 빼면 잔고예보에서도 빠져요."

/** F-02 보조 줄 — '11/5 (목) · 매월 5일 · 카드 결제', 확인 전 후보는 '11/25 (수) · 출금일 기준' */
internal fun cashflowRowSub(row: CashflowRow): String =
    listOfNotNull(row.date?.toMonthDayWeek(), row.repeatLabel, row.cardNote, row.dateNote).joinToString(" · ")

@Composable
fun CashflowListRoute(
    onBack: () -> Unit,
    onLink: () -> Unit = {},
    onAdd: () -> Unit = {},
    onEdit: (itemId: Long, occurrenceDate: LocalDate) -> Unit = { _, _ -> },
    viewModel: ForecastViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshWithEntries()   // 추가(F-02a) · 수정(F-02b)하고 돌아오면 새로
        onPauseOrDispose { }
    }
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    val today = LocalDate.now(KST)
    val c = YamuTheme.colors
    // 회색 바탕 + 카드: 요약 · 달마다 하나(프로토타입 F-02). 계좌를 이었을 때만 + (추가는 계좌 · 카드가 있어야 한다)
    FlowScreen(
        title = "예정 수입·지출",
        onBack = onBack,
        cards = true,
        actions = {
            if (!s.noAccount) IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, contentDescription = "예정 수입·지출 추가", tint = c.text1) }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val f = s.forecast
            when {
                s.cashflowLoading -> YamuCard {
                    repeat(4) {
                        SkeletonBlock(height = 48.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                // 계좌를 아직 안 이었으면 오류가 아니라 빈 상태(F-01과 같은 문장 · 행동)
                s.noAccount -> YamuCard { EmptyState("계좌를 연결하면 예정된 돈을 보여 드려요", actionText = "계좌 연결", onAction = onLink) }
                f == null && s.entries == null -> YamuCard { ErrorState(s.cashflowError ?: "예정된 돈을 불러오지 못했어요", onRetry = viewModel::refreshWithEntries) }
                else -> {
                    // 가계부 회차를 받았으면 그 줄(고칠 수 있음) + 카드 청구, 못 받았으면 예보 줄만(읽기 전용, 추정은 빼고)
                    val entries = s.entries
                    val rows = if (entries != null) cashflowRows(entries, f, s.candidates) else {
                        requireNotNull(f)   // 둘 다 없으면 위에서 오류 화면
                        forecastOnlyRows(f, s.candidates)
                    }
                    val period = cashflowPeriod(f)
                    YamuCard {
                        Text("$period 나갈 돈", style = YamuTheme.typography.titleS, color = c.text2)
                        Spacer(Modifier.height(4.dp))
                        val (outAmount, outCount) = cashflowOutflow(f, rows)
                        AmountText(outAmount)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                if (f?.from != null && f.to != null) "${f.from.toMonthDay()} ~ ${f.to.toMonthDay()}" else null,
                                "출금 ${outCount}건",
                                f?.accountLabel(s.selection.all),   // 잔고예보에서 고른 계좌로 걸렀다는 표시(기본은 기준 계좌, -484)
                            ).joinToString(" · "),
                            style = YamuTheme.typography.bodyM,
                            color = c.text2,
                        )
                    }
                    s.entriesError?.let { YamuCard { ErrorState("고칠 수 있는 목록을 불러오지 못했어요 · $it", onRetry = viewModel::refreshWithEntries) } }
                    if (rows.isEmpty()) YamuCard { EmptyState("$period 예정된 돈이 없어요", actionText = "추가", onAction = onAdd) }
                    rows.groupBy { it.date?.monthValue }.forEach { (month, monthRows) ->
                        YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
                            month?.let { Text("${it}월", style = YamuTheme.typography.cardTitle, color = c.text1) }
                            monthRows.forEachIndexed { i, row ->
                                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                                val entry = row.entry
                                val id = entry?.itemId ?: row.pending?.id
                                CashflowRowLine(
                                    row,
                                    busy = id != null && id in s.busy,
                                    onClick = when {
                                        entry != null -> ({ onEdit(entry.itemId, entry.occurrenceDate) })
                                        row.pending != null -> ({ viewModel.openCandidate(row) })
                                        else -> null
                                    },
                                    onDelete = if (row.deletable(today)) ({ viewModel.askDelete(row) }) else null,
                                )
                            }
                        }
                    }
                    // 지난 7일 카드 예정 지출 — 결제와 연결만(계좌를 일부만 골랐으면 그 계좌 카드만)
                    val scope = f?.scope
                    PastCardEntriesCard(s.pastEntries.filter { scope?.includes(it) ?: true }, onOpen = { onEdit(it.itemId, it.occurrenceDate) })
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    // 휴지통 · 후보 줄에서 한 번 더 묻기 — 수정 화면과 같은 시트 · 문장(CashflowDelete.kt)
    when (val ask = s.ask) {
        null -> Unit
        is CashflowAsk.Delete -> if (ask.entry.isRecurring) {
            DeleteScopeSheet(ask.entry, onPick = { viewModel.delete(ask.entry, it) }, onDismiss = viewModel::dismissAsk)
        } else {
            DeleteOnceDialog(ask.entry.title, onConfirm = { viewModel.delete(ask.entry, CashflowDeleteScope.ALL) }, onDismiss = viewModel::dismissAsk)
        }
        is CashflowAsk.Candidate -> CandidateSheet(
            ask,
            busy = ask.itemId in s.busy,
            onDecide = { confirm -> viewModel.decide(ask.candidate, confirm) },
            onDismiss = viewModel::dismissAsk,
        )
        is CashflowAsk.Ignore -> YamuDialog(
            title = "'${ask.candidate.title}'${ask.candidate.title.objectParticle()} 뺄까요?",
            text = "확인 전 후보에서 빠지고 잔고예보에도 넣지 않아요",
            confirmText = "빼기",
            onConfirm = { viewModel.decide(ask.candidate, confirm = false) },
            onDismiss = viewModel::dismissAsk,
        )
    }
}

/** 보이는 높이는 [visible]로 두고 실제 칸(터치 48dp)은 위아래로 넘치게 놓는다 — 프로토타입의 음수 여백 링크(.card-hd .more) */
private fun Modifier.overflowHeight(visible: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = visible.roundToPx()
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}
