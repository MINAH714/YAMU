import { useEffect, useState } from 'react'
import './App.css'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.replace(/\/+$/, '')

function App() {
  const [apiStatus, setApiStatus] = useState(
    apiBaseUrl ? '확인 중' : 'API 주소를 설정해 주세요',
  )

  useEffect(() => {
    if (!apiBaseUrl) return

    const controller = new AbortController()

    fetch(`${apiBaseUrl}/health`, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error('API 응답 오류')
        const data: { status?: string } = await response.json()
        setApiStatus(data.status === 'ok' ? '연결됨' : 'API 응답 오류')
      })
      .catch((error: Error) => {
        if (error.name !== 'AbortError') setApiStatus('연결 안 됨')
      })

    return () => controller.abort()
  }, [])

  return (
    <main className="app">
      <h1>YAMU 모의 POS</h1>
      <p>가맹점 결제 시뮬레이터의 시작 화면입니다.</p>
      <p className="status">BE 연결 상태: {apiStatus}</p>
    </main>
  )
}

export default App
