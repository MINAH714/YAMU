/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_POS_KEY?: string
  readonly VITE_STARBUCKS_POS_CODE?: string
  readonly VITE_STARBUCKS_MERCHANT_ID?: string
  readonly VITE_GS25_POS_CODE?: string
  readonly VITE_GS25_MERCHANT_ID?: string
  readonly VITE_POS_MOCK_RESULT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
