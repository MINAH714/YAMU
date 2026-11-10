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
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.KeypadKey
import com.yamu.app.core.designsystem.component.KeypadMode
import com.yamu.app.core.designsystem.component.LinkButton
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.NumberKeypad
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.component.YamuSnackbar
import com.yamu.app.core.designsystem.component.YamuTextField
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiFeature
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.newIdempotencyKey
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.payment.data.parseScannedCode
import com.yamu.app.feature.payment.data.posErrorMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
/** [posCode]: 결제를 만든 단말. 결과는 이 코드로만 볼 수 있어서, 기다리는 동안 단말을 바꿔도 이 값으로 묻는다 */
@Serializable data class PosResultRoute(val paymentId: Long, val amount: Long, val posCode: String)

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
                        onRequested = { id, amount, posCode -> nav.navigate(PosResultRoute(id, amount, posCode)) { popUpTo<PosAmount>() } },
                    )
                }
                composable<PosResultRoute> { PosResultScreen(onNewPayment = newPayment) }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { YamuSnackbar(it) }
        }
    }
}

// ───────────────────────── POS-1 단말 고르기 ─────────────────────────

/** [choosing]: 가맹점 번호를 찾고 있는 단말의 posCode */
data class PosMerchantsState(val choosing: String? = null, val error: String? = null, val chosen: Boolean = false)

@HiltViewModel
class PosMerchantsViewModel @Inject constructor(private val repo: PosRepository, private val settings: PosSettings) : ViewModel() {
    private val _state = MutableStateFlow(PosMerchantsState())
    val state: StateFlow<PosMerchantsState> = _state.asStateFlow()
    val missingKey: Boolean = !repo.hasKey && ApiFeature.POS.isReal
    val current: String? = settings.merchant?.posCode

    /** 고른 단말 브랜드의 가맹점 번호를 서버에서 찾아 저장한다. 찾는 동안 다른 단말은 눌러도 무시 */
    fun choose(terminal: PosTerminal) {
        if (_state.value.choosing != null) return
        _state.update { it.copy(choosing = terminal.posCode, error = null) }
        viewModelScope.launch {
            when (val r = repo.merchantFor(terminal)) {
                is ApiResult.Success -> {
                    settings.merchant = r.data
                    _state.update { it.copy(choosing = null, chosen = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(choosing = null, error = merchantErrorMessage(terminal, r.error)) }
            }
        }
    }

    fun navigated() = _state.update { it.copy(chosen = false) }
}

internal fun merchantErrorMessage(terminal: PosTerminal, error: ApiError): String =
    if (error.code == "MERCHANT_NOT_FOUND") "서버에 ${terminal.name} 가맹점이 없어요 · 가맹점 시드를 확인해 주세요" else error.userMessage()

@Composable
private fun PosMerchantsScreen(onBack: (() -> Unit)?, onChosen: () -> Unit, viewModel: PosMerchantsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.chosen) {
        if (s.chosen) {
            viewModel.navigated()
            onChosen()
        }
    }
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
            POS_TERMINALS.forEach { t ->
                ListRow(
                    title = t.name,
                    sub = "${t.place} · ${t.posCode}",
                    valuePlain = when (t.posCode) {
                        s.choosing -> "찾는 중"
                        viewModel.current -> "지금 단말"
                        else -> null
                    },
                    chevron = true,
                    onClick = { viewModel.choose(t) },
                )
            }
            s.error?.let {
                Spacer(Modifier.height(YamuSpacing.x3))
                Text(it, style = YamuTheme.typography.bodyM, color = c.error)
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
    val merchant: PosMerchant? = settings.merchant
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

/** 접수됐는지 모르는 요청이 남았을 때 직원에게 보여 줄 말 */
internal const val POS_OUTCOME_UNKNOWN = "결제가 접수됐는지 확인하지 못했어요 · 같은 요청을 다시 보내 주세요"
internal const val POS_PENDING_FIRST = "앞 요청을 다시 보내거나 버린 뒤에 새 코드를 찍어 주세요"

/** 스캔 화면에 들어왔는데 앞에서 접수 여부를 모르는 시도가 남아 있을 때. 금액은 그 시도의 금액이다 */
internal fun posPendingHeld(amount: Long): String = "접수됐는지 모르는 앞 요청(${amount.toWon()}원)이 남아 있어요 · 다시 보내 확인하거나 버려 주세요"

/**
 * 보낸 결제 요청이 서버에 접수됐는지 모르는 실패. 연결이 끊겼거나 시간이 지났거나, 5xx이거나, 같은 키를 서버가 아직 처리 중이다.
 * 이때 새 키로 보내면 첫 요청이 이미 코드를 써 버려 409 BARCODE_ALREADY_USED가 나므로 같은 키 · 같은 본문으로 다시 보낸다
 */
val ApiError.isPosOutcomeUnknown: Boolean
    get() = isOutcomeUnknown || this is ApiError.Server || this is ApiError.BankUnavailable || code == "IDEMPOTENCY_IN_PROGRESS"

sealed interface PosSendOutcome {
    /** 접수됐다. 금액 · 단말은 실제로 보낸 본문의 값이다(앞에서 남은 시도를 보냈다면 지금 화면의 금액과 다를 수 있다) */
    data class Requested(val paymentId: Long, val amount: Long, val posCode: String) : PosSendOutcome
    /** 접수됐는지 모름. 시도를 그대로 남겨 두었다 */
    data class Unknown(val code: String) : PosSendOutcome
    /** 서버가 거절했다. 시도를 버렸으니 다음 코드는 새 키 · 새 주문번호로 간다 */
    data class Rejected(val code: String) : PosSendOutcome
    /** 접수됐는지 모르는 시도가 있어서 다른 코드는 보내지 않았다 */
    data object Blocked : PosSendOutcome
}

/**
 * 결제 요청 보내기와 다시 보내기. ViewModel 밖에 둬서 Android 없이 시험한다(PosRequestSenderTest).
 * 시도 하나는 (키, 본문) 묶음이고 주문번호도 본문에 들어 있어서, 다시 보낼 때 아무것도 새로 만들지 않는다.
 *
 * 시도는 화면이 아니라 [store]에 둔다(MR !209 리뷰). 보내기 전에 먼저 적어 두므로 응답을 기다리다 화면을 나가도 남고,
 * 다음 화면의 sender가 같은 코드를 받으면 그 시도를 그대로 보낸다. 응답이 와서 접수 · 거절이 정해지면 지운다
 */
class PosRequestSender(
    private val repo: PosRepository,
    private val merchant: PosMerchant,
    private val amount: Long,
    private val store: PosPendingStore,
    private val newKey: () -> String = ::newIdempotencyKey,
    private val newOrderNo: () -> String = { newPosOrderNo() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 접수됐는지 모르는 시도. 있으면 이 시도만 다시 보낼 수 있다. 서버가 키를 잊을 만큼([POS_PENDING_TTL_MS]) 오래되면 버린다 */
    val pending: PosAttempt?
        get() {
            val held = store.load() ?: return null
            if (now() - held.createdAtMs >= POS_PENDING_TTL_MS) {
                store.save(null)
                return null
            }
            return held.attempt
        }

    suspend fun send(token: String): PosSendOutcome {
        val held = pending
        val attempt = when {
            held == null -> PosAttempt(newKey(), PosPaymentRequest(token, merchant.merchantId, merchant.posCode, amount, newOrderNo()))
                .also { store.save(PosPending(it, now())) }   // 응답을 받기 전에 화면을 나가도 남게 먼저 적는다
            held.body.barcodeToken == token -> held
            else -> return PosSendOutcome.Blocked
        }
        return when (val r = repo.request(attempt)) {
            is ApiResult.Success -> {
                clear(attempt)
                PosSendOutcome.Requested(r.data, attempt.body.amount, attempt.body.posCode)
            }
            is ApiResult.Failure -> if (r.error.isPosOutcomeUnknown) {
                PosSendOutcome.Unknown(r.error.code)
            } else {
                clear(attempt)
                PosSendOutcome.Rejected(r.error.code)
            }
        }
    }

    /** 남은 시도를 그대로 다시 보낸다. 없으면 null */
    suspend fun resend(): PosSendOutcome? = pending?.let { send(it.body.barcodeToken) }

    /** 남은 시도를 버린다. 첫 요청이 접수됐다면 그 결제는 고객이 승인하지 않는 한 60초 뒤 만료된다 */
    fun discard() = store.save(null)

    /** 그 사이 다른 시도로 바뀌지 않았을 때만 지운다 */
    private fun clear(attempt: PosAttempt) {
        if (store.load()?.attempt == attempt) store.save(null)
    }
}

/** [pending]: 접수됐는지 모르는 요청이 남아 있다 — [같은 요청 다시 보내기] · [이 요청 버리기]. [requested]: 접수돼 결과 화면으로 간다 */
data class PosScanState(
    val requesting: Boolean = false,
    val error: String? = null,
    val pending: Boolean = false,
    val requested: PosSendOutcome.Requested? = null,
)

@HiltViewModel
class PosScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: PosRepository,
    settings: PosSettings,
    pendingStore: PosPendingStore,
) : ViewModel() {
    val amount: Long = savedStateHandle.toRoute<PosScan>().amount
    private val merchant = settings.merchant
    private val sender = merchant?.let { PosRequestSender(repo, it, amount, pendingStore) }
    private val _state = MutableStateFlow(
        sender?.pending?.let { PosScanState(pending = true, error = posPendingHeld(it.body.amount)) } ?: PosScanState(),
    )
    val state: StateFlow<PosScanState> = _state.asStateFlow()
    private var lastFailed: Pair<String, Long>? = null

    /** 카메라는 같은 코드를 1초에 여러 번 읽는다 — 보내는 중이거나 3초 안에 실패한 코드는 무시 */
    fun onCode(raw: String) {
        val token = parseScannedCode(raw) ?: return
        val s = _state.value
        if (s.requesting || s.requested != null) return
        lastFailed?.let { (t, at) -> if (t == token && System.currentTimeMillis() - at < 3_000) return }
        val sender = sender ?: return _state.update { it.copy(error = posErrorMessage("MERCHANT_NOT_FOUND")) }
        val held = sender.pending
        if (held != null && held.body.barcodeToken != token) return _state.update { it.copy(error = POS_PENDING_FIRST) }
        launchSend(token) { sender.send(token) }
    }

    fun resend() {
        val sender = sender ?: return
        val token = sender.pending?.body?.barcodeToken ?: return
        if (_state.value.requesting) return
        launchSend(token) { sender.resend() ?: PosSendOutcome.Blocked }
    }

    fun discard() {
        if (_state.value.requesting) return
        sender?.discard()
        lastFailed = null
        _state.update { it.copy(pending = false, error = null) }
    }

    private fun launchSend(token: String, block: suspend () -> PosSendOutcome) {
        _state.update { it.copy(requesting = true) }
        viewModelScope.launch {
            val outcome = block()
            if (outcome is PosSendOutcome.Unknown || outcome is PosSendOutcome.Rejected) lastFailed = token to System.currentTimeMillis()
            _state.update { s ->
                when (outcome) {
                    is PosSendOutcome.Requested -> s.copy(requesting = false, error = null, pending = false, requested = outcome)
                    is PosSendOutcome.Unknown -> s.copy(
                        requesting = false,
                        pending = true,
                        error = if (outcome.code == "IDEMPOTENCY_IN_PROGRESS") posErrorMessage(outcome.code) else POS_OUTCOME_UNKNOWN,
                    )
                    is PosSendOutcome.Rejected -> s.copy(requesting = false, pending = false, error = posErrorMessage(outcome.code))
                    PosSendOutcome.Blocked -> s.copy(requesting = false, error = POS_PENDING_FIRST)
                }
            }
        }
    }

    fun navigated() = _state.update { it.copy(requested = null) }
}

@Composable
private fun PosScanScreen(onBack: () -> Unit, onRequested: (Long, Long, String) -> Unit, viewModel: PosScanViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var typing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.requested) {
        s.requested?.let {
            viewModel.navigated()
            onRequested(it.paymentId, it.amount, it.posCode)
        }
    }
    val c = YamuTheme.colors
    FlowScreen(
        title = "고객 코드 찍기",
        onBack = onBack,
        bottomBar = {
            if (s.pending) {
                BottomCta(secondary = { TextButtonGrey("이 요청 버리기", onClick = viewModel::discard, modifier = Modifier.fillMaxWidth(), small = true) }) {
                    PrimaryButton("같은 요청 다시 보내기", onClick = viewModel::resend, loading = s.requesting)
                }
            } else {
                BottomCta { PrimaryButton("번호로 넣기", onClick = { typing = true }, loading = s.requesting) }
            }
        },
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
                when {
                    s.requesting -> "결제 요청을 보내고 있어요"
                    s.pending -> "같은 코드를 다시 찍어도 같은 요청으로 보내요"
                    else -> "고객 앱의 QR이나 바코드를 비춰 주세요"
                },
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

/** [stopped]: 다시 물어도 바뀌지 않는 오류라 조회를 멈췄다 */
data class PosResultState(val result: PosResult? = null, val error: String? = null, val stopped: Boolean = false)

/** 다시 물어도 그대로인 오류 — 단말 키가 틀렸거나(401), 이 단말이 만든 결제가 아니거나(404), 요청이 잘못됐다(400) */
private val ApiError.stopsPolling: Boolean
    get() = this is ApiError.Unauthorized || this is ApiError.NotFound || this is ApiError.InvalidRequest

internal fun resultErrorMessage(error: ApiError): String = when {
    error.code == "INVALID_POS_KEY" -> posErrorMessage(error.code)
    error is ApiError.NotFound -> posErrorMessage("RESOURCE_NOT_FOUND")
    error is ApiError.InvalidRequest -> "결과를 조회하지 못했어요 · 단말 설정을 확인해 주세요"
    else -> error.userMessage()
}

/**
 * 출금 확인 중([PosStatus.CHECKING])을 기다리는 시간. BE `late-withdrawal-window`(임시 10분) + 주기 작업 간격
 * `recovery-interval`(60초)이다. 대조 기간은 요청 번호 시각부터 재므로 처음 본 때부터 이만큼 기다리면 넉넉하다.
 * 임시: BE 설정을 베낀 값이라 결과 응답에 대조 마감 시각이 생기면 그 값으로 바꾼다(MR !209 리뷰)
 */
const val POS_LATE_CHECK_MS: Long = 11 * 60_000L

internal const val POS_REJECTED_HINT = "다시 결제하려면 고객 앱에서 새 바코드를 받아 스캔해 주세요"

internal const val POS_LATE_CHECK_OVER = "출금 결과를 끝내 확인하지 못했어요 · 고객 앱 결제 내역에서 결제됐는지 확인한 뒤에 다시 결제받아 주세요"

/**
 * 결과 조회(#63) — 응답이 오고 나서 [intervalMs] 뒤에 다시 묻는다(느린 응답이 겹치지 않게). 매번 [onState]로 알린다.
 * 최종 상태([isFinal])나 다시 물어도 그대로인 오류에서 멈추고, 연결 오류 · 5xx는 마지막 결과를 둔 채 계속 묻는다.
 * 거절됐어도 고객이 다른 카드로 다시 승인할 수 있는 동안(RETRYING)은 계속 묻는다. 마감이 지나면 서버가 EXPIRED로 바꿔 준다.
 * 출금 확인 중(CHECKING)은 처음 본 때부터 [lateCheckMs] 동안 묻고, 그때까지 승인되지 않으면 [POS_LATE_CHECK_OVER]로 멈춘다
 */
suspend fun pollPosResult(
    repo: PosRepository,
    paymentId: Long,
    posCode: String,
    intervalMs: Long = 2_000,
    lateCheckMs: Long = POS_LATE_CHECK_MS,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    onState: (PosResultState) -> Unit,
) {
    var state = PosResultState()
    var checkingSince: Long? = null
    while (true) {
        state = when (val r = repo.result(paymentId, posCode)) {
            is ApiResult.Success -> PosResultState(result = r.data)
            is ApiResult.Failure -> state.copy(error = resultErrorMessage(r.error), stopped = r.error.stopsPolling)
        }
        if (state.result?.status == PosStatus.CHECKING && !state.stopped) {
            val since = checkingSince ?: now().also { checkingSince = it }
            if (now() - since >= lateCheckMs) state = state.copy(error = POS_LATE_CHECK_OVER, stopped = true)
        }
        onState(state)
        if (state.stopped || state.result?.status?.isFinal == true) return
        delay(intervalMs)
    }
}

@HiltViewModel
class PosResultViewModel @Inject constructor(savedStateHandle: SavedStateHandle, repo: PosRepository) : ViewModel() {
    private val route = savedStateHandle.toRoute<PosResultRoute>()
    val amount: Long = route.amount
    private val _state = MutableStateFlow(PosResultState())
    val state: StateFlow<PosResultState> = _state.asStateFlow()

    init {
        viewModelScope.launch { pollPosResult(repo, route.paymentId, route.posCode) { _state.value = it } }
    }
}

private fun PosResultState.headline(): String = when (result?.status) {
    PosStatus.APPROVED -> "결제됐어요"
    PosStatus.DECLINED -> "결제가 거절됐어요"
    PosStatus.EXPIRED -> "결제되지 않았어요"
    PosStatus.CANCELED -> "결제가 취소됐어요"
    PosStatus.REJECTED -> "고객이 결제를 거절했어요"
    PosStatus.CHECKING -> if (stopped) "결제 결과를 확인하지 못했어요" else "출금을 확인하고 있어요"
    else -> when {
        stopped -> "결제 결과를 확인하지 못했어요"
        result?.status == PosStatus.RETRYING -> "카드가 거절됐어요"
        result?.status == PosStatus.PROCESSING -> "승인을 처리하고 있어요"
        else -> "고객이 앱에서 확인하고 있어요"
    }
}

@Composable
private fun PosResultScreen(onNewPayment: () -> Unit, viewModel: PosResultViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val r = s.result
    val done = s.stopped || r?.status?.isFinal == true
    FlowScreen(
        title = "결제 결과",
        onBack = onNewPayment,
        bottomBar = { if (done) BottomCta { PrimaryButton("새 결제", onClick = onNewPayment) } },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(YamuSpacing.x8))
            CharacterImage(if (r?.status == PosStatus.APPROVED) YamuPose.Done else YamuPose.Card, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(YamuSpacing.x4))
            Text(s.headline(), style = YamuTheme.typography.headlineS, color = c.text1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(YamuSpacing.x2))
            AmountText(r?.amount?.takeIf { it > 0 } ?: viewModel.amount)
            when {
                r != null && r.status == PosStatus.APPROVED -> Receipt(r)
                r != null && r.status == PosStatus.DECLINED ->
                    Text(r.failureMessage ?: "고객 카드가 승인되지 않았어요", style = YamuTheme.typography.bodyM, color = c.error, textAlign = TextAlign.Center)
                r?.status == PosStatus.EXPIRED ->
                    Text("고객이 확인 시간 안에 승인하지 않았어요", style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
                r?.status == PosStatus.CANCELED ->
                    Text("이 결제는 취소돼 돈이 나가지 않아요", style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
                // 웹 POS Receipt와 같은 안내. 바코드가 닫혀서 같은 코드로는 다시 결제할 수 없다
                r?.status == PosStatus.REJECTED ->
                    Text(POS_REJECTED_HINT, style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
                s.stopped -> Text(s.error.orEmpty(), style = YamuTheme.typography.bodyM, color = c.error, textAlign = TextAlign.Center)
                else -> {
                    val hint = when (r?.status) {
                        PosStatus.RETRYING -> "고객이 앱에서 다른 카드로 다시 결제할 수 있어요"
                        PosStatus.PROCESSING -> "카드사 결과를 기다리고 있어요"
                        PosStatus.CHECKING -> "늦게 승인될 수 있어요 · 결과가 나오기 전에는 다시 결제받지 마세요"
                        else -> "고객이 60초 안에 카드를 확정하면 바로 바뀌어요"
                    }
                    Text(hint, style = YamuTheme.typography.bodyM, color = c.text3, textAlign = TextAlign.Center)
                    r?.takeIf { it.status == PosStatus.RETRYING || it.status == PosStatus.CHECKING }?.failureMessage?.let {
                        Text(it, style = YamuTheme.typography.bodyS, color = c.error, textAlign = TextAlign.Center)
                    }
                    s.error?.let { Text("$it · 다시 확인하고 있어요", style = YamuTheme.typography.bodyS, color = c.warning) }
                }
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
