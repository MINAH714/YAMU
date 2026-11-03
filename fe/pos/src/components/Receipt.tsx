import type { PaymentResult } from '../api/pos'
import { formatWon, planLabel } from '../payment/terms'
import { StatusScreen, type StatusRow } from './StatusScreen'

type Props = {
  result: PaymentResult
  /** 승인 뒤 다음 주문으로(입력 화면) */
  onNext: () => void
  /** 실패·만료 뒤 같은 금액으로 새 바코드를 스캔 */
  onRetry: () => void
}

export const PAYMENT_METHOD = 'YAMU페이'

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
 * 주문 메뉴 줄은 FE 담당 작업(메뉴 입력)에서 더한다.
 */
export function Receipt({ result, onNext, onRetry }: Props) {
  const base: StatusRow[] = [
    { label: '주문 금액', value: formatWon(result.amount) },
    { label: '결제 수단', value: PAYMENT_METHOD },
  ]

  // 출금 미확인은 확정된 실패가 아니다. BE 복구 작업이 늦게 출금을 확인하면 DECLINED·EXPIRED가 APPROVED로 바뀌므로
  // "실패"·"만료"로 띄우고 바로 다시 결제하게 하면 두 번 결제될 수 있다. 앱의 결제 내역부터 보게 한다.
  if (result.failure?.code === WITHDRAWAL_NOT_CONFIRMED && (result.status === 'DECLINED' || result.status === 'EXPIRED')) {
    return (
      <StatusScreen
        tone="checking"
        title="결제 결과를 확인하지 못했습니다."
        description="고객 앱의 결제 내역에서 결제되지 않은 것을 확인한 뒤 다시 결제해 주세요."
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
          lead="결제가 완료되었습니다."
          title={formatWon(charged)}
          rows={rows}
          action={{ label: '다음 주문', onClick: onNext }}
        />
      )
    }
    case 'EXPIRED':
      return (
        <StatusScreen
          tone="expired"
          title="승인 시간이 만료되었습니다."
          description="새 바코드를 스캔해 다시 결제해 주세요."
          rows={base}
          action={{ label: '다시 결제', onClick: onRetry }}
        />
      )
    case 'CANCELED':
      return (
        <StatusScreen
          tone="failed"
          title="결제가 취소되었습니다."
          rows={base}
          action={{ label: '다음 주문', onClick: onNext }}
        />
      )
    default:
      return (
        <StatusScreen
          tone="failed"
          title="결제에 실패했습니다."
          description={result.failure?.message ?? '고객 앱에서 실패 사유를 확인해 주세요.'}
          rows={base}
          action={{ label: '다시 결제', onClick: onRetry }}
        />
      )
  }
}
