import { formatToken } from '../payment/barcode'
import type { Terms } from '../payment/terms'
import { ConfirmButton } from './ConfirmButton'
import { BoardPill, TermsPill, WaitBoard } from './WaitBoard'

type Props = {
  terms: Terms
  barcodeToken: string
  /** 요청 결과를 모를 때의 사유. null이면 요청 중 */
  unknownMessage: string | null
  /** 같은 멱등 키·같은 본문으로 다시 보낸다 */
  onRetry: () => void
  onNewOrder: () => void
}

/**
 * 결제 요청 중과 요청 결과 불명. 승인 대기와 같은 판이다.
 * 결과를 모르면 판에 남아(M-WAIT-UNKNOWN) 같은 요청을 다시 보내거나, 결제 내역을 확인한 뒤 새 주문으로 넘어간다.
 * 요청 중 ↔ 결과 불명은 같은 판 하나를 고쳐 그려 링·헤일로만 바뀐다.
 */
export function RequestBoard({ terms, barcodeToken, unknownMessage, onRetry, onNewOrder }: Props) {
  const unknown = unknownMessage !== null
  return (
    <WaitBoard
      title={unknown ? '결과를 확인하지 못했어요' : '결제를 요청하고 있어요'}
      unknown={unknown}
      pills={
        <>
          <TermsPill terms={terms} />
          {unknown ? (
            <BoardPill key="unknown">{unknownMessage}</BoardPill>
          ) : (
            <BoardPill key="code">결제 코드 {formatToken(barcodeToken)}</BoardPill>
          )}
        </>
      }
      foot={
        unknown && (
          <ConfirmButton label="새 주문 시작" confirmLabel="한 번 더 누르면 새 주문을 시작해요" onConfirm={onNewOrder} />
        )
      }
    >
      {unknown && (
        <>
          <p className="board-note">
            같은 요청으로 다시 보내면 이미 접수된 결제는 새로 만들어지지 않고 처음 결과를 돌려받아요. 이 결제는 이미
            접수됐을 수 있으니 새 주문을 시작하기 전에 고객 앱의 결제 내역을 먼저 확인해 주세요.
          </p>
          <button type="button" className="primary" onClick={onRetry}>
            같은 요청 다시 보내기
          </button>
        </>
      )}
    </WaitBoard>
  )
}
