import { config } from '../config'

/** BE 공통 응답(ApiResponse). 실패면 error.code로 원인을 나눈다. */
type ApiEnvelope<T> = {
  success: boolean
  data: T | null
  error: { code: string; message: string; details: Record<string, unknown> | null } | null
}

export type FieldError = { field: string; reason: string }

/** 서버가 실패로 답한 경우(4xx·5xx이고 공통 실패 형식). */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[]) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

/**
 * 서버에 닿았는지 모르는 실패(연결 끊김·시간 초과·공통 형식이 아닌 응답).
 * 결제 요청은 서버에서 처리됐을 수 있으므로 "실패"로 단정하지 않고 같은 멱등 키로 다시 보낸다.
 */
export class UnknownResultError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UnknownResultError'
  }
}

export type ApiResult<T> = { data: T; serverTime: number | null }

type RequestOptions = {
  method?: 'GET' | 'POST'
  headers?: Record<string, string>
  body?: unknown
  timeoutMs: number
}

export async function request<T>(path: string, options: RequestOptions): Promise<ApiResult<T>> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), options.timeoutMs)
  let response: Response
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      method: options.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        'X-POS-Key': config.posKey,
        ...options.headers,
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    })
  } catch (error) {
    const timedOut = error instanceof DOMException && error.name === 'AbortError'
    throw new UnknownResultError(timedOut ? '서버 응답이 늦어요.' : '서버에 연결하지 못했어요.')
  } finally {
    clearTimeout(timer)
  }

  let envelope: ApiEnvelope<T>
  try {
    envelope = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new UnknownResultError(`서버 응답을 읽지 못했어요. (HTTP ${response.status})`)
  }

  if (response.ok && envelope.success && envelope.data !== null) {
    const date = response.headers.get('Date')
    const serverTime = date ? Date.parse(date) : NaN
    return { data: envelope.data, serverTime: Number.isNaN(serverTime) ? null : serverTime }
  }
  if (envelope.error) {
    const raw = envelope.error.details?.fieldErrors
    const fieldErrors = Array.isArray(raw) ? (raw as FieldError[]) : []
    throw new ApiError(response.status, envelope.error.code, envelope.error.message, fieldErrors)
  }
  throw new UnknownResultError(`서버 응답 형식이 예상과 달라요. (HTTP ${response.status})`)
}

/** crypto.randomUUID는 https·localhost에서만 있어서, 사내망 IP로 열어도 되도록 직접 만든다(UUID v4). */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
