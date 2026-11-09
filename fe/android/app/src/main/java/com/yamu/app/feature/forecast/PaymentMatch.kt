package com.yamu.app.feature.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.CompactButton
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuDialog
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.MatchCandidate
import com.yamu.app.feature.forecast.data.MatchCandidates
import com.yamu.app.feature.forecast.data.MatchState
import com.yamu.app.feature.forecast.data.PaymentMatch
import com.yamu.app.feature.forecast.data.toMatch
import com.yamu.app.feature.forecast.data.toMatchMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// ───────────────────────── F-02b 결제와 연결(-319) ─────────────────────────

/** F-02 '지난 7일' — BE 후보가 예정일 앞뒤 7일이라 그보다 오래된 회차는 연결할 결제가 없다 */
internal const val PAST_MATCH_DAYS = 7L

data class MatchUiState(
    val loading: Boolean = true,
    val match: PaymentMatch? = null,
    val error: String? = null,
    val sheet: Boolean = false,
    val candidates: MatchCandidates? = null,
    val candidatesError: String? = null,
    val working: Boolean = false,              // 연결 · 해제를 보내는 중 — 연타가 두 번 나가지 않게 보내기 전에 켠다
    val confirmUnlink: Boolean = false,
    val warnings: List<String> = emptyList(),  // 방금 연결한 결과의 경고(연결은 됐고 알리기만)
    val message: String? = null,
)

/**
 * 예정 지출 회차 하나의 결제 연결 — 수정 화면(CashflowEdit)과 같은 경로 값(itemId · occurrenceDate)을 읽는다.
 * 연결은 같은 쌍을 다시 보내도 200이라 응답을 못 받으면 상태를 다시 불러 확인하고, 해제의 404는 이미 끊긴 것으로 본다
 */
@HiltViewModel
class PaymentMatchViewModel internal constructor(private val key: Pair<Long, LocalDate>?, private val repo: ForecastRepository) : ViewModel() {
    @Inject constructor(savedStateHandle: SavedStateHandle, repo: ForecastRepository) : this(
        savedStateHandle.get<Long>("itemId")?.let { id -> savedStateHandle.get<String>("occurrenceDate")?.let { id to LocalDate.parse(it) } },
        repo,
    )

    private val _state = MutableStateFlow(MatchUiState(loading = key != null))
    val state: StateFlow<MatchUiState> = _state.asStateFlow()
    private var statusJob: Job? = null
    private var candidatesJob: Job? = null

    init { refresh() }

    fun refresh() {
        val (itemId, occurrence) = key ?: return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.match == null, error = null) }
            when (val r = repo.matchStatus(itemId, occurrence)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, match = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun openCandidates() {
        _state.update { it.copy(sheet = true) }
        loadCandidates()
    }

    fun closeCandidates() = _state.update { it.copy(sheet = false) }

    fun loadCandidates() {
        val (itemId, occurrence) = key ?: return
        // 시트를 닫았다 바로 열면 앞 요청이 늦게 와 새 후보를 덮을 수 있어 앞 요청은 버린다
        candidatesJob?.cancel()
        candidatesJob = viewModelScope.launch {
            _state.update { it.copy(candidates = null, candidatesError = null) }
            when (val r = repo.matchCandidates(itemId, occurrence)) {
                // 그사이 다른 기기에서 연결했으면 시트를 닫고 후보 대신 지금 연결을 보인다
                is ApiResult.Success -> _state.update {
                    val now = r.data.match ?: return@update it.copy(candidates = r.data)
                    it.copy(candidates = r.data, match = now, sheet = false, message = it.message ?: "이미 다른 결제와 연결돼 있어요")
                }
                is ApiResult.Failure -> _state.update { it.copy(candidatesError = r.error.userMessage()) }
            }
        }
    }

    fun confirm(paymentId: Long) {
        val (itemId, occurrence) = key ?: return
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            when (val r = repo.confirmMatch(itemId, occurrence, paymentId)) {
                // 고른 후보로 연결을 먼저 채운다 — 뒤이은 상태 조회가 실패해도 오류 화면 대신 연결이 보이게
                is ApiResult.Success -> _state.update {
                    val picked = it.candidates?.candidates?.firstOrNull { c -> c.paymentId == paymentId }
                    it.copy(
                        working = false,
                        sheet = false,
                        match = r.data.toMatch(paymentId, picked),
                        warnings = r.data.warnings,
                        message = "결제와 연결했어요 · 잔고예보에서 이 회차는 실제 결제로 셉니다",
                    )
                }
                is ApiResult.Failure -> fail(r.error)
            }
            refresh()
        }
    }

    private fun fail(e: ApiError) {
        if (e.isOutcomeUnknown) {
            // 반영됐을 수 있다 — 같은 쌍은 다시 보내도 200이지만 먼저 상태를 다시 읽어 보여 준다
            _state.update { it.copy(working = false, sheet = false, message = "응답을 받지 못해 연결 상태를 다시 확인했어요") }
            return
        }
        _state.update { it.copy(working = false, message = e.toMatchMessage()) }
        if (e is ApiError.Conflict || e is ApiError.NotFound || e is ApiError.InvalidRequest) loadCandidates()
    }

    fun askUnlink() = _state.update { it.copy(confirmUnlink = true) }

    fun dismissUnlink() = _state.update { it.copy(confirmUnlink = false) }

    fun unlink() {
        val match = _state.value.match ?: return
        if (_state.value.working) return
        _state.update { it.copy(working = true, confirmUnlink = false) }
        viewModelScope.launch {
            when (val r = repo.deleteMatch(match.matchId)) {
                is ApiResult.Success -> _state.update { it.copy(working = false, match = null, warnings = emptyList(), message = "연결을 끊었어요 · 이 회차가 다시 예정 지출로 들어가요") }
                // 이미 끊겼다(다른 기기 · 다시 누름) — 상태만 새로
                is ApiResult.Failure -> if (r.error is ApiError.NotFound) {
                    _state.update { it.copy(working = false, match = null, warnings = emptyList(), message = "이미 끊긴 연결이에요") }
                } else {
                    _state.update { it.copy(working = false, message = if (r.error.isOutcomeUnknown) "응답을 받지 못해 연결 상태를 다시 확인했어요" else r.error.toMatchMessage()) }
                }
            }
            refresh()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

/**
 * 연결된 결제 아래 안내 — 지난 회차는 연결을 끊어도 고칠 수 없어(BE requireChangeable이 지난 회차를 거절) '고칠 수 있어요'를 말하지 않는다
 */
internal fun matchedNote(state: MatchState, past: Boolean): String = when {
    state == MatchState.REVIEW_REQUIRED && past -> "연결한 결제가 취소돼 이 회차는 다시 예정 지출로 셉니다 · 연결을 끊으면 다른 결제와 다시 연결할 수 있어요"
    state == MatchState.REVIEW_REQUIRED -> "연결한 결제가 취소돼 이 회차는 다시 예정 지출로 셉니다 · 연결을 끊으면 고치거나 다른 결제와 연결할 수 있어요"
    past -> "잔고예보에서 이 회차는 예정 지출 대신 실제 결제로 셉니다"
    else -> "잔고예보에서 이 회차는 예정 지출 대신 실제 결제로 셉니다 · 연결을 끊기 전에는 고칠 수 없어요"
}

/** 수정 화면 아래 '실제 결제와 연결' — 카드를 지정한 예정 지출 회차만. [past]면 지난 회차(고칠 수 없음) */
@Composable
internal fun PaymentMatchSection(past: Boolean, viewModel: PaymentMatchViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    val c = YamuTheme.colors
    Spacer(Modifier.height(YamuSpacing.x6))
    HorizontalDivider(color = c.outlineVariant)
    Spacer(Modifier.height(YamuSpacing.x4))
    Text("실제 결제와 연결", style = YamuTheme.typography.sub, color = c.text3)
    Spacer(Modifier.height(YamuSpacing.x2))
    val match = s.match
    val error = s.error
    when {
        s.loading -> SkeletonBlock(height = 56.dp)
        error != null && match == null -> ErrorState(error, onRetry = viewModel::refresh)
        match != null -> MatchedPayment(match, s.warnings, past = past, working = s.working, onUnlink = viewModel::askUnlink)
        else -> {
            Text("이 회차를 실제로 결제했다면 연결해 주세요 · 잔고예보에서 두 번 빠지지 않아요", style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(YamuSpacing.x3))
            CompactButton("결제와 연결", onClick = viewModel::openCandidates)
        }
    }
    if (s.sheet) CandidatesSheet(s.candidates, s.candidatesError, working = s.working, onRetry = viewModel::loadCandidates, onDismiss = viewModel::closeCandidates, onPick = viewModel::confirm)
    if (s.confirmUnlink) {
        YamuDialog(
            title = "결제 연결을 끊을까요?",
            text = "연결을 끊으면 이 회차가 다시 예정 지출로 잔고예보에 들어가요",
            confirmText = "끊기",
            onConfirm = viewModel::unlink,
            onDismiss = viewModel::dismissUnlink,
        )
    }
}

/** 지금 연결된 결제 — 결제가 취소됐으면(REVIEW_REQUIRED) 다시 확인하라고 알린다 */
@Composable
private fun MatchedPayment(match: PaymentMatch, warnings: List<String>, past: Boolean, working: Boolean, onUnlink: () -> Unit) {
    val c = YamuTheme.colors
    val reviewNeeded = match.state == MatchState.REVIEW_REQUIRED
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YamuRadius.input))
            .background(c.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(match.merchantName, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
            StatusTag(if (reviewNeeded) "결제 취소됨" else "연결됨", tone = if (reviewNeeded) TagTone.Warning else TagTone.Success)
        }
        match.paidAmount?.let { Text("${it.toWon()}원", style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(top = 2.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(
            matchedNote(match.state, past),
            style = YamuTheme.typography.bodyS,
            color = if (reviewNeeded) c.onWarningContainer else c.text3,
        )
        warnings.forEach { Text("· $it", style = YamuTheme.typography.bodyS, color = c.onWarningContainer, modifier = Modifier.padding(top = 2.dp)) }
    }
    TextButtonGrey("연결 끊기", onClick = onUnlink, modifier = Modifier.fillMaxWidth().alpha(if (working) 0.4f else 1f), small = true)
}

/** 후보 고르기 — 근거와 경고를 미리 보이고, 누르면 바로 연결(경고가 있어도 연결은 된다) */
@Composable
private fun CandidatesSheet(
    data: MatchCandidates?,
    error: String?,
    working: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("어떤 결제였나요?", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(4.dp))
        val scheduled = listOfNotNull(data?.scheduledDate?.toKoreanDate(), data?.scheduledAmount?.let { "${it.toWon()}원" }).joinToString(" · ")
        Text(listOfNotNull(scheduled.ifEmpty { null }?.let { "예정 $it" }, "앞뒤 7일 안의 결제").joinToString(" · "), style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(YamuSpacing.x3))
        when {
            data == null && error != null -> ErrorState(error, onRetry = onRetry)
            data == null -> repeat(3) {
                SkeletonBlock(height = 56.dp)
                Spacer(Modifier.height(8.dp))
            }
            data.candidates.isEmpty() -> Text("예정일 앞뒤 7일 안에 연결할 결제가 없어요", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(vertical = 16.dp))
            else -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).alpha(if (working) 0.5f else 1f)) {
                data.candidates.forEachIndexed { i, cand ->
                    if (i > 0) HorizontalDivider(color = c.outlineVariant)
                    CandidateRow(cand, enabled = !working, onClick = { onPick(cand.paymentId) })
                }
            }
        }
        Spacer(Modifier.height(YamuSpacing.x4))
    }
}

@Composable
private fun CandidateRow(cand: MatchCandidate, enabled: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "${cand.merchantName} 결제와 연결", onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cand.merchantName, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
            cand.paidAmount?.let { Text("${it.toWon()}원", style = YamuTheme.typography.titleS, color = c.text1) }
        }
        val sub = listOfNotNull(cand.approvedDate?.toMonthDayWeek(), cand.isCredit?.let { if (it) "신용" else "체크" }) + cand.reasons
        Text(sub.joinToString(" · "), style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp))
        if (cand.warnings.isNotEmpty()) {
            Text(cand.warnings.joinToString(" · "), style = YamuTheme.typography.bodyS, color = c.onWarningContainer, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * 고칠 수 없는 회차 — 내용만 보이고 결제 연결만 한다. 지난 회차와 결제와 연결된 회차는 BE가 수정 · 삭제를 거절한다.
 * 기본 문구는 지난 회차용이고, 연결된 회차는 [heading] · [note]를 바꿔 쓴다
 */
@Composable
internal fun PastOccurrenceSummary(
    entry: CashflowEntry,
    cardName: String?,
    heading: String = "지난 예정 지출",
    note: String = "지난 회차는 고칠 수 없어요 · 실제 결제와 연결만 할 수 있어요",
) {
    val c = YamuTheme.colors
    Text(heading, style = YamuTheme.typography.titleM, color = c.text1)
    Text(note, style = YamuTheme.typography.bodyS, color = c.text3)
    Spacer(Modifier.height(YamuSpacing.x3))
    KeyValueRow("이름", entry.title, large = false)
    KeyValueRow("금액", "${entry.amount.toWon()}원", large = false)
    KeyValueRow("날짜", entry.date.toKoreanDate(), large = false)
    KeyValueRow("카드", cardName ?: "카드", large = false)
}

/** F-02 '지난 7일' — 어제까지 지난 카드 예정 지출. 결제는 보통 예정일 뒤에 해서 지난 회차에 연결하는 일이 많다 */
@Composable
internal fun PastCardEntriesCard(entries: List<CashflowEntry>, onOpen: (CashflowEntry) -> Unit) {
    if (entries.isEmpty()) return
    val c = YamuTheme.colors
    YamuCard(contentPadding = PaddingValues(YamuSpacing.x4)) {
        Text("지난 7일 카드 예정 지출", style = YamuTheme.typography.cardTitle, color = c.text1)
        Text("실제로 결제했다면 눌러서 결제와 연결해 주세요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp))
        entries.sortedByDescending { it.date }.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            ListRow(
                title = e.title,
                sub = e.date.toMonthDayWeek(),
                value = "${e.signedAmount.toWon(withPlusSign = true)}원",
                chevron = true,
                dense = true,
                onClick = { onOpen(e) },
            )
        }
    }
}
