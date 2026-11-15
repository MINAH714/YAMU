import type { Transition } from 'motion/react'

// 모션 명세 공통 토큰(M-TOKENS)의 POS 쪽 값. CSS에서 쓰는 값은 tokens.css에 같은 이름으로 둔다

/** tokens.css의 --ease와 같은 값 */
export const EASE = [0.2, 0, 0, 1] as const

/** 출발과 도착이 둘 다 보이는 움직임(트레이 닫힘, 링 그리기, 헤일로 숨). --ease-in-out */
export const EASE_IN_OUT = [0.4, 0, 0.2, 1] as const

export const FAST: Transition = { duration: 0.15, ease: EASE }

/**
 * spring.tray. 튕기지 않고 230ms에 99%까지 간다.
 * motion은 duration 끝에서 99.9%에 닿게 강성을 정하므로 0.32초가 230ms에 99%다(임계 감쇠).
 */
export const TRAY_SPRING: Transition = { type: 'spring', bounce: 0, duration: 0.32 }

/** dur.trayClose. 트레이가 버튼 자리로 줄어든다 */
export const TRAY_CLOSE: Transition = { duration: 0.16, ease: EASE_IN_OUT }

/** dur.colorShift(초). 트레이 바탕이 파랑 ↔ 흰색으로 바뀐다 */
export const COLOR_SHIFT_S = 0.12

/** screen.swap. 화면 통째 교체, 결제 요청이 나갈 때 트레이가 제자리에서 사라지고 다시 나타날 때 */
export const SWAP: Transition = { duration: 0.2, ease: EASE }

/** 모션 줄이기. 이동·크기·회전은 끄고 투명도와 색만 이 안에 바꾼다 */
export const REDUCED: Transition = { duration: 0.15, ease: EASE }

/** 대기 판 원판·캐릭터 등장. 0.92 → 1, 넘침 없음 */
export const ENTER_FROM = 0.92
export const ENTER: Transition = { duration: 0.4, ease: EASE }

/** 대기 판 링이 한 바퀴 그려지는 시간(초). 판 페이드(SWAP)가 끝날 때 시작한다 */
export const RING_S = 0.22

/** dur.halo.half_cycle. 헤일로가 0.96 ↔ 1.04를 반주기 500ms로 오간다 */
export const HALO_HALF_S = 0.5
export const HALO_MIN = 0.96
export const HALO_MAX = 1.04

/** dur.countdownTick. 남은 시간 링은 250ms마다 다시 재고 그 사이를 같은 속도로 줄인다 */
export const COUNTDOWN_TICK_MS = 250

/** 남은 시간이 10초 이하로 바뀔 때 색이 넘어가는 시간(초) */
export const VALUE_COLOR_S = 0.3

/** dur.shake. 좌우 ±3 → ±2px */
export const SHAKE: Transition = { duration: 0.25, ease: 'linear' }
export const SHAKE_X = [0, 3, -3, 2, -2, 0]
export const SHAKE_TIMES = [0, 0.2, 0.44, 0.68, 0.88, 1]

/** dur.draw. 상태 아이콘의 체크·엑스가 그려지는 시간(초). 원은 그 앞에 CIRCLE_S */
export const DRAW_S = 0.28
export const CIRCLE_S = 0.2
