/** 단계별 트레이 제목과 폭. App과 확인 페이지가 같이 쓴다 */
export const TRAY_META = {
  method: { title: '결제수단을 골라 주세요', width: 560 },
  cash: { title: '현금 결제', width: 720 },
  scan: { title: '결제 QR을 스캔해 주세요', width: 720 },
} as const
