import type { StatusItem } from '../components/StatusScreen'
import { formatWon } from '../payment/terms'
import type { MenuItem, Store } from './catalog'

/** 상품 id → 수량. 담은 순서대로 영수증에 나온다 */
export type Cart = Record<string, number>

export const MAX_QUANTITY = 99

export type OrderLine = { item: MenuItem; quantity: number; amount: number }

/** 결제하기를 누른 순간의 주문. 결제 금액은 이 값만 쓰고, 그 뒤에 장바구니가 바뀌어도 따라가지 않는다 */
export type Order = { lines: OrderLine[]; total: number; count: number }

export function changeQuantity(cart: Cart, itemId: string, delta: number): Cart {
  const next = Math.min((cart[itemId] ?? 0) + delta, MAX_QUANTITY)
  if (next <= 0) return removeItem(cart, itemId)
  return { ...cart, [itemId]: next }
}

export function removeItem(cart: Cart, itemId: string): Cart {
  const next = { ...cart }
  delete next[itemId]
  return next
}

export function toOrder(store: Store, cart: Cart): Order {
  const lines: OrderLine[] = []
  for (const [itemId, quantity] of Object.entries(cart)) {
    const item = store.items.find((candidate) => candidate.id === itemId)
    if (item) lines.push({ item, quantity, amount: item.price * quantity })
  }
  return {
    lines,
    total: lines.reduce((sum, line) => sum + line.amount, 0),
    count: lines.reduce((sum, line) => sum + line.quantity, 0),
  }
}

export function itemLabel(item: MenuItem): string {
  return item.option ? `${item.name} ${item.option}` : item.name
}

/** 영수증 품목 줄 */
export function orderItems(order: Order): StatusItem[] {
  return order.lines.map(({ item, quantity, amount }) => ({
    key: item.id,
    label: itemLabel(item),
    quantity,
    amount: formatWon(amount),
  }))
}
