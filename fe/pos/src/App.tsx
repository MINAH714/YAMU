import { AnimatePresence, MotionConfig, useIsPresent } from 'motion/react'
import { useRef, useState, type ComponentProps } from 'react'
import { newIdempotencyKey } from './api/client'
import { createPaymentRequest, type PaymentRequestBody, type PaymentResult } from './api/pos'
import { Amount } from './components/Amount'
import { AppHeader } from './components/AppHeader'
import { CashStep } from './components/CashStep'
import { Character } from './components/Character'
import { FatalScreen, UnknownScreen } from './components/FlowScreens'
import { OrderStep } from './components/OrderStep'
import { PaymentMethodStep, type PlanInput } from './components/PaymentMethodStep'
import { PAYMENT_METHOD, Receipt } from './components/Receipt'
import { ScanStep } from './components/ScanStep'
import { StatusScreen } from './components/StatusScreen'
import { StoreSelect } from './components/StoreSelect'
import { Tray, TrayStep } from './components/Tray'
import { TRAY_META } from './components/trayMeta'
import { WaitingStep } from './components/WaitingStep'
import { config, missingConfig } from './config'
import { enterFullscreenOnFirstTouch } from './hooks/useFullscreen'
import { formatToken } from './payment/barcode'
import { classifyCreateError } from './payment/errors'
import { formatWon, MIN_INSTALLMENT_AMOUNT, validateTerms, type Terms, type TermsErrors } from './payment/terms'
import { STORES, type StoreId } from './store/catalog'
import { changeQuantity, orderItems, removeItem, toOrder, type Cart, type Order } from './store/cart'
import { FAST } from './styles/motion'

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
  | { step: 'scan'; order: Order; terms: Terms; notice: string | null; rejectedToken: string | null; entry: number }
  | { step: 'sending'; attempt: Attempt }
  | { step: 'unknown'; attempt: Attempt; message: string }
  | { step: 'waiting'; attempt: Attempt; paymentId: number; orderNo: string | null; deadlineAt: number }
  | { step: 'receipt'; result: PaymentResult; attempt: Attempt }
  | { step: 'fatal'; message: string; config: boolean }

const EMPTY_PLAN: PlanInput = { plan: 'LUMP_SUM', installmentMonths: '' }
const EMPTY_CARTS: Record<StoreId, Cart> = { starbucks: {}, gs25: {} }
const CONFIRM_WINDOW_MS = 60_000

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
  const scanEntry = useRef(0)

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

  /**
   * 요청이 트레이가 닫히기 전에 실패하면 트레이가 언마운트되지 않고 되살아나, 한 번 읽고 멈춘 카메라가 그대로 남는다.
   * 스캔에 들어올 때마다 회차를 올려 ScanStep을 새로 만든다.
   */
  const showScan = (order: Order, terms: Terms, notice: string | null = null, rejectedToken: string | null = null) => {
    scanEntry.current += 1
    setPhase({ step: 'scan', order, terms, notice, rejectedToken, entry: scanEntry.current })
  }

  const toScan = (order: Order) => {
    const validated = validateTerms({ amount: String(order.total), ...planInput })
    if ('errors' in validated) {
      setPhase({ step: 'method', order, errors: validated.errors, notice: null })
      return
    }
    showScan(order, validated.terms)
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
          showScan(order, terms, `${failure.message} (${formatToken(attempt.body.barcodeToken)})`, attempt.body.barcodeToken)
          break
        case 'retry':
          setPhase({ step: 'unknown', attempt, message: failure.message })
          break
        case 'config':
          setPhase({ step: 'fatal', message: failure.message, config: true })
          break
        case 'discard':
          showScan(order, terms, failure.message)
          break
      }
    } finally {
      sendingRef.current = false
    }
  }

  const showOrder = phase.step === 'order' || phase.step === 'method' || phase.step === 'cash' || phase.step === 'scan'
  const surface = phase.step === 'home' || showOrder ? 'canvas' : 'flow'

  return (
    <MotionConfig reducedMotion="user" transition={FAST}>
      <div className="app">
        <AppHeader
          store={store}
          step={currentStep(phase)}
          mock={config.mockResult}
          onChangeStore={phase.step === 'order' ? leaveStore : undefined}
        />

        <main className="app-main" data-surface={surface}>
          {phase.step === 'home' && (
            <StoreSelect
              onSelect={(id) => {
                // 전체화면은 사용자 입력 안에서만 요청할 수 있다
                enterFullscreenOnFirstTouch()
                openStore(id)
              }}
            />
          )}

          {/* 결제수단·현금·스캔은 주문 화면을 그대로 둔 채 트레이로 띄운다. 그동안 주문은 고칠 수 없다 */}
          {showOrder && store && storeId && (
            <OrderStep
              store={store}
              cart={carts[storeId]}
              locked={phase.step !== 'order'}
              onChange={(itemId, delta) => updateCart((cart) => changeQuantity(cart, itemId, delta))}
              onRemove={(itemId) => updateCart((cart) => removeItem(cart, itemId))}
              onClear={() => updateCart(() => ({}))}
              onCheckout={checkout}
            />
          )}

          {phase.step === 'cashDone' && (
            <StatusScreen
              tone="approved"
              graphic={<Character pose="pay" size={160} />}
              lead="현금 결제를 완료했어요"
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

          {phase.step === 'sending' && (
            <StatusScreen
              tone="checking"
              title="결제를 요청하고 있어요"
              description={`결제 코드 ${formatToken(phase.attempt.body.barcodeToken)}`}
              rows={[
                { label: '주문 금액', value: formatWon(phase.attempt.terms.amount) },
                { label: '결제 수단', value: PAYMENT_METHOD },
              ]}
            />
          )}

          {phase.step === 'unknown' && (
            <UnknownScreen message={phase.message} onRetry={() => send(phase.attempt)} onNewOrder={newOrder} />
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
              onRetry={() => showScan(phase.attempt.order, phase.attempt.terms)}
            />
          )}

          {phase.step === 'fatal' && (
            <FatalScreen
              message={phase.message}
              isConfig={phase.config}
              canLeaveStore={storeId !== null}
              onNewOrder={newOrder}
              onLeaveStore={leaveStore}
            />
          )}
        </main>

        <AnimatePresence>
          {(phase.step === 'method' || phase.step === 'cash' || phase.step === 'scan') && (
            <Tray
              key="tray"
              title={TRAY_META[phase.step].title}
              width={TRAY_META[phase.step].width}
              onClose={phase.step === 'method' ? () => setPhase({ step: 'order' }) : undefined}
              onBack={
                phase.step === 'method'
                  ? undefined
                  : () => setPhase({ step: 'method', order: phase.order, errors: {}, notice: null })
              }
            >
              <Amount className="tray-amount" layoutId="order-total" value={phase.order.total} size="lg" />

              {phase.step === 'method' && (
                <TrayStep key="method">
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
                  />
                </TrayStep>
              )}

              {phase.step === 'cash' && (
                <TrayStep key="cash">
                  <CashStep
                    order={phase.order}
                    onDone={(received) => setPhase({ step: 'cashDone', order: phase.order, received })}
                  />
                </TrayStep>
              )}

              {phase.step === 'scan' && (
                <TrayStep key={`scan-${phase.entry}`}>
                  <ScanInTray
                    terms={phase.terms}
                    notice={phase.notice}
                    rejectedToken={phase.rejectedToken}
                    onToken={(token) => startAttempt(phase.order, phase.terms, token)}
                  />
                </TrayStep>
              )}
            </Tray>
          )}
        </AnimatePresence>

        <div className="portrait-guard" role="alert">
          가로로 돌려 주세요
        </div>
      </div>
    </MotionConfig>
  )
}

/**
 * 트레이가 닫히는 동안에도 스캔 화면은 잠깐 남아 있고 카메라도 켜져 있다.
 * 그 사이에 읽힌 QR이 두 번째 결제가 되지 않게 버린다.
 */
function ScanInTray({ onToken, ...props }: ComponentProps<typeof ScanStep>) {
  const isPresent = useIsPresent()
  return (
    <ScanStep
      {...props}
      onToken={(token) => {
        if (isPresent) onToken(token)
      }}
    />
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
