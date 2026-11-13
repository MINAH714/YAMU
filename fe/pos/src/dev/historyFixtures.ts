import { ApiError } from '../api/client'
import type { PaymentHistoryItem } from '../api/posHistory'
import { classifyCancelError } from '../payment/cancelErrors'

const kstDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' })

/** 오늘·어제 기준 KST 시각을 ISO로. 목록이 "오늘" 묶음으로 보이게 열 때마다 계산한다 */
function kst(daysAgo: number, time: string) {
  const day = kstDate.format(new Date(Date.now() - daysAgo * 86_400_000))
  return new Date(`${day}T${time}:00+09:00`).toISOString()
}

const SHINHAN = { issuerName: '신한카드', cardType: 'CREDIT', maskedNumber: '1234-****-****-5678', plan: 'LUMP_SUM', installmentMonths: null } as const
const KB = { issuerName: 'KB국민카드', cardType: 'CHECK', maskedNumber: '9411-****-****-5521', plan: 'LUMP_SUM', installmentMonths: null } as const
const HYUNDAI = { issuerName: '현대카드', cardType: 'CREDIT', maskedNumber: '4092-****-****-0087', plan: 'INSTALLMENT', installmentMonths: 3 } as const

function item(paymentId: number, overrides: Partial<PaymentHistoryItem>): PaymentHistoryItem {
  return {
    paymentId,
    orderNo: `POS-20261113-${String(paymentId).padStart(6, '0')}-001`,
    status: 'APPROVED',
    approvalNo: `3001${paymentId}`,
    approvedAt: null,
    canceledAt: null,
    amount: 10_500,
    chargedAmount: 10_500,
    instantDiscount: 0,
    card: SHINHAN,
    cancelable: true,
    ...overrides,
  }
}

export function demoHistory(): PaymentHistoryItem[] {
  return [
    item(2045, { approvedAt: kst(0, '14:28'), amount: 11_500, chargedAmount: 10_500, instantDiscount: 1_000 }),
    item(2044, { approvedAt: kst(0, '13:55'), amount: 5_000, chargedAmount: 5_000, card: KB }),
    item(2043, {
      status: 'CANCELED',
      approvedAt: kst(0, '12:10'),
      canceledAt: kst(0, '12:31'),
      amount: 56_800,
      chargedAmount: 56_800,
      card: HYUNDAI,
      cancelable: false,
    }),
    item(2039, { approvedAt: kst(0, '09:02'), amount: 4_500, chargedAmount: 4_500 }),
    item(2031, { approvedAt: kst(1, '18:41'), amount: 6_100, chargedAmount: 6_100, card: KB }),
    item(2027, { approvedAt: kst(1, '15:20'), amount: 23_000, chargedAmount: 21_000, instantDiscount: 2_000 }),
    item(2018, { approvedAt: kst(2, '11:47'), amount: 8_900, chargedAmount: 8_900, card: HYUNDAI }),
  ]
}

/** 다른 고객 바코드·다른 POS로 취소했을 때(BE 404 RESOURCE_NOT_FOUND) 화면에 보이는 문구. 실제 판정을 그대로 거친다 */
export const CANCEL_NOT_FOUND_MESSAGE = classifyCancelError(
  new ApiError(404, 'RESOURCE_NOT_FOUND', '요청한 정보를 찾을 수 없습니다.', []),
).message
