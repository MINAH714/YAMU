import { Character } from './Character'
import { ConfirmButton } from './ConfirmButton'

/** 결제 요청의 결과를 모를 때. 같은 멱등 키로 다시 보내거나, 결제 내역을 확인한 뒤 새 주문으로 넘어간다 */
export function UnknownScreen({
  message,
  onRetry,
  onNewOrder,
}: {
  message: string
  onRetry: () => void
  onNewOrder: () => void
}) {
  return (
    <section className="flow" aria-live="polite">
      <Character pose="thinking" size={128} />
      <h2 className="flow-title">결과를 확인하지 못했어요</h2>
      <p className="alert alert-error">{message}</p>
      <p className="flow-description">
        같은 요청으로 다시 보내면 이미 접수된 결제는 새로 만들어지지 않고 처음 결과를 돌려받아요. 이 결제는 이미 접수됐을 수
        있으니 새 주문을 시작하기 전에 고객 앱의 결제 내역을 먼저 확인해 주세요.
      </p>
      <div className="flow-actions">
        <button type="button" className="primary" onClick={onRetry}>
          같은 요청 다시 보내기
        </button>
        <ConfirmButton label="새 주문 시작" confirmLabel="한 번 더 누르면 새 주문을 시작해요" onConfirm={onNewOrder} />
      </div>
    </section>
  )
}

/** 결제를 이어갈 수 없을 때. 설정 오류면 새 주문으로 넘어가지 못한다 */
export function FatalScreen({
  message,
  isConfig,
  canLeaveStore,
  onNewOrder,
  onLeaveStore,
}: {
  message: string
  isConfig: boolean
  canLeaveStore: boolean
  onNewOrder: () => void
  onLeaveStore: () => void
}) {
  return (
    <section className="flow" aria-live="polite">
      <Character pose="worry" size={128} />
      <h2 className="flow-title">{isConfig ? 'POS 설정 오류' : '결제를 이어갈 수 없어요'}</h2>
      <p className="alert alert-error">{message}</p>
      <div className="flow-actions">
        {!isConfig && (
          <button type="button" className="primary" onClick={onNewOrder}>
            새 주문
          </button>
        )}
        {isConfig && canLeaveStore && (
          <button type="button" className="secondary" onClick={onLeaveStore}>
            매장 선택으로
          </button>
        )}
      </div>
    </section>
  )
}
