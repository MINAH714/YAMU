package com.yamu.app.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset

/**
 * 보이는 모양과 차지하는 자리는 그대로 두고 누르는 칸만 위아래로 [extra]씩 넓힌다(S15P31E103-513, 터치 칸 48dp).
 * [touch](clickable · selectable)가 넓힌 칸 전체를 받고, 그 안쪽으로 [extra]만큼 들어간 곳부터가 보이는 모양이다.
 *
 * ```
 * Modifier
 *     .overflowTouch(7.dp, Modifier.clickable(interaction, indication = null, onClick = onClick))
 *     .clip(shape).background(color)
 *     .indication(interaction, LocalIndication.current)   // 물결은 보이는 모양 안에만
 * ```
 *
 * 넓힌 칸은 위아래 이웃 쪽으로 넘친다. 위아래로 [extra]보다 가깝게 다른 누르는 칸이 붙어 있으면(빈틈 없이 쌓인 줄)
 * 겹친 곳을 이웃이 받으니, 그런 곳은 줄 높이를 48dp로 키운다(KeyValueRow).
 */
fun Modifier.overflowTouch(extra: Dp, touch: Modifier): Modifier =
    layout { measurable, constraints ->
        val e = extra.roundToPx()
        val p = measurable.measure(constraints.offset(vertical = 2 * e))
        layout(p.width, (p.height - 2 * e).coerceAtLeast(0)) { p.place(0, -e) }
    }
        .then(touch)
        .padding(vertical = extra)
