import { missingStoreConfig } from '../config'
import { STORE_IDS, STORES, type StoreId } from '../store/catalog'
import { BrandMark } from './BrandMark'

export function StoreSelect({ onSelect }: { onSelect: (storeId: StoreId) => void }) {
  return (
    <section className="store-select">
      <h2>매장을 선택해 주세요</h2>
      <div className="store-cards">
        {STORE_IDS.map((storeId) => {
          const store = STORES[storeId]
          const missing = missingStoreConfig(storeId)
          return (
            <button
              key={storeId}
              type="button"
              className={`store-card store-card-${storeId}`}
              disabled={missing.length > 0}
              onClick={() => onSelect(storeId)}
            >
              <BrandMark storeId={storeId} />
              <strong>
                {store.brand} {store.branch}
              </strong>
              <span>{missing.length > 0 ? `.env.local에 ${missing.join(', ')} 값이 없어요` : '영업 시작'}</span>
            </button>
          )
        })}
      </div>
    </section>
  )
}
