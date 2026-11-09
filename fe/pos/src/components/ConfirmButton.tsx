import { useEffect, useRef, useState } from 'react'

type Props = {
  label: string
  /** 한 번 누른 뒤의 글자. 다시 누르면 일어날 일을 적는다 */
  confirmLabel: string
  onConfirm: () => void
  /** text 또는 secondary */
  className?: string
  disabled?: boolean
}

const ARMED_MS = 3_000
const CONFIRM_GUARD_MS = 350

/** 되돌리기 어려운 동작. 그 자리에서 한 번 더 눌러야 실행되고, 3초 안에 안 누르면 처음으로 돌아간다 */
export function ConfirmButton({ label, confirmLabel, onConfirm, className = 'text', disabled }: Props) {
  const [armed, setArmed] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const armedAt = useRef(0)

  useEffect(() => () => clearTimeout(timer.current), [])

  const press = () => {
    if (armed) {
      // 연타한 두 번째 누름은 바뀐 글자를 읽기 전이라 확정으로 치지 않는다
      if (performance.now() - armedAt.current < CONFIRM_GUARD_MS) return
      clearTimeout(timer.current)
      setArmed(false)
      onConfirm()
      return
    }
    armedAt.current = performance.now()
    setArmed(true)
    timer.current = setTimeout(() => setArmed(false), ARMED_MS)
  }

  const showArmed = armed && !disabled
  return (
    <button type="button" className={className} data-armed={showArmed} disabled={disabled} onClick={press}>
      {showArmed ? confirmLabel : label}
    </button>
  )
}
