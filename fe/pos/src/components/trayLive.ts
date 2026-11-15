import { createContext, useContext } from 'react'

/**
 * 트레이와 지금 단계가 둘 다 떠 있는지. Tray가 채운다.
 * 사라지는 트레이나 걷히는 옛 단계의 카메라가 읽은 QR로 결제가 한 번 더 나가지 않게 버릴 때 쓴다.
 */
export const StepLiveContext = createContext(true)

export function useTrayStepLive(): boolean {
  return useContext(StepLiveContext)
}
