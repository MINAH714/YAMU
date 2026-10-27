package com.yamu.app.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/** 화면을 옮겨도 이어지는 스낵바(실행 취소) — 바깥 틀(YamuApp) 하나에서 띄운다 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("YamuApp 안에서만 쓸 수 있어요") }
