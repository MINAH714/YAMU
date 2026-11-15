import { useAnimate } from 'motion/react'
import { createContext, useContext, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { useReduceMotion } from '../hooks/useReduceMotion'
import { formatWon, planLabel, type Terms } from '../payment/terms'
import { COUNTDOWN_TICK_MS, SHAKE, SHAKE_TIMES, SHAKE_X } from '../styles/motion'
import '../styles/board.css'
import { PAYMENT_METHOD } from './Receipt'
import { useScreenEnter } from './screenEnter'

type Props = {
  title: string
  /** 흰 알약 줄. 판 위 흰 글자는 대비가 낮아 큰 제목만 두고 금액·안내 같은 작은 글자는 알약 안에 둔다 */
  pills: ReactNode
  /**
   * 결과 불명(M-WAIT-UNKNOWN). 같은 프레임에 링이 주황 점선으로 바뀌고 헤일로는 150ms에 사라진다.
   * 거절(실선 빨강)과 색만 다르면 정지 화면에서 구분이 어려워 선 모양도 바꾼다
   */
  unknown?: boolean
  /** 다시 할 수 있는 거절(M-WAIT-DECLINED). false → true가 되는 순간 원판이 250ms 흔들린다. 판은 그대로 */
  declined?: boolean
  /** 알약 아래(결과 다시 확인 같은 버튼) */
  children?: ReactNode
  /** 판 맨 아래(기다리지 않고 새 결제, Mock 패널) */
  foot?: ReactNode
}

/** 판이 처음 그려진 시각. 그 뒤에 새로 붙는 알약만 150ms 페이드로 나온다 */
const BoardMountContext = createContext<number | null>(null)

/**
 * 결제 승인 대기 판(모션 명세 M-WAIT-HALO의 POS 쪽). 앱 WaitingSurface와 같은 구성이다.
 * 주문 화면 자리를 브랜드색으로 덮고 흰 원판 둘레에 링, 뒤에 숨 쉬는 헤일로를 둔다.
 * 원판은 판 높이의 40% 자리에 고정해 아래 글자·버튼이 바뀌어도 움직이지 않는다.
 *
 * 등장(M-WAIT-ENTER): 판은 화면 층과 함께 200ms에 덮이고, 원판이 0.92 → 1(400ms)로 자리를 잡는다.
 * 판 페이드가 끝나는 200ms에 링이 한 바퀴(220ms) 그려지고 헤일로가 숨을 시작한다.
 * 박자는 화면 층이 들어온 시각에 맞춘 CSS 애니메이션이라, 요청 중 → 승인 대기처럼 판 내용이 바뀌어 다시 그려져도
 * 이어서 움직이고 헤일로 숨이 끊기지 않는다. 화면 층 밖에서는 끝 상태로 보인다.
 */
export function WaitBoard({ title, pills, unknown = false, declined = false, children, foot }: Props) {
  const enter = useScreenEnter()
  const reduce = useReduceMotion()
  const [mountedAt] = useState(() => performance.now())
  const [since] = useState(() => (enter ? mountedAt - enter.startedAt : null))
  const timing =
    enter && since !== null
      ? ({ '--board-delay': `${enter.delayMs}ms`, '--board-since': `${Math.round(since)}ms` } as CSSProperties)
      : undefined

  // 거절이 새로 온 순간에만 흔든다. 판이 처음 그려질 때 이미 거절이면 흔들지 않는다
  const [shaker, animate] = useAnimate<HTMLDivElement>()
  const wasDeclined = useRef(declined)
  useEffect(() => {
    const fresh = declined && !wasDeclined.current
    wasDeclined.current = declined
    if (!fresh || reduce || !shaker.current) return
    animate(shaker.current, { x: SHAKE_X }, { ...SHAKE, times: SHAKE_TIMES })
  }, [declined, reduce, animate, shaker])

  return (
    <section className="board" aria-live="polite" data-unknown={unknown || undefined} style={timing}>
      <div className="board-stage">
        <div className="board-center">
          <div className="board-disc-wrap">
            <span className="board-halo-wrap" aria-hidden="true">
              <span className="board-halo" />
            </span>
            <div ref={shaker} className="board-shaker">
              <div className="board-disc">
                <CardGlyph />
                <svg className="board-ring" viewBox="0 0 160 160" aria-hidden="true">
                  <circle className="board-ring-track" cx="80" cy="80" r={RING_R} />
                  <circle
                    className="board-ring-line"
                    cx="80"
                    cy="80"
                    r={RING_R}
                    pathLength={1}
                    strokeDasharray="1 1"
                    transform="rotate(-90 80 80)"
                  />
                </svg>
              </div>
            </div>
          </div>
          <h2 className="board-title">{title}</h2>
          <BoardMountContext.Provider value={mountedAt}>
            <div className="board-pills">{pills}</div>
          </BoardMountContext.Provider>
          {children}
        </div>
      </div>
      {foot && <div className="board-foot">{foot}</div>}
    </section>
  )
}

/** 원판 둘레 링 반지름. 원판(160) 가장자리에서 6px 안쪽에 굵기 4px */
const RING_R = 72

/** 판과 같이 그려진 알약은 판 페이드에 맡기고, 그 뒤에 붙은 알약('확인 중', 거절)만 150ms에 나타난다 */
const LATE_PILL_MS = 50

/**
 * 흰 알약 하나. error는 다시 할 수 있는 거절처럼 판은 그대로 두고 알리는 문구.
 * 같은 자리의 문구가 바뀌면 key를 달리 줘야 새 알약으로 보고 페이드한다.
 */
export function BoardPill({ tone = 'info', children }: { tone?: 'info' | 'error'; children: ReactNode }) {
  const mountedAt = useContext(BoardMountContext)
  const [late] = useState(() => mountedAt !== null && performance.now() - mountedAt > LATE_PILL_MS)
  return (
    <span className="board-pill" data-tone={tone} data-late={late || undefined}>
      {children}
    </span>
  )
}

const URGENT_MS = 10_000

/**
 * 남은 시간 알약(모션 명세 M-WAIT-COUNTDOWN). 작은 링은 250ms마다 다시 재고, 다음 틱 시각의 비율까지
 * 같은 속도로 줄여 실제 남은 시간보다 한 틱 늦지 않는다. 10초 이하면 링과 숫자가 300ms에 주황으로 넘어간다.
 * 모션 줄이기면 틱마다 계단으로 바뀐다(정보라서 남긴다).
 */
export function CountdownPill({ remainingMs, totalMs }: { remainingMs: number; totalMs: number }) {
  const reduce = useReduceMotion()
  const left = Math.max(remainingMs, 0)
  const ahead = reduce ? left : Math.max(left - COUNTDOWN_TICK_MS, 0)
  const ratio = totalMs > 0 ? Math.min(ahead / totalMs, 1) : 0
  const urgent = left > 0 && left <= URGENT_MS
  const seconds = Math.ceil(left / 1000)
  const clock = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`

  return (
    <span className="board-pill board-countdown" data-urgent={urgent}>
      <svg className="board-mini-ring" viewBox="0 0 20 20" width="20" height="20" aria-hidden="true">
        <circle className="board-mini-track" cx="10" cy="10" r="8" />
        <circle
          className="board-mini-line"
          cx="10"
          cy="10"
          r="8"
          pathLength={1}
          strokeDasharray="1 1"
          strokeDashoffset={1 - ratio}
          transform="rotate(-90 10 10)"
        />
      </svg>
      <span role="timer">
        남은 시간 <strong>{clock}</strong>
      </span>
    </span>
  )
}

/** 결제 금액·수단 알약. 할부면 개월 수를 붙인다 */
export function TermsPill({ terms }: { terms: Terms }) {
  return (
    <BoardPill>
      {formatWon(terms.amount)} · {PAYMENT_METHOD}
      {terms.plan === 'INSTALLMENT' && ` · ${planLabel(terms.plan, terms.installmentMonths)}`}
    </BoardPill>
  )
}

/** 원판 가운데 카드. 색은 브랜드색에서 계산한 토큰을 쓴다 */
function CardGlyph() {
  return (
    <svg className="board-card" viewBox="0 0 56 56" width="64" height="64" aria-hidden="true">
      <rect x="6" y="13" width="44" height="30" rx="6" fill="var(--primary)" />
      <rect x="6" y="20" width="44" height="6" fill="var(--on-primary-container)" />
      <rect x="12" y="31" width="14" height="4" rx="2" fill="var(--primary-container)" />
    </svg>
  )
}
