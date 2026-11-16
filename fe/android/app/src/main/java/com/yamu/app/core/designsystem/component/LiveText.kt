package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 화면 읽기(TalkBack)가 저절로 읽는 한 줄 — 결제 거절 · 결과 불명 · 연결 불안정처럼 화면이 바뀌며 생기는 문장(S15P31E103-513).
 * [text]가 null이면 줄이 보이지 않는다. [top]은 문장이 있을 때만 위에 띄우는 간격.
 *
 * Compose에서 새로 생긴 줄은 liveRegion을 달아도 TalkBack이 읽지 않는다(부모 줄이 바뀐 것으로 알림이 가서).
 * 있던 줄의 글자가 바뀔 때만 읽으므로(11/16 에뮬레이터 TalkBack 17로 확인) 두 가지를 지킨다.
 * - 문장이 없을 때도 줄을 지우지 않고 1px 빈 글자로 남겨 둔다. `if (…) LiveText(…)`처럼 감싸면 다시 읽히지 않는다.
 * - 화면 읽기에 건네는 글자는 [SPOKEN_DELAY_MS] 늦게 바꾼다. 가려 두었던 화면(결제 대기 판 아래)이 다시 보이는 순간
 *   문장이 같이 들어오면 그 줄도 새 줄로 올라가서, 빈 줄이 먼저 올라간 뒤 글자가 바뀌게 한다. 보이는 글자는 바로 바뀐다.
 * - 화면이 화면 읽기에서 가려져 있는 동안은 [speakable]을 false로 준다. 보이는 글자는 그대로 두고 화면 읽기 쪽만 비워 두었다가
 *   다시 보일 때 채워서 읽게 한다.
 */
@Composable
fun LiveText(
    text: String?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    textAlign: TextAlign? = null,
    speakable: Boolean = true,
) {
    val hidden = with(LocalDensity.current) { 1.toDp() }
    val target = text.takeIf { speakable }
    var spoken by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target != null) delay(SPOKEN_DELAY_MS)
        spoken = target
    }
    Text(
        text.orEmpty(),
        style = style,
        color = color,
        textAlign = textAlign,
        modifier = modifier
            .then(if (text == null) Modifier.size(hidden) else Modifier.padding(top = top))
            .clearAndSetSemantics {
                spoken?.let { this.text = AnnotatedString(it) }
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/** 화면 읽기에 건네는 글자를 늦추는 시간 — Compose가 접근성 트리 변화를 모아 보내는 간격(100ms)보다 넉넉히 */
private const val SPOKEN_DELAY_MS = 500L
