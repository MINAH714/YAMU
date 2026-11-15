import { AnimatePresence, MotionConfig } from 'motion/react'
import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState, type ComponentProps } from 'react'
import { ApiError, newIdempotencyKey } from './api/client'
import { createPaymentRequest, type PaymentRequestBody, type PaymentResult } from './api/pos'
import {
  cancelPayment,
  getPaymentHistory,
  type PaymentCancelBody,
  type PaymentCancelResult,
  type PaymentHistoryItem,
} from './api/posHistory'
import { Amount } from './components/Amount'
import { AppHeader } from './components/AppHeader'
import { CancelDoneScreen, CancelFailedScreen, CancelingScreen, CancelUnknownScreen } from './components/CancelScreens'
import { CancelScanStep } from './components/CancelScanStep'
import { CashStep } from './components/CashStep'
import { Character } from './components/Character'
import { FatalScreen, UnknownScreen } from './components/FlowScreens'
import { OrderStep } from './components/OrderStep'
import { PaymentHistory } from './components/PaymentHistory'
import { PaymentMethodStep, type PlanInput } from './components/PaymentMethodStep'
import { Receipt } from './components/Receipt'
import { ScanStep } from './components/ScanStep'
import { ScreenLayer, type SwapPlan } from './components/ScreenLayer'
import { StatusScreen } from './components/StatusScreen'
import { StoreSelect } from './components/StoreSelect'
import { Tray, type TrayAppear, type TrayExit } from './components/Tray'
import { useTrayStepLive } from './components/trayLive'
import { TRAY_META } from './components/trayMeta'
import { BoardPill, TermsPill, WaitBoard } from './components/WaitBoard'
import { WaitingStep } from './components/WaitingStep'
import { config, missingConfig } from './config'
import { enterFullscreenOnFirstTouch } from './hooks/useFullscreen'
import { FORCE_REDUCE_MOTION, useReduceMotion } from './hooks/useReduceMotion'
import { formatToken } from './payment/barcode'
import { chargedOf } from './payment/history'
import { classifyCancelError } from './payment/cancelErrors'
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
 * 결제 취소 한 건의 요청. 결제 요청과 같이 멱등 키는 이 본문과 한 묶음이다.
 * 결과를 모를 때는 같은 키·같은 본문으로 다시 보내고, 바코드를 다시 스캔하면 새 키를 만든다.
 */
type CancelAttempt = { idempotencyKey: string; paymentId: number; body: PaymentCancelBody; item: PaymentHistoryItem }

/** 결제 내역 목록. items가 null이면 불러오는 중 */
type HistoryList = { items: PaymentHistoryItem[] | null; error: string | null }

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
  // 결제 내역·취소. 장바구니는 건드리지 않으므로 주문 화면으로 돌아가면 고르던 상품이 그대로 있다
  | { step: 'history'; selectedId: number | null }
  | { step: 'cancelScan'; item: PaymentHistoryItem; entry: number }
  | { step: 'canceling'; cancel: CancelAttempt }
  | { step: 'cancelUnknown'; cancel: CancelAttempt; message: string }
  | { step: 'cancelDone'; item: PaymentHistoryItem; result: PaymentCancelResult }
  | { step: 'cancelFailed'; item: PaymentHistoryItem; message: string; already: boolean }
  | { step: 'fatal'; message: string; config: boolean }

const EMPTY_PLAN: PlanInput = { plan: 'LUMP_SUM', installmentMonths: '' }
const EMPTY_CARTS: Record<StoreId, Cart> = { starbucks: {}, gs25: {} }
const CONFIRM_WINDOW_MS = 60_000
/**
 * 뒤로 가기가 POS 페이지를 떠나지 않게 쌓는 브라우저 기록. 깊이 1 매장(주문 화면), 2 결제수단, 3 현금·QR.
 *
 * Chrome은 사용자 입력 없이 쌓은 기록을 뒤로·앞으로 버튼에서 건너뛴다(history manipulation intervention).
 * 뒤로 가기는 입력으로 치지 않으므로 기록은 버튼을 누르는 순간에만 쌓고, popstate 안에서는 쌓지 않는다.
 * 결제를 보낸 뒤 화면은 현금·QR(깊이 3)에서 이어지므로, 뒤로 가기를 받아도 아래 기록 3개가 남아 있는 동안은 화면을 지킨다.
 * 결제 내역은 결제수단과 같은 깊이 2, 취소 스캔은 깊이 3을 쓴다. 취소를 보낸 뒤 화면도 뒤로 가기를 받지 않는다.
 */
const ENTRY_DEPTH = { store: 1, method: 2, pay: 3 } as const
type EntryName = keyof typeof ENTRY_DEPTH
const ENTRY_NAMES: EntryName[] = ['store', 'method', 'pay']

/** 지금 서 있는 기록의 깊이. POS가 쌓은 기록이 아니면 0 */
function currentDepth(): number {
  const state: unknown = history.state
  if (typeof state !== 'object' || state === null) return 0
  const name = (state as { yamuPos?: unknown }).yamuPos
  return typeof name === 'string' && name in ENTRY_DEPTH ? ENTRY_DEPTH[name as EntryName] : 0
}

function entryAt(depth: number) {
  return { yamuPos: ENTRY_NAMES[depth - 1] }
}

/** 이 단계에 있어야 할 기록 깊이. 결제를 보낸 뒤 단계는 null(뒤로 가기를 받아도 움직이지 않는다) */
function expectedDepth(phase: Phase): number | null {
  switch (phase.step) {
    case 'home':
      return 0
    case 'order':
      return 1
    case 'method':
    case 'history':
      return 2
    case 'cash':
    case 'scan':
    case 'cancelScan':
      return 3
    default:
      return null
  }
}

/** 결제를 보내기 전 단계. 주문 화면이 보이고(트레이 포함) 장바구니가 아직 결제와 묶이지 않았다 */
function isBeforePayment(phase: Phase): boolean {
  return phase.step === 'order' || phase.step === 'method' || phase.step === 'cash' || phase.step === 'scan'
}

/** 결제를 보냈고 결과를 아직 모르는 단계. 이때 페이지를 떠나면 결과를 놓치므로 떠나기 전에 한 번 묻는다 */
function isAwaitingResult(phase: Phase): boolean {
  return (
    phase.step === 'sending' ||
    phase.step === 'unknown' ||
    phase.step === 'waiting' ||
    phase.step === 'canceling' ||
    phase.step === 'cancelUnknown'
  )
}

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
  // 취소도 같은 이유로 하나만 나간다
  const cancelingRef = useRef(false)
  const scanEntry = useRef(0)
  // 지금 그려진 단계. 걷히는 트레이·옛 단계에 남은 콜백이 지난 단계를 보고 결제를 보내지 않게 비교한다
  const phaseRef = useRef(phase)
  useLayoutEffect(() => {
    phaseRef.current = phase
  })
  const [historyList, setHistoryList] = useState<HistoryList>({ items: null, error: null })
  // 다시 불러오기를 연달아 누르면 늦게 온 옛 응답이 새 목록을 덮지 않게 마지막 요청만 받는다
  const historyRequest = useRef(0)
  // 화면을 먼저 바꾸고 기록만 걷어 낼 때(history.back) 따라오는 popstate는 사용자의 뒤로 가기가 아니다
  const ignoreNextPop = useRef(false)
  // 결제수단 트레이가 커져 나오고 닫히면 줄어드는 '결제하기' 버튼
  const checkoutRef = useRef<HTMLButtonElement>(null)
  // 다음에 뜰 트레이가 버튼에서 커질지(결제하기), 제자리에서 나타날지(결제 요청 실패·다시 결제)
  const [trayAppear, setTrayAppear] = useState<TrayAppear>('fade')
  // 결제 트레이가 화면에 있는 동안(닫혀 줄어드는 중 포함). 그동안 주문 화면은 눌리지 않고 진짜 버튼은 숨는다
  const trayOpen = phase.step === 'method' || phase.step === 'cash' || phase.step === 'scan'
  const [trayPresent, setTrayPresent] = useState(false)
  if (trayOpen && !trayPresent) setTrayPresent(true)

  // 화면 통째 교체(0.2초 페이드). 트레이가 제자리에서 사라진 뒤 들어오는 화면은 그만큼 기다린다
  const reduce = useReduceMotion()
  const key = screenKey(phase)
  const [swap, setSwap] = useState<SwapPlan>({ key, delayMs: 0, reveal: false, reduce })
  if (swap.key !== key) {
    const fromTray = swap.key === 'order' && (key === 'board' || key === 'cashDone')
    setSwap({
      key,
      delayMs: fromTray ? (reduce ? 150 : 200) : 0,
      // 결제 요청이 실패해 주문 화면(트레이)으로 돌아가면 판이 위에서 걷힌다
      reveal: swap.key === 'board' && key === 'order',
      reduce,
    })
  }

  const store = storeId ? STORES[storeId] : null

  const updateCart = (update: (cart: Cart) => Cart) => {
    if (!storeId) return
    setCarts((current) => ({ ...current, [storeId]: update(current[storeId]) }))
  }

  /** 기록을 n개 걷어 낸다. 화면은 이미 바꿨으므로 따라오는 popstate는 무시한다 */
  const dropEntries = (count: number) => {
    if (count <= 0) return
    ignoreNextPop.current = true
    history.go(-count)
  }

  /** 기록이 target보다 깊으면 걷어 낸다. 쌓지는 않으므로 사용자 입력 밖(요청 실패 등)에서도 부를 수 있다 */
  const trimHistory = (target: number) => dropEntries(currentDepth() - target)

  /**
   * 버튼을 누른 순간에 기록 깊이를 target에 맞춘다. 모자라면 쌓고 넘치면 걷어 낸다.
   * 쌓는 일은 사용자 입력 안에서만 해야 Chrome이 건너뛰지 않으므로 클릭 처리기에서만 부른다.
   */
  const syncHistory = (target: number) => {
    const depth = currentDepth()
    if (depth > target) {
      dropEntries(depth - target)
      return
    }
    for (let next = depth + 1; next <= target; next += 1) history.pushState(entryAt(next), '')
  }

  const openStore = (id: StoreId) => {
    setStoreId(id)
    setPlanInput(EMPTY_PLAN)
    setPhase({ step: 'order' })
    // 새로고침·앞으로 가기로 남은 기록 위에 서 있으면 그 자리를 매장 기록으로 바꿔 더 쌓이지 않게 한다
    if (currentDepth() > 0) history.replaceState(entryAt(ENTRY_DEPTH.store), '')
    else history.pushState(entryAt(ENTRY_DEPTH.store), '')
  }

  /** 매장 선택으로 돌아간다. 다시 들어왔을 때 고르던 상품이 남지 않게 장바구니도 비운다 */
  const leaveStore = () => {
    setStoreId(null)
    setCarts(EMPTY_CARTS)
    setPlanInput(EMPTY_PLAN)
    setPhase({ step: 'home' })
  }

  /** 현금·QR에서 결제수단으로 한 칸 돌아간다 */
  const backToMethod = (order: Order) => setPhase({ step: 'method', order, errors: {}, notice: null })

  /** 뒤로 가기가 아닌 길(매장 변경 버튼 등)로 나갈 때. 매장·트레이에서 쌓은 기록도 걷어 낸다 */
  const leaveStoreWithHistory = () => {
    leaveStore()
    trimHistory(0)
  }

  /**
   * 브라우저·태블릿 뒤로·앞으로 가기. 여기서는 기록을 쌓지 않는다(위 ENTRY_DEPTH 설명).
   * 내려앉은 기록 깊이가 지금 단계보다 깊으면 앞으로 가기로 보고 기록만 되돌린다(장바구니를 건드리지 않는다).
   * 아니면 뒤로 가기로 보고 한 칸 올라간다.
   * - 현금·QR → 결제수단 → 주문 화면(장바구니 유지) → 매장 선택(장바구니 비움)
   * - 결제를 보낸 뒤(요청 중·결과 불명·승인 대기·영수증·현금 완료·오류)에는 아무것도 하지 않는다.
   *   그 화면들은 '다음 주문'·'새 주문'으로만 넘어가고, 승인 대기 확인이 중간에 끊기지 않는다.
   *   아래 기록이 다 떨어져 페이지를 떠나려 하면 결과를 기다리는 동안은 beforeunload로 한 번 묻는다.
   */
  const onHistoryMove = useEffectEvent(() => {
    if (ignoreNextPop.current) {
      ignoreNextPop.current = false
      return
    }
    if (!storeId) return
    const expected = expectedDepth(phase)
    if (expected === null || sendingRef.current || cancelingRef.current) return
    const landed = currentDepth()
    if (landed > expected) {
      dropEntries(landed - expected)
      return
    }
    const next = expected - 1
    if (phase.step === 'cash' || phase.step === 'scan') backToMethod(phase.order)
    else if (phase.step === 'cancelScan') setPhase({ step: 'history', selectedId: phase.item.paymentId })
    else if (phase.step === 'method' || phase.step === 'history') setPhase({ step: 'order' })
    else leaveStore()
    // 새로고침 등으로 남은 기록 위에 내려앉았으면 마저 걷어 다음 뒤로 가기가 헛돌지 않게 한다
    if (landed > next) dropEntries(landed - next)
  })

  /** 다른 페이지로 갔다가 돌아와 브라우저가 이 화면을 그대로 되살렸을 때(bfcache)도 결제 전이면 비운다 */
  const onPageRestored = useEffectEvent(() => {
    if (storeId && isBeforePayment(phase) && !sendingRef.current) leaveStoreWithHistory()
  })

  useEffect(() => {
    if (!isAwaitingResult(phase)) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      // 옛 브라우저는 returnValue가 있어야 묻는다
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [phase])

  useEffect(() => {
    const onPopState = () => onHistoryMove()
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) onPageRestored()
    }
    window.addEventListener('popstate', onPopState)
    window.addEventListener('pageshow', onPageShow)
    return () => {
      window.removeEventListener('popstate', onPopState)
      window.removeEventListener('pageshow', onPageShow)
    }
  }, [])

  /** 결제가 끝났거나 결과를 기다리지 않기로 한 뒤. 그 주문은 끝났으므로 장바구니를 비운다 */
  const newOrder = () => {
    updateCart(() => ({}))
    setPlanInput(EMPTY_PLAN)
    setPhase({ step: 'order' })
    syncHistory(ENTRY_DEPTH.store)
  }

  /** 트레이 닫기 버튼. 장바구니는 그대로 두고 주문 화면으로 돌아간다 */
  const closeTray = () => {
    setPhase({ step: 'order' })
    syncHistory(ENTRY_DEPTH.store)
  }

  /** 트레이 안 '뒤로' 아이콘 */
  const backToMethodByButton = (order: Order) => {
    backToMethod(order)
    syncHistory(ENTRY_DEPTH.method)
  }

  const checkout = () => {
    if (!storeId) return
    const order = toOrder(STORES[storeId], carts[storeId])
    if (order.total <= 0) return
    // 주문을 줄여 할부 최소 금액 아래로 내려가면 고르던 할부를 일시불로 되돌린다
    if (order.total < MIN_INSTALLMENT_AMOUNT) setPlanInput(EMPTY_PLAN)
    setTrayAppear('origin')
    setPhase({ step: 'method', order, errors: {}, notice: null })
    syncHistory(ENTRY_DEPTH.method)
  }

  /**
   * 요청이 트레이가 닫히기 전에 실패하면 트레이가 언마운트되지 않고 되살아나, 한 번 읽고 멈춘 카메라가 그대로 남는다.
   * 스캔에 들어올 때마다 회차를 올려 ScanStep을 새로 만든다.
   * 결제수단에서 넘어올 때가 아니면(요청 실패·다시 결제) 트레이가 새로 뜨므로 버튼에서 커지지 않고 제자리에서 나타난다.
   */
  const showScan = (order: Order, terms: Terms, notice: string | null = null, rejectedToken: string | null = null) => {
    scanEntry.current += 1
    setTrayAppear('fade')
    setPhase({ step: 'scan', order, terms, notice, rejectedToken, entry: scanEntry.current })
  }

  const toScan = (order: Order) => {
    const validated = validateTerms({ amount: String(order.total), ...planInput })
    if ('errors' in validated) {
      setPhase({ step: 'method', order, errors: validated.errors, notice: null })
      return
    }
    showScan(order, validated.terms)
    syncHistory(ENTRY_DEPTH.pay)
  }

  /**
   * 지금 보이는 스캔 단계(같은 회차)에서 읽은 바코드만 결제로 보낸다. 같은 QR이 두 번 결제되지 않게,
   * 걷히는 옛 단계나 사라지는 트레이의 카메라·입력칸에서 온 것은 버린다(트레이 쪽 useTrayStepLive와 겹쳐 막는다).
   */
  const onScanToken = (entry: number, order: Order, terms: Terms, token: string) => {
    const now = phaseRef.current
    if (now.step !== 'scan' || now.entry !== entry) return
    startAttempt(order, terms, token)
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
          setTrayAppear('fade')
          setPhase({ step: 'method', order, errors: failure.errors, notice: failure.message })
          // 응답을 기다린 뒤라 사용자 입력 밖이다. 걷어 내기만 한다
          trimHistory(ENTRY_DEPTH.method)
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

  /** 이 매장 POS의 결제 내역을 새로 불러온다. 결과 Mock이어도 실제 BE를 부른다 */
  const loadHistory = async () => {
    if (!storeId) return
    const requestId = ++historyRequest.current
    setHistoryList({ items: null, error: null })
    try {
      const items = await getPaymentHistory(config.stores[storeId].posCode)
      if (requestId === historyRequest.current) setHistoryList({ items, error: null })
    } catch (error) {
      if (requestId !== historyRequest.current) return
      if (error instanceof ApiError && error.code === 'INVALID_POS_KEY') {
        setPhase({ step: 'fatal', message: 'POS 키가 없거나 올바르지 않아요. VITE_POS_KEY 설정을 확인해 주세요.', config: true })
        return
      }
      const reason = error instanceof Error ? `${error.message} ` : ''
      setHistoryList({ items: null, error: `${reason}결제 내역을 불러오지 못했어요.` })
    }
  }

  /** 헤더의 결제 내역 버튼. 장바구니는 그대로 둔다 */
  const openHistory = () => {
    setPhase({ step: 'history', selectedId: null })
    syncHistory(ENTRY_DEPTH.method)
    void loadHistory()
  }

  /** 취소를 끝냈거나 그만두고 내역으로 돌아간다. 상태가 바뀌었을 수 있으므로 다시 불러온다 */
  const returnToHistory = (selectedId: number) => {
    setPhase({ step: 'history', selectedId })
    syncHistory(ENTRY_DEPTH.method)
    void loadHistory()
  }

  /** 스캔에 들어올 때마다 회차를 올려 카메라를 새로 켠다(showScan과 같은 이유) */
  const toCancelScan = (item: PaymentHistoryItem) => {
    scanEntry.current += 1
    setPhase({ step: 'cancelScan', item, entry: scanEntry.current })
    syncHistory(ENTRY_DEPTH.pay)
  }

  const startCancel = (item: PaymentHistoryItem, barcodeToken: string) => {
    if (!storeId) return
    const body: PaymentCancelBody = { posCode: config.stores[storeId].posCode, barcodeToken }
    sendCancel({ idempotencyKey: newIdempotencyKey(), paymentId: item.paymentId, body, item })
  }

  const sendCancel = async (cancel: CancelAttempt) => {
    if (cancelingRef.current) return
    cancelingRef.current = true
    setPhase({ step: 'canceling', cancel })
    try {
      const result = await cancelPayment(cancel.paymentId, cancel.body, cancel.idempotencyKey)
      setPhase({ step: 'cancelDone', item: cancel.item, result })
    } catch (error) {
      const failure = classifyCancelError(error)
      switch (failure.kind) {
        case 'retry':
          setPhase({ step: 'cancelUnknown', cancel, message: failure.message })
          break
        case 'rejected':
        case 'already':
          setPhase({ step: 'cancelFailed', item: cancel.item, message: failure.message, already: failure.kind === 'already' })
          break
        case 'config':
          setPhase({ step: 'fatal', message: failure.message, config: true })
          break
      }
    } finally {
      cancelingRef.current = false
    }
  }

  const showOrder = isBeforePayment(phase)
  const showHistory = phase.step === 'history' || phase.step === 'cancelScan'
  const surface = phase.step === 'home' || showOrder ? 'canvas' : 'flow'

  return (
    <MotionConfig reducedMotion={FORCE_REDUCE_MOTION ? 'always' : 'user'} transition={FAST}>
      <div className="app">
        <AppHeader
          store={store}
          step={currentStep(phase)}
          mock={config.mockResult}
          onChangeStore={phase.step === 'order' ? leaveStoreWithHistory : undefined}
          onHistory={phase.step === 'order' ? openHistory : undefined}
        />

        <main className="app-main" data-surface={surface}>
          {/* 화면 통째 교체. 같은 이름의 화면 층 안에서는 페이드하지 않는다 */}
          <div className="screens">
            <AnimatePresence initial={false} custom={swap}>
              <ScreenLayer key={key} plan={swap} surface={surface}>
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
                    locked={phase.step !== 'order' || trayPresent}
                    checkoutRef={checkoutRef}
                    checkoutHidden={trayPresent}
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

                {/* 요청 중과 승인 대기는 같은 판이다(같은 화면 층이라 다시 들어오지 않는다) */}
                {phase.step === 'sending' && (
                  <WaitBoard
                    title="결제를 요청하고 있어요"
                    pills={
                      <>
                        <TermsPill terms={phase.attempt.terms} />
                        <BoardPill>결제 코드 {formatToken(phase.attempt.body.barcodeToken)}</BoardPill>
                      </>
                    }
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
                    onRetry={() => {
                      showScan(phase.attempt.order, phase.attempt.terms)
                      syncHistory(ENTRY_DEPTH.pay)
                    }}
                  />
                )}

                {showHistory && (
                  <PaymentHistory
                    items={historyList.items}
                    error={historyList.error}
                    onReload={() => void loadHistory()}
                    selectedId={phase.step === 'history' ? phase.selectedId : phase.item.paymentId}
                    inert={phase.step !== 'history'}
                    onSelect={(paymentId) => setPhase({ step: 'history', selectedId: paymentId })}
                    onCancel={toCancelScan}
                    onBack={closeTray}
                  />
                )}

                {phase.step === 'canceling' && <CancelingScreen item={phase.cancel.item} />}

                {phase.step === 'cancelUnknown' && (
                  <CancelUnknownScreen
                    item={phase.cancel.item}
                    message={phase.message}
                    onRetry={() => sendCancel(phase.cancel)}
                    onBack={() => returnToHistory(phase.cancel.paymentId)}
                  />
                )}

                {phase.step === 'cancelDone' && (
                  <CancelDoneScreen
                    item={phase.item}
                    result={phase.result}
                    onBack={() => returnToHistory(phase.item.paymentId)}
                  />
                )}

                {phase.step === 'cancelFailed' && (
                  <CancelFailedScreen
                    item={phase.item}
                    message={phase.message}
                    already={phase.already}
                    onRescan={() => toCancelScan(phase.item)}
                    onBack={() => returnToHistory(phase.item.paymentId)}
                  />
                )}

                {phase.step === 'fatal' && (
                  <FatalScreen
                    message={phase.message}
                    isConfig={phase.config}
                    canLeaveStore={storeId !== null}
                    onNewOrder={newOrder}
                    onLeaveStore={leaveStoreWithHistory}
                  />
                )}
              </ScreenLayer>
            </AnimatePresence>
          </div>
        </main>

        {/* 주문 화면으로 돌아가면 버튼 자리로 줄어들고, 결제가 나가면(요청 중·현금 완료) 제자리에서 사라진다 */}
        <AnimatePresence
          custom={(phase.step === 'order' ? 'origin' : 'fade') satisfies TrayExit}
          onExitComplete={() => setTrayPresent(false)}
        >
          {trayOpen && (
            <Tray
              key="tray"
              stepKey={phase.step === 'scan' ? `scan-${phase.entry}` : phase.step}
              title={TRAY_META[phase.step].title}
              width={TRAY_META[phase.step].width}
              origin={checkoutRef}
              appear={trayAppear}
              onClose={phase.step === 'method' ? closeTray : undefined}
              onBack={
                phase.step === 'method'
                  ? undefined
                  : () => backToMethodByButton(phase.order)
              }
            >
              <Amount className="tray-amount" value={phase.order.total} size="lg" />

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
                  onCash={() => {
                    setPhase({ step: 'cash', order: phase.order })
                    syncHistory(ENTRY_DEPTH.pay)
                  }}
                />
              )}

              {phase.step === 'cash' && (
                <CashStep
                  order={phase.order}
                  onDone={(received) => setPhase({ step: 'cashDone', order: phase.order, received })}
                />
              )}

              {phase.step === 'scan' && (
                <ScanInTray
                  terms={phase.terms}
                  notice={phase.notice}
                  rejectedToken={phase.rejectedToken}
                  onToken={(token) => onScanToken(phase.entry, phase.order, phase.terms, token)}
                />
              )}
            </Tray>
          )}
        </AnimatePresence>

        <AnimatePresence>
          {phase.step === 'cancelScan' && (
            <Tray
              key="cancel-tray"
              stepKey={`cancel-${phase.entry}`}
              title="고객 바코드를 스캔해 주세요"
              width={720}
              onBack={() => {
                setPhase({ step: 'history', selectedId: phase.item.paymentId })
                syncHistory(ENTRY_DEPTH.method)
              }}
            >
              <Amount className="tray-amount" value={chargedOf(phase.item)} size="lg" />
              <CancelScanInTray onToken={(token) => startCancel(phase.item, token)} />
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
 * 트레이가 사라지는 동안에도, 단계가 바뀌어 옛 단계가 걷히는 동안에도 스캔 화면은 잠깐 남아 있고 카메라도 켜져 있다.
 * 그 사이에 읽힌 QR이 두 번째 결제가 되지 않게 버린다.
 */
function ScanInTray({ onToken, ...props }: ComponentProps<typeof ScanStep>) {
  const live = useTrayStepLive()
  return (
    <ScanStep
      {...props}
      onToken={(token) => {
        if (live) onToken(token)
      }}
    />
  )
}

/** 닫히는 트레이의 카메라가 읽은 바코드로 취소가 나가지 않게 버린다(ScanInTray와 같은 이유) */
function CancelScanInTray({ onToken, ...props }: ComponentProps<typeof CancelScanStep>) {
  const live = useTrayStepLive()
  return (
    <CancelScanStep
      {...props}
      onToken={(token) => {
        if (live) onToken(token)
      }}
    />
  )
}

/**
 * 화면 층의 이름. 같은 이름이면 화면이 바뀌어도 페이드하지 않는다.
 * 주문 화면 위 트레이 단계들은 주문 화면 하나, 요청 중과 승인 대기는 대기 판 하나다.
 */
function screenKey(phase: Phase): string {
  switch (phase.step) {
    case 'order':
    case 'method':
    case 'cash':
    case 'scan':
      return 'order'
    case 'sending':
    case 'waiting':
      return 'board'
    case 'history':
    case 'cancelScan':
      return 'history'
    default:
      return phase.step
  }
}

/** 헤더 단계 표시. 0이면 표시하지 않는다 */
function currentStep(phase: Phase): number {
  switch (phase.step) {
    case 'home':
    case 'fatal':
    case 'history':
    case 'cancelScan':
    case 'canceling':
    case 'cancelUnknown':
    case 'cancelDone':
    case 'cancelFailed':
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
