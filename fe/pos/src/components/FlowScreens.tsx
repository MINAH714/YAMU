import { Character } from './Character'

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
