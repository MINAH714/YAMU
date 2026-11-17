import type { KeyboardEvent } from 'react'

/**
 * 결제 코드 칸에서 엔터를 누르면 바로 보낸다.
 * 태블릿 키보드는 엔터 자리에 '다음 칸으로'를 띄워 폼을 보내지 않기도 해서 직접 보낸다.
 * 한글 조합 중 엔터와 길게 눌러 반복되는 엔터는 버린다(두 번 보내지 않게).
 */
export function submitOnEnter(event: KeyboardEvent<HTMLInputElement>) {
  if (event.key !== 'Enter' || event.nativeEvent.isComposing || event.repeat) return
  event.preventDefault()
  event.currentTarget.form?.requestSubmit()
}
