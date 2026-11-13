import { ApiError, UnknownResultError } from '../api/client'

/**
 * POS 취소 요청 실패를 화면이 할 일로 나눈다. 기준은 공통 에러 code다.
 * - retry: 서버가 취소했는지 모름. 같은 멱등 키·같은 본문으로 다시 보내거나 내역에서 상태를 본다
 * - rejected: 취소되지 않았다. 바코드를 다시 스캔해 새 키로 보낸다
 * - already: 이미 취소된 결제. 내역으로 돌아간다
 * - config: POS 설정 오류. 직원이 고칠 수 없다
 */
export type CancelFailure =
  | { kind: 'retry'; message: string }
  | { kind: 'rejected'; message: string }
  | { kind: 'already'; message: string }
  | { kind: 'config'; message: string }

// 서버 기본 문구는 앱·POS 공통이라 직원이 할 일을 알기 어렵다. 결제 요청(errors.ts)처럼 코드로 바꾼다
const REJECT_MESSAGES: Record<string, string> = {
  BARCODE_NOT_FOUND: '등록되지 않은 바코드예요. 고객 앱에서 바코드를 다시 띄워 주세요.',
  BARCODE_EXPIRED: '만료된 바코드예요. 고객 앱에서 바코드를 새로 띄워 주세요.',
  BARCODE_ALREADY_USED: '이미 쓴 바코드예요. 고객 앱에서 바코드를 새로 띄워 주세요.',
  RESOURCE_NOT_FOUND: '이 결제를 한 고객의 바코드가 아니거나 이 POS에서 한 결제가 아니에요.',
  IDEMPOTENCY_KEY_REUSED: '이전 요청과 내용이 달라 보낼 수 없어요. 바코드를 다시 스캔해 주세요.',
}

export function classifyCancelError(error: unknown): CancelFailure {
  if (error instanceof UnknownResultError) {
    return { kind: 'retry', message: `${error.message} 취소됐는지 알 수 없어요.` }
  }
  if (!(error instanceof ApiError)) {
    return { kind: 'retry', message: '알 수 없는 오류가 났어요. 취소됐는지 알 수 없어요.' }
  }
  const rejectMessage = REJECT_MESSAGES[error.code]
  if (rejectMessage) return { kind: 'rejected', message: rejectMessage }

  switch (error.code) {
    case 'ALREADY_CANCELED':
      return { kind: 'already', message: '이미 취소된 결제예요.' }
    case 'IDEMPOTENCY_IN_PROGRESS':
      return { kind: 'retry', message: '같은 취소 요청을 서버가 아직 처리하고 있어요. 잠시 뒤 다시 보내 주세요.' }
    case 'INVALID_POS_KEY':
      return { kind: 'config', message: 'POS 키가 없거나 올바르지 않아요. VITE_POS_KEY 설정을 확인해 주세요.' }
  }
  // 금융망 실패(502)는 체크카드 환불 입금이 됐는지 모를 수 있다. 같은 키로 다시 보내면 서버가 정리한다
  if (error.status >= 500) {
    return { kind: 'retry', message: `서버 오류(${error.code})가 났어요. 취소됐는지 알 수 없어요.` }
  }
  return { kind: 'rejected', message: error.message }
}
