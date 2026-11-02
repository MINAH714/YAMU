package com.yamu.app.feature.my

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import com.yamu.app.core.designsystem.component.ExpandToggle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.EmptyState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.YamuCard
import com.yamu.app.core.designsystem.icon.YamuIcons
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.staleLine
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.link.CardListThumb
import com.yamu.app.feature.my.data.AppNotification
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.NotiTarget
import com.yamu.app.feature.my.data.notificationGroup
import com.yamu.app.feature.my.data.notificationTime
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.tone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── H-02 알림 ─────────────────────────

data class NotificationsState(
    val loading: Boolean = true,
    val unread: Int = 0,
    val items: List<AppNotification> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val my: MyRepository) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            when (val r = my.notifications(page = 1)) {
                is ApiResult.Success -> _state.update { NotificationsState(loading = false, unread = r.data.unread, items = r.data.items) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    /** 누르면 읽음(#50) — 화면은 바로 바꾸고 서버 실패는 다음 새로고침에 맞춰진다 */
    fun open(n: AppNotification) {
        if (n.read) return
        _state.update { s -> s.copy(unread = (s.unread - 1).coerceAtLeast(0), items = s.items.map { if (it.id == n.id) it.copy(read = true) else it }) }
        viewModelScope.launch { my.markRead(n.id) }
    }

    fun readAll(onFail: (String) -> Unit) {
        val before = _state.value
        _state.update { s -> s.copy(unread = 0, items = s.items.map { it.copy(read = true) }) }
        viewModelScope.launch {
            val r = my.markAllRead()
            if (r is ApiResult.Failure) {
                _state.value = before
                onFail("모두 읽음으로 바꾸지 못했어요 · ${r.error.userMessage()}")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationsRoute(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (NotiTarget) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val c = YamuTheme.colors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var showOlder by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            YamuTopBar("알림", onBack = onBack) {
                if (s.unread > 0) {
                    IconButton(onClick = { viewModel.readAll { m -> scope.launch { snackbar.showSnackbar(m) } } }) {
                        Icon(YamuIcons.DoneAll, contentDescription = "모두 읽음, 안 읽은 알림 ${s.unread}개", tint = c.text1)
                    }
                }
                IconButton(onClick = onSettings) { Icon(YamuIcons.Settings, contentDescription = "알림 설정", tint = c.text1) }
            }
        },
        containerColor = c.surface,
    ) { inner ->
        when {
            s.items.isEmpty() && s.loading -> Column(Modifier.padding(inner).padding(YamuSpacing.screen)) {
                repeat(5) { SkeletonBlock(height = 52.dp); Spacer(Modifier.height(YamuSpacing.x3)) }
            }
            s.items.isEmpty() && s.error != null -> Column(Modifier.padding(inner).padding(YamuSpacing.screen)) {
                ErrorState(s.error.orEmpty(), onRetry = viewModel::refresh)
            }
            s.items.isEmpty() -> Column(Modifier.padding(inner).padding(YamuSpacing.screen)) { EmptyState("새 알림이 없어요") }
            else -> LazyColumn(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
                val groups = s.items.groupBy { notificationGroup(it.createdIso) }
                val recent = groups.filterKeys { it != "이전" }
                val older = groups["이전"].orEmpty()
                recent.forEach { (group, rows) ->
                    stickyHeader(key = "g-$group") { GroupHeader(group) }
                    itemsIndexed(rows, key = { _, n -> n.id }) { i, n ->
                        if (i > 0) HorizontalDivider(color = c.outlineVariant)
                        NotificationRow(n, onClick = {
                            viewModel.open(n)
                            onOpen(n.target)
                        })
                    }
                }
                // 그 전 알림은 접어 둔다(프로토타입 H-02 '모두 보기')
                if (older.isNotEmpty()) {
                    item(key = "older-toggle") {
                        Column(Modifier.padding(horizontal = YamuSpacing.screen)) {
                            HorizontalDivider(color = c.outlineVariant)
                            Spacer(Modifier.height(4.dp))
                            ExpandToggle(if (showOlder) "접기" else "모두 보기", expanded = showOlder, onToggle = { showOlder = !showOlder })
                        }
                    }
                    if (showOlder) {
                        itemsIndexed(older, key = { _, n -> n.id }) { i, n ->
                            if (i > 0) HorizontalDivider(color = c.outlineVariant)
                            NotificationRow(n, onClick = {
                                viewModel.open(n)
                                onOpen(n.target)
                            })
                        }
                    }
                }
                item { Spacer(Modifier.height(YamuSpacing.x6)) }
            }
        }
    }
}

/** 묶음 머리 — '오늘' · '이번 주'(40dp) */
@Composable
private fun GroupHeader(text: String) {
    val c = YamuTheme.colors
    Text(
        text,
        style = YamuTheme.typography.labelM,
        color = c.text3,
        modifier = Modifier.fillMaxWidth().background(c.surface).heightIn(min = 40.dp)
            .padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 12.dp),
    )
}

/** 2줄 — 위 시각(안 읽으면 앞에 파란 점) · 아래 제목(안 읽음 Bold · 읽음 Medium). 내용은 읽어 주기에만 */
@Composable
private fun NotificationRow(n: AppNotification, onClick: () -> Unit) {
    val c = YamuTheme.colors
    val time = notificationTime(n.createdIso)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = listOfNotNull("안 읽음".takeIf { !n.read }, time, n.title, n.body).joinToString(", ") }
            .padding(horizontal = YamuSpacing.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!n.read) Box(Modifier.size(8.dp).clip(CircleShape).background(c.primary))
                time?.let { Text(it, style = YamuTheme.typography.labelM, color = c.text3) }
            }
            Text(
                n.title,
                style = YamuTheme.typography.bodyL.copy(fontWeight = if (n.read) FontWeight.Medium else FontWeight.Bold),
                color = c.text1,
            )
        }
    }
}

// ───────────────────────── H-03 내 카드 ─────────────────────────

data class MyCardsState(val loading: Boolean = true, val overview: PerfOverview? = null, val error: String? = null)

/** 실적 관리(R-01)와 같은 값(#65) — 판정은 서버 값 그대로 */
@HiltViewModel
class MyCardsViewModel @Inject constructor(private val perf: PerformanceRepository) : ViewModel() {
    private val _state = MutableStateFlow(MyCardsState(overview = perf.cachedOverview(), loading = perf.cachedOverview() == null))
    val state: StateFlow<MyCardsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.overview == null, error = null) }
            when (val r = perf.overview()) {
                is ApiResult.Success -> _state.update { MyCardsState(loading = false, overview = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }
}

@Composable
fun MyCardsRoute(
    onBack: () -> Unit,
    onAddCard: () -> Unit,
    onAnalysis: (userCardId: Long) -> Unit,
    onCardDetail: (userCardId: Long) -> Unit,
    viewModel: MyCardsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    var all by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    // 회색 바탕: 머리(카드 n장 · 한 줄) → 흰 카드 하나에 줄마다 선 — 프로토타입 H-03
    FlowScreen(
        title = "내 카드",
        onBack = onBack,
        cards = true,
        actions = { IconButton(onClick = onAddCard) { Icon(YamuIcons.AddCard, contentDescription = "카드 추가", tint = c.text1) } },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())) {
            val o = s.overview
            when {
                o == null && s.loading -> YamuCard { repeat(4) { SkeletonBlock(height = 52.dp); Spacer(Modifier.height(YamuSpacing.x3)) } }
                o == null -> YamuCard { ErrorState(s.error ?: "카드를 불러오지 못했어요", onRetry = viewModel::refresh) }
                o.all.isEmpty() -> YamuCard { EmptyState("연결한 카드가 없어요", actionText = "카드 추가", onAction = onAddCard) }
                else -> {
                    val achieved = o.all.count { it.achieved && !it.noTarget }
                    Column(Modifier.padding(start = YamuSpacing.screen, end = YamuSpacing.screen, top = 8.dp, bottom = 20.dp)) {
                        Text("카드 ${o.all.size}장", style = YamuTheme.typography.headlineS, color = c.text1)
                        Spacer(Modifier.height(YamuSpacing.x1))
                        Text(listOf("실적 달성 $achieved", "아직 기준 전 ${o.pending.size}").joinToString(" · "), style = YamuTheme.typography.sub, color = c.text3)
                        // 새로고침이 실패해도 마지막 값은 그대로 — 이유와 언제 값인지(홈과 같은 한 줄)
                        s.error?.let { Text(staleLine(it, o.syncedAtIso), style = YamuTheme.typography.bodyS, color = c.warning) }
                    }
                    YamuCard(contentPadding = PaddingValues(vertical = YamuSpacing.x1)) {
                        // 확인이 필요한 카드가 없으면 나머지를 바로 보여 준다
                        val showOthers = all || o.pending.isEmpty()
                        val rows = o.pending.map { it to true } + (if (showOthers) o.others.map { it to false } else emptyList())
                        rows.forEachIndexed { i, (card, pending) ->
                            if (i > 0) HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
                            CardLine(card, onClick = when {
                                pending -> ({ onAnalysis(card.userCardId) })
                                card.noTarget -> null
                                else -> ({ onCardDetail(card.userCardId) })
                            })
                        }
                        if (o.pending.isNotEmpty() && o.others.isNotEmpty()) {
                            HorizontalDivider(thickness = 1.dp, color = c.outlineVariant)
                            ExpandToggle(if (all) "접기" else "모두 보기", expanded = all, onToggle = { all = !all }, modifier = Modifier.padding(start = YamuSpacing.x5, top = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

/** 카드 1장 = 1줄(카드 그림 · 이름 · 상태 태그 · ›). 분석 화면이 없는 줄(실적 조건 없음)은 누를 수 없다 */
@Composable
private fun CardLine(card: CardPerf, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    val tag = when {
        card.noTarget -> "실적 조건 없음" to TagTone.Neutral
        card.achieved -> "달성 완료" to TagTone.Success
        else -> card.guide?.let { it.label to it.tone() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { }
            .padding(start = YamuSpacing.x5, end = 12.dp, top = YamuSpacing.x2, bottom = YamuSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x3),
    ) {
        CardListThumb(card.brand, card.nickname ?: card.name)   // name은 카드사 이름 — 그림은 상품명(nickname)으로 찾는다
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
            Text(card.name, style = YamuTheme.typography.titleS, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            tag?.let { (label, tone) -> StatusTag(label, tone = tone, guide = true) }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3)
    }
}
