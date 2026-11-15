// 확인 페이지 전용이라 빠른 새로 고침이 안 돼도 괜찮다
// oxlint-disable react/only-export-components
import type { ReactNode } from 'react'
import { Character } from '../components/Character'
import { FatalScreen } from '../components/FlowScreens'
import { Receipt } from '../components/Receipt'
import { RequestBoard } from '../components/RequestBoard'
import { ScreenLayer } from '../components/ScreenLayer'
import { StatusScreen } from '../components/StatusScreen'
import { WaitingView } from '../components/WaitingStep'
import { useReduceMotion } from '../hooks/useReduceMotion'
import { formatWon } from '../payment/terms'
import { orderItems } from '../store/cart'
import {
  APPROVED_RESULT,
  CANCELED_RESULT,
  DECLINED_RESULT,
  DEMO_ORDER,
  DEMO_TERMS,
  DEMO_TOKEN,
  EXPIRED_RESULT,
  NOT_CONFIRMED_RESULT,
  REJECTED_RESULT,
} from './fixtures'
import type { GalleryEntry } from './types'

const noop = () => {}

/** 화면 층에 넣어 등장 박자를 재생한다. 위 '다시 그리기'를 누르면 처음부터 다시 나온다 */
function Entering({ children }: { children: ReactNode }) {
  const reduce = useReduceMotion()
  return (
    <div className="screens">
      <ScreenLayer plan={{ key: 'entry', delayMs: 0, reveal: false, reduce }} surface="flow">
        {children}
      </ScreenLayer>
    </div>
  )
}

const GROUP = '결제 흐름'
const flow = { group: GROUP, store: 'starbucks', step: 3, surface: 'flow' } as const

/** 폴링 없이 그린 승인 대기. 남은 시간과 상태만 바꿔 쓴다 */
function waitingView(state: { remainingMs: number; checking?: boolean; stopped?: boolean; declined?: boolean; connectionError?: string }) {
  return (
    <WaitingView
      paymentId={1001}
      orderNo="POS-20261105-120000-001"
      terms={DEMO_TERMS}
      totalMs={60_000}
      checking={false}
      stopped={false}
      declined={false}
      connectionError={null}
      onRecheck={noop}
      onAbandon={noop}
      {...state}
    />
  )
}

export const flowEntries: GalleryEntry[] = [
  {
    ...flow,
    id: 'sending',
    label: '요청 중',
    render: () => (
      <Entering>
        <RequestBoard terms={DEMO_TERMS} barcodeToken={DEMO_TOKEN} unknownMessage={null} onRetry={noop} onNewOrder={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'unknown',
    label: '결과 미확인(요청)',
    render: () => (
      <RequestBoard
        terms={DEMO_TERMS}
        barcodeToken={DEMO_TOKEN}
        unknownMessage="서버 응답이 늦어요. 결제가 접수됐는지 알 수 없어요."
        onRetry={noop}
        onNewOrder={noop}
      />
    ),
  },
  {
    ...flow,
    id: 'waiting',
    label: '승인 대기',
    render: () => waitingView({ remainingMs: 42_000 }),
  },
  {
    ...flow,
    id: 'waiting-urgent',
    label: '승인 대기(마지막 10초)',
    render: () => waitingView({ remainingMs: 8_000 }),
  },
  {
    ...flow,
    id: 'waiting-declined',
    label: '승인 대기(카드 거절)',
    render: () => waitingView({ remainingMs: 30_000, declined: true }),
  },
  {
    ...flow,
    id: 'waiting-stopped',
    label: '승인 대기(조회 멈춤)',
    render: () =>
      waitingView({ remainingMs: 0, checking: true, stopped: true, connectionError: '서버에 연결하지 못했어요.' }),
  },
  {
    ...flow,
    id: 'receipt-approved',
    label: '승인 완료',
    render: () => (
      <Entering>
        <Receipt result={APPROVED_RESULT} order={DEMO_ORDER} onNext={noop} onRetry={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'receipt-declined',
    label: '거절',
    render: () => (
      <Entering>
        <Receipt result={DECLINED_RESULT} order={DEMO_ORDER} onNext={noop} onRetry={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'receipt-expired',
    label: '만료',
    render: () => (
      <Entering>
        <Receipt result={EXPIRED_RESULT} order={DEMO_ORDER} onNext={noop} onRetry={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'receipt-rejected',
    label: '고객 거절',
    render: () => (
      <Entering>
        <Receipt result={REJECTED_RESULT} order={DEMO_ORDER} onNext={noop} onRetry={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'receipt-canceled',
    label: '취소',
    render: () => (
      <Entering>
        <Receipt result={CANCELED_RESULT} order={DEMO_ORDER} onNext={noop} onRetry={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'receipt-not-confirmed',
    label: '결과 미확인(영수증)',
    render: () => (
      <Entering>
        <Receipt result={NOT_CONFIRMED_RESULT} order={DEMO_ORDER} onNext={noop} onRetry={noop} />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'cash-done',
    label: '현금 완료',
    render: () => (
      <Entering>
        <StatusScreen
          tone="approved"
          graphic={<Character pose="pay" size={160} />}
          lead="현금 결제를 완료했어요"
          title={formatWon(DEMO_ORDER.total)}
          items={orderItems(DEMO_ORDER)}
          rows={[
            { label: '결제 수단', value: '현금' },
            { label: '받은 금액', value: formatWon(30_000) },
            { label: '거스름돈', value: formatWon(30_000 - DEMO_ORDER.total) },
          ]}
          action={{ label: '다음 주문', onClick: noop }}
        />
      </Entering>
    ),
  },
  {
    ...flow,
    id: 'fatal',
    label: '오류',
    render: () => (
      <FatalScreen
        message="결제를 찾을 수 없어요. 다른 POS 키로 만든 결제이거나 없는 결제예요."
        isConfig={false}
        canLeaveStore
        onNewOrder={noop}
        onLeaveStore={noop}
      />
    ),
  },
  {
    ...flow,
    id: 'fatal-config',
    label: '설정 오류',
    render: () => (
      <FatalScreen
        message="POS 키가 올바르지 않아 결과를 볼 수 없어요. VITE_POS_KEY를 확인해 주세요."
        isConfig
        canLeaveStore
        onNewOrder={noop}
        onLeaveStore={noop}
      />
    ),
  },
]
