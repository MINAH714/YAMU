import { motion } from 'motion/react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { TRAY_SPRING } from '../styles/motion'
import '../styles/result.css'
import { StatusIcon, type StatusIconState } from './StatusIcon'

/** 결제 진행·결과 화면의 종류. 아이콘과 색이 정해진다. */
export type StatusTone = 'waiting' | 'checking' | 'failed' | 'expired' | 'unconfirmed' | 'approved'

export type StatusRow = { label: string; value: ReactNode; mono?: boolean }

/** 영수증의 품목 줄 */
export type StatusItem = { key: string; label: string; quantity: number; amount: string }

type Props = {
  tone: StatusTone
  title: ReactNode
  /** 제목 위의 작은 문장. 완료 화면은 이 문장 아래 큰 금액을 제목으로 둔다 */
  lead?: ReactNode
  description?: ReactNode
  /** 설명 아래의 금액. 제목이 금액이 아닌 화면에서 쓴다 */
  amount?: ReactNode
  /** 큰 아이콘 대신 보여 줄 그림. 이때 아이콘은 문구 옆으로 작게 붙는다 */
  graphic?: ReactNode
  /** 있으면 왼쪽 결과·오른쪽 주문 내역의 2단이 된다. 많으면 이 목록만 스크롤한다 */
  items?: StatusItem[]
  rows: StatusRow[]
  /** 문구 아래(안내·보조 버튼 등) */
  children?: ReactNode
  /** 주 버튼. 2단이면 오른쪽 맨 아래, 주문 화면의 결제 버튼과 같은 쪽이다 */
  action?: { label: string; onClick: () => void }
}

const ICON_STATE: Record<StatusTone, StatusIconState> = {
  waiting: 'pending',
  checking: 'pending',
  failed: 'error',
  expired: 'warning',
  unconfirmed: 'warning',
  approved: 'success',
}

const MORPH_DELAY_MS = 200
/** 앞 화면 버튼을 연달아 누른 두 번째 탭이 이 화면의 주 버튼에 맞지 않게 잠깐 무시한다 */
const ACTION_GUARD_MS = 350

/** 승인 대기의 스피너가 결과 화면의 아이콘으로 이어진다 */
export const PAYMENT_STATUS_MARK_ID = 'payment-status-mark'

/** 스피너로 시작해 결과 아이콘으로 바뀐다. 금액이 자리를 잡는 동안 잠깐 기다렸다가 바꾼다 */
export function StatusMark({ state, size, layoutId }: { state: StatusIconState; size: number; layoutId?: string }) {
  const [shown, setShown] = useState<StatusIconState>('pending')

  useEffect(() => {
    const timer = setTimeout(() => setShown(state), MORPH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [state])

  return (
    <motion.span className="status-mark" layoutId={layoutId} transition={TRAY_SPRING}>
      <StatusIcon state={shown} size={size} />
    </motion.span>
  )
}

/** 요청 중·현금 완료·영수증을 같은 틀로 보여 준다. 품목이 있으면 2단, 없으면 가운데 한 덩어리다 */
export function StatusScreen({ tone, title, lead, description, amount, graphic, items, rows, children, action }: Props) {
  const state = ICON_STATE[tone]
  const shownAt = useRef(Number.POSITIVE_INFINITY)
  useEffect(() => {
    shownAt.current = performance.now()
  }, [])
  const mark = graphic ? <StatusMark state={state} size={28} layoutId={PAYMENT_STATUS_MARK_ID} /> : null

  const rowList = (
    <dl className="status-rows">
      {rows.map((row) => (
        <div className="status-row" key={row.label}>
          <dt>{row.label}</dt>
          <dd className={row.mono ? 'mono' : undefined}>{row.value}</dd>
        </div>
      ))}
    </dl>
  )

  const button = action && (
    <button
      type="button"
      className="primary status-action"
      onClick={() => {
        if (performance.now() - shownAt.current >= ACTION_GUARD_MS) action.onClick()
      }}>
      {action.label}
    </button>
  )

  const split = items !== undefined && items.length > 0

  return (
    <section className="status" data-layout={split ? 'split' : 'center'} data-tone={tone} aria-live="polite">
      <div className="status-main">
        {graphic ?? <StatusMark state={state} size={64} />}
        <div className="status-text">
          {lead && (
            <p className="status-lead">
              {mark}
              {lead}
            </p>
          )}
          <h2 className={lead ? 'status-amount' : 'flow-title status-title'}>
            {!lead && mark}
            {title}
          </h2>
          {description && <p className="flow-description status-description">{description}</p>}
          {amount}
        </div>
        {children}
        {!split && rowList}
        {!split && button}
      </div>

      {split && (
        <aside className="status-side">
          <h3 className="status-side-title">주문 내역</h3>
          <ul className="status-items">
            {items.map((item) => (
              <li key={item.key}>
                <span>{item.label}</span>
                <span className="status-item-quantity">×{item.quantity}</span>
                <span className="status-item-amount">{item.amount}</span>
              </li>
            ))}
          </ul>
          {rowList}
          {button}
        </aside>
      )}
    </section>
  )
}
