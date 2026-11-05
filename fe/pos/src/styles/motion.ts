import type { Transition } from 'motion/react'

/** tokens.css의 --ease와 같은 값 */
export const EASE = [0.2, 0, 0, 1] as const

export const FAST: Transition = { duration: 0.15, ease: EASE }

/** 트레이와 트레이로 이어지는 금액. 튕기지 않는다 */
export const TRAY_SPRING: Transition = { type: 'spring', bounce: 0, duration: 0.32 }
