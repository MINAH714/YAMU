package com.yamu.app.feature.payment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.NeutralButton
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.payment.data.MemoRules
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.amountText
import com.yamu.app.feature.payment.data.instantDiscountText
import com.yamu.app.feature.payment.data.toMemoProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── P-08 거래 상세 · 메모 · 결제 취소 ─────────────────────────

data class PaymentDetailState(
    val loading: Boolean = true,
    val receipt: PaymentReceipt? = null,
    val error: String? = null,
    val memoOpen: Boolean = false,
    val savingMemo: Boolean = false,
    val memoError: String? = null,
    /** 상세에 memo가 없어 결제 내역에서 찾는 중 */
    val memoChecking: Boolean = false,
    /** 스낵바 한 번 */
    val message: String? = null,
)

/**
 * 결제 상세(#3) · 메모(#42).
 *
 * 취소는 앱이 바로 하지 않는다(11/10 QA). '결제 취소'는 결제 코드 화면(CancelCode)을 열고, 가맹점 POS가 결제 내역에서 고른 결제를
 * 그 코드로 취소한다. 그래서 이 화면은 다시 보일 때마다 상세를 다시 받아 다른 곳에서 한 취소를 반영한다([resumed]).
 * 취소를 알게 되면 저장소가 결제 내역 · 결제 완료에도 알린다(PaymentEdits)
 */
@HiltViewModel
class PaymentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val payments: PaymentRepository,
    link: LinkRepository,
) : ViewModel() {
    private val paymentId: Long = checkNotNull(savedStateHandle.get<Long>("paymentId"))   // 경로 PaymentDetail.paymentId
    private val _state = MutableStateFlow(PaymentDetailState())
    val state: StateFlow<PaymentDetailState> = _state.asStateFlow()

    /** 결제 카드 그림 — 거래 응답의 카드 이름은 별명이라 카드 목록에서 상품명을 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    /** 메모 찾기는 화면마다 한 번 — 못 찾으면 '모름'으로 두고 저장 전에 알린다 */
    private var memoLookedUp = false

    /** 처음 보일 때는 init에서 이미 받고 있다 — 그 뒤 다시 보일 때(결제 코드 화면에서 돌아옴 등)만 다시 받는다 */
    private var shownOnce = false

    init { load() }

    /** 화면이 다시 보일 때 — 다른 곳(POS)에서 취소됐을 수 있다. 받는 중이면 겹쳐 보내지 않는다 */
    fun resumed() {
        if (!shownOnce) {
            shownOnce = true
            return
        }
        if (!_state.value.loading) load()
    }

    /** 다시 불러오는 동안에도 보던 내용은 그대로 둔다(실패해도 마지막 값). 이미 아는 메모는 '모름'으로 되돌리지 않는다 */
    fun load() {
        artLoader.ensure(listOfNotNull(_state.value.receipt?.cardId))
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = payments.detail(paymentId)) {
                is ApiResult.Success -> {
                    artLoader.ensure(listOfNotNull(r.data.cardId))
                    _state.update {
                        val known = it.receipt?.takeIf { old -> old.memoKnown }
                        val fresh = if (!r.data.memoKnown && known != null) r.data.copy(memo = known.memo, memoKnown = true) else r.data
                        it.copy(loading = false, receipt = fresh)
                    }
                    findMemoIfUnknown()
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    /**
     * 목록을 거치지 않고 들어왔으면(홈 · 알림 · 결제 완료 · 앱 다시 켬) 메모를 모른다. 모르는 채로 '메모 남기기'를 보이면
     * 서버의 메모를 확인 없이 덮어쓰므로 결제 내역에서 찾아본다. 그사이 저장했으면 찾은 값으로 덮지 않는다
     */
    private fun findMemoIfUnknown() {
        val receipt = _state.value.receipt ?: return
        if (receipt.memoKnown || memoLookedUp) return
        memoLookedUp = true
        _state.update { it.copy(memoChecking = true) }
        viewModelScope.launch {
            val found = payments.findMemo(paymentId, receipt.paidAtIso)
            _state.update {
                val current = it.receipt
                it.copy(
                    memoChecking = false,
                    receipt = if (found != null && current != null && !current.memoKnown) current.copy(memo = found.text, memoKnown = true) else current,
                )
            }
        }
    }

    /** 찾는 중에는 열지 않는다 — 열면 아직 모르는 메모를 빈 칸으로 보이게 된다 */
    fun openMemo() {
        if (_state.value.memoChecking) return
        _state.update { it.copy(memoOpen = true, memoError = null) }
    }

    fun closeMemo() = _state.update { it.copy(memoOpen = false, memoError = null) }

    fun memoEdited() {
        if (_state.value.memoError != null) _state.update { it.copy(memoError = null) }
    }

    /** [raw]가 비었거나 공백뿐이면 지운다. 앱에서 먼저 BE와 같은 규칙으로 세서 넘치면 보내지 않는다 */
    fun saveMemo(raw: String) {
        if (_state.value.savingMemo) return
        MemoRules.problem(raw)?.let { problem ->
            _state.update { it.copy(memoError = problem) }
            return
        }
        val text = MemoRules.normalize(raw).ifEmpty { null }
        if (text == null && _state.value.receipt?.memoKnown == false) {   // 모르는 메모를 지우지 않는다
            _state.update { it.copy(memoError = "전에 남긴 메모를 불러오지 못해 지울 수 없어요") }
            return
        }
        _state.update { it.copy(savingMemo = true, memoError = null) }
        viewModelScope.launch {
            when (val res = payments.saveMemo(paymentId, text)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        savingMemo = false,
                        memoOpen = false,
                        receipt = it.receipt?.copy(memo = res.data, memoKnown = true),
                        message = if (res.data == null) "메모를 지웠어요" else "메모를 남겼어요",
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(savingMemo = false, memoError = res.error.toMemoProblem()) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

/** [onCancel]은 결제 코드 화면을 연다 — 취소는 매장 POS가 그 코드를 찍어 한다 */
@Composable
fun PaymentDetailRoute(onBack: () -> Unit, onCancel: (PaymentReceipt) -> Unit, viewModel: PaymentDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val arts by viewModel.arts.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val r = s.receipt
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.message) {
        val message = s.message ?: return@LaunchedEffect
        viewModel.messageShown()
        scope.launch { snackbar.showSnackbar(message) }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.resumed()
        onPauseOrDispose { }
    }

    FlowScreen(title = "거래 상세", onBack = onBack, actions = { MockPayTag() }) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                r == null && s.loading -> Column(Modifier.padding(YamuSpacing.screen)) {
                    repeat(4) {
                        SkeletonBlock(height = 44.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                r == null -> ErrorState(s.error ?: "결제 내역을 불러오지 못했어요", onRetry = viewModel::load, modifier = Modifier.padding(YamuSpacing.screen))
                else -> {
                    // 가맹점 · 일시 → 금액 → 즉시할인 한 줄('6,600원에서 660원 바로 할인') — 위 12 · 사이 6(프로토타입 P-08 .hd)
                    // 즉시할인은 빠진 금액(실제 결제 금액)을 크게 보인다. 청구할인 · 캐시백 · 적립은 결제 금액 그대로(11/10 QA).
                    // 예전 '○원 할인받고 ○원 청구 예정' 줄은 청구할인까지 더한 할인과 청구할인 전 원금을 나란히 놓아 지웠다
                    Column(Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 12.dp, bottom = 24.dp)) {
                        // 가맹점 · 업종 · 일시 — 업종을 모르면(목록에 없는 코드) 빼고 잇는다(-394)
                        Text(listOfNotNull(r.merchantName, r.categoryName, formatPaidAt(r.paidAtIso)).joinToString(" · "), style = YamuTheme.typography.bodyL, color = c.text2)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 금액이 길면 '취소' 태그 자리를 남기고 줄어든다
                            AmountText(r.paidAmount, Modifier.weight(1f, fill = false))
                            if (r.canceled) StatusTag("취소")
                        }
                        r.instantDiscountText()?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = YamuTheme.typography.bodyM, color = c.text2)
                        }
                    }
                    HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(horizontal = YamuSpacing.screen))
                    Column(Modifier.padding(horizontal = YamuSpacing.screen, vertical = 12.dp)) {
                        // 카드 그림 44dp(높이 28 — 줄 높이 40 그대로). 상품명은 카드 목록에서, 모르면 카드 이름(별명이 없으면 상품명)으로 찾는다
                        val art = r.cardId?.let { arts[it] }
                        KeyValueRow(
                            "결제 카드",
                            listOfNotNull(r.cardName, r.cardLast4?.let { "•••• $it" }).joinToString(" "),
                            large = false,
                            valueLead = (r.cardBrand ?: art?.brand)?.let { b ->
                                { CardListThumb(b, art?.productName ?: r.cardName.orEmpty(), width = 44.dp, dimmed = art?.dimmed == true) }
                            },
                        )
                        // 카드 줄 아래 오른쪽에 '추천 N순위' · '직접 고른 카드'(-394), 옛 결제 · SEED는 비운다.
                        // 키 아래(keySub)에 두면 카드 이름이 긴 큰 글자에서 키 칸이 한 글자 폭으로 눌려 세로로 쌓인다
                        r.cardPickLabel?.let {
                            Text(it, style = YamuTheme.typography.sub, color = c.text3, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        }
                        r.approvalNo?.let { KeyValueRow("승인번호", it, large = false) }
                        MemoRow(r.memo, known = r.memoKnown, checking = s.memoChecking, onClick = viewModel::openMemo)
                    }
                    HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(horizontal = YamuSpacing.screen))
                    if (r.canceled) {
                        CanceledLines(r)
                    } else {
                        BenefitLines(r)
                    }
                    if (r.cancelable) {
                        // 주 버튼 대신 아래 회색 글자 버튼 → 결제 코드 화면(매장 POS가 찍어 취소한다)
                        TextButtonGrey(
                            "결제 취소",
                            onClick = { onCancel(r) },
                            small = true,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }

    if (s.memoOpen && r != null) {
        MemoSheet(
            initial = r.memo,
            known = r.memoKnown,
            saving = s.savingMemo,
            serverError = s.memoError,
            onEdit = viewModel::memoEdited,
            onSave = viewModel::saveMemo,
            onDismiss = viewModel::closeMemo,
        )
    }
}

/**
 * 메모 줄 — 나만 보는 한 줄이고 취소된 결제에도 남길 수 있다. 100자까지라 KeyValueRow 대신 값이 남은 폭을 쓰고 두 줄에서 자른다
 * (KeyValueRow는 값이 길면 키 칸이 눌린다). 높이 · 글자는 상세의 작은 키–값 줄(40dp · bodyM / titleS)과 같다
 */
@Composable
private fun MemoRow(memo: String?, known: Boolean, checking: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    // 모르는 메모를 '메모 남기기'로 보이지 않는다 — 찾는 중 · 못 찾음을 따로
    val (value, color) = when {
        memo != null -> memo to c.text1
        checking -> "확인하는 중…" to c.text3
        !known -> "불러오지 못했어요" to c.text3
        else -> "메모 남기기" to c.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(enabled = !checking, role = Role.Button, onClickLabel = if (memo == null) "메모 남기기" else "메모 고치기", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("메모", style = YamuTheme.typography.bodyM, color = c.text2)
        Text(
            value,
            style = YamuTheme.typography.titleS,
            color = color,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3)
    }
}

/** 혜택 · 실적(추정치) — 승인 상태의 결제 */
@Composable
private fun BenefitLines(r: PaymentReceipt) {
    val c = YamuTheme.colors
    Column(Modifier.padding(horizontal = YamuSpacing.screen, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Text("혜택 · 실적", style = YamuTheme.typography.titleM, color = c.text1)
            EstimateTag(text = "추정치")
        }
        r.benefits.forEach { b -> KeyValueRow(b.name, b.amountText(), large = false, valueColor = c.primary) }
        if (r.perfRecognized != null) {
            val card = r.cardBrand?.name ?: "카드"
            val month = r.perfMonth?.let { "${it}월 " }.orEmpty()
            val total = r.perfTotalAfter
            val target = r.perfTarget
            KeyValueRow(
                "$card ${month}실적",
                when {
                    !r.perfCounted -> "이 결제는 빠져요"
                    total != null && target != null -> "${total.toWon()} / ${target.toWon()}원"
                    else -> "+${r.perfRecognized.toWon()}원"
                },
                large = false,
            )
        }
    }
}

/**
 * 취소된 결제 — 취소 시각 · 돌려받는 돈. 받지 않은 혜택 · 빠진 실적은 줄로 늘어놓지 않고 한 줄로.
 * 체크카드 입금 뒤 잔액은 취소 응답에만 있어(POS가 취소하면 앱은 못 받는다) 보이지 않는다
 */
@Composable
private fun CanceledLines(r: PaymentReceipt) {
    val c = YamuTheme.colors
    Column(Modifier.padding(horizontal = YamuSpacing.screen, vertical = 12.dp)) {
        Text("취소", style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.padding(vertical = 8.dp))
        formatPaidAt(r.canceledAtIso)?.let { KeyValueRow("취소한 때", it, large = false) }
        when {
            r.isCheck -> r.chargedAmount?.let { KeyValueRow("계좌로 돌려받음", "${it.toWon()}원", large = false) }
            r.refunds.isEmpty() -> KeyValueRow("청구", "청구에서 빠져요", large = false)
            else -> r.refunds.forEach { f ->
                KeyValueRow(
                    if (f.completed) "돌려받음" else "돌려받을 금액",
                    "${f.amount.toWon()}원",
                    large = false,
                    keySub = if (f.completed) null else formatDay(f.dueDateIso)?.let { "${it}까지 결제 계좌로" } ?: "출금 뒤 3영업일 안에 결제 계좌로",
                )
            }
        }
        Text(
            "이 결제로 쌓인 실적과 혜택 한도는 되돌렸어요",
            style = YamuTheme.typography.bodyS,
            color = c.text3,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 메모 입력 — 한 줄 100자(코드포인트). 저장할 수 없으면 칸 아래 빨간 줄 · 저장 버튼 막음.
 * [known]이 false면 전에 남긴 메모를 모른다: 저장하면 덮어쓴다고 먼저 알리고, 빈 칸 저장(지우기)은 막는다
 */
@Composable
private fun MemoSheet(
    initial: String?,
    known: Boolean,
    saving: Boolean,
    serverError: String?,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = YamuTheme.colors
    var text by rememberSaveable { mutableStateOf(initial.orEmpty()) }
    val normalized = MemoRules.normalize(text)
    val count = MemoRules.length(normalized)
    val problem = MemoRules.problem(text)
    val changed = normalized != initial.orEmpty()
    val canSave = problem == null && changed && !saving && (known || normalized.isNotEmpty())
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("메모", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(4.dp))
        Text("나만 볼 수 있고 소비분석 · 추천에는 쓰지 않아요", style = YamuTheme.typography.bodyM, color = c.text2)
        if (!known) {
            Spacer(Modifier.height(YamuSpacing.x2))
            Text(
                "전에 남긴 메모를 불러오지 못했어요. 저장하면 전에 남긴 메모가 있어도 이 내용으로 바뀌어요",
                style = YamuTheme.typography.bodyM,
                color = c.warning,
            )
        }
        Spacer(Modifier.height(YamuSpacing.x4))
        YamuTextField(
            label = "메모",
            value = text,
            onValueChange = {
                text = it
                onEdit()
            },
            placeholder = "예: 팀 회식 1/N 정산 예정",
            supporting = "$count/${MemoRules.MAX_LENGTH}자",
            error = (problem ?: serverError)?.let { "$it · $count/${MemoRules.MAX_LENGTH}자" },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(text) }),
            enabled = !saving,
        )
        Spacer(Modifier.height(YamuSpacing.x5))
        Row(horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
            if (!initial.isNullOrEmpty()) {
                NeutralButton("지우기", onClick = { onSave("") }, enabled = !saving, modifier = Modifier.weight(1f))
            }
            PrimaryButton("저장", onClick = { onSave(text) }, enabled = canSave, loading = saving, modifier = Modifier.weight(1f))
        }
    }
}
