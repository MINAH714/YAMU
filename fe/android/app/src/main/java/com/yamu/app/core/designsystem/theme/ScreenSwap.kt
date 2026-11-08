package com.yamu.app.core.designsystem.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/** 화면 교체 길이 — 보통 0.2초, 모션 줄이기에서는 0.15초 */
fun screenSwapMs(reduceMotion: Boolean) = if (reduceMotion) YamuMotion.SHORT_MS else YamuMotion.SCREEN_SWAP_MS

/** 들어오는 화면: 투명도만 0 → 1 */
fun screenSwapEnter(reduceMotion: Boolean): EnterTransition =
    fadeIn(tween(screenSwapMs(reduceMotion), easing = YamuMotion.standard))

/** 나가는 화면: 투명도만 1 → 0 */
fun screenSwapExit(reduceMotion: Boolean): ExitTransition =
    fadeOut(tween(screenSwapMs(reduceMotion), easing = YamuMotion.standard))
