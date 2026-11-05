import logo from '../assets/yamu-logo.svg'

/** YAMU 워드마크. 색은 원본 그대로 쓴다 */
export function Logo({ height = 20 }: { height?: number }) {
  return <img className="logo" src={logo} alt="YAMU" height={height} draggable={false} />
}
