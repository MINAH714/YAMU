// 확인 페이지 전용이라 빠른 새로 고침이 안 돼도 괜찮다
// oxlint-disable react/only-export-components
import { useState } from 'react'
import { OrderStep } from '../components/OrderStep'
import { StoreSelect } from '../components/StoreSelect'
import { changeQuantity, removeItem, type Cart } from '../store/cart'
import { STORES, type StoreId } from '../store/catalog'
import { GS25_CART, STARBUCKS_CART } from './fixtures'
import type { GalleryEntry } from './types'

/** 담기·수량·삭제를 눌러 볼 수 있는 주문 화면 */
function OrderDemo({ storeId, initial }: { storeId: StoreId; initial: Cart }) {
  const [cart, setCart] = useState(initial)
  return (
    <OrderStep
      store={STORES[storeId]}
      cart={cart}
      onChange={(itemId, delta) => setCart((current) => changeQuantity(current, itemId, delta))}
      onRemove={(itemId) => setCart((current) => removeItem(current, itemId))}
      onClear={() => setCart({})}
      onCheckout={() => {}}
    />
  )
}

const GROUP = '매장·주문'

export const orderEntries: GalleryEntry[] = [
  {
    id: 'home',
    group: GROUP,
    label: '매장 선택',
    store: null,
    step: 0,
    surface: 'canvas',
    render: () => <StoreSelect onSelect={() => {}} />,
  },
  {
    id: 'order-empty',
    group: GROUP,
    label: '주문 비어 있음 · 스타벅스',
    store: 'starbucks',
    step: 1,
    surface: 'canvas',
    render: () => <OrderDemo storeId="starbucks" initial={{}} />,
  },
  {
    id: 'order-filled',
    group: GROUP,
    label: '주문 담김 · 스타벅스',
    store: 'starbucks',
    step: 1,
    surface: 'canvas',
    render: () => <OrderDemo storeId="starbucks" initial={STARBUCKS_CART} />,
  },
  {
    id: 'order-empty-gs25',
    group: GROUP,
    label: '주문 비어 있음 · GS25',
    store: 'gs25',
    step: 1,
    surface: 'canvas',
    render: () => <OrderDemo storeId="gs25" initial={{}} />,
  },
  {
    id: 'order-filled-gs25',
    group: GROUP,
    label: '주문 담김 · GS25',
    store: 'gs25',
    step: 1,
    surface: 'canvas',
    render: () => <OrderDemo storeId="gs25" initial={GS25_CART} />,
  },
]
