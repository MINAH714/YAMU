import { motion } from 'motion/react'
import type { PaymentPlan } from '../api/pos'
import type { Order } from '../store/cart'
import {
  MAX_INSTALLMENT_MONTHS,
  MIN_INSTALLMENT_AMOUNT,
  MIN_INSTALLMENT_MONTHS,
  formatWon,
  type TermsErrors,
} from '../payment/terms'
import { FAST } from '../styles/motion'
import '../styles/payment.css'

export type PlanInput = { plan: PaymentPlan; installmentMonths: string }

type Props = {
  order: Order
  planInput: PlanInput
  errors: TermsErrors
  notice: string | null
  onPlanChange: (input: PlanInput) => void
  onYamu: () => void
  onCash: () => void
}

const PLANS: { value: PaymentPlan; label: string }[] = [
  { value: 'LUMP_SUM', label: '일시불' },
  { value: 'INSTALLMENT', label: '할부' },
]

const MONTH_OPTIONS = Array.from(
  { length: MAX_INSTALLMENT_MONTHS - MIN_INSTALLMENT_MONTHS + 1 },
  (_, i) => MIN_INSTALLMENT_MONTHS + i,
)

/**
 * 결제수단 고르기. YAMU페이는 결제 QR 스캔으로, 현금은 이 화면 안 계산으로 이어진다.
 * 카드·간편결제는 단말이 연결되어 있지 않아 고를 수 없다.
 */
export function PaymentMethodStep({ order, planInput, errors, notice, onPlanChange, onYamu, onCash }: Props) {
  const installmentAllowed = order.total >= MIN_INSTALLMENT_AMOUNT
  const setPlan = (plan: PaymentPlan) =>
    onPlanChange({ plan, installmentMonths: plan === 'INSTALLMENT' ? planInput.installmentMonths || '3' : '' })
  const planError = errors.amount ?? errors.plan ?? errors.installmentMonths

  return (
    <div className="pay-method">
      {notice && <p className="alert alert-error">{notice}</p>}

      <div className="pay-yamu">
        <div className="pay-yamu-head">
          <strong>YAMU페이</strong>
          <span>고객 앱의 결제 QR로 결제</span>
        </div>
        <div className="pay-segment" role="radiogroup" aria-label="결제 방식">
          {/* layoutId로 옮기면 같은 순간 트레이 높이 변화가 애니메이션 없이 튄다. 그래서 하나를 두고 x만 움직인다 */}
          <motion.span
            className="pay-segment-thumb"
            initial={false}
            animate={{ x: planInput.plan === 'INSTALLMENT' ? '100%' : 0 }}
            transition={FAST}
          />
          {PLANS.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={planInput.plan === value}
              disabled={value === 'INSTALLMENT' && !installmentAllowed}
              onClick={() => setPlan(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {planInput.plan === 'INSTALLMENT' && (
        <label className="pay-months">
          <span>할부 개월</span>
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
        </label>
      )}
      {!installmentAllowed && <p className="pay-note">할부는 {formatWon(MIN_INSTALLMENT_AMOUNT)} 이상부터 가능해요.</p>}
      {planError && <p className="pay-error">{planError}</p>}

      <button type="button" className="primary" onClick={onYamu}>
        QR 스캔으로 결제하기
      </button>

      <div className="pay-others">
        <button type="button" className="pay-other" onClick={onCash}>
          <strong>현금</strong>
          <span>받은 금액·거스름돈 계산</span>
        </button>
        <button type="button" className="pay-other" disabled>
          <strong>신용·체크카드</strong>
          <span>카드 단말기 미연결</span>
        </button>
        <button type="button" className="pay-other" disabled>
          <strong>기타 간편결제</strong>
          <span>미지원</span>
        </button>
      </div>
    </div>
  )
}
