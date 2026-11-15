import { motion, useIsPresent, type Variants } from 'motion/react'
import { useState, type ReactNode } from 'react'
import { REDUCED, SWAP } from '../styles/motion'
import { ScreenEnterContext } from './screenEnter'

/**
 * 화면 한 번 바뀜의 계획. 바깥 AnimatePresence의 custom으로도 넘겨 나가는 층이 들어오는 층에 맞춰 사라진다.
 * delayMs: 트레이가 제자리에서 사라진 뒤 들어올 때 그만큼 기다린다.
 * reveal: 결제 요청이 실패해 주문 화면으로 돌아갈 때처럼 나가는 층이 위에 있어 걷혀야 할 때.
 */
export type SwapPlan = { key: string; delayMs: number; reveal: boolean; reduce: boolean }

const variants: Variants = {
  hidden: { opacity: 0 },
  shown: (plan: SwapPlan) => ({ opacity: 1, transition: { ...(plan.reduce ? REDUCED : SWAP), delay: plan.delayMs / 1000 } }),
  // 들어오는 층이 다 덮을 때까지 그대로 있다가 지운다. 둘 다 옅어지면 그 사이로 바탕이 비친다
  gone: (plan: SwapPlan) =>
    plan.reveal
      ? { opacity: 0, transition: plan.reduce ? REDUCED : SWAP }
      : { opacity: 0, transition: { duration: 0, delay: (plan.delayMs + (plan.reduce ? 150 : 200)) / 1000 } },
}

/**
 * 화면 통째 교체(모션 명세 screen.swap). 새 화면이 0.2초에 나타나고 옛 화면은 그 아래 그대로 있다가 지워진다.
 * 금액·상태 아이콘은 화면 사이를 날아가지 않는다(결정). 나가는 화면은 눌리지 않는다.
 */
export function ScreenLayer({ plan, surface, children }: { plan: SwapPlan; surface: 'canvas' | 'flow'; children: ReactNode }) {
  const isPresent = useIsPresent()
  const [enter] = useState(() => ({ startedAt: performance.now(), delayMs: plan.delayMs }))
  return (
    <motion.div
      className="screen"
      data-surface={surface}
      inert={!isPresent}
      custom={plan}
      variants={variants}
      initial="hidden"
      animate="shown"
      exit="gone"
    >
      <ScreenEnterContext.Provider value={enter}>{children}</ScreenEnterContext.Provider>
    </motion.div>
  )
}
