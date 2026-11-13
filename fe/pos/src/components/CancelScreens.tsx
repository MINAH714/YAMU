import type { PaymentCancelResult, PaymentHistoryItem } from '../api/posHistory'
import { cardLabel, chargedOf, formatKstTime } from '../payment/history'
import { Amount } from './Amount'
import { Character } from './Character'
import { StatusScreen, type StatusRow } from './StatusScreen'

/** 취소 화면의 줄. 품목은 내역에 없으므로 카드·시각·번호만 보인다 */
function cancelRows(item: PaymentHistoryItem, canceledAt?: string | null): StatusRow[] {
  const rows: StatusRow[] = [
    { label: '카드', value: cardLabel(item.card) },
    { label: '결제 시각', value: formatKstTime(item.approvedAt) },
  ]
  if (canceledAt) rows.push({ label: '취소 시각', value: formatKstTime(canceledAt) })
  if (item.approvalNo) rows.push({ label: '승인 번호', value: item.approvalNo, mono: true })
  return rows
}

function orderAmount(item: PaymentHistoryItem) {
  return <Amount className="status-order-amount" value={chargedOf(item)} size="md" />
}

export function CancelingScreen({ item }: { item: PaymentHistoryItem }) {
  return (
    <StatusScreen
      tone="checking"
      title="결제를 취소하고 있어요"
      amount={orderAmount(item)}
      rows={cancelRows(item)}
    />
  )
}

export function CancelDoneScreen({ item, result, onBack }: { item: PaymentHistoryItem; result: PaymentCancelResult; onBack: () => void }) {
  return (
    <StatusScreen
      tone="approved"
      lead="결제를 취소했어요"
      title={<Amount value={result.chargedAmount ?? chargedOf(item)} size="xl" />}
      description="고객 앱의 결제 내역에도 취소로 보여요."
      rows={cancelRows(item, result.canceledAt)}
      action={{ label: '결제 내역으로', onClick: onBack }}
    />
  )
}

type FailedProps = {
  item: PaymentHistoryItem
  message: string
  /** 이미 취소된 결제. 다시 스캔할 일이 없으므로 내역으로만 돌아간다 */
  already: boolean
  onRescan: () => void
  onBack: () => void
}

export function CancelFailedScreen({ item, message, already, onRescan, onBack }: FailedProps) {
  if (already) {
    return (
      <StatusScreen
        tone="expired"
        graphic={<Character pose="worry" size={128} />}
        title={message}
        description="결제 내역에서 상태를 확인해 주세요."
        amount={orderAmount(item)}
        rows={cancelRows(item)}
        action={{ label: '결제 내역으로', onClick: onBack }}
      />
    )
  }
  return (
    <StatusScreen
      tone="failed"
      graphic={<Character pose="worry" size={128} />}
      title="취소하지 못했어요"
      description={message}
      amount={orderAmount(item)}
      rows={cancelRows(item)}
      action={{ label: '다시 스캔', onClick: onRescan }}>
      <button type="button" className="text" onClick={onBack}>
        결제 내역으로
      </button>
    </StatusScreen>
  )
}

type UnknownProps = { item: PaymentHistoryItem; message: string; onRetry: () => void; onBack: () => void }

/** 취소 요청의 결과를 모를 때. 같은 키로 다시 보내거나 내역에서 상태를 본다 */
export function CancelUnknownScreen({ item, message, onRetry, onBack }: UnknownProps) {
  return (
    <StatusScreen
      tone="unconfirmed"
      graphic={<Character pose="thinking" size={128} />}
      title="취소 결과를 확인하지 못했어요"
      description={message}
      amount={orderAmount(item)}
      rows={cancelRows(item)}
      action={{ label: '같은 요청 다시 보내기', onClick: onRetry }}>
      <button type="button" className="text" onClick={onBack}>
        결제 내역에서 확인하기
      </button>
    </StatusScreen>
  )
}
