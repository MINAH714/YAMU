package com.yamu.app.feature.auth

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yamu.app.R
import com.yamu.app.core.designsystem.component.YamuPreviews
import com.yamu.app.core.designsystem.component.YamuTopBar
import com.yamu.app.core.designsystem.theme.YamuSpacing
import com.yamu.app.core.designsystem.theme.YamuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 전문을 볼 수 있는 약관(A-04 ›). 원문은 `res/raw`의 글 파일(UTF-8)이고, 서버에 약관 API가 없어 앱에 넣어 둔다(2차 QA 02).
 * 원문을 바꿀 때는 파일만 고친다. 빈 줄로 문단을 나누고, '제1조(목적)'처럼 조 · 장 · 부칙으로 시작하는 줄은 제목으로 보인다([termBlocks])
 */
enum class TermDoc(val title: String, @RawRes val raw: Int) {
    SERVICE("서비스 이용약관", R.raw.terms_service),
    PRIVACY("개인정보 수집·이용", R.raw.terms_privacy),
    E_FINANCE("전자금융거래 이용약관", R.raw.terms_e_finance),
    MARKETING("마케팅 정보 수신", R.raw.terms_marketing),
    LOCATION("위치기반서비스 이용약관", R.raw.terms_location),
    ;

    companion object {
        /** 경로 값(이름) → 약관. 모르는 이름이면 null */
        fun of(name: String): TermDoc? = entries.firstOrNull { it.name == name }
    }
}

/** 약관 전문의 한 덩어리 — [heading]이면 조 제목 */
internal data class TermBlock(val text: String, val heading: Boolean)

/** '제1조(목적)' · '제 2 장 총칙' · '부칙' · '부칙 (2026. 11. 13.)' — 본문 중간의 '제1조'나 '부칙에 따라'는 아니다 */
private val ARTICLE = Regex("^(제\\s*\\d+\\s*[조장절]|부\\s*칙(\\s*[(<].*)?$)")

/** 원문 → 문단. 빈 줄로 나누고, 문단 첫 줄이 조 제목이면 그 줄만 떼어 제목으로 둔다 */
internal fun termBlocks(text: String): List<TermBlock> =
    text.replace("\r\n", "\n")
        .split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { block ->
            val first = block.substringBefore('\n').trim()
            val rest = block.substringAfter('\n', "").trim()
            if (ARTICLE.containsMatchIn(first)) listOfNotNull(TermBlock(first, true), rest.takeIf { it.isNotEmpty() }?.let { TermBlock(it, false) })
            else listOf(TermBlock(block, false))
        }

/**
 * A-04a 약관 전문 — 읽기만 한다. 동의는 약관 동의 화면의 체크로만 하고, 돌아가면 체크는 그대로다.
 * [name]은 경로 값([TermDoc] 이름). 모르는 이름이면(앱을 고친 뒤 옛 화면 기록이 되살아남) 바로 돌아간다
 */
@Composable
fun TermDocRoute(name: String, onBack: () -> Unit) {
    val doc = TermDoc.of(name)
    if (doc == null) {
        LaunchedEffect(Unit) { onBack() }
    } else {
        val context = LocalContext.current
        val text by produceState<String?>(null, doc) {
            value = withContext(Dispatchers.IO) { context.resources.openRawResource(doc.raw).bufferedReader().use { it.readText() } }
        }
        TermDocScreen(doc.title, text?.let(::termBlocks), onBack)
    }
}

@Composable
internal fun TermDocScreen(title: String, blocks: List<TermBlock>?, onBack: () -> Unit) {
    val c = YamuTheme.colors
    Scaffold(
        topBar = { YamuTopBar(title, onBack = onBack) },
        containerColor = c.surface,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .consumeWindowInsets(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = YamuSpacing.screen),
        ) {
            Spacer(Modifier.height(20.dp))
            blocks.orEmpty().forEachIndexed { i, block ->
                if (block.heading) {
                    Text(
                        block.text,
                        style = YamuTheme.typography.titleS,
                        color = c.text1,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 12.dp, bottom = 8.dp).semantics { heading() },
                    )
                } else {
                    Text(block.text, style = YamuTheme.typography.bodyM, color = c.text2, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            Spacer(Modifier.height(YamuSpacing.x6))
        }
    }
}

@YamuPreviews
@Composable
private fun TermDocPreview() = YamuTheme {
    TermDocScreen(
        "서비스 이용약관",
        termBlocks("제1조(목적)\n이 약관은 야무 서비스 이용 조건을 정합니다.\n\n제2조(정의)\n① '서비스'란 야무 앱을 말합니다."),
        onBack = {},
    )
}
