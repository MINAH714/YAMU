import { useState, type FormEvent } from 'react'
import { formatToken, parseManual } from '../payment/barcode'
import { formatWon, planLabel, type Terms } from '../payment/terms'
import type { Order } from '../store/cart'
import { CameraScanner } from './CameraScanner'
import { OrderSummary } from './OrderSummary'

type Props = {
  order: Order
  terms: Terms
  notice: string | null
  /** 방금 거절된 코드. 같은 QR이 카메라 앞에 그대로 있으면 다시 보내지 않는다 */
  rejectedToken: string | null
  onToken: (token: string) => void
  onBack: () => void
}

export function ScanStep({ order, terms, notice, rejectedToken, onToken, onBack }: Props) {
  const [manual, setManual] = useState('')
  const [hint, setHint] = useState<string | null>(null)

  const accept = (token: string) => {
    if (token === rejectedToken) {
      setHint(`같은 바코드(${formatToken(token)})예요. 앱에서 새 바코드를 띄워 주세요.`)
      return false
    }
    onToken(token)
    return true
  }

  const handleManual = (event: FormEvent) => {
    event.preventDefault()
    const token = parseManual(manual)
    if (!token) {
      setHint('결제 코드 16자리 또는 yamu://pay/… 형식으로 입력해 주세요.')
      return
    }
    accept(token)
  }

  return (
    <div className="checkout">
      <OrderSummary order={order} />

      <section className="checkout-main scan">
        <div className="scan-head">
          <h2>고객 앱의 YAMU 결제 QR을 스캔해 주세요</h2>
          <p>
            YAMU페이 · {planLabel(terms.plan, terms.installmentMonths)} · <strong>{formatWon(terms.amount)}</strong>
          </p>
        </div>
        {notice && <p className="alert alert-error">{notice}</p>}

        <CameraScannerWithGuard
          rejectedToken={rejectedToken}
          onRead={accept}
          onUnknownQr={() => setHint('YAMU 결제 QR이 아니에요.')}
        />
        {hint && <p className="alert alert-info">{hint}</p>}

        <form className="manual" onSubmit={handleManual}>
          <label className="field">
            <span>결제 코드 직접 입력</span>
            <input
              inputMode="numeric"
              autoComplete="off"
              placeholder="결제 코드 16자리"
              value={manual}
              onChange={(event) => setManual(event.target.value)}
            />
          </label>
          <button type="submit">확인</button>
        </form>

        <footer className="checkout-footer">
          <button type="button" onClick={onBack}>
            ← 결제수단 변경
          </button>
        </footer>
      </section>
    </div>
  )
}

/**
 * 거절된 QR을 다시 읽으면 카메라를 새로 켜서 다음 QR을 기다린다.
 * CameraScanner는 한 번 읽으면 멈추므로 key를 바꿔 다시 마운트한다.
 */
function CameraScannerWithGuard({
  rejectedToken,
  onRead,
  onUnknownQr,
}: {
  rejectedToken: string | null
  onRead: (token: string) => boolean
  onUnknownQr: () => void
}) {
  const [round, setRound] = useState(0)
  return (
    <CameraScanner
      key={`${rejectedToken ?? ''}-${round}`}
      onRead={(token) => {
        if (!onRead(token)) setTimeout(() => setRound((r) => r + 1), 1500)
      }}
      onUnknownQr={onUnknownQr}
    />
  )
}
