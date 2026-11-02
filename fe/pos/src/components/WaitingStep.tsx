import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { applyMockAction, type MockAction } from '../api/mockResult'
import { getPaymentResult, type PaymentResult } from '../api/pos'
import { config } from '../config'
import { formatWon, planLabel, type Terms } from '../payment/terms'

type Props = {
  paymentId: number
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

export function WaitingStep({ paymentId, orderNo, terms, deadlineAt, onFinal, onFatal, onAbandon }: Props) {
  const [now, setNow] = useState(() => Date.now())
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
        const result = await getPaymentResult(paymentId)
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
  }, [paymentId, deadlineAt, generation])

  const remaining = Math.max(0, Math.ceil((deadlineAt - now) / 1000))
  const declined = last?.status === 'DECLINED'

  return (
    <section className="panel waiting">
      <h2>앱에서 승인을 기다리는 중</h2>
      <p className="countdown" aria-live="polite">
        {remaining > 0 ? `${remaining}초` : '마감'}
      </p>
      <progress max={60} value={remaining} />

      <dl className="summary">
        <dt>결제 번호</dt>
        <dd>{paymentId}</dd>
        {orderNo && (
          <>
            <dt>주문 번호</dt>
            <dd>{orderNo}</dd>
          </>
        )}
        <dt>결제 금액</dt>
        <dd>{formatWon(terms.amount)}</dd>
        <dt>결제 방식</dt>
        <dd>{planLabel(terms.plan, terms.installmentMonths)}</dd>
      </dl>

      {last?.status === 'PROCESSING' && <p className="alert alert-info">사용자가 승인했어요. 카드사 결과를 기다리는 중…</p>}
      {declined && (
        <p className="alert alert-error">
          거절됐어요({last.failure?.message ?? '사유 없음'}). 사용자가 앱에서 다른 카드로 다시 시도할 수 있어요.
        </p>
      )}
      {connectionError && !stopped && <p className="alert alert-info">{connectionError} 다시 확인하는 중…</p>}

      {stopped && (
        <div className="alert alert-error">
          <p>마감이 지났지만 결과를 확인하지 못했어요. {connectionError}</p>
          <button
            type="button"
            onClick={() => {
              setStopped(false)
              setGeneration((g) => g + 1)
            }}
          >
            결과 다시 확인
          </button>
        </div>
      )}

      {config.mockResult && <MockPanel paymentId={paymentId} />}

      <button
        type="button"
        className="link"
        onClick={() => {
          if (window.confirm('결과를 기다리지 않고 새 결제를 시작할까요? 이 결제는 사용자가 승인하면 그대로 진행돼요.')) {
            onAbandon()
          }
        }}
      >
        기다리지 않고 새 결제
      </button>
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
        <strong>Mock 결과</strong> · 결과 조회 API 전까지 사용자의 앱 동작을 흉내 냅니다. 누르지 않으면 60초 뒤 만료돼요.
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
