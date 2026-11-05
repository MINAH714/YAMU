import { motion } from 'motion/react'
import { formatWon } from '../payment/terms'
import { TRAY_SPRING } from '../styles/motion'

export type AmountSize = 'sm' | 'md' | 'lg' | 'xl'

type Props = {
  value: number
  /** sm 16 · md 22 · lg 34 · xl 40 */
  size?: AmountSize
  /** 같은 값을 준 금액끼리 화면을 옮겨 가며 이어진다. 카트 합계와 트레이 머리는 "order-total" */
  layoutId?: string
  className?: string
}

/** 원화 금액. 자릿수가 바뀌어도 숫자 폭이 흔들리지 않는다 */
export function Amount({ value, size = 'md', layoutId, className }: Props) {
  const classes = `amount amount-${size}${className ? ` ${className}` : ''}`
  if (layoutId) {
    return (
      <motion.span className={classes} layoutId={layoutId} transition={TRAY_SPRING}>
        {formatWon(value)}
      </motion.span>
    )
  }
  return <span className={classes}>{formatWon(value)}</span>
}
