import { useState } from 'react'
import { menuImage, type Store } from '../store/catalog'
import { MAX_QUANTITY, toOrder, type Cart } from '../store/cart'
import { formatWon } from '../payment/terms'

type Props = {
  store: Store
  cart: Cart
  onChange: (itemId: string, delta: number) => void
  onRemove: (itemId: string) => void
  onClear: () => void
  onCheckout: () => void
}

const ALL = '전체'

/** 메뉴판(왼쪽)과 주문 내역(오른쪽). 결제하기를 누르면 그때의 주문으로 결제가 진행된다 */
export function OrderStep({ store, cart, onChange, onRemove, onClear, onCheckout }: Props) {
  const [category, setCategory] = useState(ALL)
  const order = toOrder(store, cart)
  const items = category === ALL ? store.items : store.items.filter((item) => item.category === category)

  return (
    <div className="order">
      <section className="menu" aria-label="메뉴">
        <div className="menu-tabs" role="tablist">
          {[ALL, ...store.categories].map((name) => (
            <button
              key={name}
              type="button"
              role="tab"
              aria-selected={category === name}
              className={category === name ? 'active' : undefined}
              onClick={() => setCategory(name)}
            >
              {name}
            </button>
          ))}
        </div>
        <div className="menu-grid">
          {items.map((item) => {
            const quantity = cart[item.id] ?? 0
            return (
              <button
                key={item.id}
                type="button"
                className="menu-item"
                disabled={quantity >= MAX_QUANTITY}
                aria-label={`${item.name} ${formatWon(item.price)} 담기`}
                onClick={() => onChange(item.id, 1)}
              >
                {quantity > 0 && <span className="menu-count">{quantity}</span>}
                <img src={menuImage(item)} alt="" loading="lazy" />
                <span className="menu-name">{item.name}</span>
                <span className="menu-price">
                  {item.option && <small>{item.option}</small>}
                  {formatWon(item.price)}
                </span>
              </button>
            )
          })}
        </div>
      </section>

      <aside className="cart" aria-label="주문 내역">
        <header className="cart-header">
          <h2>
            주문 내역 <span>{order.count}개</span>
          </h2>
          <button type="button" className="text" disabled={order.count === 0} onClick={onClear}>
            전체 삭제
          </button>
        </header>

        <ul className="cart-lines">
          {order.lines.map(({ item, quantity, amount }) => (
            <li key={item.id}>
              <div className="cart-line-top">
                <span className="cart-line-name">
                  {item.name}
                  {item.option && <small>{item.option}</small>}
                </span>
                <button type="button" className="cart-remove" aria-label={`${item.name} 빼기`} onClick={() => onRemove(item.id)}>
                  ×
                </button>
              </div>
              <div className="cart-line-bottom">
                <div className="stepper">
                  <button type="button" aria-label={`${item.name} 수량 줄이기`} onClick={() => onChange(item.id, -1)}>
                    −
                  </button>
                  <b>{quantity}</b>
                  <button
                    type="button"
                    aria-label={`${item.name} 수량 늘리기`}
                    disabled={quantity >= MAX_QUANTITY}
                    onClick={() => onChange(item.id, 1)}
                  >
                    +
                  </button>
                </div>
                <span className="cart-line-amount">{formatWon(amount)}</span>
              </div>
            </li>
          ))}
          {order.lines.length === 0 && <li className="cart-empty">메뉴를 눌러 주문을 담아 주세요.</li>}
        </ul>

        <footer className="cart-footer">
          <div className="cart-total">
            <span>총 결제금액</span>
            <strong>{formatWon(order.total)}</strong>
          </div>
          <button type="button" className="primary checkout" disabled={order.total <= 0} onClick={onCheckout}>
            결제하기
          </button>
        </footer>
      </aside>
    </div>
  )
}
