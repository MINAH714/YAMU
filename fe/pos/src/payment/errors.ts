import { ApiError, UnknownResultError } from '../api/client'
import type { TermsErrors, TermsField } from './terms'

/**
 * 결제 요청 실패를 화면이 할 일로 나눈다. 기준은 HTTP 상태가 아니라 공통 에러 code다.
 * - terms: 입력 칸으로 돌아가 칸별 오류 표시
 * - barcode: 스캔으로 돌아가 앱에서 새 바코드를 받게 안내
 * - retry: 서버가 처리했는지 모름. 같은 멱등 키로 다시 보낸다
 * - config: POS 설정 오류. 직원이 고칠 수 없으므로 더 보내지 않는다
 * - discard: 이 요청은 버리고 처음부터(새 멱등 키)
 */
export type CreateFailure =
  | { kind: 'terms'; errors: TermsErrors; message: string | null }
  | { kind: 'barcode'; message: string }
  | { kind: 'retry'; message: string }
  | { kind: 'config'; message: string }
  | { kind: 'discard'; message: string }

const BARCODE_MESSAGES: Record<string, string> = {
  BARCODE_NOT_FOUND: '등록되지 않은 바코드예요. 앱에서 바코드를 다시 띄워 주세요.',
  BARCODE_EXPIRED: '만료된 바코드예요. 앱에서 바코드를 새로 발급받아 주세요.',
  BARCODE_ALREADY_USED: '이미 결제에 사용한 바코드예요. 앱에서 바코드를 새로 발급받아 주세요.',
}

const TERMS_FIELDS: readonly string[] = ['amount', 'plan', 'installmentMonths'] satisfies TermsField[]

export function classifyCreateError(error: unknown): CreateFailure {
  if (error instanceof UnknownResultError) {
    return { kind: 'retry', message: `${error.message} 결제가 접수됐는지 알 수 없어요.` }
  }
  if (!(error instanceof ApiError)) {
    return { kind: 'retry', message: '알 수 없는 오류가 났어요. 결제가 접수됐는지 알 수 없어요.' }
  }
  const barcodeMessage = BARCODE_MESSAGES[error.code]
  if (barcodeMessage) return { kind: 'barcode', message: barcodeMessage }

  switch (error.code) {
    case 'INVALID_AMOUNT':
    case 'INVALID_INSTALLMENT':
    case 'INVALID_REQUEST': {
      const errors: TermsErrors = {}
      const others: string[] = []
      for (const fieldError of error.fieldErrors) {
        if (TERMS_FIELDS.includes(fieldError.field)) errors[fieldError.field as TermsField] = fieldError.reason
        else others.push(fieldError.reason)
      }
      const message = others.length > 0 ? others.join(' ') : Object.keys(errors).length === 0 ? error.message : null
      return { kind: 'terms', errors, message }
    }
    case 'IDEMPOTENCY_IN_PROGRESS':
      return { kind: 'retry', message: '같은 결제 요청을 서버가 아직 처리하고 있어요. 잠시 뒤 다시 보내 주세요.' }
    case 'IDEMPOTENCY_KEY_REUSED':
      return { kind: 'discard', message: '이전 요청과 내용이 달라 보낼 수 없어요. 처음부터 다시 결제해 주세요.' }
    case 'INVALID_POS_KEY':
      return { kind: 'config', message: 'POS 키가 없거나 올바르지 않아요. VITE_POS_KEY 설정을 확인해 주세요.' }
    case 'MERCHANT_NOT_FOUND':
      return { kind: 'config', message: `${error.message} VITE_POS_CODE·VITE_MERCHANT_ID 설정을 확인해 주세요.` }
  }
  if (error.status >= 500) {
    return { kind: 'retry', message: `서버 오류(${error.code})가 났어요. 결제가 접수됐는지 알 수 없어요.` }
  }
  return { kind: 'discard', message: `${error.message} (${error.code})` }
}
