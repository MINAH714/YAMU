import type { StoreId } from './store/catalog'

// POS 설정값은 .env.local에서만 읽는다(.env.example 참고).
const env = import.meta.env

/** 매장 하나의 단말. posCode는 BE yamu.pos.terminals, merchantId는 그 brand-code의 merchant.merchant_id */
export type StoreConfig = { posCode: string; merchantId: number }

export const config = {
  apiBaseUrl: (env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, ''),
  posKey: env.VITE_POS_KEY ?? '',
  mockResult: env.VITE_POS_MOCK_RESULT === 'true',
  stores: {
    starbucks: { posCode: env.VITE_STARBUCKS_POS_CODE ?? '', merchantId: Number(env.VITE_STARBUCKS_MERCHANT_ID) },
    gs25: { posCode: env.VITE_GS25_POS_CODE ?? '', merchantId: Number(env.VITE_GS25_MERCHANT_ID) },
  } satisfies Record<StoreId, StoreConfig>,
}

const STORE_ENV_PREFIX: Record<StoreId, string> = { starbucks: 'VITE_STARBUCKS', gs25: 'VITE_GS25' }

/** 모든 매장이 같이 쓰는 설정 중 비어 있는 것. 하나라도 있으면 POS를 열지 않는다. */
export function missingConfig(): string[] {
  return config.posKey ? [] : ['VITE_POS_KEY']
}

/** 이 매장 설정 중 비어 있는 것. 있으면 그 매장은 고를 수 없다. */
export function missingStoreConfig(storeId: StoreId): string[] {
  const store = config.stores[storeId]
  const prefix = STORE_ENV_PREFIX[storeId]
  const missing: string[] = []
  if (!store.posCode) missing.push(`${prefix}_POS_CODE`)
  if (!Number.isSafeInteger(store.merchantId) || store.merchantId <= 0) missing.push(`${prefix}_MERCHANT_ID`)
  return missing
}
