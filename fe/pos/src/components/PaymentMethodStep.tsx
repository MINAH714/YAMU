import type { PaymentPlan } from '../api/pos'
import type { Order } from '../store/cart'
import {
  MAX_INSTALLMENT_MONTHS,
  MIN_INSTALLMENT_AMOUNT,
  MIN_INSTALLMENT_MONTHS,
  type TermsErrors,
} from '../payment/terms'
import { OrderSummary } from './OrderSummary'

export type PlanInput = { plan: PaymentPlan; installmentMonths: string }

type Props = {
  order: Order
  planInput: PlanInput
  errors: TermsErrors
  notice: string | null
  onPlanChange: (input: PlanInput) => void
  onYamu: () => void
  onCash: () => void
  onBack: () => void
}

const MONTH_OPTIONS = Array.from(
  { length: MAX_INSTALLMENT_MONTHS - MIN_INSTALLMENT_MONTHS + 1 },
  (_, i) => MIN_INSTALLMENT_MONTHS + i,
)

/**
 * 결제수단 고르기. YAMU페이는 결제 QR 스캔으로, 현금은 이 화면 안 계산으로 이어진다.
 * 카드·간편결제는 단말이 연결되어 있지 않아 고를 수 없다.
 */
export function PaymentMethodStep({ order, planInput, errors, notice, onPlanChange, onYamu, onCash, onBack }: Props) {
  const installmentAllowed = order.total >= MIN_INSTALLMENT_AMOUNT
  const setPlan = (plan: PaymentPlan) =>
    onPlanChange({ plan, installmentMonths: plan === 'INSTALLMENT' ? planInput.installmentMonths || '3' : '' })
  const planError = errors.amount ?? errors.plan ?? errors.installmentMonths

  return (
    <div className="checkout">
      <OrderSummary order={order} />

      <section className="checkout-main">
        <h2>결제수단을 선택해 주세요</h2>
        {notice && <p className="alert alert-error">{notice}</p>}

        <div className="method yamu">
          <div className="method-head">
            <strong>YAMU페이</strong>
            <span>고객 앱의 결제 QR로 결제</span>
          </div>
          <div className="segmented" role="radiogroup" aria-label="결제 방식">
            <button
              type="button"
              role="radio"
              aria-checked={planInput.plan === 'LUMP_SUM'}
              className={planInput.plan === 'LUMP_SUM' ? 'active' : undefined}
              onClick={() => setPlan('LUMP_SUM')}
            >
              일시불
            </button>
            <button
              type="button"
              role="radio"
              aria-checked={planInput.plan === 'INSTALLMENT'}
              className={planInput.plan === 'INSTALLMENT' ? 'active' : undefined}
              disabled={!installmentAllowed}
              onClick={() => setPlan('INSTALLMENT')}
            >
              할부
            </button>
            {planInput.plan === 'INSTALLMENT' && (
              <select
                aria-label="할부 개월 수"
                value={planInput.installmentMonths}
                onChange={(event) => onPlanChange({ ...planInput, installmentMonths: event.target.value })}
              >
                {MONTH_OPTIONS.map((months) => (
                  <option key={months} value={months}>
                    {months}개월
                  </option>
                ))}
              </select>
            )}
          </div>
          {!installmentAllowed && <small>할부는 50,000원 이상부터 가능합니다.</small>}
          {planError && <em className="field-error">{planError}</em>}
          <button type="button" className="primary" onClick={onYamu}>
            결제 QR 스캔
          </button>
        </div>

        <div className="method-grid">
          <button type="button" className="method" onClick={onCash}>
            <strong>현금</strong>
            <span>받은 금액·거스름돈 계산</span>
          </button>
          <button type="button" className="method" disabled>
            <strong>신용·체크카드</strong>
            <span>카드 단말기 미연결</span>
          </button>
          <button type="button" className="method" disabled>
            <strong>기타 간편결제</strong>
            <span>미지원</span>
          </button>
        </div>

        <footer className="checkout-footer">
          <button type="button" onClick={onBack}>
            ← 주문 수정
          </button>
        </footer>
      </section>
    </div>
  )
}
