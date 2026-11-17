import { useEffect, useRef, useState, type FormEvent, type RefObject } from 'react'
import { formatToken, parseManual } from '../payment/barcode'
import { planLabel, type Terms } from '../payment/terms'
import { CameraScanner } from './CameraScanner'
import { submitOnEnter } from './manualCode'
import '../styles/payment.css'

type Props = {
  terms: Terms
  notice: string | null
  /** 방금 거절된 코드. 같은 QR이 카메라 앞에 그대로 있으면 다시 보내지 않는다 */
  rejectedToken: string | null
  onToken: (token: string) => void
}

export function ScanStep({ terms, notice, rejectedToken, onToken }: Props) {
  const [manual, setManual] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const [typing, setTyping] = useState(false)
  // 직접 입력을 시작하면 카메라를 끈다. 포커스를 따라 껐다 켜면 키보드가 들썩일 때마다 카메라가 커졌다 작아진다
  const [manualMode, setManualMode] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useKeyboardInset(typing, rootRef, inputRef)

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
    <div className="pay-scan" ref={rootRef} data-typing={typing} data-manual={manualMode}>
      <p className="pay-scan-terms">YAMU페이 · {planLabel(terms.plan, terms.installmentMonths)}</p>
      {notice && <p className="alert alert-error">{notice}</p>}

      {manualMode ? (
        <button
          type="button"
          className="text pay-camera-back"
          onClick={() => {
            setManualMode(false)
            setHint(null)
          }}
        >
          카메라로 스캔하기
        </button>
      ) : (
        <CameraScannerWithGuard
          rejectedToken={rejectedToken}
          onRead={accept}
          onUnknownQr={() => setHint('YAMU 결제 QR이 아니에요.')}
        />
      )}
      {hint && <p className="alert alert-info">{hint}</p>}

      <form className="pay-manual" onSubmit={handleManual}>
        <label className="field">
          <span>결제 코드 직접 입력</span>
          <input
            ref={inputRef}
            inputMode="numeric"
            enterKeyHint="go"
            autoComplete="off"
            placeholder="결제 코드 16자리"
            value={manual}
            onChange={(event) => setManual(event.target.value)}
            onKeyDown={submitOnEnter}
            onFocus={() => {
              setTyping(true)
              setManualMode(true)
            }}
            onBlur={() => setTyping(false)}
          />
        </label>
        <button type="submit" className="secondary">
          이 코드로 결제하기
        </button>
      </form>
    </div>
  )
}

/**
 * index.html의 interactive-widget을 따르지 않는 브라우저는 키보드가 떠도 레이아웃 높이가 그대로라 화면만 가려진다.
 * 가려진 만큼을 트레이에 --keyboard-inset으로 알려 입력칸을 키보드 위로 올린다.
 */
function useKeyboardInset(
  typing: boolean,
  rootRef: RefObject<HTMLDivElement | null>,
  inputRef: RefObject<HTMLInputElement | null>,
) {
  useEffect(() => {
    const trayRoot = rootRef.current?.closest<HTMLElement>('.tray-root')
    const viewport = window.visualViewport
    if (!typing || !trayRoot || !viewport) return

    // resizes-content에서는 innerHeight도 같이 줄어드니 키보드가 떴는지는 포커스 때 높이와 비교한다
    // 키보드가 내려갔다고 포커스를 빼지 않는다. 예전엔 그때 카메라를 다시 키웠는데, 키보드 높이가 잠깐 출렁이기만 해도
    // 포커스가 빠졌다 다시 들어가 카메라가 커졌다 작아지기를 되풀이했다. 이제 직접 입력 중엔 카메라가 꺼져 있다
    const sync = () => {
      // 손가락으로 확대한 것은 키보드가 아니다
      if (viewport.scale > 1) return
      const hidden = Math.max(0, Math.round(window.innerHeight - viewport.height) - viewport.offsetTop)
      trayRoot.style.setProperty('--keyboard-inset', `${hidden}px`)
      inputRef.current?.scrollIntoView({ block: 'nearest' })
    }

    sync()
    viewport.addEventListener('resize', sync)
    viewport.addEventListener('scroll', sync)
    return () => {
      viewport.removeEventListener('resize', sync)
      viewport.removeEventListener('scroll', sync)
      trayRoot.style.removeProperty('--keyboard-inset')
    }
  }, [typing, rootRef, inputRef])
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
