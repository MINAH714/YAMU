import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

const root = createRoot(document.getElementById('root')!)

// 화면 확인 페이지. 조건이 빌드 때 false로 정해져 배포 번들에는 들어가지 않는다
if (import.meta.env.DEV && new URLSearchParams(location.search).has('gallery')) {
  import('./dev/Gallery').then(({ Gallery }) => {
    root.render(
      <StrictMode>
        <Gallery />
      </StrictMode>,
    )
  })
} else {
  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
