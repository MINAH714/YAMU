package com.yamu.app.feature.my

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.yamu.app.core.designsystem.component.ExpandToggle
import com.yamu.app.core.designsystem.component.LinkButton
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
import com.yamu.app.feature.link.data.CardArtRef
import com.yamu.app.feature.link.data.CardArtRefsLoader
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.my.data.AppNotification
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.NotiTarget
import com.yamu.app.feature.my.data.NotificationsPage
import com.yamu.app.feature.my.data.notificationGroup
import com.yamu.app.feature.my.data.notificationTime
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.tone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    val page: Int = 0,                       // 지금까지 읽은 쪽(20개씩)
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val moreError: String? = null,
)

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val my: MyRepository) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsState())
    val state: StateFlow<NotificationsState> = _state.asStateFlow()
    /** 새로고침 · 더 보기가 함께 쓰는 자리 — 새로고침은 도는 더 보기를 취소하고, 무엇이든 도는 동안 더 보기는 무시한다 */
    private var listJob: Job? = null
    /** [listJob]이 새로고침인지(모두 읽음이 끊었을 때 끝나고 다시 받을지 정한다) */
    private var listJobIsRefresh = false

    /**
     * '모두 읽음'을 서버에 보내는 중 — 이 사이엔 쪽을 받지 않는다(!327 리뷰). 겹치면 모두 읽음 전에 보낸 쪽 응답의 unread가
     * 성공한 읽음 처리를 뒤집거나, 실패 되돌리기가 그사이 붙은 쪽을 지운다. viewModelScope는 메인 스레드 하나라 Boolean으로 충분하다
     */
    private var readingAll = false
    /** 모두 읽음 중에 미루거나 끊은 새로고침 — 끝나면 한 번 받는다 */
    private var refreshAfterReadAll = false

    /**
     * 화면에 돌아올 때마다 — 더 보기로 읽은 쪽까지 1쪽부터 다시 받는다(알림을 눌러 다녀와도 줄 수 · 스크롤 자리가 그대로).
     * 다 받은 뒤 한 번에 바꾸고, 중간에 실패하면 보던 목록을 둔다(-497, 환급 목록과 같은 방식). 모두 읽음 중이면 끝난 뒤로 미룬다
     */
    fun refresh() {
        if (readingAll) {
            refreshAfterReadAll = true
            return
        }
        listJob?.cancel()
        listJobIsRefresh = true
        val pages = _state.value.page.coerceAtLeast(1)
        listJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null, loadingMore = false, moreError = null) }
            val rows = mutableListOf<AppNotification>()
            var read = 0
            var last: NotificationsPage? = null
            while (read < pages) {
                when (val r = my.notifications(page = read + 1)) {
                    is ApiResult.Success -> {
                        rows += r.data.items
                        last = r.data
                        read++
                        if (!r.data.hasNext) break
                    }
                    is ApiResult.Failure -> {
                        _state.update { it.copy(loading = false, error = r.error.userMessage()) }
                        return@launch
                    }
                }
            }
            _state.update {
                it.copy(loading = false, unread = last?.unread ?: 0, items = rows.distinctBy { n -> n.id }, page = read, hasNext = last?.hasNext == true)
            }
        }
    }

    /**
     * 다음 쪽을 붙인다. 쪽 사이에 새 알림이 오면 서버 쪽이 밀려 같은 알림이 다시 오니 먼저 받은 쪽(읽음 표시가 반영된 줄)을 남긴다.
     * 연타 · 새로고침 중 · 모두 읽음 중이면 무시
     */
    fun loadMore() {
        val s = _state.value
        if (readingAll || listJob?.isActive == true || !s.hasNext) return
        val next = s.page + 1
        _state.update { it.copy(loadingMore = true, moreError = null) }
        listJobIsRefresh = false
        listJob = viewModelScope.launch {
            val r = my.notifications(page = next)
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(
                        loadingMore = false,
                        unread = r.data.unread,
                        items = (it.items + r.data.items).distinctBy { n -> n.id },
                        page = next,
                        hasNext = r.data.hasNext,
                    )
                    is ApiResult.Failure -> it.copy(loadingMore = false, moreError = r.error.userMessage())
                }
            }
        }
    }

    /** 누르면 읽음(#50) — 화면은 바로 바꾸고 서버 실패는 다음 새로고침에 맞춰진다 */
    fun open(n: AppNotification) {
        if (n.read) return
        _state.update { s -> s.copy(unread = (s.unread - 1).coerceAtLeast(0), items = s.items.map { if (it.id == n.id) it.copy(read = true) else it }) }
        viewModelScope.launch { my.markRead(n.id) }
    }

    /**
     * 화면은 바로 모두 읽음으로 바꾸고 서버에 보낸다. 도는 쪽 받기는 끊는다 — 모두 읽음 전에 보낸 요청이라 그 응답의 unread · 읽음 표시는 옛 값이다.
     * 끊은 게 새로고침이었으면 끝난 뒤 다시 받는다(끊은 더 보기는 다시 누르면 된다). 실패하면 읽음 표시와 안 읽은 수만 되돌리고 목록 · 쪽은 지금 것을 둔다
     */
    fun readAll(onFail: (String) -> Unit) {
        if (readingAll) return
        if (listJob?.isActive == true) {
            listJob?.cancel()
            if (listJobIsRefresh) refreshAfterReadAll = true
        }
        val wasRead = _state.value.items.associate { it.id to it.read }
        val unreadBefore = _state.value.unread
        readingAll = true
        _state.update { s -> s.copy(unread = 0, items = s.items.map { it.copy(read = true) }, loadingMore = false) }
        viewModelScope.launch {
            val r = my.markAllRead()
            if (r is ApiResult.Failure) {
                _state.update { s -> s.copy(unread = unreadBefore, items = s.items.map { n -> wasRead[n.id]?.let { n.copy(read = it) } ?: n }) }
                onFail("모두 읽음으로 바꾸지 못했어요 · ${r.error.userMessage()}")
            }
            readingAll = false
            if (refreshAfterReadAll) {
                refreshAfterReadAll = false
                refresh()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationsRoute(
    onBack: () -> Unit,
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
            // 오른쪽엔 이 화면의 일만 — 알림 설정은 마이에서만 연다(S15P31E103-521). 아이콘만으로는 뜻이 안 읽혀 글자로 쓴다
            YamuTopBar("알림", onBack = onBack) {
                if (s.unread > 0) {
                    LinkButton(
                        "모두 읽음 표시",
                        onClick = { viewModel.readAll { m -> scope.launch { snackbar.showSnackbar(m) } } },
                        modifier = Modifier.padding(end = YamuSpacing.x2),
                    )
                }
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
                // 21번째부터(-497) — '이전'을 접어 두면 펼친 뒤에만 보인다. 새로 붙는 알림은 대개 '이전'이라 누르면 같이 펼친다
                if ((s.hasNext || s.moreError != null) && (older.isEmpty() || showOlder)) {
                    item(key = "more") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = YamuSpacing.screen)) {
                            if (s.hasNext) {
                                Spacer(Modifier.height(YamuSpacing.x2))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    LinkButton(if (s.loadingMore) "불러오는 중…" else "더 보기", onClick = {
                                        showOlder = true
                                        viewModel.loadMore()
                                    })
                                }
                            }
                            s.moreError?.let {
                                Text("$it · 다시 눌러 주세요", style = YamuTheme.typography.bodyS, color = c.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
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

/**
 * 위 시각(안 읽으면 앞에 파란 점) · 제목(안 읽음 Bold · 읽음 Medium) · 내용 두 줄까지.
 * 내용은 서버가 가맹점 · 금액 · 카드를 넣어 보낸다('스타벅스 4,500원을 ○○카드로 결제했어요') — 카드 승인 문자처럼 무엇이 얼마였는지 목록에서 바로 보이게
 */
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
            n.body?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = YamuTheme.typography.bodyM, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ───────────────────────── H-03 내 카드 ─────────────────────────

data class MyCardsState(val loading: Boolean = true, val overview: PerfOverview? = null, val error: String? = null)

/** 실적 관리(R-01)와 같은 값(#65) — 판정은 서버 값 그대로 */
@HiltViewModel
class MyCardsViewModel @Inject constructor(private val perf: PerformanceRepository, link: LinkRepository) : ViewModel() {
    private val _state = MutableStateFlow(MyCardsState(overview = perf.cachedOverview(), loading = perf.cachedOverview() == null))
    val state: StateFlow<MyCardsState> = _state.asStateFlow()

    /** 별명 카드의 그림 — 실적 응답엔 상품명이 없어 카드 목록에서 찾는다 */
    private val artLoader = CardArtRefsLoader(link, viewModelScope)
    val arts: StateFlow<Map<Long, CardArtRef>> = artLoader.refs

    fun refresh() {
        artLoader.ensure(_state.value.overview?.all.orEmpty().map { it.userCardId })
        viewModelScope.launch {
            _state.update { it.copy(loading = it.overview == null, error = null) }
            when (val r = perf.overview()) {
                is ApiResult.Success -> {
                    _state.update { MyCardsState(loading = false, overview = r.data) }
                    artLoader.ensure(r.data.all.map { it.userCardId })
                }
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
    val arts by viewModel.arts.collectAsStateWithLifecycle()
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
                            CardLine(card, arts[card.userCardId], onClick = when {
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
private fun CardLine(card: CardPerf, art: CardArtRef?, onClick: (() -> Unit)?) {
    val c = YamuTheme.colors
    val tag = when {
        card.noTarget -> "실적 조건 없음" to TagTone.Neutral
        card.achieved -> "달성 완료" to TagTone.Success
        else -> card.guide?.let { g -> g.label?.let { it to g.tone() } }
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
        // name은 카드사 이름 — 그림은 카드 목록의 상품명으로, 목록에 없으면 nickname(별명이 없으면 상품명)으로 찾는다
        CardListThumb(card.brand, art?.productName ?: card.nickname ?: card.name, dimmed = art?.dimmed == true)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YamuSpacing.x2)) {
            Text(card.name, style = YamuTheme.typography.titleS, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            tag?.let { (label, tone) -> StatusTag(label, tone = tone, guide = true) }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.text3)
    }
}
