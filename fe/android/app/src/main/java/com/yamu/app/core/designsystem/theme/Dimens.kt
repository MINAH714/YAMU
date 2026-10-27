package com.yamu.app.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

/** 반경 — 태그 6 · 입력 12 · 버튼 16 · 카드 20 · 시트 24 */
object YamuRadius {
    val tag = 6.dp
    val s = 8.dp
    val input = 12.dp
    val button = 16.dp
    val card = 20.dp
    val sheet = 24.dp
}

/** 간격 (당근 SEED) */
object YamuSpacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val screen = 16.dp        // 좌우 여백
    val blockGap = 12.dp      // 카드 · 덩어리 사이
    val bottomSafe = 56.dp    // 화면 맨 아래 여백
}

object YamuSize {
    val appBar = 56.dp
    val appBarLarge = 112.dp   // 큰 제목 앱바(탭 첫 화면)
    val bottomBar = 64.dp
    val button = 56.dp
    val touch = 48.dp
    val listRowMin = 72.dp      // 두 줄(제목 + 보조)
    val listRowOne = 56.dp      // 한 줄(제목만) — 프로토타입 .list-item.one
    val tag = 22.dp
    val chip = 34.dp
    val segmented = 40.dp
    val tabs = 48.dp
}

object YamuMotion {
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SHORT_MS = 150
}
