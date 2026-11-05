import { useState } from 'react'
import type { Order } from '../store/cart'
import { formatWon } from '../payment/terms'
import { OrderSummary } from './OrderSummary'

type Props = {
  order: Order
  onDone: (received: number) => void
  onBack: () => void
}

const BILLS = [1_000, 5_000, 10_000, 50_000]
const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '00', '0', '⌫']
// 받은 금액은 1억 원 미만까지만 입력받는다(자릿수 실수 방지)
const MAX_DIGITS = 8

/** 현금 결제. 받은 금액과 거스름돈만 계산하고 서버에는 보내지 않는다 */
export function CashStep({ order, onDone, onBack }: Props) {
  const [digits, setDigits] = useState('')
  const received = Number(digits || '0')
  const change = received - order.total

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
    <div className="checkout">
      <OrderSummary order={order} />

      <section className="checkout-main cash">
        <h2>현금 결제</h2>
        <dl className="cash-figures">
          <div>
            <dt>결제할 금액</dt>
            <dd>{formatWon(order.total)}</dd>
          </div>
          <div>
            <dt>받은 금액</dt>
            <dd className="cash-received">{formatWon(received)}</dd>
          </div>
          <div className={change < 0 ? 'short' : undefined}>
            <dt>{change < 0 ? '부족한 금액' : '거스름돈'}</dt>
            <dd>{formatWon(Math.abs(change))}</dd>
          </div>
        </dl>

        <div className="cash-input">
          <div className="cash-bills">
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
          <div className="keypad">
            {KEYS.map((key) => (
              <button key={key} type="button" aria-label={key === '⌫' ? '한 자리 지우기' : undefined} onClick={() => press(key)}>
                {key}
              </button>
            ))}
          </div>
        </div>

        <footer className="checkout-footer">
          <button type="button" onClick={onBack}>
            ← 결제수단 변경
          </button>
          <button type="button" className="primary" disabled={change < 0} onClick={() => onDone(received)}>
            현금 결제 완료
          </button>
        </footer>
      </section>
    </div>
  )
}
