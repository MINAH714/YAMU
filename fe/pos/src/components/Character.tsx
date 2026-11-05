import { motion } from 'motion/react'
import pay from '../assets/character/pay.webp'
import thinking from '../assets/character/thinking.webp'
import wave from '../assets/character/wave.webp'
import worry from '../assets/character/worry.webp'
import { EASE } from '../styles/motion'

export type CharacterPose = 'wave' | 'thinking' | 'pay' | 'worry'
export type CharacterSize = 96 | 128 | 160

// width·height는 webp 원본 크기. 그림이 늦게 받아져도 자리가 밀리지 않게 비율을 미리 잡는다
const POSES: Record<CharacterPose, { src: string; width: number; height: number }> = {
  wave: { src: wave, width: 349, height: 447 },
  thinking: { src: thinking, width: 420, height: 431 },
  pay: { src: pay, width: 502, height: 446 },
  worry: { src: worry, width: 444, height: 412 },
}

// 결제 결과 화면에서 그림이 늦게 뜨지 않게 미리 받아 둔다
for (const { src } of Object.values(POSES)) new Image().src = src

type Props = {
  pose: CharacterPose
  /** 그림 높이(px) */
  size?: CharacterSize
  className?: string
}

/** 장식용 캐릭터 한 컷. 들어올 때 한 번만 볼록하고 그 뒤로는 움직이지 않는다 */
export function Character({ pose, size = 128, className }: Props) {
  const { src, width, height } = POSES[pose]
  return (
    <motion.img
      key={pose}
      className={`character${className ? ` ${className}` : ''}`}
      src={src}
      alt=""
      draggable={false}
      height={size}
      width={Math.round((size * width) / height)}
      initial={{ scale: 0.92, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={{ duration: 0.4, ease: EASE }}
    />
  )
}
