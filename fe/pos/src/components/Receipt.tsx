import type { PaymentResult } from '../api/pos'
import { formatWon, planLabel } from '../payment/terms'

type Props = {
  result: PaymentResult
  onNew: () => void
}

const TITLES: Partial<Record<PaymentResult['status'], string>> = {
  APPROVED: '결제 승인',
  DECLINED: '결제 거절',
  EXPIRED: '결제 만료',
  CANCELED: '결제 취소',
}

const kstDateTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  dateStyle: 'medium',
  timeStyle: 'medium',
})

export function Receipt({ result, onNew }: Props) {
  const approved = result.status === 'APPROVED'
  const discount = result.instantDiscount ?? 0

  return (
    <section className={`panel receipt receipt-${result.status.toLowerCase()}`}>
      <h2>{TITLES[result.status] ?? result.status}</h2>

      <dl className="summary">
        <dt>결제 번호</dt>
        <dd>{result.paymentId}</dd>
        {result.orderNo && (
          <>
            <dt>주문 번호</dt>
            <dd>{result.orderNo}</dd>
          </>
        )}
        {approved && (
          <>
            <dt>승인 번호</dt>
            <dd className="mono">{result.approvalNo}</dd>
            {result.approvedAt && (
              <>
                <dt>승인 일시</dt>
                <dd>{kstDateTime.format(new Date(result.approvedAt))}</dd>
              </>
            )}
            {result.card && (
              <>
                <dt>카드</dt>
                <dd>
                  {result.card.issuerName} {result.card.cardType === 'CREDIT' ? '신용' : '체크'} {result.card.maskedNumber}
                </dd>
                <dt>결제 방식</dt>
                <dd>{planLabel(result.card.plan, result.card.installmentMonths)}</dd>
              </>
            )}
          </>
        )}
        <dt>주문 금액</dt>
        <dd>{formatWon(result.amount)}</dd>
        {approved && discount > 0 && (
          <>
            <dt>즉시할인</dt>
            <dd>-{formatWon(discount)}</dd>
          </>
        )}
        {approved && (
          <>
            <dt className="total">받은 금액</dt>
            <dd className="total">{formatWon(result.chargedAmount ?? result.amount - discount)}</dd>
          </>
        )}
      </dl>

      {result.status === 'DECLINED' && (
        <p className="alert alert-error">
          거절 사유: {result.failure?.message ?? '알 수 없음'}
          {result.failure?.code && <span className="mono"> ({result.failure.code})</span>}
        </p>
      )}
      {result.status === 'EXPIRED' && (
        <p className="alert alert-error">사용자가 60초 안에 승인하지 않았어요. 새 바코드로 다시 결제해 주세요.</p>
      )}

      <button type="button" className="primary" onClick={onNew}>
        새 결제
      </button>
    </section>
  )
}
