package com.yamu.app.feature.payment

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountSize
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.GreyChip
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NotificationBell
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.RoundCheckbox
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.BrandCardArt
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.Recommendation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecommendState(
    val merchants: List<Merchant> = emptyList(),
    val merchantsLoading: Boolean = false,
    /** 가맹점 목록을 못 받았을 때 — 시트 안에 이유와 '다시 시도'(추천 오류 [error]와 따로) */
    val merchantsError: String? = null,
    val merchant: Merchant? = null,
    val amount: Long? = null,
    val loading: Boolean = false,
    val result: Recommendation? = null,
    val error: String? = null,
    /** 앱바 종 배지 — 알림함 첫 쪽(GET notifications)의 안 읽은 수, 홈과 같은 값 */
    val unread: Int = 0,
)

/** P-01 카드추천 — 가맹점 · 금액을 고르면 서버 추천(#64) 그대로 */
@HiltViewModel
class RecommendViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val payments: PaymentRepository,
    private val my: MyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RecommendState())
    val state: StateFlow<RecommendState> = _state.asStateFlow()

    /** 탭에 돌아올 때마다(알림함을 보고 온 뒤 포함) — 실패하면 배지만 안 바뀐다 */
    fun refreshUnread() = viewModelScope.launch {
        (my.notifications(page = 0) as? ApiResult.Success)?.let { r -> _state.update { it.copy(unread = r.data.unread) } }
    }

    init {
        // 탭을 다시 열거나 앱이 다시 켜져도 고른 가맹점 · 금액은 남긴다
        val merchantId = savedStateHandle.get<Long>(KEY_MERCHANT)
        val amount = savedStateHandle.get<Long>(KEY_AMOUNT)
        if (merchantId != null && amount != null) {
            viewModelScope.launch {
                loadMerchants()
                _state.value.merchants.firstOrNull { it.id == merchantId }?.let { choose(it, amount) }
            }
        }
    }

    suspend fun loadMerchants() {
        if (_state.value.merchants.isNotEmpty() || _state.value.merchantsLoading) return
        _state.update { it.copy(merchantsLoading = true, merchantsError = null) }
        when (val r = payments.merchants()) {
            is ApiResult.Success -> _state.update { it.copy(merchantsLoading = false, merchants = r.data) }
            is ApiResult.Failure -> _state.update { it.copy(merchantsLoading = false, merchantsError = r.error.userMessage()) }
        }
    }

    fun openPicker() = viewModelScope.launch { loadMerchants() }

    fun choose(merchant: Merchant, amount: Long) {
        savedStateHandle[KEY_MERCHANT] = merchant.id
        savedStateHandle[KEY_AMOUNT] = amount
        _state.update { it.copy(merchant = merchant, amount = amount) }
        recommend()
    }

    fun recommend() {
        val s = _state.value
        val merchant = s.merchant ?: return
        val amount = s.amount ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = payments.recommend(merchant.id, amount)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, result = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, result = null, error = r.error.userMessage()) }
            }
        }
    }

    private companion object {
        const val KEY_MERCHANT = "merchantId"
        const val KEY_AMOUNT = "amount"
    }
}

@Composable
fun RecommendRoute(onOpenPayCode: () -> Unit, onAddCard: () -> Unit, onNotifications: () -> Unit = {}, viewModel: RecommendViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshUnread() }
    var picking by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    val openPicker = {
        viewModel.openPicker()
        picking = true
    }
    TabScreen(
        title = "카드추천",
        actions = { NotificationBell(unread = s.unread, onClick = onNotifications) },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            YamuCard {
                val merchant = s.merchant
                val amount = s.amount
                if (merchant == null || amount == null) {
                    Text("어디서 얼마를 쓰나요?", style = YamuTheme.typography.titleL, color = c.text1)
                    Spacer(Modifier.height(6.dp))
                    Text("가맹점과 금액을 고르면 가장 이득인 카드를 알려 드려요", style = YamuTheme.typography.bodyM, color = c.text3)
                    Spacer(Modifier.height(YamuSpacing.x4))
                    GreyChip("가맹점 고르기", onClick = openPicker)
                } else {
                    Text(
                        "${merchant.name} · ${amount.toWon()}원",
                        style = YamuTheme.typography.sub,
                        color = c.text3,
                        modifier = Modifier.clickable(onClick = openPicker),
                    )
                    Spacer(Modifier.height(8.dp))
                    val result = s.result
                    when {
                        s.loading -> {
                            SkeletonBlock(height = 28.dp, widthFraction = 0.6f)
                            Spacer(Modifier.height(YamuSpacing.x3))
                            SkeletonBlock(height = 56.dp)
                        }
                        s.error != null -> ErrorState(s.error.orEmpty(), onRetry = viewModel::recommend)
                        result == null -> Unit
                        result.cards.isEmpty() -> EmptyState("결제할 카드가 없어요", actionText = "카드 추가", onAction = onAddCard)
                        else -> RecommendResult(result)
                    }
                }
                Spacer(Modifier.height(16.dp))
                val best = s.result?.cards?.firstOrNull()?.brand?.name
                PrimaryButton(best?.let { "$it${it.directionParticle()} 결제 코드 열기" } ?: "QR로 결제하기", onClick = onOpenPayCode)
                // 왜 이 카드인지 한 줄 — 버튼 아래 가운데(프로토타입 P-01)
                s.result?.let { r ->
                    val first = r.cards.firstOrNull()
                    val second = r.cards.getOrNull(1)
                    if (first != null && second != null && first.netBenefit > second.netBenefit) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "${second.brand.name}보다 ${(first.netBenefit - second.netBenefit).toWon()}원 더 받아요",
                            style = YamuTheme.typography.sub,
                            color = c.text3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    if (picking) {
        MerchantPicker(
            s = s,
            onRetry = { viewModel.openPicker() },
            onDismiss = { picking = false },
            onChoose = { m, a ->
                picking = false
                viewModel.choose(m, a)
            },
        )
    }
}

@Composable
private fun RecommendResult(result: Recommendation) {
    val c = YamuTheme.colors
    val best = result.cards.first()
    Text("${best.brand.name}${best.brand.name.directionParticle()} 결제하면", style = YamuTheme.typography.headlineS, color = c.text1)
    // 핵심 숫자 — '1,500원 할인' 30sp 파랑 + 옆 '예상', 머리 아래 2(프로토타입 P-01 .rec .amt)
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.Top) {
        Text("${best.netBenefit.toWon()}원 할인", style = YamuTheme.typography.amountL, color = c.primary)
        Spacer(Modifier.width(6.dp))
        EstimateTag(text = "예상", modifier = Modifier.padding(top = 12.dp))   // 프로토타입은 글자 바닥에서 6 위(vertical-align 6px)
    }
    Spacer(Modifier.height(20.dp))
    // 추천 카드를 실물 카드 크기로 — 이름 · 이유는 그 아래
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BrandCardArt(best.brand, best.name, best.last4, Modifier.widthIn(max = 320.dp))
    }
    Spacer(Modifier.height(YamuSpacing.x3))
    Text(best.name, style = YamuTheme.typography.titleM, color = c.text1)
    (best.reason ?: best.benefitName)?.let { Text(it, style = YamuTheme.typography.sub, color = c.text3) }
}

/** 가맹점 · 금액 고르기 시트 */
@Composable
private fun MerchantPicker(s: RecommendState, onRetry: () -> Unit, onDismiss: () -> Unit, onChoose: (Merchant, Long) -> Unit) {
    var chosen by remember { mutableStateOf(s.merchant) }
    var amountText by remember { mutableStateOf(s.amount?.toString().orEmpty()) }
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text("어디서 결제하나요?", style = YamuTheme.typography.titleL, color = c.text1)
        Spacer(Modifier.height(YamuSpacing.x3))
        if (s.merchantsLoading) SkeletonBlock(height = 160.dp)
        // 목록을 못 받으면 고를 게 없어 막히므로 이유와 '다시 시도'
        if (!s.merchantsLoading && s.merchants.isEmpty() && s.merchantsError != null) ErrorState(s.merchantsError, onRetry = onRetry)
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            s.merchants.forEach { m ->
                ListRow(
                    title = m.name,
                    sub = listOfNotNull(m.categoryName, if (m.isOnline) "온라인" else null).joinToString(" · "),
                    trailing = { RoundCheckbox(checked = chosen?.id == m.id, onCheckedChange = null) },
                    onClick = { chosen = m },
                )
            }
        }
        Spacer(Modifier.height(YamuSpacing.x3))
        YamuTextField(
            label = "금액(원)",
            value = amountText,
            onValueChange = { v -> amountText = v.filter(Char::isDigit).take(9) },
            placeholder = "15000",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(YamuSpacing.x4))
        val amount = amountText.toLongOrNull()?.takeIf { it > 0 }
        val m = chosen
        PrimaryButton(
            "추천 보기",
            onClick = { if (m != null && amount != null) onChoose(m, amount) },
            enabled = m != null && amount != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
