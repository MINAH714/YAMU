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
  /** 명세 Q1 제안. 아직 없을 수 있어 선택값으로 둔다. false면 DECLINED가 마감 전에도 최종이다 */
  retryable?: boolean
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

export function getPaymentResult(paymentId: number): Promise<PaymentResult> {
  if (config.mockResult) return mockPaymentResult(paymentId)
  return request<PaymentResult>(`/pos/payment-requests/${paymentId}`, { timeoutMs: RESULT_TIMEOUT_MS }).then(
    (result) => result.data,
  )
}
