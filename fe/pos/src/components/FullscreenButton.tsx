import { useFullscreen } from '../hooks/useFullscreen'

/** 헤더 오른쪽 전체화면 버튼. 지원하지 않는 브라우저에서는 그리지 않는다 */
export function FullscreenButton() {
  const { supported, active, toggle } = useFullscreen()
  if (!supported) return null
  return (
    <button
      type="button"
      className="icon-button"
      aria-label={active ? '전체화면 나가기' : '전체화면으로 보기'}
      onClick={toggle}
    >
      <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {active ? (
          <path d="M9 4v3a2 2 0 0 1-2 2H4M15 4v3a2 2 0 0 0 2 2h3M9 20v-3a2 2 0 0 0-2-2H4M15 20v-3a2 2 0 0 1 2-2h3" />
        ) : (
          <path d="M4 9V6a2 2 0 0 1 2-2h3M20 9V6a2 2 0 0 0-2-2h-3M4 15v3a2 2 0 0 0 2 2h3M20 15v3a2 2 0 0 1-2 2h-3" />
        )}
      </svg>
    </button>
  )
}
