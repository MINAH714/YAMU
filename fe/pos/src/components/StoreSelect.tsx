import { missingStoreConfig } from '../config'
import { STORE_IDS, STORES, type StoreId } from '../store/catalog'
import '../styles/order.css'
import { BrandMark } from './BrandMark'
import { Character } from './Character'

export function StoreSelect({ onSelect }: { onSelect: (storeId: StoreId) => void }) {
  return (
    <section className="store-select">
      <Character pose="wave" size={128} />
      <h2 className="store-select-title">어느 매장을 열까요?</h2>
      <div className="store-cards">
        {STORE_IDS.map((storeId) => {
          const store = STORES[storeId]
          const missing = missingStoreConfig(storeId)
          return (
            <button
              key={storeId}
              type="button"
              className="store-card"
              disabled={missing.length > 0}
              onClick={() => onSelect(storeId)}
            >
              <BrandMark storeId={storeId} />
              <strong>{store.branch}</strong>
              {missing.length > 0 && <small>.env.local에 {missing.join(', ')} 값이 없어요</small>}
            </button>
          )
        })}
      </div>
    </section>
  )
}
