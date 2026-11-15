import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { menuImage, type Store } from '../store/catalog'
import { MAX_QUANTITY, toOrder, type Cart } from '../store/cart'
import { formatWon } from '../payment/terms'
import { EASE, FAST } from '../styles/motion'
import '../styles/order.css'
import { ConfirmButton } from './ConfirmButton'

type Props = {
  store: Store
  cart: Cart
  onChange: (itemId: string, delta: number) => void
  onRemove: (itemId: string) => void
  onClear: () => void
  onCheckout: () => void
  /** 트레이가 떠 있는 동안 주문을 고치지 못하게 막는다 */
  locked?: boolean
  /** 결제수단 트레이가 커져 나오는 버튼 */
  checkoutRef?: RefObject<HTMLButtonElement | null>
  /** 트레이가 버튼 자리를 덮는 동안 진짜 버튼을 숨긴다. 트레이가 줄어든 마지막 프레임에 다시 보인다 */
  checkoutHidden?: boolean
}

const ALL = '전체'
const SLIDE_PX = 16
const MOUNT_GUARD_MS = 300
const SLIDE = { duration: 0.18, ease: EASE }

/** 카테고리 레일(왼쪽), 메뉴판(가운데), 주문 내역(오른쪽). 결제하기를 누르면 그때의 주문으로 결제가 진행된다 */
export function OrderStep({
  store,
  cart,
  onChange,
  onRemove,
  onClear,
  onCheckout,
  locked = false,
  checkoutRef,
  checkoutHidden = false,
}: Props) {
  // direction은 메뉴판이 밀려 들어오는 쪽. 처음 그릴 때는 0이라 움직이지 않는다
  const [view, setView] = useState({ category: ALL, direction: 0 })
  const order = toOrder(store, cart)
  const lineCount = order.lines.length
  const categories = [ALL, ...store.categories]
  const items = view.category === ALL ? store.items : store.items.filter((item) => item.category === view.category)

  // 매장 카드를 연타한 두 번째 탭이 막 뜬 메뉴판에 맞아 메뉴가 담기지 않게 한다
  const tappableAt = useRef(0)
  useEffect(() => {
    tappableAt.current = performance.now() + MOUNT_GUARD_MS
  }, [])

  const chooseCategory = (name: string) => {
    if (name === view.category) return
    setView({ category: name, direction: categories.indexOf(name) > categories.indexOf(view.category) ? 1 : -1 })
  }

  // 새로 담은 줄은 맨 아래에 붙으니 목록이 넘치면 거기까지 내려 준다
  const linesRef = useRef<HTMLUListElement>(null)
  const previousLineCount = useRef(lineCount)
  useEffect(() => {
    if (lineCount > previousLineCount.current && linesRef.current) {
      linesRef.current.scrollTop = linesRef.current.scrollHeight
    }
    previousLineCount.current = lineCount
  }, [lineCount])

  return (
    <div className="order" inert={locked}>
      <div className="rail" role="tablist" aria-orientation="vertical" aria-label="카테고리">
        {categories.map((name) => {
          const active = view.category === name
          return (
            <button
              key={name}
              type="button"
              role="tab"
              aria-selected={active}
              className="rail-item"
              onClick={() => chooseCategory(name)}
            >
              {active && <motion.span className="rail-indicator" layoutId="rail-indicator" transition={SLIDE} />}
              <span className="rail-label">{name}</span>
            </button>
          )
        })}
      </div>

      <section className="menu" aria-label="메뉴">
        {/* key가 바뀌면 스크롤도 맨 위로 돌아간다 */}
        <motion.div
          key={view.category}
          className="menu-grid"
          initial={view.direction === 0 ? false : { opacity: 0, y: view.direction * SLIDE_PX }}
          animate={{ opacity: 1, y: 0 }}
          transition={SLIDE}
        >
          {items.map((item) => {
            const quantity = cart[item.id] ?? 0
            return (
              <button
                key={item.id}
                type="button"
                className="menu-item"
                disabled={quantity >= MAX_QUANTITY}
                aria-label={`${item.name} ${formatWon(item.price)} 담기`}
                onClick={() => {
                  if (performance.now() >= tappableAt.current) onChange(item.id, 1)
                }}
              >
                {/* 카테고리를 옮겨 카드가 새로 그려질 때는 배지가 튀지 않는다 */}
                <AnimatePresence initial={false}>
                  {quantity > 0 && (
                    <motion.span
                      key={quantity}
                      className="menu-count"
                      initial={{ scale: 0.7 }}
                      animate={{ scale: 1 }}
                      transition={FAST}
                    >
                      {quantity}
                    </motion.span>
                  )}
                </AnimatePresence>
                <img className="menu-image" src={menuImage(item)} alt="" draggable={false} />
                <span className="menu-name">{item.name}</span>
                <span className="menu-price">
                  {formatWon(item.price)}
                  {item.option && <small>{item.option}</small>}
                </span>
              </button>
            )
          })}
        </motion.div>
      </section>

      <aside className="cart" aria-label="주문 내역">
        <header className="cart-header">
          <h2>
            주문 내역 <span>{order.count}개</span>
          </h2>
          <ConfirmButton
            label="전체 삭제"
            confirmLabel="한 번 더 누르면 비워요"
            disabled={order.count === 0}
            onConfirm={onClear}
          />
        </header>

        <div className="cart-body">
          <motion.ul ref={linesRef} className="cart-lines" layoutScroll>
            <AnimatePresence initial={false} mode="popLayout">
              {order.lines.map(({ item, quantity, amount }) => (
                <motion.li
                  key={item.id}
                  className="cart-line"
                  layout="position"
                  layoutDependency={lineCount}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0 }}
                  transition={FAST}
                >
                  <div className="cart-line-top">
                    <span className="cart-line-name">
                      {item.name}
                      {item.option && <small>{item.option}</small>}
                    </span>
                    <button
                      type="button"
                      className="icon-button cart-remove"
                      aria-label={`${item.name} 빼기`}
                      onClick={() => onRemove(item.id)}
                    >
                      <RemoveIcon />
                    </button>
                  </div>
                  <div className="cart-line-bottom">
                    <div className="stepper">
                      {/* 1에서 줄이면 줄이 빠지고 아랫줄이 올라와 연타가 다른 메뉴로 번진다. 빼기는 X로만 한다 */}
                      <button
                        type="button"
                        aria-label={`${item.name} 수량 줄이기`}
                        disabled={quantity <= 1}
                        onClick={() => onChange(item.id, -1)}
                      >
                        <MinusIcon />
                      </button>
                      <b>{quantity}</b>
                      <button
                        type="button"
                        aria-label={`${item.name} 수량 늘리기`}
                        disabled={quantity >= MAX_QUANTITY}
                        onClick={() => onChange(item.id, 1)}
                      >
                        <PlusIcon />
                      </button>
                    </div>
                    <span className="cart-line-amount">{formatWon(amount)}</span>
                  </div>
                </motion.li>
              ))}
            </AnimatePresence>
          </motion.ul>
          {lineCount === 0 && <p className="cart-empty">아직 담은 메뉴가 없어요</p>}
        </div>

        <footer className="cart-footer">
          <div className="cart-total">
            <span>총 결제금액</span>
            <CartTotal value={order.total} />
          </div>
          <button
            ref={checkoutRef}
            type="button"
            className="primary checkout"
            style={checkoutHidden ? { visibility: 'hidden' } : undefined}
            disabled={order.total <= 0}
            onClick={onCheckout}
          >
            <CheckoutLabel total={order.total} />
          </button>
        </footer>
      </aside>
    </div>
  )
}

/**
 * 주문 합계. 금액이 바뀌면 숫자만 번진다.
 * 트레이 머리 금액으로 날아가지 않는다(모션 명세 결정: 금액은 화면 사이를 날아오지 않는다)
 */
function CartTotal({ value }: { value: number }) {
  return (
    <span className="amount amount-lg cart-total-amount" data-empty={value <= 0}>
      <span key={value} className="cart-total-value">
        {formatWon(value)}
      </span>
    </span>
  )
}

/** 메뉴를 담으면 금액이 나타나며 '결제하기'가 옆으로 비켜난다. 금액 자릿수가 늘어도 '결제하기' 자리는 그대로다 */
function CheckoutLabel({ total }: { total: number }) {
  const empty = total <= 0
  return (
    <span className="checkout-label" data-empty={empty}>
      <span className="checkout-amount" aria-hidden={empty}>
        <span key={total} className="cart-total-value">
          {formatWon(total)}
        </span>
      </span>{' '}
      <span className="checkout-word">결제하기</span>
    </span>
  )
}

const ICON = {
  viewBox: '0 0 24 24',
  width: 20,
  height: 20,
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round',
  'aria-hidden': true,
} as const

function RemoveIcon() {
  return (
    <svg {...ICON}>
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  )
}

function MinusIcon() {
  return (
    <svg {...ICON}>
      <path d="M5 12h14" />
    </svg>
  )
}

function PlusIcon() {
  return (
    <svg {...ICON}>
      <path d="M12 5v14M5 12h14" />
    </svg>
  )
}
