package com.yamu.app.feature.my

import androidx.compose.ui.text.font.FontWeight
import com.yamu.app.core.designsystem.component.CompactButton
import com.yamu.app.core.designsystem.component.KeyValueRow
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.BuildConfig
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.ListRow
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TabScreen
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuBottomSheet
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.component.YamuSwitch
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toManWon
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.link.BrandMark
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.my.data.AUTO_LOCK_CHOICES
import com.yamu.app.feature.my.data.Device
import com.yamu.app.feature.my.data.MyInfo
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.NotiKind
import com.yamu.app.feature.my.data.NotiSettings
import com.yamu.app.feature.my.data.autoLockLabel
import com.yamu.app.feature.my.data.deviceLastUsed
import com.yamu.app.feature.my.data.koreanTime
import com.yamu.app.feature.my.data.maskEmail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── 공통 ─────────────────────────

/** 값을 눌러 고르는 설정 줄(받는 시각 · 자동 잠금) — 값 쪽 터치 칸 48이라 한 줄도 64dp(프로토타입 .trail.tap · .tval) */
internal val TapRowHeight = 64.dp

/** 설정 목록 묶음 이름 — '결제' · '알림 종류' */
@Composable
internal fun SectionLabel(text: String, first: Boolean = false) {
    Text(
        text,
        style = YamuTheme.typography.sub.copy(fontWeight = FontWeight.SemiBold),
        color = YamuTheme.colors.text3,
        // 묶음 사이는 28dp(프로토타입 .grp + .grp 12 + 라벨 위 16), 화면 첫 라벨만 16
        modifier = Modifier.padding(top = if (first) YamuSpacing.x4 else 28.dp, bottom = YamuSpacing.x1),
    )
}

/** 스위치 줄 — 줄 전체가 스위치 하나(TalkBack '스위치, 켜짐') */
@Composable
internal fun SwitchRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, sub: String? = null, enabled: Boolean = true) {
    ListRow(
        title = title,
        sub = sub,
        // 스위치 자리 60 × 48(프로토타입 .swt) — 한 줄이어도 줄이 64dp
        trailing = {
            Box(Modifier.size(width = 60.dp, height = 48.dp), contentAlignment = Alignment.CenterEnd) {
                YamuSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
    )
}

/** 고르기 시트 — 앱 자동 잠금 · 결제 코드 인증 방식 */
@Composable
private fun <T> ChoiceSheet(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    val c = YamuTheme.colors
    YamuBottomSheet(onDismiss = onDismiss) {
        Text(title, style = YamuTheme.typography.titleL, color = c.text1, modifier = Modifier.padding(vertical = YamuSpacing.x3))
        options.forEach { (value, label) ->
            ListRow(
                title = label,
                trailing = { if (value == selected) Icon(Icons.Rounded.Check, contentDescription = "선택됨", tint = c.primary) },
                onClick = {
                    onDismiss()
                    if (value != selected) onSelect(value)
                },
            )
        }
    }
}

/** 저장 실패 문장 — 서버 코드별 */
private fun ApiError.settingMessage(): String = when (code) {
    "DEFAULT_CARD_REQUIRED" -> "자동 선택을 끄려면 기본 카드가 있어야 해요"
    else -> "저장하지 못했어요 · ${userMessage()}"
}

// ───────────────────────── M-01 마이(탭) ─────────────────────────

data class MyState(
    val loading: Boolean = true,
    val info: MyInfo? = null,
    val unread: Int = 0,
    val cardCount: Int? = null,
    val notificationsOn: Boolean? = null,
    val error: String? = null,
)

@HiltViewModel
class MyViewModel @Inject constructor(
    private val my: MyRepository,
    private val link: LinkRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MyState())
    val state: StateFlow<MyState> = _state.asStateFlow()

    /** 탭에 돌아올 때마다 — 다른 화면에서 바꾼 설정 · 읽은 알림이 바로 보이게 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.info == null, error = null) }
            val info = async { my.me() }
            val notifications = async { my.notifications(page = 1) }
            val cards = async { link.linkedOverview() }
            val settings = async { my.notificationSettings() }
            val i = info.await()
            _state.update { s ->
                s.copy(
                    loading = false,
                    info = (i as? ApiResult.Success)?.data ?: s.info,
                    error = (i as? ApiResult.Failure)?.error?.userMessage(),
                    unread = (notifications.await() as? ApiResult.Success)?.data?.unread ?: s.unread,
                    cardCount = (cards.await() as? ApiResult.Success)?.data?.cards?.size ?: s.cardCount,
                    notificationsOn = (settings.await() as? ApiResult.Success)?.data?.let { it.performance || it.balance || it.payment } ?: s.notificationsOn,
                )
            }
        }
    }

    fun logout(then: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            then()
        }
    }
}

@Composable
fun MyRoute(
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
    onCardManage: () -> Unit,
    onNotificationSettings: () -> Unit,
    onSecurity: () -> Unit,
    onNotReady: (String) -> Unit,
    onLoggedOut: () -> Unit,
    onDebugMenu: (() -> Unit)?,
    viewModel: MyViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    TabScreen(
        title = "마이",
        largeTitle = true,
        actions = {
            IconButton(onClick = onNotifications) {
                BadgedBox(badge = { if (s.unread > 0) Badge { Text("${s.unread}") } }) {
                    Icon(YamuIcons.Notifications, contentDescription = "알림 ${s.unread}개", tint = c.text1)
                }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val info = s.info
            when {
                info == null && s.loading -> YamuCard {
                    SkeletonBlock(height = 28.dp, widthFraction = 0.4f)
                    Spacer(Modifier.height(YamuSpacing.x2))
                    SkeletonBlock(height = 18.dp, widthFraction = 0.6f)
                }
                info == null -> YamuCard { ErrorState(s.error ?: "내 정보를 불러오지 못했어요", onRetry = viewModel::refresh) }
                else -> YamuCard(onClick = onProfile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${info.name}님", style = YamuTheme.typography.titleL, color = c.text1)
                            maskEmail(info.email)?.let { Text(it, style = YamuTheme.typography.bodyM, color = c.text3, modifier = Modifier.padding(top = 4.dp)) }
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3)
                    }
                }
            }
            YamuCard(contentPadding = MenuPadding) {
                ListRow(title = "카드 관리", valuePlain = s.cardCount?.let { "${it}장" }, chevron = true, onClick = onCardManage)
                ListRow(
                    title = "알림 설정",
                    valuePlain = s.notificationsOn?.let { if (it) "켜짐" else "꺼짐" },
                    chevron = true,
                    onClick = onNotificationSettings,
                )
                ListRow(
                    title = "보안 · 인증",
                    valuePlain = info?.let { if (it.biometricEnabled) "지문" else "간편 비밀번호" },
                    chevron = true,
                    onClick = onSecurity,
                )
            }
            YamuCard(contentPadding = MenuPadding) {
                // 주소가 정해지면 Custom Tabs로 연다(13 문서 9·10-6 ④)
                ListRow(title = "고객센터", trailing = { ExternalMark() }, onClick = { onNotReady("고객센터는 준비 중이에요") })
                ListRow(title = "약관 및 정책", trailing = { ExternalMark() }, onClick = { onNotReady("약관 및 정책은 준비 중이에요") })
                ListRow(title = "로그아웃", onClick = { viewModel.logout(onLoggedOut) })
                if (onDebugMenu != null) ListRow(title = "디버그 메뉴", chevron = true, onClick = onDebugMenu)
            }
            Text(
                "YAMU v${BuildConfig.VERSION_NAME}",
                style = YamuTheme.typography.bodyS,
                color = c.text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = YamuSpacing.x4),
            )
        }
    }
}

/** 메뉴 카드 안쪽 — 위아래 8 · 왼쪽 20 · 오른쪽 16(프로토타입 M-01 .menu) */
private val MenuPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)

/** 웹 페이지로 나가는 줄 표시(open_in_new) */
@Composable
private fun ExternalMark() {
    Icon(YamuIcons.OpenInNew, contentDescription = "새 창", tint = YamuTheme.colors.text3, modifier = Modifier.size(20.dp))
}

// ───────────────────────── M-02 카드 관리 ─────────────────────────

data class CardManageState(val loading: Boolean = true, val autoSelect: Boolean? = null, val cards: List<LinkedCard> = emptyList(), val error: String? = null)

@HiltViewModel
class CardManageViewModel @Inject constructor(private val my: MyRepository, private val link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(CardManageState())
    val state: StateFlow<CardManageState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.cards.isEmpty(), error = null) }
            val info = async { my.me() }
            val overview = link.linkedOverview()
            val i = info.await()
            _state.update { s ->
                when (overview) {
                    is ApiResult.Success -> s.copy(loading = false, cards = overview.data.cards, autoSelect = (i as? ApiResult.Success)?.data?.autoSelect ?: s.autoSelect)
                    is ApiResult.Failure -> s.copy(loading = false, error = overview.error.userMessage())
                }
            }
        }
    }

    /** 바로 반영 — 실패하면 원래대로 + 이유(onDone에 문장, 성공이면 null) */
    fun setAutoSelect(on: Boolean, onDone: (String?) -> Unit) {
        val before = _state.value.autoSelect
        _state.update { it.copy(autoSelect = on) }
        viewModelScope.launch {
            when (val r = my.setAutoSelect(on)) {
                is ApiResult.Success -> onDone(null)
                is ApiResult.Failure -> {
                    _state.update { it.copy(autoSelect = before) }
                    onDone(r.error.settingMessage())
                }
            }
        }
    }
}

@Composable
fun CardManageRoute(onBack: () -> Unit, onAddCard: () -> Unit, viewModel: CardManageViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()   // 카드 추가(C-11 ~ C-14)에서 돌아오면 새 카드가 보이게
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    fun toggle(on: Boolean) {
        viewModel.setAutoSelect(on) { error ->
            scope.launch {
                if (error != null) {
                    snackbar.showSnackbar(error)
                    return@launch
                }
                val r = snackbar.showSnackbar(
                    if (on) "결제 카드 자동 선택을 켰어요" else "결제 카드 자동 선택을 껐어요 · 기본 카드로 결제해요",
                    actionLabel = "실행 취소",
                    duration = SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) viewModel.setAutoSelect(!on) { e -> e?.let { scope.launch { snackbar.showSnackbar(it) } } }
            }
        }
    }
    Scaffold(
        topBar = {
            YamuTopBar("카드 관리", onBack = onBack) {
                IconButton(onClick = onAddCard) { Icon(YamuIcons.AddCard, contentDescription = "카드 추가", tint = c.text1) }
            }
        },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            when {
                s.loading -> repeat(4) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                s.cards.isEmpty() && s.error != null -> ErrorState(s.error.orEmpty(), onRetry = viewModel::load)
                else -> {
                    SectionLabel("결제", first = true)
                    s.autoSelect?.let { on -> SwitchRow("결제 카드 자동 선택", checked = on, onToggle = ::toggle, sub = "혜택이 가장 큰 카드로 결제") }
                    SectionLabel("내 카드 ${s.cards.size}장")
                    if (s.cards.isEmpty()) EmptyState("연결한 카드가 없어요", actionText = "카드 추가", onAction = onAddCard)
                    s.cards.forEach { card ->
                        ListRow(
                            title = card.productName,
                            lead = { BrandMark(card.brand, large = true) },
                            titleTag = if (card.isDefault) ({ StatusTag("기본") }) else null,
                            minHeight = 64.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

// ───────────────────────── M-04 알림 설정 ─────────────────────────

data class NotiSettingsState(val loading: Boolean = true, val settings: NotiSettings? = null, val error: String? = null)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(private val my: MyRepository) : ViewModel() {
    private val _state = MutableStateFlow(NotiSettingsState())
    val state: StateFlow<NotiSettingsState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = my.notificationSettings()) {
                is ApiResult.Success -> _state.update { NotiSettingsState(loading = false, settings = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    private fun change(next: NotiSettings, onFail: (String) -> Unit, call: suspend () -> ApiResult<Unit>) {
        val before = _state.value.settings ?: return
        _state.update { it.copy(settings = next) }
        viewModelScope.launch {
            val r = call()
            if (r is ApiResult.Failure) {
                _state.update { it.copy(settings = before) }
                onFail(r.error.settingMessage())
            }
        }
    }

    fun toggle(kind: NotiKind, on: Boolean, onFail: (String) -> Unit) {
        val s = _state.value.settings ?: return
        val next = when (kind) {
            NotiKind.PERFORMANCE -> s.copy(performance = on)
            NotiKind.BALANCE -> s.copy(balance = on)
            NotiKind.PAYMENT -> s.copy(payment = on)
        }
        change(next, onFail) { my.setNotification(kind, on) }
    }

    fun setTime(hhmm: String, onFail: (String) -> Unit) {
        val s = _state.value.settings ?: return
        change(s.copy(time = hhmm), onFail) { my.setNotificationTime(hhmm) }
    }
}

private fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsRoute(onBack: () -> Unit, viewModel: NotificationSettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val onFail: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    // 휴대폰 알림 허용 — 시스템 설정에서 바꾸고 돌아오면 다시 읽는다
    var systemOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        systemOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    FlowScreen(title = "알림 설정", onBack = onBack) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            SwitchRow("앱 알림", checked = systemOn, onToggle = { context.openAppNotificationSettings() }, sub = "휴대폰 알림 허용")
            if (!systemOn) Text("휴대폰 알림이 꺼져 있어 아래 알림이 오지 않아요", style = YamuTheme.typography.bodyS, color = c.warning)
            val st = s.settings
            when {
                st == null && s.loading -> repeat(4) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                st == null -> ErrorState(s.error ?: "알림 설정을 불러오지 못했어요", onRetry = viewModel::load)
                else -> {
                    SectionLabel("알림 종류")   // 위에 '앱 알림' 묶음(라벨 없음)이 있어 첫 라벨이 아니다
                    SwitchRow("실적 마감 알림", checked = st.performance, onToggle = { viewModel.toggle(NotiKind.PERFORMANCE, it, onFail) })
                    SwitchRow(
                        "잔액 부족 예측",
                        checked = st.balance,
                        onToggle = { viewModel.toggle(NotiKind.BALANCE, it, onFail) },
                        sub = st.balanceThreshold?.let { "기준 ${it.toManWon()}" },
                    )
                    SwitchRow("결제 결과", checked = st.payment, onToggle = { viewModel.toggle(NotiKind.PAYMENT, it, onFail) })
                    // 보안 알림(새 기기 로그인)은 끌 수 없어 목록에 없다 — 프로토타입은 안내 문장도 뺐다
                    SectionLabel("받는 방식")
                    ListRow(title = "받는 시각", valuePlain = koreanTime(st.time), chevron = true, minHeight = TapRowHeight, onClick = { picking = true })
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    val st = s.settings
    if (picking && st != null) {
        val (h, m) = st.time.split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 8 }) to (it.getOrElse(1) { 30 }) }
        val picker = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = false)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("받는 시각", style = YamuTheme.typography.titleL) },
            text = {
                // 기본 색(분홍 · 보라) 대신 앱 색 — 고른 칸은 파랑, 나머지는 회색 칸
                TimePicker(
                    state = picker,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.surfaceVariant,
                        clockDialSelectedContentColor = c.onPrimary,
                        clockDialUnselectedContentColor = c.text1,
                        selectorColor = c.primary,
                        containerColor = c.surface,
                        periodSelectorBorderColor = c.outline,
                        periodSelectorSelectedContainerColor = c.primaryContainer,
                        periodSelectorUnselectedContainerColor = c.surface,
                        periodSelectorSelectedContentColor = c.onPrimaryContainer,
                        periodSelectorUnselectedContentColor = c.text2,
                        timeSelectorSelectedContainerColor = c.primaryContainer,
                        timeSelectorUnselectedContainerColor = c.surfaceVariant,
                        timeSelectorSelectedContentColor = c.onPrimaryContainer,
                        timeSelectorUnselectedContentColor = c.text1,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    viewModel.setTime("%02d:%02d".format(picker.hour, picker.minute), onFail)
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("닫기") } },
            containerColor = c.surface,
        )
    }
}

// ───────────────────────── M-06 회원 정보 ─────────────────────────

data class ProfileState(val loading: Boolean = true, val info: MyInfo? = null, val devices: List<Device> = emptyList(), val error: String? = null)

@HiltViewModel
class ProfileViewModel @Inject constructor(private val my: MyRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val devices = async { my.devices() }
            when (val i = my.me()) {
                is ApiResult.Success -> _state.update {
                    ProfileState(loading = false, info = i.data, devices = (devices.await() as? ApiResult.Success)?.data.orEmpty())
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = i.error.userMessage()) }
            }
        }
    }

    /** 다른 기기 로그아웃(#16) — 그 기기의 세션을 서버가 끊는다 */
    fun revoke(device: Device, onDone: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = my.revokeDevice(device.id)) {
                is ApiResult.Success -> {
                    _state.update { s -> s.copy(devices = s.devices.filter { it.id != device.id }) }
                    onDone("${device.name}에서 로그아웃했어요")
                }
                is ApiResult.Failure -> onDone("로그아웃하지 못했어요 · ${r.error.userMessage()}")
            }
        }
    }
}

@Composable
fun ProfileRoute(onBack: () -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    FlowScreen(title = "회원 정보", onBack = onBack) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            val info = s.info
            when {
                s.loading -> repeat(4) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                info == null -> ErrorState(s.error ?: "회원 정보를 불러오지 못했어요", onRetry = viewModel::load)
                else -> {
                    SectionLabel("기본 정보", first = true)
                    // 휴대폰 번호는 API에 없어 줄을 뺐다(13 문서 9·10-1)
                    KeyValueRow(
                        "이름",
                        info.name,
                        keyWidth = 64.dp,
                        minHeight = 56.dp,
                        trailing = { Icon(YamuIcons.Lock, contentDescription = "바꿀 수 없음", tint = c.text3, modifier = Modifier.size(24.dp)) },
                    )
                    info.email?.let { KeyValueRow("이메일", it, keyWidth = 64.dp, minHeight = 56.dp) }
                    SectionLabel("로그인한 기기")
                    s.devices.forEach { d ->
                        ListRow(
                            title = d.name,
                            titleTag = if (d.current) ({ StatusTag("이 기기") }) else null,
                            sub = if (d.current) null else deviceLastUsed(d.lastUsedIso),
                            trailing = {
                                if (!d.current) CompactButton("로그아웃", onClick = { viewModel.revoke(d) { m -> scope.launch { snackbar.showSnackbar(m) } } }, small = true)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

// ───────────────────────── M-08 보안 · 인증 ─────────────────────────

data class SecurityState(
    val loading: Boolean = true,
    val info: MyInfo? = null,
    val biometricAvailable: Boolean = false,   // 이 휴대폰에 지문이 등록돼 있나
    val biometricOn: Boolean = false,          // 이 기기 키가 등록돼 있나
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(private val my: MyRepository, private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(SecurityState())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.info == null, error = null) }
            val available = auth.canUseBiometric()
            val on = auth.biometricReady()
            when (val i = my.me()) {
                is ApiResult.Success -> _state.update { SecurityState(loading = false, info = i.data, biometricAvailable = available, biometricOn = on) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = i.error.userMessage(), biometricAvailable = available, biometricOn = on) }
            }
        }
    }

    /** 지문 켜기 = 기기 키 만들고 등록(#15) · 끄기 = 이 기기 등록 해제(#16) + 키 삭제 */
    fun setBiometric(on: Boolean, onFail: (String) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, biometricOn = on) }
        viewModelScope.launch {
            val r = if (on) auth.registerDevice() else auth.disableBiometric(_state.value.info?.currentDeviceId)
            _state.update { it.copy(busy = false, biometricOn = if (r is ApiResult.Success) on else !on) }
            if (r is ApiResult.Failure) onFail(r.error.settingMessage())
        }
    }

    fun setAutoLock(seconds: Int, onFail: (String) -> Unit) {
        val info = _state.value.info ?: return
        _state.update { it.copy(info = info.copy(autoLockSeconds = seconds)) }
        viewModelScope.launch {
            val r = my.setAutoLock(seconds)
            if (r is ApiResult.Failure) {
                _state.update { it.copy(info = info) }
                onFail(r.error.settingMessage())
            }
        }
    }

    fun setPinOnly(pinOnly: Boolean, onFail: (String) -> Unit) {
        val info = _state.value.info ?: return
        _state.update { it.copy(info = info.copy(pinOnlyForCode = pinOnly)) }
        viewModelScope.launch {
            val r = my.setPinOnlyForCode(pinOnly)
            if (r is ApiResult.Failure) {
                _state.update { it.copy(info = info) }
                onFail(r.error.settingMessage())
            }
        }
    }
}

private enum class SecuritySheet { AUTO_LOCK, CODE_AUTH }

private fun Context.openBiometricEnroll() {
    try {
        startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}

@Composable
fun SecurityRoute(onBack: () -> Unit, viewModel: SecurityViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()   // 시스템 설정에서 지문을 등록하고 돌아오면 스위치를 쓸 수 있게
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val onFail: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    var sheet by rememberSaveable { mutableStateOf<SecuritySheet?>(null) }
    FlowScreen(title = "보안 · 인증", onBack = onBack) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            val info = s.info
            when {
                info == null && s.loading -> repeat(3) {
                    Spacer(Modifier.height(YamuSpacing.x3))
                    SkeletonBlock(height = 52.dp)
                }
                info == null -> ErrorState(s.error ?: "보안 설정을 불러오지 못했어요", onRetry = viewModel::load)
                else -> {
                    SectionLabel("잠금 · 인증", first = true)
                    if (s.biometricAvailable) {
                        SwitchRow("지문 인증 사용", checked = s.biometricOn, onToggle = { viewModel.setBiometric(it, onFail) }, enabled = !s.busy)
                    } else {
                        ListRow(
                            title = "지문 인증 사용",
                            sub = "이 휴대폰에 등록된 지문이 없어요 · 눌러서 등록",
                            trailing = { YamuSwitch(checked = false, onCheckedChange = null, enabled = false) },
                            onClick = { context.openBiometricEnroll() },
                        )
                    }
                    ListRow(
                        title = "앱 자동 잠금",
                        minHeight = TapRowHeight,
                        valuePlain = autoLockLabel(info.autoLockSeconds),
                        trailing = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.text3) },
                        onClick = { sheet = SecuritySheet.AUTO_LOCK },
                    )
                    ListRow(
                        title = "결제 코드 열 때 인증",
                        minHeight = TapRowHeight,
                        valuePlain = if (info.pinOnlyForCode || !s.biometricOn) "간편 비밀번호" else "지문",
                        trailing = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.text3) },
                        onClick = { sheet = SecuritySheet.CODE_AUTH },
                    )
                    SectionLabel("이럴 때 확인해요")
                    ListRow(title = "앱 열기", trailing = { StatusTag("앱 잠금") })
                    ListRow(title = "결제 코드 열기", trailing = { StatusTag("추가 인증") })
                    ListRow(title = "계좌 · 카드 등록", trailing = { StatusTag("추가 인증") })
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
    val info = s.info
    when (sheet) {
        SecuritySheet.AUTO_LOCK -> if (info != null) ChoiceSheet(
            title = "앱 자동 잠금",
            options = AUTO_LOCK_CHOICES,
            selected = info.autoLockSeconds,
            onSelect = { viewModel.setAutoLock(it, onFail) },
            onDismiss = { sheet = null },
        )
        SecuritySheet.CODE_AUTH -> if (info != null) ChoiceSheet(
            title = "결제 코드 열 때 인증",
            options = listOf(false to "지문 또는 간편 비밀번호", true to "간편 비밀번호만"),
            selected = info.pinOnlyForCode,
            onSelect = { viewModel.setPinOnly(it, onFail) },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

