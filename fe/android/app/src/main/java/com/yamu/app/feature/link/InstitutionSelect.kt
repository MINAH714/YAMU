package com.yamu.app.feature.link

import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import com.yamu.app.core.designsystem.component.FlowStep
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.FlowScreen
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.ui.LocalSnackbar
import com.yamu.app.core.ui.userMessage
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.link.data.Institution
import com.yamu.app.feature.link.data.InstitutionType
import com.yamu.app.feature.link.data.LinkPurpose
import com.yamu.app.feature.link.data.LinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InstitutionSelectState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val userName: String = "야무",
    val mine: List<Institution> = emptyList(),     // 내 명의 있음 — 미리 고름
    val others: List<Institution> = emptyList(),   // 내 명의 없음 — 접어 둠
    val selected: Set<String> = emptySet(),
    val starting: Boolean = false,
    val message: String? = null,                   // 스낵바 한 번
    val startedSessionId: String? = null,          // 세션이 생기면 다음 화면으로
)

/** C-01(가입 뒤 — 여러 곳) · C-11(카드 추가 — 카드사 한 곳) 공통 */
abstract class InstitutionSelectBase(
    private val link: LinkRepository,
    private val auth: AuthRepository,
    private val type: InstitutionType?,
    private val purpose: String,
    private val single: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(InstitutionSelectState())
    val state: StateFlow<InstitutionSelectState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            val name = auth.session().name ?: "야무"
            when (val r = link.institutions(type)) {
                is ApiResult.Failure -> _state.update { it.copy(loading = false, loadError = r.error.userMessage(), userName = name) }
                is ApiResult.Success -> {
                    val (mine, others) = r.data.partition { it.hasMyProducts && it.selectable }
                    _state.update {
                        it.copy(
                            loading = false,
                            userName = name,
                            mine = mine,
                            others = others,
                            selected = if (single) emptySet() else mine.map { m -> m.id }.toSet(),
                        )
                    }
                }
            }
        }
    }

    fun toggle(item: Institution) {
        val s = _state.value
        if (item !in s.mine) {
            return _state.update { it.copy(message = "${item.brand.name}에는 ${s.userName}님 명의 상품이 없어요") }
        }
        _state.update {
            val next = when {
                item.id in it.selected -> it.selected - item.id
                single -> setOf(item.id)
                else -> it.selected + item.id
            }
            it.copy(selected = next)
        }
    }

    fun start() {
        val s = _state.value
        if (s.selected.isEmpty() || s.starting) return
        viewModelScope.launch {
            _state.update { it.copy(starting = true) }
            when (val r = link.startSession(s.selected.toList(), purpose)) {
                is ApiResult.Success -> _state.update { it.copy(starting = false, startedSessionId = r.data.id) }
                is ApiResult.Failure -> _state.update { it.copy(starting = false, message = r.error.userMessage()) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
    fun navigated() = _state.update { it.copy(startedSessionId = null) }
}

@HiltViewModel
class InstitutionSelectViewModel @Inject constructor(link: LinkRepository, auth: AuthRepository) :
    InstitutionSelectBase(link, auth, type = null, purpose = LinkPurpose.ONBOARDING, single = false)

@HiltViewModel
class AddCardInstitutionViewModel @Inject constructor(link: LinkRepository, auth: AuthRepository) :
    InstitutionSelectBase(link, auth, type = InstitutionType.CARD, purpose = LinkPurpose.CARD_ADD, single = true)

/** C-01 연결할 은행 · 카드사 고르기 */
@Composable
fun InstitutionSelectRoute(onBack: (() -> Unit)?, onStarted: (linkSessionId: String) -> Unit, viewModel: InstitutionSelectViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    InstitutionSelectContent(
        s = s,
        appBarTitle = "계좌 · 카드 연결",
        headline = "연결할 은행 · 카드사를\n골라 주세요",
        step = FlowStep(1, 2),
        sub = if (s.mine.isEmpty()) "${s.userName}님 명의 계좌 · 카드를 찾지 못했어요" else "${s.userName}님 명의가 있는 ${s.mine.size}곳을 골라 뒀어요",
        othersLabel = "다른 은행 · 카드사",
        buttonText = "본인 확인하기",
        onBack = onBack,
        onStarted = onStarted,
        vm = viewModel,
    )
}

/** 라디오 줄 — 카드사 칩 · 이름 · 오른쪽 라디오(56dp, 프로토타입 C-11) */
@Composable
private fun RadioRow(brand: com.yamu.app.feature.link.data.Brand, selected: Boolean, onSelect: () -> Unit) {
    val c = YamuTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandMark(brand, Modifier.width(56.dp), large = true)   // 프로토타입 .iss > .cw 56dp
        Text(brand.name, style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.outline))
    }
}

/** C-11 카드 추가 — 카드사 한 곳 */
@Composable
fun AddCardInstitutionRoute(onBack: () -> Unit, onStarted: (linkSessionId: String) -> Unit, viewModel: AddCardInstitutionViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val picked = s.mine.firstOrNull { it.id in s.selected }
    InstitutionSelectContent(
        s = s,
        appBarTitle = "카드 추가",
        headline = "새 카드가 있는 카드사를\n골라 주세요",
        radioList = true,
        sub = "카드 번호 없이 내 카드를 찾아요",
        othersLabel = "카드사 더 보기",
        buttonText = picked?.let { "${it.brand.name}에서 내 카드 찾기" } ?: "카드사를 골라 주세요",
        onBack = onBack,
        onStarted = onStarted,
        vm = viewModel,
    )
}

@Composable
private fun InstitutionSelectContent(
    s: InstitutionSelectState,
    appBarTitle: String,
    headline: String,
    sub: String,
    othersLabel: String,
    buttonText: String,
    onBack: (() -> Unit)?,
    onStarted: (String) -> Unit,
    vm: InstitutionSelectBase,
    step: FlowStep? = null,
    radioList: Boolean = false,          // C-11: 한 곳만 — 라디오 목록 3곳 + '카드사 더 보기'
) {
    val snackbar = LocalSnackbar.current
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it) }
        vm.messageShown()
    }
    LaunchedEffect(s.startedSessionId) {
        s.startedSessionId?.let {
            vm.navigated()
            onStarted(it)
        }
    }
    var othersOpen by rememberSaveable { mutableStateOf(false) }
    val c = YamuTheme.colors
    FlowScreen(
        title = appBarTitle,
        onBack = onBack,
        step = step,
        bottomBar = {
            if (!s.loading && s.loadError == null) {
                BottomCta { PrimaryButton(buttonText, onClick = vm::start, enabled = s.selected.isNotEmpty(), loading = s.starting) }
            }
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(headline, style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(6.dp))
            Text(sub, style = YamuTheme.typography.bodyM, color = c.text2)
            Spacer(Modifier.height(if (radioList) 12.dp else 20.dp))   // 머리 아래 — 타일 20(C-01 .hd) · 라디오 목록 12(C-11 .hd)
            when {
                s.loading -> repeat(2) {
                    SkeletonBlock(height = 96.dp)
                    Spacer(Modifier.height(YamuSpacing.x2))
                }
                s.loadError != null -> ErrorState(s.loadError, onRetry = vm::load)
                radioList -> {
                    val all = s.mine + s.others
                    val shown = if (othersOpen) all else s.mine.take(3).ifEmpty { all.take(3) }
                    shown.forEach { item ->
                        RadioRow(item.brand, selected = item.id in s.selected, onSelect = { vm.toggle(item) })
                    }
                    if (all.size > shown.size || othersOpen) FoldToggle(othersLabel, expanded = othersOpen, onToggle = { othersOpen = !othersOpen })
                }
                else -> {
                    TileGrid(
                        items = s.mine,
                        brand = { it.brand },
                        selected = { it.id in s.selected },
                        onToggle = vm::toggle,
                        stateText = { if (it.linkedCount > 0) "연결됨" else "내 명의 있음" },
                    )
                    if (s.others.isNotEmpty()) {
                        val open = othersOpen || s.mine.isEmpty()
                        if (s.mine.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))   // 타일과 '다른 은행 · 카드사' 사이(.others margin-top 12)
                            FoldToggle(othersLabel, expanded = othersOpen, onToggle = { othersOpen = !othersOpen })
                        }
                        if (open) {
                            TileGrid(
                                items = s.others,
                                brand = { it.brand },
                                selected = { false },
                                onToggle = vm::toggle,
                                dimmed = { true },
                                stateText = { "내 명의 없음" },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}
