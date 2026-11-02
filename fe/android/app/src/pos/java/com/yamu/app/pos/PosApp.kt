package com.yamu.app.pos

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuSnackbar
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiFeature
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.parseScannedCode
import com.yamu.app.feature.payment.data.posErrorMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 모의 POS 단말(가맹점 쪽) 진입점 — 'YAMU 가맹점' 앱. 고객 앱과 화면 · 권한을 섞지 않는다(03 문서 8장) */
@AndroidEntryPoint
class PosActivity : ComponentActivity() {
    @Inject lateinit var settings: PosSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 계산대 단말 — 화면이 꺼지지 않게
        val start: Any = if (settings.merchant == null) PosMerchants else PosAmount
        setContent { YamuTheme { PosApp(start) } }
    }
}

// ── 경로 ──
@Serializable data object PosMerchants
@Serializable data object PosAmount
@Serializable data class PosScan(val amount: Long)
@Serializable data class PosResultRoute(val paymentId: Long, val amount: Long)

@Composable
fun PosApp(start: Any) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val newPayment: () -> Unit = { nav.navigate(PosAmount) { popUpTo(nav.graph.id) { inclusive = true } } }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Box(Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = start) {
                composable<PosMerchants> {
                    PosMerchantsScreen(
                        onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
                        onChosen = newPayment,
                    )
                }
                composable<PosAmount> {
                    PosAmountScreen(onChangeMerchant = { nav.navigate(PosMerchants) }, onScan = { amount -> nav.navigate(PosScan(amount)) })
                }
                composable<PosScan> {
                    PosScanScreen(
                        onBack = { nav.popBackStack() },
                        onRequested = { id, amount -> nav.navigate(PosResultRoute(id, amount)) { popUpTo<PosAmount>() } },
                    )
                }
                composable<PosResultRoute> { PosResultScreen(onNewPayment = newPayment) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { YamuSnackbar(it) }
        }
    }
}

// ───────────────────────── POS-1 가맹점 고르기(#5) ─────────────────────────

data class PosMerchantsState(val loading: Boolean = true, val merchants: List<Merchant> = emptyList(), val error: String? = null)

@HiltViewModel
class PosMerchantsViewModel @Inject constructor(private val repo: PosRepository, private val settings: PosSettings) : ViewModel() {
    private val _state = MutableStateFlow(PosMerchantsState())
    val state: StateFlow<PosMerchantsState> = _state.asStateFlow()
    val missingKey: Boolean = !repo.hasKey && ApiFeature.POS.isReal

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.merchants()) {
                is ApiResult.Success -> _state.update { PosMerchantsState(loading = false, merchants = r.data) }
                is ApiResult.Failure -> _state.update { PosMerchantsState(loading = false, error = posErrorMessage(r.error.code).takeIf { r.error.code == "INVALID_POS_KEY" } ?: r.error.userMessage()) }
            }
        }
    }

    fun choose(merchant: Merchant) {
        settings.merchant = merchant
    }
}

@Composable
private fun PosMerchantsScreen(onBack: (() -> Unit)?, onChosen: () -> Unit, viewModel: PosMerchantsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    FlowScreen(title = "YAMU 가맹점", onBack = onBack) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("이 단말의 가맹점을 골라 주세요", style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(6.dp))
            Text("한 번 고르면 다음부터 바로 금액 화면이 열려요", style = YamuTheme.typography.bodyM, color = c.text3)
            if (viewModel.missingKey) {
                Spacer(Modifier.height(YamuSpacing.x3))
                Text("단말 키가 없어요 · local.properties에 yamu.posKey를 넣고 다시 빌드해 주세요", style = YamuTheme.typography.bodyM, color = c.error)
            }
            Spacer(Modifier.height(YamuSpacing.x4))
            when {
                s.loading -> repeat(4) {
                    SkeletonBlock(height = 56.dp)
                    Spacer(Modifier.height(YamuSpacing.x2))
                }
                s.error != null -> ErrorState(s.error.orEmpty(), onRetry = viewModel::load)
                s.merchants.isEmpty() -> Text("고를 수 있는 오프라인 가맹점이 없어요", style = YamuTheme.typography.bodyL, color = c.text2)
                else -> s.merchants.forEach { m ->
                    ListRow(title = m.name, sub = m.categoryName, chevron = true, onClick = {
                        viewModel.choose(m)
                        onChosen()
                    })
                }
            }
        }
    }
}

// ───────────────────────── POS-2 금액 ─────────────────────────

/** 금액 키 — 0으로 시작하지 않고 9자리까지 */
private fun String.applyAmountKey(key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> if (isEmpty() && key.value == 0) this else (this + key.value).take(9)
    KeypadKey.DoubleZero -> if (isEmpty()) this else (this + "00").take(9)
    KeypadKey.Delete -> dropLast(1)
}

@HiltViewModel
class PosAmountViewModel @Inject constructor(settings: PosSettings) : ViewModel() {
    val merchant: Merchant? = settings.merchant
}

@Composable
private fun PosAmountScreen(onChangeMerchant: () -> Unit, onScan: (Long) -> Unit, viewModel: PosAmountViewModel = hiltViewModel()) {
    var digits by rememberSaveable { mutableStateOf("") }
    val amount = digits.toLongOrNull() ?: 0L
    val c = YamuTheme.colors
    FlowScreen(
        title = viewModel.merchant?.name ?: "YAMU 가맹점",
        onBack = null,
        bottomBar = {
            Column {
                NumberKeypad(KeypadMode.Amount, onKey = { digits = digits.applyAmountKey(it) })
                BottomCta { PrimaryButton("고객 코드 찍기", onClick = { onScan(amount) }, enabled = amount > 0) }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().padding(horizontal = YamuSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(YamuSpacing.x8))
            StatusTag("모의 POS")
            Spacer(Modifier.height(YamuSpacing.x3))
            Text("결제 금액", style = YamuTheme.typography.titleM, color = c.text2)
            Spacer(Modifier.height(YamuSpacing.x2))
            AmountText(amount)
            LinkButton("가맹점 바꾸기", onClick = onChangeMerchant)
        }
    }
}

// ───────────────────────── POS-3 고객 코드 찍기(#27) ─────────────────────────

data class PosScanState(val requesting: Boolean = false, val error: String? = null, val paymentId: Long? = null)

@HiltViewModel
class PosScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PosRepository,
    settings: PosSettings,
) : ViewModel() {
    val amount: Long = savedStateHandle.toRoute<PosScan>().amount
    private val merchant = settings.merchant
    private val _state = MutableStateFlow(PosScanState())
    val state: StateFlow<PosScanState> = _state.asStateFlow()
    /** 같은 코드 · 같은 금액 = 같은 키(통신이 끊겨 다시 보내도 결제 요청은 하나) */
    private val keys = mutableMapOf<String, String>()
    private var lastFailed: Pair<String, Long>? = null

    /** 카메라는 같은 코드를 1초에 여러 번 읽는다 — 보내는 중이거나 방금 실패한 코드는 무시 */
    fun onCode(raw: String) {
        val token = parseScannedCode(raw) ?: return
        val s = _state.value
        if (s.requesting || s.paymentId != null) return
        lastFailed?.let { (t, at) -> if (t == token && System.currentTimeMillis() - at < 3_000) return }
        val m = merchant ?: return _state.update { it.copy(error = posErrorMessage("MERCHANT_NOT_FOUND")) }
        viewModelScope.launch {
            _state.update { it.copy(requesting = true, error = null) }
            val key = keys.getOrPut("$token:$amount") { newIdempotencyKey() }
            val orderNo = "POS-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            when (val r = repo.request(token, m.id, amount, orderNo, key)) {
                is ApiResult.Success -> _state.update { it.copy(requesting = false, paymentId = r.data) }
                is ApiResult.Failure -> {
                    lastFailed = token to System.currentTimeMillis()
                    _state.update { it.copy(requesting = false, error = posErrorMessage(r.error.code)) }
                }
            }
        }
    }

    fun navigated() = _state.update { it.copy(paymentId = null) }
}

@Composable
private fun PosScanScreen(onBack: () -> Unit, onRequested: (Long, Long) -> Unit, viewModel: PosScanViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var typing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.paymentId) {
        s.paymentId?.let {
            viewModel.navigated()
            onRequested(it, viewModel.amount)
        }
    }
    val c = YamuTheme.colors
    FlowScreen(
        title = "고객 코드 찍기",
        onBack = onBack,
        bottomBar = { BottomCta { PrimaryButton("번호로 넣기", onClick = { typing = true }, loading = s.requesting) } },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(YamuSpacing.x4))
            AmountText(viewModel.amount)
            Spacer(Modifier.height(YamuSpacing.x4))
            PosCodeScanner(
                onCode = viewModel::onCode,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(YamuRadius.card)),
            )
            Spacer(Modifier.height(YamuSpacing.x3))
            Text(
                if (s.requesting) "결제 요청을 보내고 있어요" else "고객 앱의 QR이나 바코드를 비춰 주세요",
                style = YamuTheme.typography.bodyM,
                color = c.text2,
                textAlign = TextAlign.Center,
            )
            s.error?.let {
                Spacer(Modifier.height(YamuSpacing.x2))
                Text(it, style = YamuTheme.typography.bodyM, color = c.error, textAlign = TextAlign.Center)
            }
        }
    }
    if (typing) {
        var text by remember { mutableStateOf("") }
        YamuBottomSheet(onDismiss = { typing = false }) {
            Text("결제번호 넣기", style = YamuTheme.typography.titleL, color = c.text1)
            Spacer(Modifier.height(YamuSpacing.x3))
            YamuTextField(
                label = "결제번호",
                value = text,
                onValueChange = { text = it.take(40) },
                placeholder = "고객 앱 바코드 아래 번호",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.imePadding(),
            )
            Spacer(Modifier.height(YamuSpacing.x4))
            PrimaryButton("결제 요청", onClick = {
                typing = false
                viewModel.onCode(text)
            }, enabled = parseScannedCode(text) != null)
        }
    }
}

// ───────────────────────── POS-4 결과(#63, 2초마다) ─────────────────────────

data class PosResultState(val result: PosResult? = null, val error: String? = null)

@HiltViewModel
class PosResultViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repo: PosRepository) : ViewModel() {
    private val route = savedStateHandle.toRoute<PosResultRoute>()
    val amount: Long = route.amount
    private val _state = MutableStateFlow(PosResultState())
    val state: StateFlow<PosResultState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                when (val r = repo.result(route.paymentId)) {
                    is ApiResult.Success -> {
                        _state.update { PosResultState(result = r.data) }
                        if (r.data.status != PosStatus.WAITING) return@launch
                    }
                    is ApiResult.Failure -> _state.update { it.copy(error = r.error.userMessage()) }
                }
                delay(2_000)
            }
        }
    }
}

@Composable
private fun PosResultScreen(onNewPayment: () -> Unit, viewModel: PosResultViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val r = s.result
    val waiting = r == null || r.status == PosStatus.WAITING
    FlowScreen(
        title = "결제 결과",
        onBack = onNewPayment,
        bottomBar = { if (!waiting) BottomCta { PrimaryButton("새 결제", onClick = onNewPayment) } },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(YamuSpacing.x8))
            CharacterImage(if (r?.status == PosStatus.APPROVED) YamuPose.Done else YamuPose.Card, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(YamuSpacing.x4))
            val headline = when (r?.status) {
                null, PosStatus.WAITING -> "고객이 앱에서 확인하고 있어요"
                PosStatus.APPROVED -> "결제됐어요"
                PosStatus.DECLINED -> "결제가 거절됐어요"
                PosStatus.ENDED -> "결제되지 않았어요"
            }
            Text(headline, style = YamuTheme.typography.headlineS, color = c.text1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(YamuSpacing.x2))
            AmountText(r?.amount?.takeIf { it > 0 } ?: viewModel.amount)
            when (r?.status) {
                null, PosStatus.WAITING -> {
                    Text("고객이 60초 안에 카드를 확정하면 바로 바뀌어요", style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
                    s.error?.let { Text("$it · 다시 확인하고 있어요", style = YamuTheme.typography.bodyS, color = c.warning) }
                }
                PosStatus.APPROVED -> Receipt(r)
                PosStatus.DECLINED -> Text(r.failureMessage ?: "고객 카드가 승인되지 않았어요", style = YamuTheme.typography.bodyM, color = c.error, textAlign = TextAlign.Center)
                PosStatus.ENDED -> Text("고객이 거절했거나 확인 시간이 지났어요", style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
            }
        }
    }
}

/** 승인 영수증 — #63 값 그대로 */
@Composable
private fun Receipt(r: PosResult) {
    val c = YamuTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = YamuSpacing.x5)) {
        StatusTag("모의 결제")
        HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(top = YamuSpacing.x3))
        r.instantDiscount?.let { ListRow(title = "즉시할인", valuePlain = "−${it.toWon()}원") }
        r.chargedAmount?.let { ListRow(title = "받은 금액", valuePlain = "${it.toWon()}원") }
        ListRow(title = "카드", valuePlain = listOfNotNull(r.cardIssuer, r.cardLast4?.let { "·$it" }).joinToString(" ").ifEmpty { "—" })
        ListRow(title = "할부", valuePlain = r.installmentMonths?.let { "${it}개월" } ?: "일시불")
        r.approvalNo?.let { ListRow(title = "승인번호", valuePlain = it) }
    }
}
