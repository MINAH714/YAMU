import type { ReactNode } from 'react'

/** 결제 진행·결과 화면의 종류. 아이콘과 색이 정해진다. */
export type StatusTone = 'waiting' | 'checking' | 'failed' | 'expired' | 'approved'

export type StatusRow = { label: string; value: ReactNode; mono?: boolean }

/** 영수증의 품목 줄 */
export type StatusItem = { key: string; label: string; quantity: number; amount: string }

type Props = {
  tone: StatusTone
  title: ReactNode
  /** 제목 위의 작은 문장. 완료 화면은 이 문장 아래 큰 금액을 제목으로 둔다 */
  lead?: ReactNode
  description?: ReactNode
  /** 요약 카드 위쪽에 품목 줄로 보인다 */
  items?: StatusItem[]
  rows: StatusRow[]
  /** 요약 카드 아래(안내·보조 버튼 등) */
  children?: ReactNode
  /** 화면 아래 오른쪽의 주 버튼 */
  action?: { label: string; onClick: () => void }
}

/** 고객 승인 대기·결과 확인 중·실패·만료·완료를 같은 틀(아이콘 → 문구 → 요약 카드 → 버튼)로 보여 준다. */
export function StatusScreen({ tone, title, lead, description, items, rows, children, action }: Props) {
  return (
    <section className={`status status-${tone}`} aria-live="polite">
      <div className="status-body">
        <span className="status-icon" aria-hidden="true">
          <StatusIcon tone={tone} />
        </span>
        {lead && <p className="status-lead">{lead}</p>}
        <h2 className="status-title">{title}</h2>
        {description && <p className="status-description">{description}</p>}
        <div className="status-card">
          {items && items.length > 0 && (
            <ul className="status-items">
              {items.map((item) => (
                <li key={item.key}>
                  <span>{item.label}</span>
                  <span className="qty">×{item.quantity}</span>
                  <span>{item.amount}</span>
                </li>
              ))}
            </ul>
          )}
          <dl className="status-rows">
            {rows.map((row) => (
              <div className="status-row" key={row.label}>
                <dt>{row.label}</dt>
                <dd className={row.mono ? 'mono' : undefined}>{row.value}</dd>
              </div>
            ))}
          </dl>
        </div>
        {children}
      </div>
      {action && (
        <footer className="status-footer">
          <button type="button" className="primary" onClick={action.onClick}>
            {action.label}
          </button>
        </footer>
      )}
    </section>
  )
}

function StatusIcon({ tone }: { tone: StatusTone }) {
  switch (tone) {
    case 'approved':
      return (
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" strokeWidth="2.4"
          strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 12.5l4 4 8-9" />
        </svg>
      )
    case 'failed':
      return (
        <svg viewBox="0 0 24 24" width="40" height="40" fill="currentColor">
          <rect x="10.6" y="4.5" width="2.8" height="10" rx="1.4" />
          <circle cx="12" cy="18.5" r="1.7" />
        </svg>
      )
    case 'expired':
      return (
        <svg viewBox="0 0 24 24" width="44" height="44" fill="none" stroke="currentColor" strokeWidth="2"
          strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="8" />
          <path d="M12 7.5V12h4" />
        </svg>
      )
    default:
      return (
        <svg viewBox="0 0 24 24" width="40" height="40" fill="currentColor">
          <circle cx="6.5" cy="15" r="1.8" />
          <circle cx="12" cy="15" r="1.8" />
          <circle cx="17.5" cy="15" r="1.8" />
        </svg>
      )
  }
}
