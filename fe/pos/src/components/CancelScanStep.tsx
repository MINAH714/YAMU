import { useState, type FormEvent } from 'react'
import { parseManual } from '../payment/barcode'
import { CameraScanner } from './CameraScanner'
import '../styles/payment.css'
import '../styles/history.css'

type Props = {
  onToken: (token: string) => void
}

/** 취소 트레이 본문. 고객이 앱에서 띄운 결제 바코드를 찍어 본인 확인으로 쓴다(BE -424) */
export function CancelScanStep({ onToken }: Props) {
  const [manual, setManual] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const [typing, setTyping] = useState(false)

  const handleManual = (event: FormEvent) => {
    event.preventDefault()
    const token = parseManual(manual)
    if (!token) {
      setHint('결제 코드 16자리 또는 yamu://pay/… 형식으로 입력해 주세요.')
      return
    }
    onToken(token)
  }

  return (
    <div className="pay-scan" data-typing={typing}>
      <p className="cancel-scan-guide">결제한 고객의 앱에서 바코드를 띄워 화면에 비춰 주세요</p>

      <CameraScanner onRead={onToken} onUnknownQr={() => setHint('YAMU 결제 QR이 아니에요.')} />
      {hint && <p className="alert alert-info">{hint}</p>}

      <form className="pay-manual" onSubmit={handleManual}>
        <label className="field">
          <span>결제 코드 직접 입력</span>
          <input
            inputMode="numeric"
            autoComplete="off"
            placeholder="결제 코드 16자리"
            value={manual}
            onChange={(event) => setManual(event.target.value)}
            onFocus={() => setTyping(true)}
            onBlur={() => setTyping(false)}
          />
        </label>
        <button type="submit" className="secondary">
          이 코드로 취소하기
        </button>
      </form>
    </div>
  )
}
