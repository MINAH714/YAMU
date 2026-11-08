package com.yamu.app.feature.forecast

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.SectionBlock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
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
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.MoreDetails
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toMonthDayWeek
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.BuyCheck
import com.yamu.app.feature.forecast.data.BuyOption
import com.yamu.app.feature.forecast.data.BuyVerdict
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.SavedPlan
import com.yamu.app.navigation.BuyCheckResult
import com.yamu.app.navigation.BuyCheckSaved
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** 판정 색 — 괜찮아요 초록 · 미루기 주황 · 사지 않기 빨강 · 판단 보류 회색 */
private fun BuyVerdict.tone(): TagTone = when (this) {
    BuyVerdict.OK -> TagTone.Success
    BuyVerdict.CAUTION -> TagTone.Warning
    BuyVerdict.HOLD -> TagTone.Error
    BuyVerdict.UNKNOWN -> TagTone.Neutral
}

// ───────────────────────── I-01 금액 넣기 ─────────────────────────

/** 가격 칸 — 숫자 + 파란 커서 + 오른쪽 끝 '원', 아래 2dp 파란 밑줄(프로토타입 .amount-field) */
@Composable
private fun AmountField(amount: Long) {
    val c = YamuTheme.colors
    val line = c.primary
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(line, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 2.dp.toPx()) }
            .padding(top = 8.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "가격 ${amount.toWon()}원" },
        // 숫자와 '원'은 글자 바닥을 맞추고 커서만 가운데(프로토타입 .amount-field align-items: baseline · gap 4)
    ) {
        Text(if (amount > 0) amount.toWon() else "0", style = YamuTheme.typography.amountL, color = if (amount > 0) c.text1 else c.text3, modifier = Modifier.alignByBaseline())
        Box(Modifier.align(Alignment.CenterVertically).padding(start = 4.dp).width(2.dp).height(32.dp).background(c.primary))
        Spacer(Modifier.weight(1f))
        Text("원", style = YamuTheme.typography.titleL, color = c.text2, modifier = Modifier.alignByBaseline())
    }
}

/** 금액 키 — 0으로 시작하지 않고 10자리까지 */
private fun String.applyAmountKey(key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> if (isEmpty() && key.value == 0) this else (this + key.value).take(10)
    KeypadKey.DoubleZero -> if (isEmpty()) this else (this + "00").take(10)
    KeypadKey.Delete -> dropLast(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuyCheckInputRoute(onBack: () -> Unit, onCheck: (amount: Long, title: String?, purchaseDate: LocalDate) -> Unit) {
    var digits by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now(KST).toEpochDay()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val amount = digits.toLongOrNull() ?: 0L
    val date = LocalDate.ofEpochDay(day)
    val c = YamuTheme.colors
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        bottomBar = {
            // 주 버튼은 키패드 바로 위(프로토타입 I-01 — 키보드 위 버튼)
            Column {
                BottomCta(insets = false) { PrimaryButton("사도 될지 보기", onClick = { onCheck(amount, title.trim().ifEmpty { null }, date) }, enabled = amount > 0) }
                NumberKeypad(KeypadMode.Amount, onKey = { digits = digits.applyAmountKey(it) })
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("얼마짜리를 살지\n알려 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(24.dp))
            Text("가격", style = YamuTheme.typography.bodyS, color = c.primary)
            AmountField(amount)
            Spacer(Modifier.height(16.dp))   // 칸 위 24(.tf margin-top) − 입력칸이 라벨 자리로 두는 8
            YamuTextField(
                label = "무엇을 사나요 · 선택",
                value = title,
                onValueChange = { title = it.take(30) },
                placeholder = "태블릿",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(8.dp))
            // 날짜 — 이름(32dp) · 값을 나눈 한 줄(52dp). 선은 줄과 줄 사이에만(프로토타입 .ir + .ir) — 지금은 한 줄이라 없음
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("날짜", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.width(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "${date.toKoreanDate()} (${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)})${if (date == LocalDate.now(KST)) " · 오늘" else ""}",
                    style = YamuTheme.typography.titleS,
                    color = c.text1,
                    modifier = Modifier.weight(1f),
                )
                // 글자 끝을 화면 여백선(16dp)에 맞춘다 — 버튼 안쪽 여백(12)만큼 밖으로
                LinkButton("바꾸기", onClick = { picking = true }, modifier = Modifier.offset(x = 12.dp))
            }
            Text("결제 방법은 결과에서 비교해 드려요", style = YamuTheme.typography.bodyS, color = c.text3)
        }
    }
    if (picking) {
        val todayUtc = LocalDate.now(KST).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc   // 오늘부터(과거엔 못 삼)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    picking = false
                }) { Text("고르기", style = YamuTheme.typography.labelL, color = c.primary) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("닫기", style = YamuTheme.typography.labelL, color = c.text2) } },
        ) { DatePicker(state = state) }
    }
}

// ───────────────────────── I-02 · I-03 결과 + I-04 방법 비교 ─────────────────────────

data class BuyCheckState(
    val loading: Boolean = true,
    val check: BuyCheck? = null,
    val noData: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
    val savedPlanId: Long? = null,
    val message: String? = null,
)

/** 서버 판정(#33) 그대로 — 입력은 경로에서(결과는 저장된 게 아니라 이 입력으로 부른 값) */
@HiltViewModel
class BuyCheckViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repo: ForecastRepository) : ViewModel() {
    private val input = savedStateHandle.toRoute<BuyCheckResult>()
    val title: String? = input.title
    val amount: Long = input.amount
    private val purchaseDate: LocalDate = LocalDate.parse(input.purchaseDate)
    private val _state = MutableStateFlow(BuyCheckState())
    val state: StateFlow<BuyCheckState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, noData = false) }
            when (val r = repo.check(amount, title, purchaseDate)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, check = r.data) }
                is ApiResult.Failure -> _state.update {
                    if (r.error is ApiError.DataInsufficient) it.copy(loading = false, noData = true)
                    else it.copy(loading = false, error = r.error.userMessage())
                }
            }
        }
    }

    fun save(option: BuyOption) {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            when (val r = repo.savePlan(option, title, amount)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, savedPlanId = r.data.id) }
                is ApiResult.Failure -> _state.update { it.copy(saving = false, message = r.error.userMessage()) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun navigated() = _state.update { it.copy(savedPlanId = null) }
}

@Composable
fun BuyCheckResultRoute(onBack: () -> Unit, onPay: () -> Unit, onSaved: (planId: Long) -> Unit, viewModel: BuyCheckViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    var comparing by rememberSaveable { mutableStateOf(false) }
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
    val c = YamuTheme.colors
    val check = s.check
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onBack,
        bottomBar = {
            if (check != null) {
                val delay = check.delay
                when {
                    check.verdict == BuyVerdict.OK -> BottomCta { PrimaryButton("결제하러 가기", onClick = onPay) }
                    // 비교 입구는 본문 파란 링크 하나만(아래 보조 버튼과 겹치지 않게)
                    delay != null -> BottomCta { PrimaryButton("${delay.date.toKoreanDate()}로 계획 저장", onClick = { viewModel.save(delay) }, loading = s.saving) }
                    else -> BottomCta { PrimaryButton("다른 방법과 비교", onClick = { comparing = true }) }
                }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val what = listOfNotNull(viewModel.title, "${viewModel.amount.toWon()}원", check?.firstMethodLabel).joinToString(" · ")
            when {
                s.loading -> SectionBlock(divider = false) {
                    Text(what, style = YamuTheme.typography.bodyM, color = c.text3)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    Text("야무가 계산 중이에요", style = YamuTheme.typography.headlineS, color = c.text1)
                    Spacer(Modifier.height(YamuSpacing.x4))
                    repeat(3) {
                        SkeletonBlock(height = 40.dp)
                        Spacer(Modifier.height(YamuSpacing.x3))
                    }
                }
                s.noData -> SectionBlock(divider = false) {
                    Text(what, style = YamuTheme.typography.bodyM, color = c.text3)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    StatusTag(BuyVerdict.UNKNOWN.label, tone = TagTone.Neutral)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    Text("거래가 쌓이면 더 정확히 알려 드려요", style = YamuTheme.typography.headlineS, color = c.text1)
                }
                check == null -> SectionBlock(divider = false) { ErrorState(s.error ?: "판단을 불러오지 못했어요", onRetry = viewModel::load) }
                else -> ResultBody(check, what, onCompare = { comparing = true })
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    if (comparing && check != null) {
        CompareSheet(check, saving = s.saving, onDismiss = { comparing = false }, onSave = { option ->
            comparing = false
            viewModel.save(option)
        })
    }
}

@Composable
private fun ResultBody(check: BuyCheck, what: String, onCompare: () -> Unit) {
    val c = YamuTheme.colors
    val delay = check.delay
    // ① 답 — 판정 태그 · 머리 문장(24sp), 캐릭터는 오른쪽 위 구석(글 흐름 밖). 구역은 위아래 16(프로토타입 I-02 .sec)
    SectionBlock(divider = false, top = 16.dp, bottom = 16.dp) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = 76.dp)) {
                Text(what, style = YamuTheme.typography.bodyM, color = c.text3)
                Spacer(Modifier.height(12.dp))
                StatusTag(check.verdict.label, tone = check.verdict.tone())
                check.headline?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = YamuTheme.typography.headlineS, color = c.text1)
                }
            }
            CharacterImage(if (check.verdict == BuyVerdict.OK) YamuPose.ThumbsUp else YamuPose.Alert, size = CharacterSize.S, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
    // ② 숫자 하나 — 평소 한 달보다
    if (check.baselineRatio != null || check.reasons.isNotEmpty()) {
        SectionBlock(top = 16.dp, bottom = 16.dp) {
            if (check.baselineRatio != null) {
                Text("평소 한 달보다", style = YamuTheme.typography.titleS, color = c.text2)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildAnnotatedString {
                        append("+${check.baselineRatio}")
                        withStyle(SpanStyle(fontSize = YamuTheme.typography.amountL.fontSize * 0.6f, fontWeight = FontWeight.SemiBold)) { append("%") }
                    },
                    style = YamuTheme.typography.amountL,
                    color = c.text1,
                )   // 지난 달들 평균이라 '예측'이 아님
                Spacer(Modifier.height(4.dp))   // 작은 '%'가 줄 아래로 내려가는 만큼(AmountText와 같음)
                listOfNotNull(check.baselinePeriod, check.baselineAverage?.let { "${it.toWon()}원" }).joinToString(" ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = YamuTheme.typography.sub, color = c.text3)
                }
            }
            if (check.reasons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                MoreDetails {
                    check.reasons.forEach { Text("· $it", style = YamuTheme.typography.sub, color = c.text2) }
                }
            }
        }
    }
    // ③ 다른 방법 — 대안 한 줄 + 비교 링크(줄 전체 폭, › 끝). 괜찮아요면 비교할 필요가 없다
    val canCompare = check.options.size > 1
    if (check.verdict != BuyVerdict.OK && (delay != null || canCompare)) {
        SectionBlock(top = 16.dp, bottom = 16.dp) {
            delay?.let { Text("${it.date.toKoreanDate()} ${it.methodLabel}에 사면 괜찮아요", style = YamuTheme.typography.titleM, color = c.text1) }
            if (canCompare) {
                if (delay != null) Spacer(Modifier.height(4.dp))
                val installment = check.options.firstOrNull { it.months != null }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(YamuRadius.s)).clickable(role = Role.Button, onClick = onCompare),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(listOfNotNull(installment?.title, "다른 날과 비교").joinToString(" · "), style = YamuTheme.typography.labelL, color = c.primary, modifier = Modifier.weight(1f))
                    Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** I-04 사는 방법 비교 — 줄을 고르고 그 방법으로 계획 저장 */
@Composable
private fun CompareSheet(check: BuyCheck, saving: Boolean, onDismiss: () -> Unit, onSave: (BuyOption) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(check.options.indexOfFirst { it.recommended }.coerceAtLeast(0)) }
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("사는 방법 비교", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(4.dp))   // 제목 아래 4 · 보조 아래 16(프로토타입 .sheet-title · .sheet-sub)
        Text(listOfNotNull(check.title, "${check.amount.toWon()}원").joinToString(" "), style = YamuTheme.typography.bodyM, color = c.text2)
        Spacer(Modifier.height(16.dp))
        check.options.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .selectable(selected = i == selected, role = Role.RadioButton, onClick = { selected = i })
                    .padding(vertical = YamuSpacing.x2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(o.title, style = YamuTheme.typography.titleM, color = c.text1)
                        if (o.recommended) StatusTag("추천", tone = TagTone.Primary)
                    }
                    Text(o.methodLabel, style = YamuTheme.typography.sub, color = c.text3)
                }
                // 금액 / 단위('월급 전' · '매달') 두 줄 — 추천은 파랑, 모자라면 빨강
                val amount = if (o.monthlyAmount != null && o.months != null) o.monthlyAmount else o.minBalance
                if (amount != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${amount.toWon()}원",
                            style = YamuTheme.typography.titleM,
                            color = when {
                                amount < 0 && o.monthlyAmount == null -> c.error
                                o.recommended -> c.primary
                                else -> c.text1
                            },
                        )
                        Text(if (o.months != null) "매달" else "월급 전", style = YamuTheme.typography.bodyS, color = c.text3)
                    }
                }
                RoundCheckbox(checked = i == selected, onCheckedChange = null)
            }
        }
        Spacer(Modifier.height(YamuSpacing.x4))
        val option = check.options.getOrNull(selected)
        PrimaryButton(
            option?.let { if (it.months != null) "${it.title}로 계획 저장" else "${it.date.toKoreanDate()}로 계획 저장" } ?: "계획 저장",
            onClick = { option?.let(onSave) },
            enabled = option != null,
            loading = saving,
        )
    }
}

// ───────────────────────── I-05 계획 저장 ─────────────────────────

data class PlanState(val plan: SavedPlan? = null, val deleting: Boolean = false, val deleted: Boolean = false, val message: String? = null)

@HiltViewModel
class PlanViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repo: ForecastRepository) : ViewModel() {
    private val planId = savedStateHandle.toRoute<BuyCheckSaved>().planId
    private val _state = MutableStateFlow(PlanState(plan = repo.savedPlan(planId)))
    val state: StateFlow<PlanState> = _state.asStateFlow()

    fun delete() {
        if (_state.value.deleting) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            when (val r = repo.deletePlan(planId)) {
                is ApiResult.Success -> _state.update { it.copy(deleting = false, deleted = true) }
                is ApiResult.Failure -> _state.update { it.copy(deleting = false, message = r.error.userMessage()) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

@Composable
fun BuyCheckSavedRoute(onForecast: () -> Unit, onDeleted: (message: String) -> Unit, viewModel: PlanViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        viewModel.messageShown()
    }
    LaunchedEffect(s.deleted) { if (s.deleted) onDeleted("계획을 지웠어요 · 잔고예보를 다시 계산했어요") }
    val c = YamuTheme.colors
    val p = s.plan
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onForecast,
        bottomBar = {
            BottomCta(secondary = { if (p != null) TextButtonGrey("계획 지우기", onClick = viewModel::delete, modifier = Modifier.fillMaxWidth(), muted = true, small = true) }) {
                PrimaryButton("잔고예보에서 보기", onClick = onForecast, loading = s.deleting)
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            CharacterImage(YamuPose.Done, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(12.dp))
            if (p == null) {
                Text("계획을 찾을 수 없어요", style = YamuTheme.typography.headlineS, color = c.text1)
                return@Column
            }
            val thing = if (p.title == "구매 계획") "" else "${p.title}${p.title.objectParticle()} "
            Text("${p.date.toKoreanDate()}에 ${thing}\n사기로 했어요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(6.dp))
            when {
                p.minBefore != null && p.minAfter != null && p.minBefore == p.minAfter ->
                    Text("월급 전 예상 잔액은 ${p.minAfter.toWon()}원 그대로예요", style = YamuTheme.typography.bodyM, color = c.text2)
                p.minBefore != null && p.minAfter != null ->
                    Text("월급 전 예상 잔액이 ${p.minBefore.toWon()}원 → ${p.minAfter.toWon()}원이 돼요", style = YamuTheme.typography.bodyM, color = if (p.minAfter < 0) c.error else c.text2)
            }
            p.shortfallFrom?.let { Text("${it.toMonthDayWeek()}부터 부족 예상", style = YamuTheme.typography.bodyM, color = c.error) }
            Spacer(Modifier.height(24.dp))   // 머리 아래 8 + 덩어리 위 16(프로토타입 I-05 .hd · .blk)
            // 키–값(48dp) — 날짜는 머리 문장이 말해 뺀다. 알림 · 결제 후 잔액 줄은 서버 값이 생기면(14 문서 14-6)
            KeyValueRow("예정 지출", "${(-p.amount).toWon()}원", large = false, minHeight = 48.dp)
        }
    }
}
