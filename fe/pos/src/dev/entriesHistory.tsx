// 확인 페이지 전용이라 빠른 새로 고침이 안 돼도 괜찮다
// oxlint-disable react/only-export-components
import { useState } from 'react'
import type { PaymentHistoryItem } from '../api/posHistory'
import { Amount } from '../components/Amount'
import { CancelScanStep } from '../components/CancelScanStep'
import { CancelDoneScreen, CancelFailedScreen, CancelingScreen, CancelUnknownScreen } from '../components/CancelScreens'
import { PaymentHistory } from '../components/PaymentHistory'
import { chargedOf, HISTORY_CONFIG_MISMATCH, HISTORY_LOAD_FAILED } from '../payment/history'
import { Tray } from '../components/Tray'
import { CANCEL_NOT_FOUND_MESSAGE, demoHistory } from './historyFixtures'
import type { GalleryEntry } from './types'

const noop = () => {}

const GROUP = '결제 내역·취소'
const base = { group: GROUP, store: 'starbucks', step: 0, surface: 'flow' } as const

function HistoryDemo({ items = demoHistory(), initial }: { items?: PaymentHistoryItem[]; initial?: number | null }) {
  const [selectedId, setSelectedId] = useState<number | null>(initial === undefined ? (items[0]?.paymentId ?? null) : initial)
  return <PaymentHistory items={items} selectedId={selectedId} onSelect={setSelectedId} onCancel={noop} onBack={noop} />
}

function CancelTray({ item }: { item: PaymentHistoryItem }) {
  return (
    <Tray key="tray" stepKey="cancel" title="고객 바코드를 스캔해 주세요" width={720} onBack={noop}>
      <Amount className="tray-amount" value={chargedOf(item)} size="lg" />
      <CancelScanStep onToken={noop} />
    </Tray>
  )
}

const first = () => demoHistory()[0]

export const historyEntries: GalleryEntry[] = [
  {
    ...base,
    id: 'history',
    label: '내역',
    render: () => <HistoryDemo />,
  },
  {
    ...base,
    id: 'history-canceled',
    label: '내역(취소된 결제 선택)',
    render: () => <HistoryDemo initial={2043} />,
  },
  {
    ...base,
    id: 'history-none-selected',
    label: '내역(선택 전)',
    render: () => <HistoryDemo initial={null} />,
  },
  {
    ...base,
    id: 'history-empty',
    label: '내역(없음)',
    render: () => <HistoryDemo items={[]} />,
  },
  {
    ...base,
    id: 'history-scan',
    label: '취소 스캔',
    render: () => <HistoryDemo />,
    tray: () => <CancelTray item={first()} />,
  },
  {
    ...base,
    id: 'canceling',
    label: '취소 중',
    render: () => <CancelingScreen item={first()} />,
  },
  {
    ...base,
    id: 'cancel-done',
    label: '취소 완료',
    render: () => {
      const item = first()
      const result = { paymentId: item.paymentId, orderNo: item.orderNo, status: 'CANCELED', canceledAt: new Date().toISOString(), amount: item.amount, chargedAmount: item.chargedAmount } as const
      return <CancelDoneScreen item={item} result={result} onBack={noop} />
    },
  },
  {
    ...base,
    id: 'cancel-failed',
    label: '취소 실패',
    render: () => <CancelFailedScreen item={first()} message={CANCEL_NOT_FOUND_MESSAGE} already={false} onRescan={noop} onBack={noop} />,
  },
  {
    ...base,
    id: 'cancel-already',
    label: '이미 취소됨',
    render: () => <CancelFailedScreen item={first()} message="이미 취소된 결제예요." already onRescan={noop} onBack={noop} />,
  },
  {
    ...base,
    id: 'cancel-unknown',
    label: '취소 결과 미확인',
    render: () => (
      <CancelUnknownScreen item={first()} message="서버 응답이 늦어요. 취소됐는지 알 수 없어요." onRetry={noop} onBack={noop} />
    ),
  },
  {
    ...base,
    id: 'history-loading',
    label: '내역(불러오는 중)',
    render: () => <PaymentHistory items={null} selectedId={null} onSelect={noop} onCancel={noop} onBack={noop} />,
  },
  {
    ...base,
    id: 'history-error',
    label: '내역(불러오기 실패)',
    render: () => (
      <PaymentHistory items={null} error={HISTORY_LOAD_FAILED} onReload={noop} selectedId={null} onSelect={noop} onCancel={noop} onBack={noop} />
    ),
  },
  {
    ...base,
    id: 'history-error-config',
    label: '내역(POS 설정 불일치)',
    render: () => (
      <PaymentHistory items={null} error={HISTORY_CONFIG_MISMATCH} onReload={noop} selectedId={null} onSelect={noop} onCancel={noop} onBack={noop} />
    ),
  },
]
