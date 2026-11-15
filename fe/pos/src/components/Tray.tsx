import {
  AnimatePresence,
  motion,
  useAnimate,
  useIsPresent,
  usePresence,
  usePresenceData,
  type AnimationPlaybackControls,
  type Transition,
} from 'motion/react'
import {
  useContext,
  useEffect,
  useEffectEvent,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react'
import { useReduceMotion } from '../hooks/useReduceMotion'
import { COLOR_SHIFT_S, EASE, FAST, REDUCED, SWAP, TRAY_CLOSE, TRAY_SPRING } from '../styles/motion'
import { StepLiveContext } from './trayLive'

/**
 * 트레이가 사라지는 길. 바깥 AnimatePresence의 custom으로 넘긴다.
 * origin은 버튼 자리로 줄어들고(닫기), fade는 제자리에서 사라진다(결제 요청이 나갈 때)
 */
export type TrayExit = 'origin' | 'fade'

/** 트레이가 처음 나타나는 길. fade는 결제 요청이 실패해 트레이로 돌아올 때 */
export type TrayAppear = 'origin' | 'fade'

type Props = {
  title: string
  /** 단계 이름. 바뀌면 같은 트레이의 높이·폭만 바뀌고 내용은 교차 페이드한다 */
  stepKey: string
  /** 첫 단계. 닫기 아이콘을 보이고 바깥을 눌러도 닫힌다 */
  onClose?: () => void
  /** 다음 단계. 뒤로 아이콘을 보인다. onClose보다 우선한다 */
  onBack?: () => void
  children: ReactNode
  /** 최대 폭(px). 기본 560 */
  width?: number
  /** 트레이가 커져 나오는 버튼. 닫히면 이 자리로 줄어든다. 없으면 아래에서 올라오고 내려간다 */
  origin?: RefObject<HTMLElement | null>
  appear?: TrayAppear
}

type Box = { left: number; top: number; width: number; height: number }

// 모서리가 찌그러지지 않게 px로 움직인다. --r-tray와 같은 값
const TRAY_RADIUS = 24
const CLOSE_GUARD_MS = 350
/** 버튼에서 커지는 동안 연타한 두 번째 탭이 트레이 안에 떨어지지 않게 막는다 */
const OPEN_GUARD_MS = 350
const REDUCED_MS = 150
const SWAP_MS = 200
/** 내용은 트레이가 조금 커진 뒤 나온다(60ms부터 200ms) */
const CONTENT_DELAY_S = 0.06
const CONTENT_FADE_S = 0.2
/** 줄어들 때 내용은 먼저 걷히고 바탕은 40ms 뒤부터 파랑으로 돌아간다 */
const CONTENT_OUT_S = 0.08
const COLOR_BACK_DELAY_S = 0.04

/**
 * 주문 화면 위에 뜨는 트레이(모션 명세 M-TRAY-pos-open · close · step · exit).
 * 결제하기 버튼(origin) 사각형이 그대로 커져 트레이가 되고, 닫으면 같은 자리로 줄어든다.
 * 바깥 AnimatePresence에 key를 고정해 두면 단계가 바뀌어도 같은 트레이의 크기만 바뀐다.
 * 크기와 자리는 left·top·width·height로 직접 움직여 내용이 늘어나거나 찌그러지지 않는다.
 */
export function Tray({ title, stepKey, onClose, onBack, children, width = 560, origin, appear = 'origin' }: Props) {
  const [isPresent, safeToRemove] = usePresence()
  const exit = usePresenceData() as TrayExit | undefined
  const reduce = useReduceMotion()
  const titleId = useId()
  const [scope, animate] = useAnimate<HTMLDivElement>()
  const sheetRef = useRef<HTMLElement>(null)
  const scrimRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLElement | null>(null)
  const place = useRef<Box | null>(null)
  const geometry = useRef<AnimationPlaybackControls | null>(null)
  // 사라지는 중인지와 그 회차. 사라지다 다시 불리면 회차가 바뀌어 옛 회차는 트레이를 지우지 않는다
  const leaving = useRef(false)
  const leaveRun = useRef(0)
  const closableAt = useRef(0)
  const [guarded, setGuarded] = useState(true)
  const dismiss = onBack ?? onClose

  /** 상자를 to로 옮긴다. transition이 없으면 바로 놓는다. 언제나 지금 그려진 값에서 출발한다 */
  const move = (to: Box, transition: Transition | null) => {
    const sheet = sheetRef.current
    if (!sheet) return null
    geometry.current?.stop()
    geometry.current = null
    if (!transition) {
      placeBox(sheet, to)
      return null
    }
    const from = drawnBox(sheet)
    geometry.current = animate(
      sheet,
      {
        left: [from.left, to.left],
        top: [from.top, to.top],
        width: [from.width, to.width],
        height: [from.height, to.height],
      },
      transition,
    )
    return geometry.current
  }

  /** 지금 단계 내용의 크기에 맞춘 자리로. 단계가 바뀌거나 내용 높이가 바뀌면 spring.tray로 따라간다 */
  const fit = () => {
    const root = scope.current
    const panel = panelRef.current
    if (!root || !panel || leaving.current || !place.current) return
    const next = placeOf(root, panel.offsetWidth, panel.offsetHeight)
    if (sameBox(place.current, next)) return
    place.current = next
    move(next, reduce ? null : TRAY_SPRING)
  }
  const refit = useEffectEvent(fit)

  /** 지금 단계가 바뀌었거나 그 크기가 바뀌었다(TrayPanel이 effect 안에서 부른다) */
  const onPanel = (el: HTMLElement) => {
    panelRef.current = el
    fit()
  }

  /** 처음 나타날 때. 그리기 전에 출발 자리를 놓아 깜박이지 않는다 */
  const enter = useEffectEvent(() => {
    const root = scope.current
    const sheet = sheetRef.current
    const scrim = scrimRef.current
    const panel = panelRef.current
    if (!root || !sheet || !scrim || !panel) return undefined
    const to = placeOf(root, panel.offsetWidth, panel.offsetHeight)
    place.current = to
    const { primary, surface } = colorsOf(root)
    const button = origin?.current ?? null
    let guardMs = 0
    // 애니메이션은 다음 프레임에 시작하므로 첫 프레임 값은 직접 놓는다
    scrim.style.opacity = '0'

    if (reduce) {
      // 모션 줄이기: 자리에 놓고 투명도만
      placeBox(sheet, to)
      paint(sheet, TRAY_RADIUS, surface)
      sheet.style.opacity = '0'
      animate(sheet, { opacity: [0, 1] }, REDUCED)
      animate(scrim, { opacity: [0, 1] }, REDUCED)
      guardMs = REDUCED_MS
    } else if (button && appear === 'origin') {
      const from = boxOf(button, root)
      const radius = radiusOf(button)
      placeBox(sheet, from)
      paint(sheet, radius, primary)
      panel.style.opacity = '0'
      move(to, TRAY_SPRING)
      animate(sheet, { borderRadius: [radius, TRAY_RADIUS] }, TRAY_SPRING)
      animate(sheet, { backgroundColor: [primary, surface] }, { duration: COLOR_SHIFT_S, ease: EASE })
      animate(panel, { opacity: [0, 1] }, { delay: CONTENT_DELAY_S, duration: CONTENT_FADE_S, ease: EASE })
      animate(scrim, { opacity: [0, 1] }, TRAY_SPRING)
      guardMs = OPEN_GUARD_MS
    } else if (origin) {
      // 결제 요청이 실패해 돌아왔다. 커지지 않고 제자리에서 나타난다
      placeBox(sheet, to)
      paint(sheet, TRAY_RADIUS, surface)
      sheet.style.opacity = '0'
      animate(sheet, { opacity: [0, 1] }, SWAP)
      animate(scrim, { opacity: [0, 1] }, SWAP)
      guardMs = SWAP_MS
    } else {
      // 버튼이 없는 트레이(결제 취소 스캔)는 아래에서 올라온다
      placeBox(sheet, { ...to, top: root.clientHeight })
      paint(sheet, TRAY_RADIUS, surface)
      move(to, TRAY_SPRING)
      animate(scrim, { opacity: [0, 1] }, FAST)
    }

    closableAt.current = performance.now() + Math.max(CLOSE_GUARD_MS, guardMs)
    const timer = setTimeout(() => {
      setGuarded(false)
      sheet.focus({ preventScroll: true })
    }, guardMs)
    return () => clearTimeout(timer)
  })

  /** 사라질 때와, 사라지는 중에 다시 불렸을 때(결제 요청이 곧바로 실패했다) */
  const onPresence = useEffectEvent((present: boolean) => {
    const root = scope.current
    const sheet = sheetRef.current
    const scrim = scrimRef.current
    if (!root || !sheet || !scrim) return
    const { primary, surface } = colorsOf(root)

    if (present) {
      if (!leaving.current) return
      // 지금 그려진 상태에서 200ms에 다시 나타난다
      leaving.current = false
      leaveRun.current += 1
      const panel = panelRef.current
      const to = panel ? placeOf(root, panel.offsetWidth, panel.offsetHeight) : place.current
      const back = reduce ? REDUCED : SWAP
      if (to) {
        place.current = to
        move(to, reduce ? null : back)
      }
      animate(sheet, { opacity: [num(sheet.style.opacity, 1), 1] }, back)
      animate(sheet, { borderRadius: [num(sheet.style.borderRadius, TRAY_RADIUS), TRAY_RADIUS] }, back)
      animate(sheet, { backgroundColor: [sheet.style.backgroundColor || surface, surface] }, back)
      animate(scrim, { opacity: [num(scrim.style.opacity, 1), 1] }, back)
      animate(sheet.querySelectorAll('.tray-panel:not([inert])'), { opacity: 1 }, back)
      return
    }

    leaving.current = true
    const run = ++leaveRun.current
    const button = origin?.current ?? null
    const runs: Promise<unknown>[] = []
    const fadeOut = (transition: Transition) => {
      runs.push(animate(sheet, { opacity: [num(sheet.style.opacity, 1), 0] }, transition).finished)
      runs.push(animate(scrim, { opacity: [num(scrim.style.opacity, 1), 0] }, transition).finished)
    }

    if (reduce) {
      fadeOut(REDUCED)
    } else if (exit === 'fade' || (origin && !button)) {
      // 결제 요청이 나갔다. 버튼으로 돌아가지 않고 제자리에서 사라진다(주문 화면이 먼저 빠져 버튼이 없어도 같다)
      fadeOut(SWAP)
    } else if (button) {
      // 버튼 자리로 줄어든다. 진짜 버튼은 트레이가 지워지는 순간 부르는 쪽이 다시 보인다
      const to = boxOf(button, root)
      const radius = radiusOf(button)
      const shrink = move(to, TRAY_CLOSE)
      if (shrink) runs.push(shrink.finished)
      runs.push(animate(sheet.querySelectorAll('.tray-panel'), { opacity: 0 }, { duration: CONTENT_OUT_S, ease: EASE }).finished)
      runs.push(animate(sheet, { borderRadius: [num(sheet.style.borderRadius, TRAY_RADIUS), radius] }, TRAY_CLOSE).finished)
      runs.push(
        animate(
          sheet,
          { backgroundColor: [sheet.style.backgroundColor || surface, primary] },
          { duration: COLOR_SHIFT_S, delay: COLOR_BACK_DELAY_S, ease: EASE },
        ).finished,
      )
      runs.push(animate(scrim, { opacity: [num(scrim.style.opacity, 1), 0] }, TRAY_CLOSE).finished)
    } else {
      const slide = move({ ...drawnBox(sheet), top: root.clientHeight }, TRAY_SPRING)
      if (slide) runs.push(slide.finished)
      runs.push(animate(scrim, { opacity: [num(scrim.style.opacity, 1), 0] }, FAST).finished)
    }

    Promise.all(runs).then(() => {
      if (leaving.current && leaveRun.current === run) safeToRemove?.()
    })
  })

  useLayoutEffect(() => enter(), [])

  useLayoutEffect(() => onPresence(isPresent), [isPresent])

  // 키보드가 뜨거나 화면 크기가 바뀌면 자리를 다시 잡는다
  useEffect(() => {
    const root = scope.current
    if (!root) return
    const observer = new ResizeObserver(() => refit())
    observer.observe(root)
    return () => observer.disconnect()
  }, [scope])

  // 버튼을 연타하면 두 번째 탭이 막 바뀐 단계의 바깥이나 닫기에 떨어진다. 단계가 바뀐 직후의 닫기는 버린다
  useEffect(() => {
    closableAt.current = Math.max(closableAt.current, performance.now() + CLOSE_GUARD_MS)
  }, [stepKey])

  const close = () => {
    if (performance.now() >= closableAt.current) onClose?.()
  }

  // 닫히는 중인 트레이의 옛 콜백이 불리면 결제를 보낸 뒤에 주문 잠금이 풀린다
  useEffect(() => {
    if (!dismiss || !isPresent) return
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') dismiss()
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [dismiss, isPresent])

  return (
    // 사라지는 동안 남아 있는 버튼이 눌리지 않게 막는다
    <div ref={scope} className="tray-root" inert={!isPresent}>
      <div ref={scrimRef} className="tray-scrim" onClick={onBack ? undefined : close} />
      <section
        ref={sheetRef}
        className="tray"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        inert={guarded}
      >
        <StepLiveContext.Provider value={isPresent}>
          <AnimatePresence initial={false}>
            <TrayPanel key={stepKey} width={width} onPresent={onPanel}>
              <header className="tray-header">
                <h2 className="tray-title" id={titleId}>
                  {title}
                </h2>
                {onBack ? (
                  <button type="button" className="icon-button" aria-label="뒤로" onClick={onBack}>
                    <BackIcon />
                  </button>
                ) : (
                  onClose && (
                    <button type="button" className="icon-button" aria-label="닫기" onClick={close}>
                      <CloseIcon />
                    </button>
                  )
                )}
              </header>
              <div className="tray-body">{children}</div>
            </TrayPanel>
          </AnimatePresence>
        </StepLiveContext.Provider>
      </section>
    </div>
  )
}

/**
 * 트레이 한 단계. 단계가 바뀌면 새 단계가 150ms에 나타나고 옛 단계는 150ms에 걷힌다(교차 페이드).
 * 둘 다 트레이 왼쪽 위에 겹쳐 놓고 크기는 지금 단계만 잰다. 걷히는 단계는 눌리지 않는다.
 */
function TrayPanel({
  width,
  onPresent,
  children,
}: {
  width: number
  onPresent: (el: HTMLElement) => void
  children: ReactNode
}) {
  const isPresent = useIsPresent()
  const trayLive = useContext(StepLiveContext)
  const ref = useRef<HTMLDivElement>(null)
  const report = useEffectEvent((el: HTMLElement) => onPresent(el))

  // 지금 단계일 때만 크기를 알린다
  useLayoutEffect(() => {
    const el = ref.current
    if (!isPresent || !el) return
    report(el)
    const observer = new ResizeObserver(() => report(el))
    observer.observe(el)
    return () => observer.disconnect()
  }, [isPresent])

  return (
    <motion.div
      ref={ref}
      className="tray-panel"
      style={{ width: `min(${width}px, 100cqw)` }}
      inert={!isPresent}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={FAST}
    >
      <StepLiveContext.Provider value={isPresent && trayLive}>{children}</StepLiveContext.Provider>
    </motion.div>
  )
}

/** tray-root 기준 사각형 */
function boxOf(el: Element, root: Element): Box {
  const rect = el.getBoundingClientRect()
  const base = root.getBoundingClientRect()
  return { left: rect.left - base.left, top: rect.top - base.top, width: rect.width, height: rect.height }
}

/** 트레이 자리: tray-root 여백 안쪽의 아래 가운데. 스캔 입력칸에 키보드가 뜨면 아래 여백이 늘어난다 */
function placeOf(root: HTMLElement, width: number, height: number): Box {
  const style = getComputedStyle(root)
  const padLeft = parseFloat(style.paddingLeft)
  const padRight = parseFloat(style.paddingRight)
  const padBottom = parseFloat(style.paddingBottom)
  const inner = root.clientWidth - padLeft - padRight
  return { left: padLeft + (inner - width) / 2, top: root.clientHeight - padBottom - height, width, height }
}

function drawnBox(sheet: HTMLElement): Box {
  return {
    left: num(sheet.style.left, 0),
    top: num(sheet.style.top, 0),
    width: num(sheet.style.width, 0),
    height: num(sheet.style.height, 0),
  }
}

function placeBox(sheet: HTMLElement, box: Box) {
  sheet.style.left = `${box.left}px`
  sheet.style.top = `${box.top}px`
  sheet.style.width = `${box.width}px`
  sheet.style.height = `${box.height}px`
}

function paint(sheet: HTMLElement, radius: number, background: string) {
  sheet.style.borderRadius = `${radius}px`
  sheet.style.backgroundColor = background
}

function sameBox(a: Box, b: Box) {
  return (
    Math.abs(a.left - b.left) < 0.5 &&
    Math.abs(a.top - b.top) < 0.5 &&
    Math.abs(a.width - b.width) < 0.5 &&
    Math.abs(a.height - b.height) < 0.5
  )
}

function radiusOf(el: Element) {
  return parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0
}

function colorsOf(root: Element) {
  const style = getComputedStyle(root)
  return {
    primary: style.getPropertyValue('--primary').trim(),
    surface: style.getPropertyValue('--surface').trim(),
  }
}

function num(value: string, fallback: number) {
  const parsed = parseFloat(value)
  return Number.isNaN(parsed) ? fallback : parsed
}

function BackIcon() {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M15 5l-7 7 7 7" />
    </svg>
  )
}

function CloseIcon() {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  )
}
