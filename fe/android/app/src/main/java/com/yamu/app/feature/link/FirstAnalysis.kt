package com.yamu.app.feature.link

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.designsystem.component.AmountText
import com.yamu.app.core.designsystem.component.BottomCta
import com.yamu.app.core.designsystem.component.CharacterImage
import com.yamu.app.core.designsystem.component.CharacterSize
import com.yamu.app.core.designsystem.component.ErrorState
import com.yamu.app.core.designsystem.component.EstimateTag
import com.yamu.app.core.designsystem.component.PrimaryButton
import com.yamu.app.core.designsystem.component.SkeletonBlock
import com.yamu.app.core.designsystem.component.StatusTag
import com.yamu.app.core.designsystem.component.TagTone
import com.yamu.app.core.designsystem.component.TextButtonGrey
import com.yamu.app.core.designsystem.component.YamuPose
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.format.toMonthDay
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.home.data.ForecastSummary
import com.yamu.app.feature.home.data.HomeRepository
import com.yamu.app.feature.home.data.HomeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FirstAnalysisState(val loading: Boolean = true, val summary: HomeSummary? = null, val failed: Boolean = false) {
    /**
     * 받았지만 그릴 것이 하나도 없다 — 예상 잔액(기준 계좌가 없으면 null) · 할 일 · 오늘 쓸 카드가 모두 없음.
     * 이때 "나왔어요" 제목 아래가 비지 않게 따로 말한다(S15P31E103-435)
     */
    val nothingToShow: Boolean
        get() = summary != null && summary.forecast == null && summary.todos.isEmpty() && summary.todayCard == null

    /** 제목은 불러오는 중 · 실패 · 빈 결과 · 성공마다 따로 — 실패했는데 "나왔어요"가 보이지 않게(B-09) */
    val title: String
        get() = when {
            loading -> "야무가 계산 중이에요"
            failed || summary == null -> "첫 분석을 불러오지 못했어요"
            nothingToShow -> "아직 분석할 거래가 적어요"
            else -> "첫 분석이 나왔어요"
        }
}

/** 실패 본문 — 아래 두 버튼으로 홈에 갈 수 있다는 것까지. 404(홈 API 없음)도 같은 글을 쓴다 */
internal const val FIRST_ANALYSIS_FAILED = "다시 시도하거나\n아래 버튼으로 먼저 시작해도 돼요"

/** 빈 결과 본문 — 무엇을 하면 어디에 무엇이 보이는지 */
internal const val FIRST_ANALYSIS_EMPTY = "결제하거나 가계부에 수입·지출을 넣으면\n홈에서 예상 잔액과 챙길 일을 알려 드려요"

/** B-07 첫 분석 — 홈과 같은 서버 값(#58): 예상 잔액(월급 전 가장 낮을 때) · 할 일 · 오늘 쓸 카드. 앱은 계산하지 않음 */
@HiltViewModel
class FirstAnalysisViewModel @Inject constructor(private val home: HomeRepository) : ViewModel() {
    private val _state = MutableStateFlow(FirstAnalysisState())
    val state: StateFlow<FirstAnalysisState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            when (val r = home.loadHome()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, summary = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}

/**
 * [알림 켜고 시작하기] → B-07b 시스템 권한 창(Android 13+) → 허용 여부와 관계없이 홈.
 * [알림 없이 홈으로] · 시스템 뒤로 = 홈(스택 비움).
 */
@Composable
fun FirstAnalysisRoute(onFinish: () -> Unit, viewModel: FirstAnalysisViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onFinish() }
    val enableAndStart = {
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onFinish()
    }
    BackHandler(onBack = onFinish)
    val c = YamuTheme.colors
    Scaffold(
        bottomBar = {
            BottomCta(secondary = { TextButtonGrey("알림 없이 홈으로", onClick = onFinish, modifier = Modifier.fillMaxWidth()) }) {
                PrimaryButton("알림 켜고 시작하기", onClick = enableAndStart)
            }
        },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            // 위 32 · 캐릭터(96) 아래 12(프로토타입 B-07 .hd · .ch)
            Spacer(Modifier.height(32.dp))
            CharacterImage(YamuPose.Chart, size = CharacterSize.M, animate = true)
            Spacer(Modifier.height(12.dp))
            val summary = s.summary
            Text(s.title, style = YamuTheme.typography.headlineS, color = c.text1)
            Spacer(Modifier.height(24.dp))
            when {
                s.loading -> repeat(3) {
                    SkeletonBlock(height = 56.dp)
                    Spacer(Modifier.height(YamuSpacing.x3))
                }
                s.failed || summary == null -> ErrorState(FIRST_ANALYSIS_FAILED, onRetry = viewModel::load)
                s.nothingToShow -> Text(FIRST_ANALYSIS_EMPTY, style = YamuTheme.typography.bodyL, color = c.text2)
                else -> AnalysisRows(summary)
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

@Composable
private fun AnalysisRows(summary: HomeSummary) {
    val c = YamuTheme.colors
    Column(Modifier.fillMaxWidth()) {
        summary.forecast?.let { f -> ForecastBlock(f) }
        summary.todos.firstOrNull()?.let { todo ->
            Spacer(Modifier.height(28.dp))   // 태그 아래 16 + 두 줄 목록 줄(64dp) 위 12
            // 프로토타입 모양: 제목 + '잠정' 태그 / 아래 짧은 회색 줄. 서버 #58은 title · tag만 주므로 tag를 아래 줄로(14-6 ⑤)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(todo.title, style = YamuTheme.typography.titleM, color = c.text1, modifier = Modifier.weight(1f, fill = false))
                if (summary.forecast?.isProvisional == true) StatusTag("잠정")
            }
            todo.tag?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = YamuTheme.typography.sub, color = c.text3)
            }
        }
        summary.todayCard?.let { card ->
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("오늘 쓸 카드", style = YamuTheme.typography.titleM, color = c.text1)
                    card.benefit?.let { Text(it, style = YamuTheme.typography.sub, color = c.text3) }
                }
                Text(card.cardName, style = YamuTheme.typography.value, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
        }
    }
}

@Composable
private fun ForecastBlock(f: ForecastSummary) {
    val c = YamuTheme.colors
    // 줄 높이 24 — 프로토타입은 이 줄에 › 아이콘(24)이 있어 높이가 24(앱은 ›를 뺐지만 자리는 같게)
    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.label, style = YamuTheme.typography.titleS, color = c.text2)
        if (f.isEstimate) {
            Spacer(Modifier.padding(start = 6.dp))
            EstimateTag()
        }
    }
    Spacer(Modifier.height(4.dp))
    AmountText(f.amount, shortage = f.shortageFrom != null)
    val sub = when {
        f.shortageFrom != null -> "${f.shortageFrom.toMonthDay()}부터 부족해요"
        f.incomeDate != null -> "월급날(${f.incomeDate.toMonthDay()})까지 부족 없음"
        else -> null
    }
    sub?.let {
        Spacer(Modifier.height(8.dp))
        StatusTag(it, tone = if (f.shortageFrom != null) TagTone.Error else TagTone.Success)
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = YamuTheme.colors.outlineVariant)
    // 위 10 · 아래 11 — 프로토타입 목록 줄 사이(12 + 12, 선 두께 없음)에서 '잠정' 태그 줄 높이(24)만큼 덜어 낸다
    Spacer(Modifier.height(11.dp))
}
