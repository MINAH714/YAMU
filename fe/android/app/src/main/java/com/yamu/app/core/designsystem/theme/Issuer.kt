package com.yamu.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** 카드사 짧은 이름 · 대표 색 — 테마(라이트 · 다크)와 무관하게 고정. GUIDE '카드사 · 은행 이름과 색' */
enum class Issuer(val shortName: String, val background: Color, val content: Color) {
    SHINHAN("신한", Color(0xFF0046FF), Color.White),
    KB("KB국민", Color(0xFFFFBC00), Color(0xFF3B2E00)),
    WOORI("우리", Color(0xFF0083CA), Color.White),
    NH("NH농협", Color(0xFF00A651), Color.White),
    HANA("하나", Color(0xFF008C8C), Color.White),
    KB_CHECK("KB국민", Color(0xFF60584C), Color.White),
}
