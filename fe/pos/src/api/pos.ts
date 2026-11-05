import { config } from '../config'
import { request, type ApiResult } from './client'
import { mockPaymentResult, registerMockPayment } from './mockResult'

export type PaymentPlan = 'LUMP_SUM' | 'INSTALLMENT'
export type PaymentStatus = 'REQUESTED' | 'PROCESSING' | 'APPROVED' | 'DECLINED' | 'EXPIRED' | 'CANCELED'

/** POST /api/pos/payment-requests 본문. 일시불이면 installmentMonths 칸 자체를 보내지 않는다(null도 400). */
export type PaymentRequestBody = {
  barcodeToken: string
  merchantId: number
  posCode: string
  amount: number
  plan: PaymentPlan
  installmentMonths?: number
  orderNo?: string
}

export type PaymentRequestAccepted = {
  paymentId: number
  orderNo: string | null
  status: PaymentStatus
  confirmDeadline: string
}

/** GET /api/pos/payment-requests/{paymentId} 응답(명세 「POS 결제 결과 조회」). */
export type PaymentResult = {
  paymentId: number
  orderNo: string | null
  status: PaymentStatus
  approvalNo: string | null
  approvedAt: string | null
  amount: number
  chargedAmount: number | null
  instantDiscount: number | null
  card: {
    issuerName: string
    cardType: 'CREDIT' | 'CHECK'
    maskedNumber: string
    plan: PaymentPlan
    installmentMonths: number | null
  } | null
  failure: { code: string; message: string } | null
  /** 명세 Q1(11/3 반영). DECLINED일 때만 값이 있고 다른 상태는 null이다. false면 DECLINED가 마감 전에도 최종이다 */
  retryable?: boolean | null
}

// 추천 계산(목표 10초)과 서버의 멱등 잠금(15초)보다 길게 기다린다
const CREATE_TIMEOUT_MS = 20_000
const RESULT_TIMEOUT_MS = 5_000

export async function createPaymentRequest(
  body: PaymentRequestBody,
  idempotencyKey: string,
): Promise<ApiResult<PaymentRequestAccepted>> {
  const result = await request<PaymentRequestAccepted>('/pos/payment-requests', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
    timeoutMs: CREATE_TIMEOUT_MS,
  })
  if (config.mockResult) registerMockPayment(result.data, body)
  return result
}

export function getPaymentResult(paymentId: number, posCode: string): Promise<PaymentResult> {
  if (config.mockResult) return mockPaymentResult(paymentId)
  // 결제 요청을 만든 POS(posCode)만 결과를 볼 수 있다. 다르면 404
  const path = `/pos/payment-requests/${paymentId}?posCode=${encodeURIComponent(posCode)}`
  return request<PaymentResult>(path, { timeoutMs: RESULT_TIMEOUT_MS }).then(
    (result) => result.data,
  )
}
