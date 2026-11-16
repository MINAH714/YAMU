import type { PaymentHistoryItem } from '../api/posHistory'
import { cardLabel, chargedOf, formatKstDateTime, formatKstTime } from '../payment/history'
import { formatWon, planLabel } from '../payment/terms'
import { Amount } from './Amount'
import '../styles/result.css'
import '../styles/history.css'

type Props = {
  /** null이면 불러오는 중 */
  items: PaymentHistoryItem[] | null
  /** 불러오기에 실패한 사유. 있으면 목록 대신 다시 불러오기를 보인다 */
  error?: string | null
  onReload?: () => void
  selectedId: number | null
  /** 위에 트레이가 떠 있는 동안 눌리지 않게 한다 */
  inert?: boolean
  onSelect: (paymentId: number) => void
  /** 고른 결제를 취소하러 간다(고객 바코드 스캔 트레이) */
  onCancel: (item: PaymentHistoryItem) => void
  onBack: () => void
}

const kstDay = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric' })
const kstDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' })

function dayLabel(iso: string | null, today: string) {
  if (!iso) return '날짜 없음'
  const date = new Date(iso)
  return kstDate.format(date) === today ? '오늘' : kstDay.format(date)
}

/** 날짜별로 묶는다. 서버가 결제 번호 내림차순으로 주므로 순서를 그대로 둔다 */
function groupByDay(items: PaymentHistoryItem[]) {
  const today = kstDate.format(new Date())
  const groups: { label: string; items: PaymentHistoryItem[] }[] = []
  for (const item of items) {
    const label = dayLabel(item.approvedAt, today)
    const last = groups.at(-1)
    if (last?.label === label) last.items.push(item)
    else groups.push({ label, items: [item] })
  }
  return groups
}

/** POS 결제 내역. 왼쪽 목록, 오른쪽 352px 상세(결과 화면의 주문 내역 자리)와 주 버튼 하나 */
export function PaymentHistory({ items, error = null, onReload, selectedId, inert, onSelect, onCancel, onBack }: Props) {
  const selected = items?.find((item) => item.paymentId === selectedId) ?? null

  return (
    <section className="history" inert={inert}>
      <div className="history-main">
        <header className="history-header">
          <h2>
            결제 내역 {items && <span>{items.length}건</span>}
          </h2>
          <button type="button" className="text" onClick={onBack}>
            주문으로
          </button>
        </header>

        {error ? (
          <div className="history-empty history-error">
            <p>{error}</p>
            {onReload && (
              <button type="button" className="secondary" onClick={onReload}>
                다시 불러오기
              </button>
            )}
          </div>
        ) : items === null ? (
          <p className="history-empty">결제 내역을 불러오고 있어요</p>
        ) : items.length === 0 ? (
          <p className="history-empty">최근 7일 동안 이 POS에서 결제한 내역이 없어요.</p>
        ) : (
          <div className="history-list">
            {groupByDay(items).map((group) => (
              <section key={group.label} className="history-group">
                <h3>{group.label}</h3>
                <ul>
                  {group.items.map((item) => {
                    const canceled = item.status === 'CANCELED'
                    return (
                      <li key={item.paymentId}>
                        <button
                          type="button"
                          className="history-row"
                          data-selected={item.paymentId === selectedId}
                          data-canceled={canceled}
                          aria-pressed={item.paymentId === selectedId}
                          onClick={() => onSelect(item.paymentId)}>
                          <span className="history-time">{formatKstTime(item.approvedAt)}</span>
                          <span className="history-card">{cardLabel(item.card)}</span>
                          <span className="history-amount">{formatWon(chargedOf(item))}</span>
                          <span className="history-tag" data-status={canceled ? 'canceled' : 'approved'}>
                            {canceled ? '취소' : '승인'}
                          </span>
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </section>
            ))}
          </div>
        )}
      </div>

      <aside className="history-side">
        {selected ? (
          <HistoryDetail item={selected} onCancel={onCancel} />
        ) : (
          <p className="history-side-empty">취소할 결제를 골라 주세요</p>
        )}
      </aside>
    </section>
  )
}

function HistoryDetail({ item, onCancel }: { item: PaymentHistoryItem; onCancel: Props['onCancel'] }) {
  const discount = item.instantDiscount ?? 0
  const rows: { label: string; value: string; mono?: boolean }[] = [
    { label: '카드', value: cardLabel(item.card) },
  ]
  if (item.card) rows.push({ label: '결제 방식', value: planLabel(item.card.plan, item.card.installmentMonths) })
  if (discount > 0) {
    rows.push({ label: '주문 금액', value: formatWon(item.amount) })
    rows.push({ label: '즉시할인', value: `-${formatWon(discount)}` })
  }
  if (item.approvalNo) rows.push({ label: '승인 번호', value: item.approvalNo, mono: true })
  if (item.orderNo) rows.push({ label: '주문 번호', value: item.orderNo, mono: true })

  return (
    <>
      <div className="history-detail-head">
        <p>{formatKstDateTime(item.approvedAt)} · YAMU페이</p>
        <Amount value={chargedOf(item)} size="lg" />
      </div>
      <dl className="status-rows history-rows">
        {rows.map((row) => (
          <div className="status-row" key={row.label}>
            <dt>{row.label}</dt>
            <dd className={row.mono ? 'mono' : undefined}>{row.value}</dd>
          </div>
        ))}
      </dl>
      {item.status === 'CANCELED' ? (
        <p className="history-canceled">{formatKstDateTime(item.canceledAt)}에 취소한 결제예요</p>
      ) : (
        item.cancelable && (
          <button type="button" className="primary history-action" onClick={() => onCancel(item)}>
            고객 바코드로 취소하기
          </button>
        )
      )}
    </>
  )
}
