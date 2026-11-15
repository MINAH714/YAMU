import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { FORCE_REDUCE_MOTION } from './hooks/useReduceMotion'

const root = createRoot(document.getElementById('root')!)

// CSS 애니메이션·전환도 모션 줄이기 규칙을 따르게 한다(index.css)
if (FORCE_REDUCE_MOTION) document.documentElement.dataset.reduceMotion = ''

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
