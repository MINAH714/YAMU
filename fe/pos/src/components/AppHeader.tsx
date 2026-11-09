import { useEffect, useState } from 'react'
import type { Store } from '../store/catalog'
import { BrandMark } from './BrandMark'
import { FullscreenButton } from './FullscreenButton'
import { Logo } from './Logo'

type Props = {
  store: Store | null
  /** 현재 결제 단계(1부터). 0이면 표시하지 않는다 */
  step: number
  mock: boolean
  /** 있으면 매장 변경 버튼을 보인다 */
  onChangeStore?: () => void
}

const STEPS = ['주문', '결제수단', '결제']

export function AppHeader({ store, step, mock, onChangeStore }: Props) {
  return (
    <header className="app-header">
      <div className="app-brand">
        {store ? (
          <>
            <BrandMark storeId={store.id} />
            <span className="app-branch">{store.branch}</span>
          </>
        ) : (
          <h1 className="app-title">
            <Logo />
          </h1>
        )}
      </div>

      {step > 0 ? (
        <ol className="steps" aria-label="결제 단계">
          {STEPS.map((label, i) => (
            <li key={label} aria-current={i + 1 === step ? 'step' : undefined}>
              {label}
            </li>
          ))}
        </ol>
      ) : (
        <span />
      )}

      <div className="app-meta">
        {mock && <span className="badge">결과 Mock</span>}
        <Clock />
        {onChangeStore && (
          <button type="button" className="text" onClick={onChangeStore}>
            매장 변경
          </button>
        )}
        <FullscreenButton />
      </div>
    </header>
  )
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
