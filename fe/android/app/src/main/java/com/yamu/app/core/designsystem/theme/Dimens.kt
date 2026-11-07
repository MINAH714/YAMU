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

/**
 * 모션 값 한 곳. 화면은 숫자 대신 이 이름을 쓴다.
 * 모션 줄이기(LocalYamuReduceMotion)가 켜지면 이동 · 크기 · 회전은 끄고 투명도만 SHORT_MS 안에 바꾼다.
 */
object YamuMotion {
    /** 기본 곡선 — 빨리 출발해 부드럽게 멈춤 */
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** 들어오며 퍼지는 것(리플 · 파티클) — 출발이 더 빠름 */
    val out = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** 오가는 것 — 양 끝이 부드러움 */
    val inOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val SHORT_MS = 150
    const val BASE_MS = 200

    /** 캐릭터 조용한 등장: 0.92 → 1 + 페이드, 넘침 없음 */
    const val CHARACTER_ENTER_MS = 400
    const val CHARACTER_ENTER_FROM = 0.92f

    /** 축하 팝(카드 등록 · 가입 완료처럼 드문 순간에만): 눌림 → 정점 → 안착 */
    const val POP_MS = 1040
    const val POP_PRESS_AT = 160
    const val POP_PRESS_SCALE = 0.80f
    const val POP_PEAK_AT = 378
    const val POP_PEAK_SCALE = 1.19f
    const val POP_SETTLE_AT = 803
    const val POP_SETTLE_SCALE = 0.993f
    const val POP_TILT_DEG = -6f
    const val POP_TILT_BACK_DEG = 0.7f
    const val POP_ROTATE_MS = 520

    /** 팝 뒤 고리 두 겹: 캐릭터 크기의 1.0 → 1.6배로 퍼지며 사라짐 */
    const val RIPPLE_START_MS = 160
    const val RIPPLE_MS = 330
    const val RIPPLE_MAX_SCALE = 1.6f
    const val RIPPLE_INNER_RATIO = 0.8f

    /** 파티클 7개: 캐릭터 크기의 0.8배까지 흩어지며 사라짐. 연달아 터질 때는 쉬는 시간과 횟수 제한 */
    const val PARTICLE_START_MS = 210
    const val PARTICLE_MS = 390
    const val PARTICLE_COUNT = 7
    const val PARTICLE_DISTANCE = 0.8f
    const val PARTICLE_SIZE = 0.12f
    const val PARTICLE_COOLDOWN_MS = 400
    const val PARTICLE_MAX_IN_ROW = 3
}
