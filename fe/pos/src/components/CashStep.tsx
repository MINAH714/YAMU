import { useState } from 'react'
import type { Order } from '../store/cart'
import { formatWon } from '../payment/terms'
import { Amount } from './Amount'
import '../styles/payment.css'

type Props = {
  order: Order
  onDone: (received: number) => void
}

const BILLS = [1_000, 5_000, 10_000, 50_000]
const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '00', '0', '⌫']
// 받은 금액은 1억 원 미만까지만 입력받는다(자릿수 실수 방지)
const MAX_DIGITS = 8

/** 현금 결제. 받은 금액과 거스름돈만 계산하고 서버에는 보내지 않는다 */
export function CashStep({ order, onDone }: Props) {
  const [digits, setDigits] = useState('')
  const received = Number(digits || '0')
  const change = received - order.total
  // 아직 아무것도 안 받았을 때 오류처럼 보이지 않게 한다
  const changeState = received === 0 ? 'empty' : change < 0 ? 'short' : 'ready'

  const press = (key: string) => {
    if (key === '⌫') {
      setDigits((value) => value.slice(0, -1))
      return
    }
    setDigits((value) => {
      const next = (value + key).replace(/^0+/, '')
      return next.length > MAX_DIGITS ? value : next
    })
  }
  const add = (bill: number) =>
    setDigits((value) => {
      const next = String(Number(value || '0') + bill)
      return next.length > MAX_DIGITS ? value : next
    })

  return (
    <div className="pay-cash">
      <div className="pay-cash-body">
        <div className="pay-cash-left">
          <dl className="pay-cash-figures">
            <div>
              <dt>받은 금액</dt>
              <dd>
                <Amount value={received} size="xl" />
              </dd>
            </div>
            <div className="pay-cash-change" data-state={changeState}>
              <dt>{change < 0 ? '부족한 금액' : '거스름돈'}</dt>
              <dd>
                <Amount value={Math.abs(change)} size="lg" />
              </dd>
            </div>
          </dl>
          <div className="pay-cash-bills">
            <button type="button" onClick={() => setDigits(String(order.total))}>
              금액 맞춤
            </button>
            {BILLS.map((bill) => (
              <button key={bill} type="button" onClick={() => add(bill)}>
                +{formatWon(bill)}
              </button>
            ))}
            <button type="button" onClick={() => setDigits('')}>
              지우기
            </button>
          </div>
        </div>
        <div className="pay-keypad">
          {KEYS.map((key) => (
            <button key={key} type="button" aria-label={key === '⌫' ? '한 자리 지우기' : undefined} onClick={() => press(key)}>
              {key === '⌫' ? <BackspaceIcon /> : key}
            </button>
          ))}
        </div>
      </div>

      <button type="button" className="primary" disabled={change < 0} onClick={() => onDone(received)}>
        현금 결제 완료
      </button>
    </div>
  )
}

function BackspaceIcon() {
  return (
    <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7z" />
      <path d="M12 10l4 4M16 10l-4 4" />
    </svg>
  )
}
