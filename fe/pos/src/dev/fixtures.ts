import type { PaymentResult, PaymentStatus } from '../api/pos'
import { toOrder, type Cart } from '../store/cart'
import { STORES } from '../store/catalog'
import type { Terms } from '../payment/terms'

export const STARBUCKS_CART: Cart = { 'sb-americano': 2, 'sb-croissant': 1, 'sb-cheesecake': 1 }
export const GS25_CART: Cart = { 'gs-triangle-kimbap': 2, 'gs-cup-ramen': 1, 'gs-cola': 1, 'gs-jeyuk-dosirak': 1 }

export const DEMO_ORDER = toOrder(STORES.starbucks, STARBUCKS_CART)

/** 할부를 고를 수 있는 금액(5만 원 이상)의 주문 */
export const LARGE_CART: Cart = { 'sb-americano': 10, 'sb-cheesecake': 2 }
export const LARGE_ORDER = toOrder(STORES.starbucks, LARGE_CART)

export const DEMO_TERMS: Terms = { amount: DEMO_ORDER.total, plan: 'LUMP_SUM' }

export const DEMO_TOKEN = '1234567890123456'

export function demoResult(status: PaymentStatus, overrides: Partial<PaymentResult> = {}): PaymentResult {
  return {
    paymentId: 1001,
    orderNo: 'POS-20261105-120000-001',
    status,
    approvalNo: null,
    approvedAt: null,
    amount: DEMO_ORDER.total,
    chargedAmount: null,
    instantDiscount: null,
    card: null,
    failure: null,
    ...overrides,
  }
}

export const APPROVED_RESULT = demoResult('APPROVED', {
  approvalNo: '30012345',
  approvedAt: '2026-11-05T12:00:30+09:00',
  chargedAmount: DEMO_ORDER.total - 1_000,
  instantDiscount: 1_000,
  card: { issuerName: '신한카드', cardType: 'CREDIT', maskedNumber: '1234-****-****-5678', plan: 'LUMP_SUM', installmentMonths: null },
})

export const DECLINED_RESULT = demoResult('DECLINED', {
  retryable: false,
  failure: { code: 'INSUFFICIENT_BALANCE', message: '카드 한도가 부족해 결제하지 못했어요.' },
})

export const EXPIRED_RESULT = demoResult('EXPIRED')

export const CANCELED_RESULT = demoResult('CANCELED')

/** 은행 처리가 늦어 출금 결과를 아직 모르는 거절. 영수증이 "결과 미확인"으로 보인다 */
export const NOT_CONFIRMED_RESULT = demoResult('DECLINED', {
  retryable: false,
  failure: { code: 'WITHDRAWAL_NOT_CONFIRMED', message: '출금 결과를 확인하지 못했어요.' },
})
