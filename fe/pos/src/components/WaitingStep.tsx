import { useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { applyMockAction, type MockAction } from '../api/mockResult'
import { getPaymentResult, type PaymentResult } from '../api/pos'
import { config } from '../config'
import { planLabel, type Terms } from '../payment/terms'
import '../styles/result.css'
import { Amount } from './Amount'
import { Character } from './Character'
import { ConfirmButton } from './ConfirmButton'
import { PAYMENT_AMOUNT_ID, PAYMENT_METHOD } from './Receipt'
import { PAYMENT_STATUS_MARK_ID, StatusMark } from './StatusScreen'

type Props = {
  paymentId: number
  /** 결제를 요청한 매장의 posCode. 결과도 이 posCode로만 볼 수 있다 */
  posCode: string
  orderNo: string | null
  terms: Terms
  /** 이 POS 시계 기준 마감 시각(ms). 서버 시계와의 차이를 뺀 값 */
  deadlineAt: number
  onFinal: (result: PaymentResult) => void
  onFatal: (message: string, config: boolean) => void
  onAbandon: () => void
}

const POLL_INTERVAL_MS = 2_000
// 마감 직후에는 서버가 아직 EXPIRED로 바꾸지 않았을 수 있다. 이만큼 더 물어본 뒤 멈춘다
const AFTER_DEADLINE_GRACE_MS = 10_000
// 마감이 이만큼 남으면 카운트다운이 주의 색으로 바뀐다
const URGENT_MS = 10_000

/**
 * DECLINED는 마감 전이면 최종이 아니다. 사용자가 앱에서 다른 카드로 다시 승인할 수 있다(명세 Q1).
 * 서버가 retryable=false를 주면 그때는 바로 최종으로 본다.
 */
function isFinal(result: PaymentResult, pastDeadline: boolean): boolean {
  switch (result.status) {
    case 'APPROVED':
    case 'EXPIRED':
    case 'CANCELED':
      return true
    case 'DECLINED':
      return pastDeadline || result.retryable === false
    default:
      return false
  }
}

export function WaitingStep({ paymentId, posCode, orderNo, terms, deadlineAt, onFinal, onFatal, onAbandon }: Props) {
  const [now, setNow] = useState(() => Date.now())
  // 카운트다운 선의 기준. 처음 뜬 순간 남아 있던 시간을 꽉 찬 선으로 그린다
  const [totalMs] = useState(() => Math.max(deadlineAt - Date.now(), 1))
  const [last, setLast] = useState<PaymentResult | null>(null)
  const [connectionError, setConnectionError] = useState<string | null>(null)
  const [stopped, setStopped] = useState(false)
  const [generation, setGeneration] = useState(0)
  const callbacks = useRef({ onFinal, onFatal })

  useEffect(() => {
    callbacks.current = { onFinal, onFatal }
  })

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(timer)
  }, [])

  // 응답이 오고 나서 2초 뒤에 다음 조회를 한다(setInterval이면 느린 응답이 겹친다)
  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const poll = async () => {
      try {
        const result = await getPaymentResult(paymentId, posCode)
        if (cancelled) return
        setLast(result)
        setConnectionError(null)
        const pastDeadline = Date.now() > deadlineAt
        if (isFinal(result, pastDeadline)) {
          callbacks.current.onFinal(result)
          return
        }
        // 승인 처리 중(PROCESSING)이면 금융망 결과를 기다리므로 마감이 지나도 계속 본다
        if (result.status !== 'PROCESSING' && Date.now() > deadlineAt + AFTER_DEADLINE_GRACE_MS) {
          setStopped(true)
          return
        }
      } catch (error) {
        if (cancelled) return
        if (error instanceof ApiError && error.code === 'INVALID_POS_KEY') {
          callbacks.current.onFatal('POS 키가 올바르지 않아 결과를 볼 수 없어요. VITE_POS_KEY를 확인해 주세요.', true)
          return
        }
        if (error instanceof ApiError && error.status === 404) {
          callbacks.current.onFatal('결제를 찾을 수 없어요. 다른 POS 키로 만든 결제이거나 없는 결제예요.', false)
          return
        }
        setConnectionError(error instanceof Error ? error.message : '결과를 확인하지 못했어요.')
        if (Date.now() > deadlineAt + AFTER_DEADLINE_GRACE_MS) {
          setStopped(true)
          return
        }
      }
      timer = setTimeout(poll, POLL_INTERVAL_MS)
    }

    poll()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [paymentId, posCode, deadlineAt, generation])

  const pastDeadline = now > deadlineAt

  // 승인 처리 중(카드사 결과 대기)·연결 오류·마감 뒤 결과 대기는 "확인 중"으로 보인다
  const checking = stopped || last?.status === 'PROCESSING' || connectionError !== null || pastDeadline
  const declined = last?.status === 'DECLINED'

  return (
    <WaitingView
      paymentId={paymentId}
      orderNo={orderNo}
      terms={terms}
      remainingMs={deadlineAt - now}
      totalMs={totalMs}
      checking={checking}
      stopped={stopped}
      declined={declined}
      connectionError={connectionError}
      onRecheck={() => {
        setStopped(false)
        setGeneration((g) => g + 1)
      }}
      onAbandon={onAbandon}
    />
  )
}

type WaitingViewProps = {
  paymentId: number
  orderNo: string | null
  terms: Terms
  remainingMs: number
  /** 카운트다운 선이 꽉 찼을 때의 남은 시간 */
  totalMs: number
  checking: boolean
  stopped: boolean
  declined: boolean
  connectionError: string | null
  onRecheck: () => void
  onAbandon: () => void
}

/** 승인 대기 화면. 폴링과 판정은 WaitingStep에 있고 여기서는 받은 값만 그린다 */
export function WaitingView({
  paymentId,
  orderNo,
  terms,
  remainingMs,
  totalMs,
  checking,
  stopped,
  declined,
  connectionError,
  onRecheck,
  onAbandon,
}: WaitingViewProps) {
  const remainingSeconds = Math.max(Math.ceil(remainingMs / 1000), 0)
  const ratio = Math.min(Math.max(remainingMs / totalMs, 0), 1)
  const urgent = !checking && remainingMs <= URGENT_MS
  // layoutId가 있는 요소는 다시 그려질 때마다 자리를 잰다. 250ms마다 오는 타이머에 끌려가지 않게 묶어 둔다
  const amount = useMemo(() => <Amount value={terms.amount} size="xl" layoutId={PAYMENT_AMOUNT_ID} />, [terms.amount])
  const mark = useMemo(
    () => <StatusMark state={stopped ? 'warning' : 'pending'} size={24} layoutId={PAYMENT_STATUS_MARK_ID} />,
    [stopped],
  )

  return (
    <section className="waiting">
      <div className="waiting-block">
        <Character pose="thinking" size={128} />
        <div className="waiting-text" aria-live="polite">
          <h2 className="flow-title">{stopped ? '결제 결과를 확인하지 못했어요' : checking ? '결제 결과를 확인하고 있어요' : '고객 앱에서 승인을 기다리고 있어요'}</h2>
          <p className="flow-description">
            {stopped
              ? connectionError ?? '결과 다시 확인을 눌러 주세요.'
              : checking
                ? '결과가 확인될 때까지 잠시 기다려 주세요.'
                : declined
                  ? '카드가 거절됐어요. 고객이 앱에서 다른 카드로 다시 결제할 수 있어요.'
                  : '고객의 YAMU 앱에서 결제를 승인해 주세요.'}
          </p>
        </div>

        {amount}
        <p className="waiting-method">
          {PAYMENT_METHOD}
          {terms.plan === 'INSTALLMENT' && ` · ${planLabel(terms.plan, terms.installmentMonths)}`}
        </p>

        <div className="waiting-timer" data-urgent={urgent}>
          {mark}
          {!checking && (
            <span role="timer">
              남은 시간 <strong>{remainingSeconds}초</strong>
            </span>
          )}
        </div>
        <div className="waiting-countdown" data-urgent={urgent} hidden={checking} aria-hidden="true">
          <span style={{ transform: `scaleX(${ratio})` }} />
        </div>

        {stopped && (
          <button type="button" className="primary waiting-recheck" onClick={onRecheck}>
            결과 다시 확인
          </button>
        )}
      </div>

      <div className="waiting-foot">
        {config.mockResult && <MockPanel paymentId={paymentId} />}
        <span title={orderNo ? `결제 번호 ${paymentId} · 주문 번호 ${orderNo}` : `결제 번호 ${paymentId}`}>
          <ConfirmButton
            label="기다리지 않고 새 결제"
            confirmLabel="고객이 승인하면 이 결제는 그대로 진행돼요. 한 번 더 누르면 새 결제를 시작해요"
            onConfirm={onAbandon}
          />
        </span>
      </div>
    </section>
  )
}

const MOCK_ACTIONS: { action: MockAction; label: string }[] = [
  { action: 'approve', label: '앱 승인' },
  { action: 'approveWithDiscount', label: '승인(즉시할인 10%)' },
  { action: 'decline', label: '거절(재시도 가능)' },
  { action: 'declineFinal', label: '거절(최종)' },
  { action: 'disconnect', label: '연결 끊김 켜기/끄기' },
]

function MockPanel({ paymentId }: { paymentId: number }) {
  return (
    <div className="mock">
      <p>
        <strong>Mock 결과</strong> · 결과 조회 API 전까지 사용자의 앱 동작을 흉내 내요. 누르지 않으면 60초 뒤 만료돼요.
      </p>
      <div className="mock-actions">
        {MOCK_ACTIONS.map(({ action, label }) => (
          <button key={action} type="button" onClick={() => applyMockAction(paymentId, action)}>
            {label}
          </button>
        ))}
      </div>
    </div>
  )
}
