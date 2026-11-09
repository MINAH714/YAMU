package com.yamu.app.core.designsystem.component

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** 모든 컴포넌트 · 화면 미리보기: 라이트 · 다크 · 글자 1.3배 */
@Preview(name = "라이트", showBackground = true)
@Preview(name = "다크", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "글자 1.3배", showBackground = true, fontScale = 1.3f)
annotation class YamuPreviews
