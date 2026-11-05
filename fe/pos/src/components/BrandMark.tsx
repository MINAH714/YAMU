import type { StoreId } from '../store/catalog'

/** 매장 글자 로고. 상표 이미지는 쓰지 않고 브랜드 색 글자로만 그린다 */
export function BrandMark({ storeId }: { storeId: StoreId }) {
  if (storeId === 'gs25') {
    return (
      <span className="brand-mark brand-gs25" aria-label="GS25">
        GS<span>25</span>
      </span>
    )
  }
  return (
    <span className="brand-mark brand-starbucks" aria-label="스타벅스">
      STARBUCKS
    </span>
  )
}
