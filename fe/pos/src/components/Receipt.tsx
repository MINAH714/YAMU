import type { PaymentResult } from '../api/pos'
import { formatWon, planLabel } from '../payment/terms'
import { orderItems, type Order } from '../store/cart'
import { Amount } from './Amount'
import { Character } from './Character'
import { StatusScreen, type StatusRow } from './StatusScreen'

type Props = {
  result: PaymentResult
  /** 이 결제의 주문. 결제 API는 금액만 받으므로 품목은 POS 화면에만 있다 */
  order: Order
  /** 승인 뒤 다음 주문으로(입력 화면) */
  onNext: () => void
  /** 실패·만료 뒤 같은 금액으로 새 바코드를 스캔 */
  onRetry: () => void
}

export const PAYMENT_METHOD = 'YAMU페이'

/** 승인 대기의 금액이 결과 화면의 금액 자리로 이어진다 */
export const PAYMENT_AMOUNT_ID = 'payment-amount'

/** BE FailureCode. 은행 처리가 늦어 출금 결과를 아직 모르는 거절 */
const WITHDRAWAL_NOT_CONFIRMED = 'WITHDRAWAL_NOT_CONFIRMED'

const kstTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: 'numeric',
  minute: '2-digit',
})

/**
 * 최종 결과 화면. 거절이면 서버가 준 사유(failure.message)를 띄운다.
 * 이 문구는 사유별 기본 문구라 잔액 같은 고객 정보가 들어 있지 않다(BE PosPaymentResult.Failure).
 */
export function Receipt({ result, order, onNext, onRetry }: Props) {
  const items = orderItems(order)
  // 주문 금액은 왼쪽에 크게 보이므로 목록에는 다시 적지 않는다
  const base: StatusRow[] = [{ label: '결제 수단', value: PAYMENT_METHOD }]
  const orderAmount = (
    <Amount className="status-order-amount" value={result.amount} size="md" layoutId={PAYMENT_AMOUNT_ID} />
  )

  // 출금 미확인은 확정된 실패가 아니다. BE 복구 작업이 늦게 출금을 확인하면 DECLINED·EXPIRED가 APPROVED로 바뀌므로
  // "실패"·"만료"로 띄우고 바로 다시 결제하게 하면 두 번 결제될 수 있다. 앱의 결제 내역부터 보게 한다.
  if (result.failure?.code === WITHDRAWAL_NOT_CONFIRMED && (result.status === 'DECLINED' || result.status === 'EXPIRED')) {
    return (
      <StatusScreen
        tone="unconfirmed"
        graphic={<Character pose="thinking" size={128} />}
        title="결제 결과를 확인하지 못했어요"
        description="고객 앱의 결제 내역에서 결제되지 않은 것을 확인한 뒤 다시 결제해 주세요."
        amount={orderAmount}
        items={items}
        rows={base}
        action={{ label: '다시 결제', onClick: onRetry }}
      />
    )
  }

  switch (result.status) {
    case 'APPROVED': {
      const discount = result.instantDiscount ?? 0
      const charged = result.chargedAmount ?? result.amount - discount
      const rows: StatusRow[] = [{ label: '결제 수단', value: PAYMENT_METHOD }]
      if (result.card?.plan === 'INSTALLMENT') {
        rows.push({ label: '결제 방식', value: planLabel(result.card.plan, result.card.installmentMonths) })
      }
      if (discount > 0) {
        rows.push({ label: '주문 금액', value: formatWon(result.amount) })
        rows.push({ label: '즉시할인', value: `-${formatWon(discount)}` })
      }
      if (result.approvedAt) rows.push({ label: '승인 시각', value: kstTime.format(new Date(result.approvedAt)) })
      if (result.approvalNo) rows.push({ label: '승인 번호', value: result.approvalNo, mono: true })
      return (
        <StatusScreen
          tone="approved"
          graphic={<Character pose="pay" size={160} />}
          lead="결제를 완료했어요"
          title={<Amount value={charged} size="xl" layoutId={PAYMENT_AMOUNT_ID} />}
          items={items}
          rows={rows}
          action={{ label: '다음 주문', onClick: onNext }}
        />
      )
    }
    case 'EXPIRED':
      return (
        <StatusScreen
          tone="expired"
          graphic={<Character pose="worry" size={128} />}
          title="승인 시간이 지났어요"
          description="새 바코드를 스캔해 다시 결제해 주세요."
          amount={orderAmount}
          items={items}
          rows={base}
          action={{ label: '다시 결제', onClick: onRetry }}
        />
      )
    // 고객이 앱에서 결제 요청을 거절했다. 카드 거절 뒤 거절하면 마지막 카드 거절 사유가 failure에 남아 있지만
    // 결제를 끝낸 건 고객의 거절이라 그 사유는 띄우지 않는다. 바코드가 닫혔으므로 새 바코드로 다시 결제한다.
    case 'REJECTED':
      return (
        <StatusScreen
          tone="expired"
          graphic={<Character pose="worry" size={128} />}
          title="고객이 결제를 거절했어요"
          description="다시 결제하려면 고객 앱에서 새 바코드를 받아 스캔해 주세요."
          amount={orderAmount}
          items={items}
          rows={base}
          action={{ label: '다시 결제', onClick: onRetry }}
        />
      )
    case 'CANCELED':
      return (
        <StatusScreen
          tone="failed"
          graphic={<Character pose="worry" size={128} />}
          title="결제가 취소됐어요"
          amount={orderAmount}
          items={items}
          rows={base}
          action={{ label: '다음 주문', onClick: onNext }}
        />
      )
    default:
      return (
        <StatusScreen
          tone="failed"
          graphic={<Character pose="worry" size={128} />}
          title="결제하지 못했어요"
          description={result.failure?.message ?? '고객 앱에서 실패 사유를 확인해 주세요.'}
          amount={orderAmount}
          items={items}
          rows={base}
          action={{ label: '다시 결제', onClick: onRetry }}
        />
      )
  }
}
