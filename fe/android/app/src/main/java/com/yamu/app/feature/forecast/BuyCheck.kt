package com.yamu.app.feature.forecast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.GreyChip
import com.yamu.app.core.designsystem.component.KeyValueRow
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.Segmented
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.KST
import com.yamu.app.core.format.objectParticle
import com.yamu.app.core.format.toKoreanDate
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.forecast.data.CatalogMerchant
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.MerchantCategory
import com.yamu.app.feature.forecast.data.PaymentChannel
import com.yamu.app.feature.forecast.data.SavedPlan
import com.yamu.app.feature.forecast.data.SimCardOption
import com.yamu.app.feature.forecast.data.SimulationQuery
import com.yamu.app.navigation.BuyCheckConditions
import com.yamu.app.navigation.BuyCheckSaved
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

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

/** 금액 키 — 0으로 시작하지 않고 10자리까지(BE 상한 1,000억 원보다 작다) */
private fun String.applyAmountKey(key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> if (isEmpty() && key.value == 0) this else (this + key.value).take(10)
    KeypadKey.DoubleZero -> if (isEmpty()) this else (this + "00").take(10)
    KeypadKey.Delete -> dropLast(1)
}

@Composable
fun BuyCheckInputRoute(onBack: () -> Unit, onNext: (amount: Long, title: String?) -> Unit) {
    var digits by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    val amount = digits.toLongOrNull() ?: 0L
    val c = YamuTheme.colors
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onBack,
        modifier = Modifier.imePadding(),
        bottomBar = {
            // 주 버튼은 키패드 바로 위(프로토타입 I-01 — 키보드 위 버튼)
            Column {
                BottomCta(insets = false) { PrimaryButton("다음", onClick = { onNext(amount, title.trim().ifEmpty { null }) }, enabled = amount > 0) }
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
            Text("어디서 · 어떤 카드로 살지는 다음 화면에서 골라요", style = YamuTheme.typography.bodyS, color = c.text3)
        }
    }
}

// ───────────────────────── I-01b 어디서 · 어떻게 ─────────────────────────

data class ConditionsState(
    val categories: List<MerchantCategory>? = null,
    val categoriesError: String? = null,
    val category: MerchantCategory? = null,
    val merchants: List<CatalogMerchant>? = null,
    val merchantsError: String? = null,
    val merchant: CatalogMerchant? = null,
    val channel: PaymentChannel = PaymentChannel.OFFLINE,   // 화면 기본값은 매장(BE 2026-11-05 결정)
    val cards: List<SimCardOption>? = null,
    val cardsError: String? = null,
    val cardId: Long? = null,                               // null = 혜택이 가장 큰 카드(추천 1순위)
    val months: Int? = null,                                // null = 일시불
    val date: LocalDate = LocalDate.now(KST),
) {
    val card: SimCardOption? get() = cards?.firstOrNull { it.userCardId == cardId }
}

/** 할부를 고를 수 있는지 — 신용카드 · 5만 원 이상(BE PaymentTerms). 카드를 고르지 않았으면 추천 카드가 신용카드일 수 있어 연다 */
internal fun installmentAllowed(amount: Long, card: SimCardOption?): Boolean =
    amount >= SimulationQuery.INSTALLMENT_MIN_AMOUNT && (card == null || card.isCredit)

/** I-01b — 고른 조건은 이 화면에만(결과로 넘길 때 경로 값으로 굳힌다) */
@HiltViewModel
class BuyConditionsViewModel internal constructor(val amount: Long, val title: String?, private val repo: ForecastRepository) : ViewModel() {
    @Inject constructor(savedStateHandle: SavedStateHandle, repo: ForecastRepository) :
        this(savedStateHandle.toRoute<BuyCheckConditions>().amount, savedStateHandle.toRoute<BuyCheckConditions>().title, repo)

    private val _state = MutableStateFlow(ConditionsState())
    val state: StateFlow<ConditionsState> = _state.asStateFlow()
    private var merchantsJob: Job? = null

    init {
        loadCategories()
        loadCards()
    }

    fun loadCategories() {
        viewModelScope.launch {
            _state.update { it.copy(categoriesError = null) }
            when (val r = repo.categories()) {
                is ApiResult.Success -> _state.update { it.copy(categories = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(categoriesError = r.error.userMessage()) }
            }
        }
    }

    fun loadCards() {
        viewModelScope.launch {
            _state.update { it.copy(cardsError = null) }
            when (val r = repo.simulationCards()) {
                is ApiResult.Success -> _state.update { it.copy(cards = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(cardsError = r.error.userMessage()) }
            }
        }
    }

    /** 업종을 바꾸면 브랜드는 비우고 그 업종의 브랜드를 새로(앞 업종 요청은 버린다) */
    fun pickCategory(category: MerchantCategory) {
        if (_state.value.category == category) return
        _state.update { it.copy(category = category, merchant = null, merchants = null, merchantsError = null) }
        merchantsJob?.cancel()
        merchantsJob = viewModelScope.launch {
            when (val r = repo.merchants(category.code)) {
                is ApiResult.Success -> _state.update { it.copy(merchants = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(merchantsError = r.error.userMessage()) }
            }
        }
    }

    fun retryMerchants() {
        val c = _state.value.category ?: return
        _state.update { it.copy(category = null) }
        pickCategory(c)
    }

    fun pickMerchant(merchant: CatalogMerchant?) = _state.update { it.copy(merchant = merchant) }

    fun setChannel(channel: PaymentChannel) = _state.update { it.copy(channel = channel) }

    /** 체크카드를 고르면 할부를 푼다(체크카드는 일시불만) */
    fun pickCard(cardId: Long?) = _state.update { s ->
        val card = s.cards?.firstOrNull { it.userCardId == cardId }
        s.copy(cardId = cardId, months = s.months.takeIf { installmentAllowed(amount, card) })
    }

    fun setMonths(months: Int?) = _state.update { it.copy(months = months.takeIf { m -> m != null && installmentAllowed(amount, it.card) }) }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    /** 업종은 필수 — 없으면 추천도 혜택도 계산할 수 없다(2026-11-08 결정) */
    fun query(): SimulationQuery? {
        val s = _state.value
        val category = s.category ?: return null
        return SimulationQuery(amount, title, category.code, category.name, s.merchant?.id, s.merchant?.name, s.channel, s.cardId, s.months, s.date)
    }
}

/** 업종 칩에 먼저 보일 것 — 목록에 있는 것만, 나머지는 '전체 업종' 시트 */
private val POPULAR_CATEGORIES = listOf("HYPERMARKET", "ONLINE_MARKETPLACE", "CONVENIENCE_STORE", "COFFEE_SHOP", "DELIVERY_APP", "HEALTH_BEAUTY_STORE", "GENERAL_RESTAURANT")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuyConditionsRoute(onBack: () -> Unit, onCompare: (SimulationQuery) -> Unit, viewModel: BuyConditionsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }   // category · brand · card · months
    var picking by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    FlowScreen(
        title = "이거 사도 될까?",
        onBack = onBack,
        bottomBar = {
            BottomCta { PrimaryButton("사기 전후 비교하기", onClick = { viewModel.query()?.let(onCompare) }, enabled = s.category != null) }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(viewModel.title, "${viewModel.amount.toWon()}원").joinToString(" · "), style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.weight(1f))
                LinkButton("고치기", onClick = onBack, modifier = Modifier.offset(x = 12.dp))
            }
            Text("어디서 어떻게 살지\n알려 주세요", style = YamuTheme.typography.headlineS, color = c.text1)

            Spacer(Modifier.height(24.dp))
            SectionLabel("어디서 · 필수")
            Spacer(Modifier.height(10.dp))
            val categories = s.categories
            when {
                categories == null && s.categoriesError != null -> ErrorState(s.categoriesError ?: "", onRetry = viewModel::loadCategories)
                categories == null -> SkeletonBlock(height = 40.dp)
                else -> {
                    val popular = POPULAR_CATEGORIES.mapNotNull { code -> categories.firstOrNull { it.code == code } }
                    val shown = (popular + listOfNotNull(s.category)).distinct()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { cat -> GreyChip(cat.name, selected = cat == s.category, onClick = { viewModel.pickCategory(cat) }) }
                        GreyChip("전체 업종", onClick = { sheet = "category" })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                label = "브랜드",
                value = s.merchant?.name ?: "고르지 않음",
                enabled = s.category != null,
                onClick = { sheet = "brand" },
            )
            Text("브랜드를 고르면 브랜드 혜택까지 계산해요", style = YamuTheme.typography.bodyS, color = c.text3)

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = c.outlineVariant)
            Spacer(Modifier.height(20.dp))
            SectionLabel("어떻게")
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("매장에서", "온라인으로"), selectedIndex = s.channel.ordinal, onSelect = { viewModel.setChannel(PaymentChannel.entries[it]) })

            Spacer(Modifier.height(18.dp))
            Text("카드", style = YamuTheme.typography.bodyM, color = c.text3)
            RadioRow(
                title = "혜택이 가장 큰 카드",
                sub = s.cards?.count { it.unusableReason == null }?.let { "내 카드 ${it}장 중에서 골라 드려요" },
                selected = s.cardId == null,
                onClick = { viewModel.pickCard(null) },
            )
            HorizontalDivider(color = c.outlineVariant)
            RadioRow(
                title = s.card?.name ?: "직접 고르기",
                sub = s.card?.let { if (it.isCredit) "신용카드" else "체크카드" },
                selected = s.cardId != null,
                onClick = { sheet = "card" },
                chevron = true,
            )

            Spacer(Modifier.height(14.dp))
            Text("결제", style = YamuTheme.typography.bodyM, color = c.text3)
            Spacer(Modifier.height(8.dp))
            val canInstallment = installmentAllowed(viewModel.amount, s.card)
            if (canInstallment) {
                Segmented(
                    listOf("일시불", s.months?.let { "할부 ${it}개월" } ?: "할부"),
                    selectedIndex = if (s.months == null) 0 else 1,
                    onSelect = { if (it == 0) viewModel.setMonths(null) else sheet = "months" },
                )
            } else {
                Text("일시불", style = YamuTheme.typography.titleS, color = c.text1)
            }
            Text("할부는 신용카드로 5만 원 이상일 때만 볼 수 있어요", style = YamuTheme.typography.bodyS, color = c.text3, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("날짜", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.width(48.dp))
                Text(
                    s.date.toKoreanDateWeek() + if (s.date == LocalDate.now(KST)) " · 오늘" else "",
                    style = YamuTheme.typography.titleS,
                    color = c.text1,
                    modifier = Modifier.weight(1f),
                )
                LinkButton("바꾸기", onClick = { picking = true }, modifier = Modifier.offset(x = 12.dp))
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    when (sheet) {
        "category" -> CategorySheet(s.categories.orEmpty(), s.category, onDismiss = { sheet = null }, onPick = {
            sheet = null
            viewModel.pickCategory(it)
        })
        "brand" -> BrandSheet(s.category?.name, s.merchants, s.merchantsError, s.merchant, onRetry = viewModel::retryMerchants, onDismiss = { sheet = null }, onPick = {
            sheet = null
            viewModel.pickMerchant(it)
        })
        "card" -> CardSheet(s.cards, s.cardsError, s.cardId, onRetry = viewModel::loadCards, onDismiss = { sheet = null }, onPick = {
            sheet = null
            viewModel.pickCard(it)
        })
        "months" -> MonthsSheet(s.months, onDismiss = { sheet = null }, onPick = {
            sheet = null
            viewModel.setMonths(it)
        })
    }
    if (picking) PurchaseDatePicker(s.date, onDismiss = { picking = false }, onPick = {
        picking = false
        viewModel.setDate(it)
    })
}

@Composable
private fun SectionLabel(text: String) = Text(text, style = YamuTheme.typography.bodyS, color = YamuTheme.colors.primary)

/** 이름 · 값 · › 한 줄(52dp) */
@Composable
private fun ChoiceRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.width(64.dp))
        Text(value, style = YamuTheme.typography.titleS, color = c.text1, modifier = Modifier.weight(1f))
        Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
    }
}

/** 둘 중 하나 고르기 줄 — 둥근 표시 + 이름 · 보조 */
@Composable
internal fun RadioRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, chevron: Boolean = false) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundCheckbox(checked = selected, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = YamuTheme.typography.titleS, color = c.text1)
            sub?.let { Text(it, style = YamuTheme.typography.bodyS, color = c.text3) }
        }
        if (chevron) Icon(YamuIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(18.dp))
    }
}

/** 시트 안 긴 목록 — 화면 높이를 넘지 않게 스크롤 */
@Composable
private fun SheetList(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), content = content)
}

@Composable
private fun CategorySheet(categories: List<MerchantCategory>, selected: MerchantCategory?, onDismiss: () -> Unit, onPick: (MerchantCategory) -> Unit) {
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("업종", style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
        Spacer(Modifier.height(8.dp))
        SheetList {
            categories.forEach { cat -> RadioRow(cat.name, null, selected = cat == selected, onClick = { onPick(cat) }) }
        }
    }
}

@Composable
private fun BrandSheet(
    categoryName: String?,
    merchants: List<CatalogMerchant>?,
    error: String?,
    selected: CatalogMerchant?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (CatalogMerchant?) -> Unit,
) {
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text(listOfNotNull(categoryName, "브랜드").joinToString(" "), style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(8.dp))
        when {
            merchants == null && error != null -> ErrorState(error, onRetry = onRetry)
            merchants == null -> repeat(3) {
                SkeletonBlock(height = 48.dp)
                Spacer(Modifier.height(8.dp))
            }
            else -> SheetList {
                RadioRow("고르지 않음", "업종 혜택만 계산해요", selected = selected == null, onClick = { onPick(null) })
                if (merchants.isEmpty()) Text("이 업종에는 고를 브랜드가 없어요", style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(vertical = 12.dp))
                merchants.forEach { m -> RadioRow(m.name, null, selected = m == selected, onClick = { onPick(m) }) }
            }
        }
    }
}

/** 카드 고르기 — 맨 위는 '혜택이 가장 큰 카드'(null), 결제할 수 없는 카드는 이유를 달고 고를 수 없다 */
@Composable
internal fun CardSheet(
    cards: List<SimCardOption>?,
    error: String?,
    selectedId: Long?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("카드", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(8.dp))
        RadioRow("혜택이 가장 큰 카드", "야무가 골라 드려요", selected = selectedId == null, onClick = { onPick(null) })
        when {
            cards == null && error != null -> ErrorState(error, onRetry = onRetry)
            cards == null -> repeat(2) {
                SkeletonBlock(height = 48.dp)
                Spacer(Modifier.height(8.dp))
            }
            else -> SheetList {
                cards.forEach { card ->
                    HorizontalDivider(color = c.outlineVariant)
                    RadioRow(
                        title = card.name,
                        sub = listOfNotNull(if (card.isCredit) "신용카드" else "체크카드", card.unusableReason).joinToString(" · "),
                        selected = card.userCardId == selectedId,
                        enabled = card.unusableReason == null,
                        onClick = { onPick(card.userCardId) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonthsSheet(selected: Int?, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("할부 개월", style = YamuTheme.typography.titleL, color = YamuTheme.colors.text1)
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationQuery.INSTALLMENT_MONTHS.forEach { m -> GreyChip("${m}개월", selected = m == selected, onClick = { onPick(m) }) }
        }
        Spacer(Modifier.height(YamuSpacing.x4))
    }
}

/** 구매일 — 오늘부터(지난 날은 BE가 400) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PurchaseDatePicker(date: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val c = YamuTheme.colors
    val todayUtc = LocalDate.now(KST).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("고르기", style = YamuTheme.typography.labelL, color = c.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기", style = YamuTheme.typography.labelL, color = c.text2) } },
    ) { DatePicker(state = state) }
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
        _state.update { it.copy(deleting = true) }   // 연타가 두 번 나가지 않게 코루틴 전에
        viewModelScope.launch {
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
            // 전후 값은 저장하기 전 가상 구매 비교 값(가계부 등록 응답에는 잔액 영향이 없다)
            when {
                p.minBefore != null && p.minAfter != null && p.minBefore == p.minAfter ->
                    Text("가장 낮을 때 잔액은 ${p.minAfter.toWon()}원 그대로예요", style = YamuTheme.typography.bodyM, color = c.text2)
                p.minBefore != null && p.minAfter != null ->
                    Text("가장 낮을 때 잔액이 ${p.minBefore.toWon()}원 → ${p.minAfter.toWon()}원이 돼요", style = YamuTheme.typography.bodyM, color = if (p.minAfter < 0) c.error else c.text2)
            }
            Spacer(Modifier.height(24.dp))
            KeyValueRow("예정 지출", "${(-p.amount).toWon()}원", large = false, minHeight = 48.dp)
        }
    }
}

/** 시트 · 칩에서 쓰는 작은 둥근 바탕 — 결과 화면 칩과 같은 모양 */
@Composable
internal fun PillButton(text: String, contentDescription: String, onClick: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = YamuTheme.typography.labelL, color = c.text1)
        Icon(YamuIcons.ExpandMore, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}
