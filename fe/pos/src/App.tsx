import { useRef, useState } from 'react'
import './App.css'
import { newIdempotencyKey } from './api/client'
import { createPaymentRequest, type PaymentRequestBody, type PaymentResult } from './api/pos'
import { PaymentForm } from './components/PaymentForm'
import { Receipt } from './components/Receipt'
import { ScanStep } from './components/ScanStep'
import { WaitingStep } from './components/WaitingStep'
import { config, missingConfig } from './config'
import { formatToken } from './payment/barcode'
import { classifyCreateError } from './payment/errors'
import { validateTerms, type Terms, type TermsErrors, type TermsInput } from './payment/terms'

/**
 * 결제 한 건의 요청. 멱등 키는 이 본문과 한 묶음이다.
 * 결과를 모를 때 다시 보내면 같은 키·같은 본문을 보내 결제가 두 번 생기지 않는다.
 * 금액·방식·바코드가 바뀌면 새 Attempt(새 키)를 만든다. 같은 키에 다른 본문은 서버가 409로 거절한다.
 */
type Attempt = { idempotencyKey: string; body: PaymentRequestBody; terms: Terms }

type Phase =
  | { step: 'form'; errors: TermsErrors; notice: string | null }
  | { step: 'scan'; terms: Terms; notice: string | null; rejectedToken: string | null }
  | { step: 'sending'; attempt: Attempt }
  | { step: 'unknown'; attempt: Attempt; message: string }
  | { step: 'waiting'; attempt: Attempt; paymentId: number; orderNo: string | null; deadlineAt: number }
  | { step: 'receipt'; result: PaymentResult }
  | { step: 'fatal'; message: string; config: boolean }

const EMPTY_INPUT: TermsInput = { amount: '', plan: 'LUMP_SUM', installmentMonths: '' }
const CONFIRM_WINDOW_MS = 60_000

function App() {
  const missing = missingConfig()
  const [input, setInput] = useState<TermsInput>(EMPTY_INPUT)
  const [phase, setPhase] = useState<Phase>(() =>
    missing.length > 0
      ? { step: 'fatal', message: `.env.local에 ${missing.join(', ')} 값을 넣어 주세요.`, config: true }
      : { step: 'form', errors: {}, notice: null },
  )
  // 버튼 연타·스캔 콜백이 겹쳐도 요청은 하나만 나간다(상태 갱신 전에 막아야 해서 ref로 둔다)
  const sendingRef = useRef(false)

  const reset = () => {
    setInput(EMPTY_INPUT)
    setPhase({ step: 'form', errors: {}, notice: null })
  }

  const toScan = () => {
    const validated = validateTerms(input)
    if ('errors' in validated) {
      setPhase({ step: 'form', errors: validated.errors, notice: null })
      return
    }
    setPhase({ step: 'scan', terms: validated.terms, notice: null, rejectedToken: null })
  }

  const startAttempt = (terms: Terms, barcodeToken: string) => {
    const body: PaymentRequestBody = {
      barcodeToken,
      merchantId: config.merchantId,
      posCode: config.posCode,
      amount: terms.amount,
      plan: terms.plan,
      ...(terms.plan === 'INSTALLMENT' ? { installmentMonths: terms.installmentMonths } : {}),
      orderNo: newOrderNo(),
    }
    send({ idempotencyKey: newIdempotencyKey(), body, terms })
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
      switch (failure.kind) {
        case 'terms':
          setPhase({ step: 'form', errors: failure.errors, notice: failure.message })
          break
        case 'barcode':
          setPhase({
            step: 'scan',
            terms: attempt.terms,
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
          setPhase({ step: 'scan', terms: attempt.terms, notice: failure.message, rejectedToken: null })
          break
      }
    } finally {
      sendingRef.current = false
    }
  }

  return (
    <main className="app">
      <header className="app-header">
        <h1>YAMU 모의 POS</h1>
        <p>
          {config.posCode || 'posCode 없음'}
          {config.mockResult && <span className="badge">결과 Mock</span>}
        </p>
      </header>

      {phase.step === 'form' && (
        <PaymentForm
          input={input}
          errors={phase.errors}
          notice={phase.notice}
          onChange={setInput}
          onSubmit={toScan}
        />
      )}

      {phase.step === 'scan' && (
        <ScanStep
          terms={phase.terms}
          notice={phase.notice}
          rejectedToken={phase.rejectedToken}
          onToken={(token) => startAttempt(phase.terms, token)}
          onBack={() => setPhase({ step: 'form', errors: {}, notice: null })}
        />
      )}

      {phase.step === 'sending' && (
        <section className="panel">
          <h2>결제 요청 보내는 중…</h2>
          <p>바코드 {formatToken(phase.attempt.body.barcodeToken)}</p>
        </section>
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
              if (window.confirm('처음부터 다시 할까요? 이 바코드는 이미 접수됐을 수 있어 새 바코드가 필요할 수 있어요.')) reset()
            }}
          >
            처음부터 다시
          </button>
        </section>
      )}

      {phase.step === 'waiting' && (
        <WaitingStep
          key={phase.paymentId}
          paymentId={phase.paymentId}
          orderNo={phase.orderNo}
          terms={phase.attempt.terms}
          deadlineAt={phase.deadlineAt}
          onFinal={(result) => setPhase({ step: 'receipt', result })}
          onFatal={(message, isConfig) => setPhase({ step: 'fatal', message, config: isConfig })}
          onAbandon={reset}
        />
      )}

      {phase.step === 'receipt' && <Receipt result={phase.result} onNew={reset} />}

      {phase.step === 'fatal' && (
        <section className="panel">
          <h2>{phase.config ? 'POS 설정 오류' : '결제를 이어갈 수 없어요'}</h2>
          <p className="alert alert-error">{phase.message}</p>
          {!phase.config && (
            <button type="button" className="primary" onClick={reset}>
              새 결제
            </button>
          )}
        </section>
      )}
    </main>
  )
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
