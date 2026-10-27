package com.yamu.app.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yamu.app.core.designsystem.theme.YamuRadius
import com.yamu.app.core.designsystem.theme.YamuTheme

/**
 * 입력 칸 — 위에 이름, 회색 칸, 아래 안내 · 오류 한 줄.
 * 오류가 있으면 칸 테두리와 아래 글이 빨강.
 */
@Composable
fun YamuTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,     // 비밀번호 보기 · 자동 분류 같은 칸 안 오른쪽
    labelBackground: Color = YamuTheme.colors.surface,
) {
    val c = YamuTheme.colors
    val t = YamuTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        error != null -> c.error
        focused -> c.primary
        else -> c.outline
    }
    val shape = RoundedCornerShape(YamuRadius.input)
    Column(modifier.fillMaxWidth()) {
        // 테두리형 56dp · 라벨이 테두리 위에 걸침(프로토타입 .tf)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = t.bodyL.copy(color = c.text1),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label },
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .border(if (focused || error != null) 2.dp else 1.dp, borderColor, shape)
                            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(placeholder, style = t.bodyL, color = c.text3)
                            }
                            inner()
                        }
                        trailing?.invoke()
                    }
                },
            )
            Text(
                label,
                style = t.bodyS,
                color = when {
                    error != null -> c.error
                    focused -> c.primary
                    else -> c.text2
                },
                modifier = Modifier
                    .offset(x = 12.dp, y = (-8).dp)
                    .background(labelBackground)
                    .padding(horizontal = 4.dp),
            )
        }
        val below = error ?: supporting
        if (below != null) {
            Text(below, style = t.bodyS, color = if (error != null) c.error else c.text2, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
    }
}

/** 간편 비밀번호 칸 — 채운 수만큼 진한 점 */
@Composable
fun PinDots(filled: Int, modifier: Modifier = Modifier, length: Int = 6, error: Boolean = false) {
    val c = YamuTheme.colors
    Row(
        modifier.semantics { contentDescription = "간편 비밀번호 $length 자리 중 $filled 자리 입력" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),   // 프로토타입 .pin gap 16
    ) {
        repeat(length) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> c.error
                            i < filled -> c.primary
                            else -> c.outlineVariant
                        },
                    ),
            )
        }
    }
}
