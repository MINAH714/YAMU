import { AnimatePresence, MotionConfig } from 'motion/react'
import { useState, type CSSProperties } from 'react'
import { AppHeader } from '../components/AppHeader'
import { config } from '../config'
import { STORES } from '../store/catalog'
import { FORCE_REDUCE_MOTION } from '../hooks/useReduceMotion'
import { FAST } from '../styles/motion'
import { flowEntries } from './entriesFlow'
import { historyEntries } from './entriesHistory'
import { orderEntries } from './entriesOrder'
import { trayEntries } from './entriesTray'
import './gallery.css'

// 화면 확인 페이지(개발 서버 전용).
// ?gallery=<id>            목록과 함께 기기 크기 틀 안에 보여 준다
// ?gallery=<id>&size=1333x690   틀 크기를 정한다
// ?gallery=<id>&bare       목록 없이 창을 꽉 채운다(스크린샷용)
// 항목은 entriesOrder·entriesTray·entriesFlow 배열에 추가한다.

const ENTRIES = [...orderEntries, ...trayEntries, ...flowEntries, ...historyEntries]
const GROUPS = [...new Set(ENTRIES.map((entry) => entry.group))]
const SIZES = ['1333x800', '1333x690', '1024x768']

function readParams() {
  const params = new URLSearchParams(location.search)
  return {
    id: params.get('gallery') || ENTRIES[0].id,
    size: params.get('size') ?? SIZES[0],
    bare: params.has('bare'),
  }
}

export function Gallery() {
  const [{ id, size, bare }, setParams] = useState(readParams)
  const [replay, setReplay] = useState(0)
  const entry = ENTRIES.find((candidate) => candidate.id === id) ?? ENTRIES[0]

  const update = (next: { id?: string; size?: string }) => {
    const merged = { id, size, bare, ...next }
    const params = new URLSearchParams({ gallery: merged.id, size: merged.size })
    history.replaceState(null, '', `?${params}`)
    setParams(merged)
  }

  const [width, height] = size.split('x').map(Number)
  const frameStyle = (bare ? undefined : { width, height, '--app-height': `${height}px` }) as CSSProperties | undefined

  const frame = (
    <div className="app" style={frameStyle} key={`${entry.id}-${replay}`}>
      <AppHeader store={entry.store ? STORES[entry.store] : null} step={entry.step} mock={config.mockResult} />
      <main className="app-main" data-surface={entry.surface}>
        {entry.render()}
      </main>
      <AnimatePresence>{entry.tray?.()}</AnimatePresence>
    </div>
  )

  return (
    <MotionConfig reducedMotion={FORCE_REDUCE_MOTION ? 'always' : 'user'} transition={FAST}>
      {bare ? (
        frame
      ) : (
        <div className="gallery">
          <nav className="gallery-nav" aria-label="화면 목록">
            <div className="gallery-sizes">
              {SIZES.map((candidate) => (
                <button
                  key={candidate}
                  type="button"
                  aria-pressed={candidate === size}
                  onClick={() => update({ size: candidate })}
                >
                  {candidate}
                </button>
              ))}
            </div>
            <button type="button" className="gallery-replay" onClick={() => setReplay((n) => n + 1)}>
              다시 그리기
            </button>
            {GROUPS.map((group) => (
              <section key={group}>
                <h2>{group}</h2>
                {ENTRIES.filter((candidate) => candidate.group === group).map((candidate) => (
                  <button
                    key={candidate.id}
                    type="button"
                    aria-current={candidate.id === entry.id ? 'true' : undefined}
                    onClick={() => update({ id: candidate.id })}
                  >
                    {candidate.label}
                  </button>
                ))}
              </section>
            ))}
          </nav>
          <div className="gallery-stage">{frame}</div>
        </div>
      )}
    </MotionConfig>
  )
}
