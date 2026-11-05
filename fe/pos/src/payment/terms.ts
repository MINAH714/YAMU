import type { PaymentPlan } from '../api/pos'

// POL-013. 서버(PaymentTerms)와 같은 값·같은 순서(금액 → 개월 수 → 할부 최소 금액)로 먼저 막는다.
export const MIN_INSTALLMENT_AMOUNT = 50_000
export const MIN_INSTALLMENT_MONTHS = 2
export const MAX_INSTALLMENT_MONTHS = 12

export type TermsInput = {
  amount: string
  plan: PaymentPlan
  installmentMonths: string
}

export type TermsField = 'amount' | 'plan' | 'installmentMonths'
export type TermsErrors = Partial<Record<TermsField, string>>

export type Terms = { amount: number; plan: PaymentPlan; installmentMonths?: number }

export function validateTerms(input: TermsInput): { terms: Terms } | { errors: TermsErrors } {
  const errors: TermsErrors = {}
  const amount = parseWholeNumber(input.amount)
  if (amount === null) {
    errors.amount = '결제 금액은 원 단위 정수로 입력해 주세요.'
  } else if (amount < 1) {
    errors.amount = '결제 금액은 1원 이상이어야 합니다.'
  } else if (!Number.isSafeInteger(amount)) {
    errors.amount = '결제 금액이 너무 큽니다.'
  }

  if (input.plan === 'LUMP_SUM') {
    if (errors.amount) return { errors }
    return { terms: { amount: amount!, plan: 'LUMP_SUM' } }
  }

  const months = parseWholeNumber(input.installmentMonths)
  if (months === null) {
    errors.installmentMonths = '할부 개월 수를 입력해 주세요.'
  } else if (months < MIN_INSTALLMENT_MONTHS || months > MAX_INSTALLMENT_MONTHS) {
    errors.installmentMonths = '할부는 2개월부터 12개월까지만 가능합니다.'
  }
  if (!errors.amount && amount! < MIN_INSTALLMENT_AMOUNT) {
    errors.amount = '할부는 50,000원 이상부터 가능합니다.'
  }
  if (errors.amount || errors.installmentMonths) return { errors }
  return { terms: { amount: amount!, plan: 'INSTALLMENT', installmentMonths: months! } }
}

/** 숫자만 받는다. 쉼표는 지우고, 소수점·부호·지수 표기는 정수가 아니므로 null. */
function parseWholeNumber(raw: string): number | null {
  const text = raw.replaceAll(',', '').trim()
  if (!/^\d+$/.test(text)) return null
  return Number(text)
}

export function formatWon(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

export function planLabel(plan: PaymentPlan, months?: number | null): string {
  return plan === 'INSTALLMENT' ? `할부 ${months}개월` : '일시불'
}
