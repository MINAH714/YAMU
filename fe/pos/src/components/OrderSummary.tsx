import type { Order } from '../store/cart'
import { formatWon } from '../payment/terms'

/** 결제 단계 왼쪽의 주문 요약. 읽기 전용이라 여기서는 주문을 바꿀 수 없다 */
export function OrderSummary({ order }: { order: Order }) {
  return (
    <aside className="order-summary" aria-label="주문 요약">
      <h2>
        주문 내역 <span>{order.count}개</span>
      </h2>
      <ul>
        {order.lines.map(({ item, quantity, amount }) => (
          <li key={item.id}>
            <span>
              {item.name}
              {item.option && <small>{item.option}</small>}
            </span>
            <span className="qty">×{quantity}</span>
            <span className="amount">{formatWon(amount)}</span>
          </li>
        ))}
      </ul>
      <div className="order-summary-total">
        <span>총 결제금액</span>
        <strong>{formatWon(order.total)}</strong>
      </div>
    </aside>
  )
}
