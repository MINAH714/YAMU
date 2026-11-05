// 확인 페이지 전용이라 빠른 새로 고침이 안 돼도 괜찮다
// oxlint-disable react/only-export-components
import { useState, type ReactNode } from 'react'
import { Amount } from '../components/Amount'
import { CashStep } from '../components/CashStep'
import { OrderStep } from '../components/OrderStep'
import { PaymentMethodStep, type PlanInput } from '../components/PaymentMethodStep'
import { ScanStep } from '../components/ScanStep'
import { Tray, TrayStep } from '../components/Tray'
import { TRAY_META } from '../components/trayMeta'
import type { Cart, Order } from '../store/cart'
import { STORES } from '../store/catalog'
import { DEMO_ORDER, DEMO_TERMS, LARGE_CART, LARGE_ORDER, STARBUCKS_CART } from './fixtures'
import type { GalleryEntry } from './types'

const noop = () => {}
const LUMP_SUM: PlanInput = { plan: 'LUMP_SUM', installmentMonths: '' }

function LockedOrder({ cart = STARBUCKS_CART }: { cart?: Cart }) {
  return (
    <OrderStep
      store={STORES.starbucks}
      cart={cart}
      locked
      onChange={noop}
      onRemove={noop}
      onClear={noop}
      onCheckout={noop}
    />
  )
}

type TrayFrameProps = { step: keyof typeof TRAY_META; first?: boolean; order?: Order; children: ReactNode }

function TrayFrame({ step, first, order = DEMO_ORDER, children }: TrayFrameProps) {
  return (
    <Tray
      title={TRAY_META[step].title}
      width={TRAY_META[step].width}
      onClose={first ? noop : undefined}
      onBack={first ? undefined : noop}
    >
      <Amount className="tray-amount" layoutId="order-total" value={order.total} size="lg" />
      <TrayStep>{children}</TrayStep>
    </Tray>
  )
}

function MethodDemo({ order = DEMO_ORDER, initial = LUMP_SUM }: { order?: Order; initial?: PlanInput }) {
  const [planInput, setPlanInput] = useState(initial)
  return (
    <PaymentMethodStep
      order={order}
      planInput={planInput}
      errors={{}}
      notice={null}
      onPlanChange={setPlanInput}
      onYamu={noop}
      onCash={noop}
    />
  )
}

const GROUP = '트레이'

export const trayEntries: GalleryEntry[] = [
  {
    id: 'tray-method',
    group: GROUP,
    label: '결제수단',
    store: 'starbucks',
    step: 2,
    surface: 'canvas',
    render: () => <LockedOrder />,
    tray: () => (
      <TrayFrame key="tray" step="method" first>
        <MethodDemo />
      </TrayFrame>
    ),
  },
  {
    id: 'tray-method-installment',
    group: GROUP,
    label: '결제수단(할부)',
    store: 'starbucks',
    step: 2,
    surface: 'canvas',
    render: () => <LockedOrder cart={LARGE_CART} />,
    tray: () => (
      <TrayFrame key="tray" step="method" first order={LARGE_ORDER}>
        <MethodDemo order={LARGE_ORDER} initial={{ plan: 'INSTALLMENT', installmentMonths: '6' }} />
      </TrayFrame>
    ),
  },
  {
    id: 'tray-cash',
    group: GROUP,
    label: '현금',
    store: 'starbucks',
    step: 2,
    surface: 'canvas',
    render: () => <LockedOrder />,
    tray: () => (
      <TrayFrame key="tray" step="cash">
        <CashStep order={DEMO_ORDER} onDone={noop} />
      </TrayFrame>
    ),
  },
  {
    id: 'tray-scan',
    group: GROUP,
    label: '스캔',
    store: 'starbucks',
    step: 3,
    surface: 'canvas',
    render: () => <LockedOrder />,
    tray: () => (
      <TrayFrame key="tray" step="scan">
        <ScanStep terms={DEMO_TERMS} notice={null} rejectedToken={null} onToken={noop} />
      </TrayFrame>
    ),
  },
]
