package com.yamu.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * 브랜드(메인) 색 — 메인 색 교체는 여기만.
 * 2026-10-23 회의: 토스 파랑에서 바꾼다. 확정 전엔 지금 값.
 * 후보: 청록 0xFF0B8576(흰 글자 4.53:1) · 강조 그래픽 0xFF12A594.
 */
internal object BrandColor {
    val Light = Color(0xFF3182F6)
    val LightContainer = Color(0xFFE8F3FF)
    val OnLightContainer = Color(0xFF1B64DA)
    val LightInverse = Color(0xFF90C2FF)

    val Dark = Color(0xFF4593FC)
    val DarkContainer = Color(0xFF1C3256)
    val OnDarkContainer = Color(0xFFC9E2FF)
    val DarkInverse = Color(0xFF3182F6)

    /** 컴포넌트 갤러리의 '브랜드 색 미리보기' 후보 */
    val CandidateTeal = Color(0xFF0B8576)
}
