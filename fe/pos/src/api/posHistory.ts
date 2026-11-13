import { request } from './client'
import type { PaymentResult, PaymentStatus } from './pos'

/**
 * GET /api/pos/payment-requests?posCode= 응답의 한 건(BE PosPaymentHistory.Item, S15P31E103-423).
 * 품목은 POS 화면에만 있던 값이라 내역에는 없다. 시각은 UTC(끝에 Z).
 */
export type PaymentHistoryItem = {
  paymentId: number
  orderNo: string | null
  status: PaymentStatus
  approvalNo: string | null
  approvedAt: string | null
  canceledAt: string | null
  amount: number
  chargedAmount: number | null
  instantDiscount: number | null
  card: PaymentResult['card']
  /** 지금 취소를 요청할 수 있는지(APPROVED). 환불 계좌 같은 조건은 취소할 때 서버가 다시 본다 */
  cancelable: boolean
}

/** POST /api/pos/payment-requests/{paymentId}/cancel 본문 */
export type PaymentCancelBody = { posCode: string; barcodeToken: string }

/** POST /api/pos/payment-requests/{paymentId}/cancel 응답(BE PosPaymentCancelResult, S15P31E103-424) */
export type PaymentCancelResult = {
  paymentId: number
  orderNo: string | null
  status: PaymentStatus
  canceledAt: string | null
  amount: number
  chargedAmount: number | null
}

const HISTORY_TIMEOUT_MS = 5_000
// 체크카드는 취소 안에서 금융망 입금까지 한다. 결제 요청과 같은 여유를 둔다
const CANCEL_TIMEOUT_MS = 20_000

/** 이 POS(posCode)가 만든 최근 7일 승인·취소 결제, 최신 50건. 결과 Mock이어도 실제 BE를 부른다 */
export async function getPaymentHistory(posCode: string): Promise<PaymentHistoryItem[]> {
  const path = `/pos/payment-requests?posCode=${encodeURIComponent(posCode)}`
  const result = await request<{ items: PaymentHistoryItem[] }>(path, { timeoutMs: HISTORY_TIMEOUT_MS })
  return result.data.items
}

/**
 * 고객 바코드로 본인 확인을 하고 취소한다. 같은 키로 다시 보내면 서버가 처음 응답을 돌려주므로
 * 결과를 모를 때는 같은 키·같은 본문으로 다시 보낸다. 결과 Mock이어도 실제 BE를 부른다.
 */
export async function cancelPayment(
  paymentId: number,
  body: PaymentCancelBody,
  idempotencyKey: string,
): Promise<PaymentCancelResult> {
  const result = await request<PaymentCancelResult>(`/pos/payment-requests/${paymentId}/cancel`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
    timeoutMs: CANCEL_TIMEOUT_MS,
  })
  return result.data
}
