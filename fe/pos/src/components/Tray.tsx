import { motion, useIsPresent } from 'motion/react'
import { useEffect, useId, useRef, type ReactNode } from 'react'
import { FAST, TRAY_SPRING } from '../styles/motion'

type Props = {
  title: string
  /** 첫 단계. 닫기 아이콘을 보이고 바깥을 눌러도 닫힌다 */
  onClose?: () => void
  /** 다음 단계. 뒤로 아이콘을 보인다. onClose보다 우선한다 */
  onBack?: () => void
  children: ReactNode
  /** 최대 폭(px). 기본 560 */
  width?: number
}

// 레이아웃 애니메이션 중 모서리가 찌그러지지 않으려면 motion이 px 숫자로 알아야 한다. --r-tray와 같은 값
const TRAY_RADIUS = 24
const CLOSE_GUARD_MS = 350

/**
 * 주문 화면 위에 뜨는 시트. AnimatePresence 바로 아래에 key를 고정해 두면 단계가 바뀌어도
 * scrim과 시트는 그대로 있고 높이만 바뀐다. 단계별 내용은 TrayStep으로 감싼다.
 */
export function Tray({ title, onClose, onBack, children, width = 560 }: Props) {
  const isPresent = useIsPresent()
  const titleId = useId()
  const sheetRef = useRef<HTMLElement>(null)
  const closableAt = useRef(0)
  const dismiss = onBack ?? onClose

  // 뒤의 주문 화면이 inert라 포커스가 갈 곳이 없어진다
  useEffect(() => {
    sheetRef.current?.focus({ preventScroll: true })
  }, [])

  // 버튼을 연타하면 두 번째 탭이 막 뜬 트레이의 바깥이나 닫기에 떨어진다. 단계가 바뀐 직후의 닫기는 버린다
  useEffect(() => {
    closableAt.current = performance.now() + CLOSE_GUARD_MS
  }, [title])

  const close = () => {
    if (performance.now() >= closableAt.current) onClose?.()
  }

  // 닫히는 중인 트레이의 옛 콜백이 불리면 결제를 보낸 뒤에 주문 잠금이 풀린다
  useEffect(() => {
    if (!dismiss || !isPresent) return
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') dismiss()
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [dismiss, isPresent])

  return (
    // 닫히는 동안에는 남아 있는 버튼이 눌리지 않게 막는다
    <div className="tray-root" inert={!isPresent}>
      <motion.div
        className="tray-scrim"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={FAST}
        onClick={onBack ? undefined : close}
      />
      <motion.section
        ref={sheetRef}
        className="tray"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        layout
        style={{ maxWidth: width, borderRadius: TRAY_RADIUS }}
        initial={{ y: '100%' }}
        animate={{ y: 0 }}
        exit={{ y: '100%' }}
        transition={TRAY_SPRING}
      >
        <motion.header className="tray-header" layout="position" transition={TRAY_SPRING}>
          <h2 className="tray-title" id={titleId}>
            {title}
          </h2>
          {onBack ? (
            <button type="button" className="icon-button" aria-label="뒤로" onClick={onBack}>
              <BackIcon />
            </button>
          ) : (
            onClose && (
              <button type="button" className="icon-button" aria-label="닫기" onClick={close}>
                <CloseIcon />
              </button>
            )
          )}
        </motion.header>
        <motion.div className="tray-body" layout="position" transition={TRAY_SPRING}>
          {children}
        </motion.div>
      </motion.section>
    </div>
  )
}

/** 트레이의 단계 하나. 단계마다 key를 달리 주면 내용이 바로 바뀌고 새 내용만 살짝 나타난다 */
export function TrayStep({ children }: { children: ReactNode }) {
  return (
    <motion.div className="tray-step" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={FAST}>
      {children}
    </motion.div>
  )
}

function BackIcon() {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M15 5l-7 7 7 7" />
    </svg>
  )
}

function CloseIcon() {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  )
}
