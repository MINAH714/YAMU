import { formatWon } from '../payment/terms'

export type AmountSize = 'sm' | 'md' | 'lg' | 'xl'

type Props = {
  value: number
  /** sm 16 · md 22 · lg 34 · xl 40 */
  size?: AmountSize
  className?: string
}

/** 원화 금액. 자릿수가 바뀌어도 숫자 폭이 흔들리지 않는다. 화면 사이를 날아가지 않는다(모션 명세 결정) */
export function Amount({ value, size = 'md', className }: Props) {
  const classes = `amount amount-${size}${className ? ` ${className}` : ''}`
  return <span className={classes}>{formatWon(value)}</span>
}
