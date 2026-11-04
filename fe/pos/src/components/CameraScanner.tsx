import QrScanner from 'qr-scanner'
import { useEffect, useRef, useState } from 'react'
import { parseQr } from '../payment/barcode'

type Props = {
  /** 처음 읽은 YAMU 결제 코드 하나만 넘긴다. 넘긴 뒤에는 카메라를 멈춘다 */
  onRead: (token: string) => void
  onUnknownQr: () => void
}

type CameraState = { kind: 'starting' } | { kind: 'on' } | { kind: 'error'; message: string }

export function CameraScanner({ onRead, onUnknownQr }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const onReadRef = useRef(onRead)
  const onUnknownRef = useRef(onUnknownQr)
  const [state, setState] = useState<CameraState>(() =>
    window.isSecureContext
      ? { kind: 'starting' }
      : { kind: 'error', message: '카메라는 https 또는 localhost 주소에서만 켜져요. 아래에 직접 입력해 주세요.' },
  )

  useEffect(() => {
    onReadRef.current = onRead
    onUnknownRef.current = onUnknownQr
  })

  useEffect(() => {
    const video = videoRef.current
    if (!video || !window.isSecureContext) return

    // 카메라는 같은 QR을 1초에 여러 번 읽는다. 첫 번째만 넘기고 나머지는 버린다
    let done = false
    let scanner: QrScanner | null = null

    // 개발 모드(StrictMode)는 이 effect를 붙였다 바로 떼고 다시 붙인다. 바로 켜면 두 스캐너가 같은 <video>를 쓰다가
    // 먼저 정리되는 쪽이 나중 쪽의 영상을 끊어, 카메라는 켜졌는데 화면이 비고 QR을 읽지 못한다.
    // 한 박자 늦게 켜고, 그 전에 정리되면 켜지 않는다. 배포 빌드에서는 한 번만 붙으므로 동작이 같다.
    const startTimer = setTimeout(() => {
      const current = new QrScanner(
        video,
        (result) => {
          if (done) return
          const token = parseQr(result.data)
          if (!token) {
            onUnknownRef.current()
            return
          }
          done = true
          current.stop()
          onReadRef.current(token)
        },
        { returnDetailedScanResult: true, preferredCamera: 'environment', maxScansPerSecond: 5, highlightScanRegion: true },
      )
      scanner = current
      current
        .start()
        .then(() => {
          if (!done) setState({ kind: 'on' })
        })
        .catch((error: unknown) => {
          if (done) return
          const denied = error instanceof DOMException && error.name === 'NotAllowedError'
          setState({
            kind: 'error',
            message: denied
              ? '카메라 권한이 거부됐어요. 브라우저 설정에서 허용하거나 아래에 직접 입력해 주세요.'
              : '카메라를 켜지 못했어요. 아래에 직접 입력해 주세요.',
          })
        })
    }, 0)

    return () => {
      done = true
      clearTimeout(startTimer)
      scanner?.destroy()
    }
  }, [])

  return (
    <div className="camera">
      <video ref={videoRef} muted playsInline hidden={state.kind === 'error'} />
      {state.kind === 'starting' && <p className="camera-message">카메라를 켜는 중…</p>}
      {state.kind === 'error' && <p className="camera-message">{state.message}</p>}
    </div>
  )
}
