import type { PaymentHistoryItem } from '../api/posHistory'

const kstTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false })
const kstDay = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric' })
const kstDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' })

/**
 * 결제 내역 상세·취소 화면의 시각. 오늘(KST)이면 14:28, 다른 날이면 11월 15일 18:41.
 * 내역은 최근 7일이라 연도는 붙이지 않는다. 목록 줄은 날짜 묶음 아래라 formatKstTime을 쓴다
 */
export function formatKstDateTime(iso: string | null, now: Date = new Date()) {
  if (!iso) return '-'
  const date = new Date(iso)
  const time = kstTime.format(date)
  return kstDate.format(date) === kstDate.format(now) ? time : `${kstDay.format(date)} ${time}`
}

/** 영수증 금액. 즉시할인이 있으면 할인 뒤 금액이다(결과 화면과 같음) */
export function chargedOf(item: Pick<PaymentHistoryItem, 'amount' | 'chargedAmount' | 'instantDiscount'>) {
  return item.chargedAmount ?? item.amount - (item.instantDiscount ?? 0)
}

/** 카드사와 끝 4자리. 1234-****-****-5678 → 신한카드 ·5678 */
export function cardLabel(card: PaymentHistoryItem['card']) {
  if (!card) return '-'
  return `${card.issuerName} ·${card.maskedNumber.slice(-4)}`
}

export function formatKstTime(iso: string | null) {
  return iso ? kstTime.format(new Date(iso)) : '-'
}
