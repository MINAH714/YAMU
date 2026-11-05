import type { ReactNode } from 'react'
import type { StoreId } from '../store/catalog'

/** 확인 페이지의 항목 하나. App의 틀(헤더·배경)에 render 결과를 넣어 보여 준다 */
export type GalleryEntry = {
  /** 주소에 쓴다. ?gallery=order-filled */
  id: string
  group: string
  label: string
  store: StoreId | null
  /** 헤더 단계 표시(1 주문, 2 결제수단, 3 결제). 0이면 숨김 */
  step: number
  /** canvas 회색 바탕, flow 흰 바탕 */
  surface: 'canvas' | 'flow'
  render: () => ReactNode
  /** render 위에 띄울 트레이. Tray에 key를 달아서 돌려준다 */
  tray?: () => ReactNode
}
