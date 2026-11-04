import type { FormEvent } from 'react'
import type { PaymentPlan } from '../api/pos'
import {
  MAX_INSTALLMENT_MONTHS,
  MIN_INSTALLMENT_MONTHS,
  type TermsErrors,
  type TermsInput,
} from '../payment/terms'

type Props = {
  input: TermsInput
  errors: TermsErrors
  notice: string | null
  onChange: (input: TermsInput) => void
  onSubmit: () => void
}

const MONTH_OPTIONS = Array.from(
  { length: MAX_INSTALLMENT_MONTHS - MIN_INSTALLMENT_MONTHS + 1 },
  (_, i) => MIN_INSTALLMENT_MONTHS + i,
)

export function PaymentForm({ input, errors, notice, onChange, onSubmit }: Props) {
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    onSubmit()
  }
  const setPlan = (plan: PaymentPlan) =>
    onChange({ ...input, plan, installmentMonths: plan === 'INSTALLMENT' ? input.installmentMonths || '3' : '' })

  return (
    <form className="panel" onSubmit={handleSubmit} noValidate>
      <h2>결제 정보</h2>
      {notice && <p className="alert alert-error">{notice}</p>}

      <label className="field">
        <span>결제 금액(원)</span>
        <input
          inputMode="numeric"
          autoComplete="off"
          placeholder="예: 15000"
          value={input.amount}
          aria-invalid={Boolean(errors.amount)}
          onChange={(event) => onChange({ ...input, amount: event.target.value })}
        />
        {errors.amount && <em className="field-error">{errors.amount}</em>}
      </label>

      <fieldset className="field">
        <legend>결제 방식</legend>
        <div className="segmented">
          <label>
            <input type="radio" checked={input.plan === 'LUMP_SUM'} onChange={() => setPlan('LUMP_SUM')} />
            일시불
          </label>
          <label>
            <input type="radio" checked={input.plan === 'INSTALLMENT'} onChange={() => setPlan('INSTALLMENT')} />
            할부
          </label>
        </div>
        {errors.plan && <em className="field-error">{errors.plan}</em>}
      </fieldset>

      {input.plan === 'INSTALLMENT' && (
        <label className="field">
          <span>할부 개월 수</span>
          <select
            value={input.installmentMonths}
            aria-invalid={Boolean(errors.installmentMonths)}
            onChange={(event) => onChange({ ...input, installmentMonths: event.target.value })}
          >
            {MONTH_OPTIONS.map((months) => (
              <option key={months} value={months}>
                {months}개월
              </option>
            ))}
          </select>
          <small>할부는 50,000원 이상, 2~12개월. 신용카드만 추천 대상입니다.</small>
          {errors.installmentMonths && <em className="field-error">{errors.installmentMonths}</em>}
        </label>
      )}

      <button type="submit" className="primary">
        QR 스캔하기
      </button>
    </form>
  )
}
