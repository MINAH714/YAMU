import { UnknownResultError } from './client'
import type { PaymentRequestAccepted, PaymentRequestBody, PaymentResult } from './pos'

/**
 * POS 결제 결과 조회 API(S15P31E103-229) 전까지 쓰는 Mock. VITE_POS_MOCK_RESULT=true일 때만 쓴다.
 * 결제 요청 생성은 실제 BE가 처리하고, 앱에서 승인·거절한 결과만 화면의 Mock 버튼으로 흉내 낸다.
 * 마감이 지난 REQUESTED는 조회할 때 EXPIRED로 바꾼다(명세 Q4 제안과 같음).
 */
export type MockAction = 'approve' | 'approveWithDiscount' | 'decline' | 'declineFinal' | 'reject' | 'disconnect'

type MockPayment = {
  accepted: PaymentRequestAccepted
  body: PaymentRequestBody
  result: PaymentResult
  disconnected: boolean
}

const payments = new Map<number, MockPayment>()

export function registerMockPayment(accepted: PaymentRequestAccepted, body: PaymentRequestBody) {
  // 같은 멱등 키 재전송이면 같은 paymentId가 다시 온다. 이미 진행한 상태를 지우지 않는다
  if (payments.has(accepted.paymentId)) return
  payments.set(accepted.paymentId, {
    accepted,
    body,
    disconnected: false,
    result: {
      paymentId: accepted.paymentId,
      orderNo: accepted.orderNo,
      status: 'REQUESTED',
      approvalNo: null,
      approvedAt: null,
      amount: body.amount,
      chargedAmount: null,
      instantDiscount: null,
      card: null,
      failure: null,
    },
  })
}

export function applyMockAction(paymentId: number, action: MockAction) {
  const payment = payments.get(paymentId)
  if (!payment) return
  if (action === 'disconnect') {
    payment.disconnected = !payment.disconnected
    return
  }
  const { body, result } = payment
  // 고객 거절은 REQUESTED·DECLINED만 받는다. 승인된 결제는 그대로 둔다(BE 409와 같음).
  // 마감이 지났으면 BE처럼 거절 대신 EXPIRED로 바꾼다(BE는 EXPIRED를 저장하고 410을 준다)
  if (action === 'reject') {
    if (result.status === 'REQUESTED' || result.status === 'DECLINED') {
      const pastDeadline = Date.now() >= Date.parse(payment.accepted.confirmDeadline)
      payment.result = pastDeadline
        ? { ...result, status: 'EXPIRED' }
        : { ...result, status: 'REJECTED', retryable: null }
    }
    return
  }
  if (action === 'approve' || action === 'approveWithDiscount') {
    const discount = action === 'approveWithDiscount' ? Math.min(body.amount, Math.floor(body.amount * 0.1)) : 0
    const now = new Date()
    payment.result = {
      ...result,
      status: 'APPROVED',
      approvalNo: `Y${kstDate(now)}${String(paymentId).padStart(10, '0')}`,
      approvedAt: now.toISOString(),
      chargedAmount: body.amount - discount,
      instantDiscount: discount,
      card: {
        issuerName: '모의카드',
        cardType: body.plan === 'INSTALLMENT' ? 'CREDIT' : 'CHECK',
        maskedNumber: '4570-****-****-1234',
        plan: body.plan,
        installmentMonths: body.installmentMonths ?? null,
      },
      failure: null,
    }
    return
  }
  payment.result = {
    ...result,
    status: 'DECLINED',
    failure: { code: 'INSUFFICIENT_BALANCE', message: '잔액 부족으로 결제가 거절됐습니다.' },
    retryable: action === 'decline',
  }
}

export async function mockPaymentResult(paymentId: number): Promise<PaymentResult> {
  await new Promise((resolve) => setTimeout(resolve, 150))
  const payment = payments.get(paymentId)
  if (!payment) throw new UnknownResultError('Mock에 없는 결제입니다. 새로고침하면 Mock 상태가 사라져요.')
  if (payment.disconnected) throw new UnknownResultError('서버에 연결하지 못했어요. (Mock)')
  const expired = Date.now() > Date.parse(payment.accepted.confirmDeadline)
  if (expired && payment.result.status === 'REQUESTED') {
    payment.result = { ...payment.result, status: 'EXPIRED' }
  }
  return payment.result
}

function kstDate(date: Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(date).replaceAll('-', '')
}
