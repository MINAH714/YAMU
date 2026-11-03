import { useEffect, useRef, useState } from 'react'
import './App.css'
import { newIdempotencyKey } from './api/client'
import { createPaymentRequest, type PaymentRequestBody, type PaymentResult } from './api/pos'
import { BrandMark } from './components/BrandMark'
import { CashStep } from './components/CashStep'
import { OrderStep } from './components/OrderStep'
import { PaymentMethodStep, type PlanInput } from './components/PaymentMethodStep'
import { PAYMENT_METHOD, Receipt } from './components/Receipt'
import { ScanStep } from './components/ScanStep'
import { StatusScreen } from './components/StatusScreen'
import { StoreSelect } from './components/StoreSelect'
import { WaitingStep } from './components/WaitingStep'
import { config, missingConfig } from './config'
import { formatToken } from './payment/barcode'
import { classifyCreateError } from './payment/errors'
import { formatWon, MIN_INSTALLMENT_AMOUNT, validateTerms, type Terms, type TermsErrors } from './payment/terms'
import { STORES, type StoreId } from './store/catalog'
import { changeQuantity, orderItems, removeItem, toOrder, type Cart, type Order } from './store/cart'

/**
 * 결제 한 건의 요청. 멱등 키는 이 본문과 한 묶음이다.
 * 결과를 모를 때 다시 보내면 같은 키·같은 본문을 보내 결제가 두 번 생기지 않는다.
 * 금액·방식·바코드가 바뀌면 새 Attempt(새 키)를 만든다. 같은 키에 다른 본문은 서버가 409로 거절한다.
 */
type Attempt = { idempotencyKey: string; body: PaymentRequestBody; terms: Terms; order: Order }

/**
 * 장바구니는 'order' 단계에서만 바꿀 수 있다. 결제하기를 누르면 그때의 주문(Order)을 단계마다 넘기므로
 * 결제 요청 금액은 항상 그 주문 합계이고, 결제를 보낸 뒤에는 주문 화면으로 돌아갈 길이 없다.
 */
type Phase =
  | { step: 'home' }
  | { step: 'order' }
  | { step: 'method'; order: Order; errors: TermsErrors; notice: string | null }
  | { step: 'cash'; order: Order }
  | { step: 'cashDone'; order: Order; received: number }
  | { step: 'scan'; order: Order; terms: Terms; notice: string | null; rejectedToken: string | null }
  | { step: 'sending'; attempt: Attempt }
  | { step: 'unknown'; attempt: Attempt; message: string }
  | { step: 'waiting'; attempt: Attempt; paymentId: number; orderNo: string | null; deadlineAt: number }
  | { step: 'receipt'; result: PaymentResult; attempt: Attempt }
  | { step: 'fatal'; message: string; config: boolean }

const EMPTY_PLAN: PlanInput = { plan: 'LUMP_SUM', installmentMonths: '' }
const EMPTY_CARTS: Record<StoreId, Cart> = { starbucks: {}, gs25: {} }
const CONFIRM_WINDOW_MS = 60_000
const STEPS = ['주문', '결제수단', '결제']

function App() {
  const missing = missingConfig()
  const [storeId, setStoreId] = useState<StoreId | null>(null)
  const [carts, setCarts] = useState(EMPTY_CARTS)
  const [planInput, setPlanInput] = useState(EMPTY_PLAN)
  const [phase, setPhase] = useState<Phase>(() =>
    missing.length > 0
      ? { step: 'fatal', message: `.env.local에 ${missing.join(', ')} 값을 넣어 주세요.`, config: true }
      : { step: 'home' },
  )
  // 버튼 연타·스캔 콜백이 겹쳐도 요청은 하나만 나간다(상태 갱신 전에 막아야 해서 ref로 둔다)
  const sendingRef = useRef(false)

  const store = storeId ? STORES[storeId] : null

  const updateCart = (update: (cart: Cart) => Cart) => {
    if (!storeId) return
    setCarts((current) => ({ ...current, [storeId]: update(current[storeId]) }))
  }

  const openStore = (id: StoreId) => {
    setStoreId(id)
    setPlanInput(EMPTY_PLAN)
    setPhase({ step: 'order' })
  }

  const leaveStore = () => {
    setStoreId(null)
    setPhase({ step: 'home' })
  }

  /** 결제가 끝났거나 결과를 기다리지 않기로 한 뒤. 그 주문은 끝났으므로 장바구니를 비운다 */
  const newOrder = () => {
    updateCart(() => ({}))
    setPlanInput(EMPTY_PLAN)
    setPhase({ step: 'order' })
  }

  const checkout = () => {
    if (!storeId) return
    const order = toOrder(STORES[storeId], carts[storeId])
    if (order.total <= 0) return
    // 주문을 줄여 할부 최소 금액 아래로 내려가면 고르던 할부를 일시불로 되돌린다
    if (order.total < MIN_INSTALLMENT_AMOUNT) setPlanInput(EMPTY_PLAN)
    setPhase({ step: 'method', order, errors: {}, notice: null })
  }

  const toScan = (order: Order) => {
    const validated = validateTerms({ amount: String(order.total), ...planInput })
    if ('errors' in validated) {
      setPhase({ step: 'method', order, errors: validated.errors, notice: null })
      return
    }
    setPhase({ step: 'scan', order, terms: validated.terms, notice: null, rejectedToken: null })
  }

  const startAttempt = (order: Order, terms: Terms, barcodeToken: string) => {
    if (!storeId) return
    const { posCode, merchantId } = config.stores[storeId]
    const body: PaymentRequestBody = {
      barcodeToken,
      merchantId,
      posCode,
      amount: terms.amount,
      plan: terms.plan,
      ...(terms.plan === 'INSTALLMENT' ? { installmentMonths: terms.installmentMonths } : {}),
      orderNo: newOrderNo(),
    }
    send({ idempotencyKey: newIdempotencyKey(), body, terms, order })
  }

  const send = async (attempt: Attempt) => {
    if (sendingRef.current) return
    sendingRef.current = true
    setPhase({ step: 'sending', attempt })
    try {
      const { data, serverTime } = await createPaymentRequest(attempt.body, attempt.idempotencyKey)
      setPhase({
        step: 'waiting',
        attempt,
        paymentId: data.paymentId,
        orderNo: data.orderNo,
        deadlineAt: localDeadline(data.confirmDeadline, serverTime),
      })
    } catch (error) {
      const failure = classifyCreateError(error)
      const { order, terms } = attempt
      switch (failure.kind) {
        case 'terms':
          setPhase({ step: 'method', order, errors: failure.errors, notice: failure.message })
          break
        case 'barcode':
          setPhase({
            step: 'scan',
            order,
            terms,
            notice: `${failure.message} (${formatToken(attempt.body.barcodeToken)})`,
            rejectedToken: attempt.body.barcodeToken,
          })
          break
        case 'retry':
          setPhase({ step: 'unknown', attempt, message: failure.message })
          break
        case 'config':
          setPhase({ step: 'fatal', message: failure.message, config: true })
          break
        case 'discard':
          setPhase({ step: 'scan', order, terms, notice: failure.message, rejectedToken: null })
          break
      }
    } finally {
      sendingRef.current = false
    }
  }

  const stepIndex = currentStep(phase)

  return (
    <div className="app" data-store={storeId ?? undefined}>
      <header className="app-header">
        <div className="app-brand">
          {storeId && store ? (
            <>
              <BrandMark storeId={storeId} />
              <span className="app-branch">{store.branch}</span>
            </>
          ) : (
            <h1>
              YAMU <span>POS</span>
            </h1>
          )}
        </div>

        {stepIndex > 0 && (
          <ol className="steps" aria-label="결제 단계">
            {STEPS.map((label, i) => (
              <li key={label} className={i + 1 === stepIndex ? 'current' : i + 1 < stepIndex ? 'done' : undefined}>
                <i>{i + 1}</i>
                {label}
              </li>
            ))}
          </ol>
        )}

        <div className="app-meta">
          {config.mockResult && <span className="badge">결과 Mock</span>}
          <Clock />
          {phase.step === 'order' && (
            <button type="button" className="text" onClick={leaveStore}>
              매장 변경
            </button>
          )}
        </div>
      </header>

      <main className={`app-main app-main-${phase.step}`}>
        {phase.step === 'home' && <StoreSelect onSelect={openStore} />}

        {phase.step === 'order' && store && storeId && (
          <OrderStep
            store={store}
            cart={carts[storeId]}
            onChange={(itemId, delta) => updateCart((cart) => changeQuantity(cart, itemId, delta))}
            onRemove={(itemId) => updateCart((cart) => removeItem(cart, itemId))}
            onClear={() => updateCart(() => ({}))}
            onCheckout={checkout}
          />
        )}

        {phase.step === 'method' && (
          <PaymentMethodStep
            order={phase.order}
            planInput={planInput}
            errors={phase.errors}
            notice={phase.notice}
            onPlanChange={(input) => {
              setPlanInput(input)
              setPhase({ ...phase, errors: {}, notice: null })
            }}
            onYamu={() => toScan(phase.order)}
            onCash={() => setPhase({ step: 'cash', order: phase.order })}
            onBack={() => setPhase({ step: 'order' })}
          />
        )}

        {phase.step === 'cash' && (
          <CashStep
            order={phase.order}
            onDone={(received) => setPhase({ step: 'cashDone', order: phase.order, received })}
            onBack={() => setPhase({ step: 'method', order: phase.order, errors: {}, notice: null })}
          />
        )}

        {phase.step === 'cashDone' && (
          <StatusScreen
            tone="approved"
            lead="현금 결제가 완료되었습니다."
            title={formatWon(phase.order.total)}
            items={orderItems(phase.order)}
            rows={[
              { label: '결제 수단', value: '현금' },
              { label: '받은 금액', value: formatWon(phase.received) },
              { label: '거스름돈', value: formatWon(phase.received - phase.order.total) },
            ]}
            action={{ label: '다음 주문', onClick: newOrder }}
          />
        )}

        {phase.step === 'scan' && (
          <ScanStep
            order={phase.order}
            terms={phase.terms}
            notice={phase.notice}
            rejectedToken={phase.rejectedToken}
            onToken={(token) => startAttempt(phase.order, phase.terms, token)}
            onBack={() => setPhase({ step: 'method', order: phase.order, errors: {}, notice: null })}
          />
        )}

        {phase.step === 'sending' && (
          <StatusScreen
            tone="checking"
            title="결제를 요청하고 있어요."
            description={`결제 코드 ${formatToken(phase.attempt.body.barcodeToken)}`}
            rows={[
              { label: '주문 금액', value: formatWon(phase.attempt.terms.amount) },
              { label: '결제 수단', value: PAYMENT_METHOD },
            ]}
          />
        )}

        {phase.step === 'unknown' && (
          <section className="panel">
            <h2>결과를 확인하지 못했어요</h2>
            <p className="alert alert-error">{phase.message}</p>
            <p>같은 요청으로 다시 보내면 이미 접수된 결제는 새로 만들어지지 않고 처음 결과를 돌려받아요.</p>
            <button type="button" className="primary" onClick={() => send(phase.attempt)}>
              같은 요청 다시 보내기
            </button>
            <button
              type="button"
              className="link"
              onClick={() => {
                if (window.confirm('새 주문을 시작할까요? 이 결제는 이미 접수됐을 수 있어 고객 앱의 결제 내역을 먼저 확인해 주세요.')) newOrder()
              }}
            >
              새 주문 시작
            </button>
          </section>
        )}

        {phase.step === 'waiting' && (
          <WaitingStep
            key={phase.paymentId}
            paymentId={phase.paymentId}
            posCode={phase.attempt.body.posCode}
            orderNo={phase.orderNo}
            terms={phase.attempt.terms}
            deadlineAt={phase.deadlineAt}
            onFinal={(result) => setPhase({ step: 'receipt', result, attempt: phase.attempt })}
            onFatal={(message, isConfig) => setPhase({ step: 'fatal', message, config: isConfig })}
            onAbandon={newOrder}
          />
        )}

        {phase.step === 'receipt' && (
          <Receipt
            result={phase.result}
            order={phase.attempt.order}
            onNext={newOrder}
            onRetry={() =>
              setPhase({ step: 'scan', order: phase.attempt.order, terms: phase.attempt.terms, notice: null, rejectedToken: null })
            }
          />
        )}

        {phase.step === 'fatal' && (
          <section className="panel">
            <h2>{phase.config ? 'POS 설정 오류' : '결제를 이어갈 수 없어요'}</h2>
            <p className="alert alert-error">{phase.message}</p>
            {!phase.config && (
              <button type="button" className="primary" onClick={newOrder}>
                새 주문
              </button>
            )}
            {phase.config && storeId && (
              <button type="button" onClick={leaveStore}>
                매장 선택으로
              </button>
            )}
          </section>
        )}
      </main>
    </div>
  )
}

/** 헤더 단계 표시. 0이면 표시하지 않는다 */
function currentStep(phase: Phase): number {
  switch (phase.step) {
    case 'home':
    case 'fatal':
      return 0
    case 'order':
      return 1
    case 'method':
    case 'cash':
      return 2
    default:
      return 3
  }
}

const clockFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function Clock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 10_000)
    return () => clearInterval(timer)
  }, [])
  return <time className="clock">{clockFormat.format(now)}</time>
}

/**
 * 서버 마감 시각을 이 POS 시계로 옮긴다. 응답 Date 헤더로 서버 시각을 알면 그 차이를 빼고,
 * 모르면 이 POS 시계를 그대로 쓴다. 어느 쪽이든 남은 시간은 0~60초로 자른다.
 */
function localDeadline(confirmDeadline: string, serverTime: number | null): number {
  const now = Date.now()
  const remaining = Date.parse(confirmDeadline) - (serverTime ?? now)
  if (Number.isNaN(remaining)) return now + CONFIRM_WINDOW_MS
  return now + Math.min(Math.max(remaining, 0), CONFIRM_WINDOW_MS)
}

/** POS 주문번호(한국 시각). 멱등 키와 함께 한 번 정하고, 다시 보낼 때도 같은 값을 쓴다. */
function newOrderNo(): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(new Date())
      .map((part) => [part.type, part.value]),
  )
  const suffix = String(Math.floor(Math.random() * 1000)).padStart(3, '0')
  return `POS-${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}${parts.second}-${suffix}`
}

export default App
