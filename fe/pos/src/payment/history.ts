import type { PaymentHistoryItem } from '../api/posHistory'

const kstTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false })

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
