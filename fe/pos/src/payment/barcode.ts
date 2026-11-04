// 결제 코드는 숫자 16자리 문자열이다(BE BarcodeTokenGenerator). 앞자리 0이 있을 수 있어 숫자로 바꾸지 않는다.
const QR_PREFIX = 'yamu://pay/'
const TOKEN = /^\d{16}$/

/** 카메라로 읽은 QR. YAMU 결제 QR(yamu://pay/{코드})만 받는다. 다른 QR이면 null. */
export function parseQr(text: string): string | null {
  const value = text.trim()
  if (!value.startsWith(QR_PREFIX)) return null
  const token = value.slice(QR_PREFIX.length)
  return TOKEN.test(token) ? token : null
}

/** 직원이 직접 입력한 값. QR 문자열 그대로이거나 코드 16자리(띄어쓰기·하이픈 허용). */
export function parseManual(text: string): string | null {
  const fromQr = parseQr(text)
  if (fromQr) return fromQr
  const digits = text.replace(/[\s-]/g, '')
  return TOKEN.test(digits) ? digits : null
}

export function formatToken(token: string): string {
  return token.replace(/(\d{4})(?=\d)/g, '$1 ')
}
