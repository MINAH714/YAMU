import { AnimatePresence, motion } from 'motion/react'
import { EASE } from '../styles/motion'

export type StatusIconState = 'pending' | 'success' | 'error' | 'warning'

type Props = {
  state: StatusIconState
  size?: number
  className?: string
}

const CENTER = 32
const RADIUS = 26
const DRAW = { duration: 0.28, ease: EASE }

const GLYPHS: Record<Exclude<StatusIconState, 'pending'>, string[]> = {
  success: ['M21 33 l8 8 l14 -17'],
  error: ['M23.5 23.5 L40.5 40.5', 'M40.5 23.5 L23.5 40.5'],
  warning: ['M32 20 V35', 'M32 44 V44.01'],
}

/**
 * 결과를 기다리는 스피너가 결과에 맞는 선으로 바뀐다. 장식이므로 상태는 옆 문구로 전한다.
 * 회전은 CSS라 동작 줄이기 설정이면 index.css 규칙이 멈춘다. 나머지는 선 길이와 투명도만 움직인다.
 */
export function StatusIcon({ state, size = 64, className }: Props) {
  const pending = state === 'pending'
  return (
    <svg
      className={`status-icon${className ? ` ${className}` : ''}`}
      data-state={state}
      viewBox="0 0 64 64"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {pending && <circle className="status-icon-track" cx={CENTER} cy={CENTER} r={RADIUS} />}
      <motion.circle
        className="status-icon-spin"
        cx={CENTER}
        cy={CENTER}
        r={RADIUS}
        pathLength={1}
        strokeDasharray="0.25 0.75"
        initial={false}
        animate={{ opacity: pending ? 1 : 0 }}
        transition={DRAW}
      />
      <motion.circle
        cx={CENTER}
        cy={CENTER}
        r={RADIUS}
        transform={`rotate(-90 ${CENTER} ${CENTER})`}
        initial={false}
        animate={{ pathLength: pending ? 0 : 1, opacity: pending ? 0 : 1 }}
        transition={DRAW}
      />
      <AnimatePresence initial={false}>
        {!pending &&
          GLYPHS[state].map((d, i) => (
            <motion.path
              key={`${state}-${i}`}
              d={d}
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ ...DRAW, delay: 0.1 + i * 0.08 }}
            />
          ))}
      </AnimatePresence>
    </svg>
  )
}
