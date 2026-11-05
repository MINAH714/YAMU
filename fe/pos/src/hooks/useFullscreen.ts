import { useSyncExternalStore } from 'react'

// 브라우저 전체화면은 페이지에 하나뿐이라 상태와 부수 효과(방향 고정·화면 켜짐)를 모듈에 둔다.
// 전체화면을 나가도 React 트리는 그대로라 주문은 유지된다.

type OrientationWithLock = ScreenOrientation & {
  lock?: (orientation: string) => Promise<void>
  unlock?: () => void
}

let wakeLock: WakeLockSentinel | null = null
let wakeLockPending = false
let autoEntered = false

const listeners = new Set<() => void>()

function isActive(): boolean {
  return document.fullscreenElement !== null
}

function isSupported(): boolean {
  return document.fullscreenEnabled === true && typeof document.documentElement.requestFullscreen === 'function'
}

async function acquireWakeLock() {
  if (wakeLock || wakeLockPending || !('wakeLock' in navigator)) return
  wakeLockPending = true
  try {
    const sentinel = await navigator.wakeLock.request('screen')
    // 요청 중에 전체화면을 나갔으면 바로 놓는다
    if (!isActive()) {
      await sentinel.release()
      return
    }
    wakeLock = sentinel
    sentinel.addEventListener('release', () => {
      if (wakeLock === sentinel) wakeLock = null
    })
  } catch {
    // 지원하지 않거나 배터리 절약 중이면 화면 켜짐 없이 진행한다
  } finally {
    wakeLockPending = false
  }
}

function releaseWakeLock() {
  wakeLock?.release().catch(() => {})
  wakeLock = null
}

function syncEffects() {
  const orientation = screen.orientation as OrientationWithLock | undefined
  if (isActive()) {
    orientation?.lock?.('landscape').catch(() => {})
    void acquireWakeLock()
    return
  }
  try {
    orientation?.unlock?.()
  } catch {
    // 고정한 적이 없으면 던지는 브라우저가 있다
  }
  releaseWakeLock()
}

function handleFullscreenChange() {
  syncEffects()
  listeners.forEach((listener) => listener())
}

// 화면이 꺼졌다 켜지면 브라우저가 wake lock을 풀어 둔다
function handleVisibilityChange() {
  if (document.visibilityState === 'visible' && isActive()) void acquireWakeLock()
}

function subscribe(listener: () => void) {
  if (listeners.size === 0) {
    document.addEventListener('fullscreenchange', handleFullscreenChange)
    document.addEventListener('visibilitychange', handleVisibilityChange)
  }
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0) {
      document.removeEventListener('fullscreenchange', handleFullscreenChange)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }
}

/** 사용자 입력 처리 안에서 불러야 한다. 거절되면 전체화면 없이 그대로 쓴다 */
function enter() {
  if (!isSupported() || isActive()) return
  document.documentElement.requestFullscreen({ navigationUI: 'hide' }).catch(() => {})
}

function exit() {
  if (!isActive()) return
  document.exitFullscreen().catch(() => {})
}

function toggle() {
  if (isActive()) exit()
  else enter()
}

/** 터치 기기에서 처음 한 번만 전체화면을 시도한다. 사용자가 나간 뒤에는 다시 들어가지 않는다 */
export function enterFullscreenOnFirstTouch() {
  if (autoEntered) return
  autoEntered = true
  if (window.matchMedia('(pointer: coarse)').matches) enter()
}

export function useFullscreen() {
  const active = useSyncExternalStore(subscribe, isActive)
  return { supported: isSupported(), active, enter, exit, toggle }
}
