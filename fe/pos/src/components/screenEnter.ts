import { createContext, useContext } from 'react'

/**
 * 화면 층(ScreenLayer)이 들어오기 시작한 시각과 페이드 전 기다림(ms). 층 안의 부품이 등장 박자를 이 시각에 맞춘다.
 * 대기 판처럼 층 안에서 다시 그려지는 부품도 같은 시각을 보므로 등장 · 헤일로 박자가 끊기지 않는다.
 * 층 밖(확인 페이지 등)에서는 null이라 등장 없이 끝 상태로 보인다.
 */
export type ScreenEnter = { startedAt: number; delayMs: number }

export const ScreenEnterContext = createContext<ScreenEnter | null>(null)

export function useScreenEnter(): ScreenEnter | null {
  return useContext(ScreenEnterContext)
}
