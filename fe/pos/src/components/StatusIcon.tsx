import { motion } from 'motion/react'
import { useState } from 'react'
import { useReduceMotion } from '../hooks/useReduceMotion'
import { CIRCLE_S, DRAW_S, EASE_IN_OUT, REDUCED, SHAKE, SHAKE_TIMES, SHAKE_X } from '../styles/motion'
import { useScreenEnter } from './screenEnter'

/**
 * 결과마다 끝 모양이 다르다(모션 명세 M-DONE-pos-receipt). 정지 화면에서도 결과가 갈리도록 선 모양까지 바꾼다.
 * success 원 + 체크, error 원 + 엑스, expired 회색 원 + 가로선, unconfirmed 점선 원 + 느낌표, pending 도는 원
 */
export type StatusIconState = 'pending' | 'success' | 'error' | 'expired' | 'unconfirmed'

type Props = {
  state: StatusIconState
  size?: number
  className?: string
}

const CENTER = 32
const RADIUS = 26

type Shape = { marks: string[]; draw: boolean; shake?: boolean; dashed?: boolean }

const SHAPES: Record<Exclude<StatusIconState, 'pending'>, Shape> = {
  success: { marks: ['M21 33 l8 8 l14 -17'], draw: true },
  error: { marks: ['M23.5 23.5 L40.5 40.5', 'M40.5 23.5 L23.5 40.5'], draw: true, shake: true },
  expired: { marks: ['M20 32 H44'], draw: false },
  unconfirmed: { marks: ['M32 20 V35', 'M32 44 V44.01'], draw: false, dashed: true },
}

/** 화면 교체(0.2초)가 끝난 뒤 그린다 */
const AFTER_SWAP_MS = 200
/** 엑스 두 획은 합쳐서 260ms라 원(200ms) 다음 660ms에 끝난다 */
const CROSS_S = 0.26
/** 점선 원. 둘레를 선·간격 쌍 16개로 나눠 12시에서 선이 붙어 보이지 않는다 */
const DASH = '0.03125 0.03125'

/**
 * 결제 진행·결과 아이콘. 장식이므로 상태는 옆 문구로 전한다.
 * 승인·실패만 그린다: 교체 200ms 뒤 원 200ms, 이어서 체크 280ms(엑스는 두 획이 660ms에 끝나고 아이콘이 200ms부터 250ms 흔들린다).
 * 만료·출금 미확인은 그리기 없이 나온다. 처음부터 결과 상태로 붙어도 그린다.
 * 박자는 화면 층이 들어온 시각에 맞춘다(층 밖이면 붙은 순간부터). 모션 줄이기면 그리기·흔들기 없이 150ms에 나타난다.
 * 기다리는 원의 회전은 CSS라 모션 줄이기 설정이면 index.css 규칙이 멈춘다.
 */
export function StatusIcon({ state, size = 64, className }: Props) {
  const enter = useScreenEnter()
  const reduce = useReduceMotion()
  const [start] = useState(() =>
    enter ? Math.max(0, enter.delayMs + AFTER_SWAP_MS - (performance.now() - enter.startedAt)) / 1000 : 0,
  )
  const classes = `status-icon${className ? ` ${className}` : ''}`

  if (state === 'pending') {
    return (
      <svg className={classes} data-state={state} {...SVG} width={size} height={size}>
        <circle className="status-icon-track" cx={CENTER} cy={CENTER} r={RADIUS} />
        <circle className="status-icon-spin" cx={CENTER} cy={CENTER} r={RADIUS} pathLength={1} strokeDasharray="0.25 0.75" />
      </svg>
    )
  }

  const shape = SHAPES[state]
  const draw = shape.draw && !reduce
  const each = state === 'error' ? CROSS_S / shape.marks.length : DRAW_S

  const svg = (
    <svg className={classes} data-state={state} {...SVG} width={size} height={size}>
      <circle className="status-icon-track" cx={CENTER} cy={CENTER} r={RADIUS} />
      {draw ? (
        <motion.circle
          cx={CENTER}
          cy={CENTER}
          r={RADIUS}
          transform={`rotate(-90 ${CENTER} ${CENTER})`}
          initial={{ pathLength: 0 }}
          animate={{ pathLength: 1 }}
          transition={{ duration: CIRCLE_S, delay: start, ease: EASE_IN_OUT }}
        />
      ) : (
        <circle
          cx={CENTER}
          cy={CENTER}
          r={RADIUS}
          pathLength={1}
          strokeDasharray={shape.dashed ? DASH : undefined}
          strokeLinecap={shape.dashed ? 'butt' : undefined}
          transform={`rotate(-90 ${CENTER} ${CENTER})`}
        />
      )}
      {shape.marks.map((d, i) =>
        draw ? (
          <motion.path
            key={d}
            d={d}
            initial={{ pathLength: 0, opacity: 0 }}
            animate={{ pathLength: 1, opacity: 1 }}
            transition={{
              pathLength: { duration: each, delay: start + CIRCLE_S + i * each, ease: EASE_IN_OUT },
              // 길이 0인 획의 둥근 끝이 점으로 먼저 보이지 않게 그리기 시작할 때 나타난다
              opacity: { duration: 0, delay: start + CIRCLE_S + i * each },
            }}
          />
        ) : (
          <path key={d} d={d} />
        ),
      )}
    </svg>
  )

  if (reduce) {
    // 모션 줄이기: 그리던 모양만 150ms에 나타난다(그리기 없는 모양은 화면과 같이 바로)
    return shape.draw ? (
      <motion.span className="status-icon-wrap" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ ...REDUCED, delay: start }}>
        {svg}
      </motion.span>
    ) : (
      svg
    )
  }

  if (shape.shake) {
    return (
      <motion.span
        className="status-icon-wrap"
        initial={{ x: 0 }}
        animate={{ x: SHAKE_X }}
        transition={{ ...SHAKE, times: SHAKE_TIMES, delay: start }}
      >
        {svg}
      </motion.span>
    )
  }

  return svg
}

const SVG = {
  viewBox: '0 0 64 64',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 4,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
} as const
