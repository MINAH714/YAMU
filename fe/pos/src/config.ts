// POS 설정값은 .env.local에서만 읽는다(.env.example 참고).
const env = import.meta.env

export const config = {
  apiBaseUrl: (env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, ''),
  posKey: env.VITE_POS_KEY ?? '',
  posCode: env.VITE_POS_CODE ?? '',
  merchantId: Number(env.VITE_MERCHANT_ID),
  mockResult: env.VITE_POS_MOCK_RESULT === 'true',
}

/** 비어 있는 설정 이름. 하나라도 있으면 결제를 보내지 않는다. */
export function missingConfig(): string[] {
  const missing: string[] = []
  if (!config.posKey) missing.push('VITE_POS_KEY')
  if (!config.posCode) missing.push('VITE_POS_CODE')
  if (!Number.isSafeInteger(config.merchantId) || config.merchantId <= 0) missing.push('VITE_MERCHANT_ID')
  return missing
}
