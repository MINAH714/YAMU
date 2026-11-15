import { useReducedMotion } from 'motion/react'

/**
 * 개발 서버에서 주소에 ?reduce를 붙이면 OS 설정과 상관없이 모션 줄이기로 본다.
 * 녹화로 모션 줄이기 박자를 확인할 때 쓴다. 배포 번들에서는 false로 정해진다.
 */
export const FORCE_REDUCE_MOTION = import.meta.env.DEV && new URLSearchParams(location.search).has('reduce')

/**
 * 모션 줄이기 신호. motion 컴포넌트는 MotionConfig가 이동·크기만 꺼 주지만
 * 트레이 크기(left·top·width·height)처럼 직접 움직이는 값은 이 값을 보고 투명도·색만 바꾼다.
 */
export function useReduceMotion(): boolean {
  const prefers = useReducedMotion()
  return prefers === true || FORCE_REDUCE_MOTION
}
