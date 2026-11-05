// 매장·메뉴 데이터. 상품 사진은 생성 이미지(src/assets/menu, 400px webp)이고 실제 제품 사진이 아니다.
//
// 가격은 2026-11-03에 찾은 가격 정리 글·기사를 보고 정했다. 공식 가격표가 아니어서 실제 매장 가격과 다를 수 있다.
// - 스타벅스: 2025년 1월 인상 뒤 톨 사이즈 가격을 정리한 글 기준
// - GS25: 2025년 4월 편의점 가격 인상 기사 기준
// - 끝에 "어림"이라고 적은 것은 출처를 찾지 못해 비슷한 메뉴 가격으로 정한 값이다

export type StoreId = 'starbucks' | 'gs25'

export type MenuItem = {
  /** 이미지 파일 이름과 같다 */
  id: string
  name: string
  /** 사이즈·용량. 없으면 null */
  option: string | null
  price: number
  category: string
}

export type Store = {
  id: StoreId
  brand: string
  branch: string
  categories: string[]
  items: MenuItem[]
}

export const STORE_IDS: StoreId[] = ['starbucks', 'gs25']

export const STORES: Record<StoreId, Store> = {
  starbucks: {
    id: 'starbucks',
    brand: '스타벅스',
    branch: '역삼점',
    categories: ['커피', '음료', '푸드'],
    items: [
      { id: 'sb-americano', name: '아이스 카페 아메리카노', option: 'Tall', price: 4_700, category: '커피' },
      { id: 'sb-latte', name: '카페 라떼', option: 'Tall', price: 5_200, category: '커피' },
      { id: 'sb-caramel-macchiato', name: '카라멜 마키아또', option: 'Tall', price: 6_100, category: '커피' },
      { id: 'sb-cold-brew', name: '콜드 브루', option: 'Tall', price: 5_100, category: '커피' },
      { id: 'sb-vanilla-cream-cold-brew', name: '바닐라 크림 콜드 브루', option: 'Tall', price: 6_000, category: '커피' },
      { id: 'sb-mocha', name: '카페 모카', option: 'Tall', price: 5_700, category: '커피' },
      { id: 'sb-matcha-latte', name: '아이스 제주 말차 라떼', option: 'Tall', price: 6_300, category: '음료' }, // 어림
      { id: 'sb-refresher', name: '딸기 아사이 레모네이드 리프레셔', option: 'Tall', price: 6_100, category: '음료' }, // 어림
      { id: 'sb-grapefruit-tea', name: '자몽 허니 블랙 티', option: 'Tall', price: 5_900, category: '음료' },
      { id: 'sb-croissant', name: '버터 크루아상', option: null, price: 4_300, category: '푸드' }, // 어림
      { id: 'sb-cheesecake', name: '뉴욕 치즈 케이크', option: null, price: 6_900, category: '푸드' }, // 어림
      { id: 'sb-sandwich', name: '햄 & 치즈 샌드위치', option: null, price: 5_900, category: '푸드' }, // 어림
    ],
  },
  gs25: {
    id: 'gs25',
    brand: 'GS25',
    branch: '역삼중앙점',
    categories: ['간편식', '음료', '과자'],
    items: [
      { id: 'gs-triangle-kimbap', name: '참치마요 삼각김밥', option: null, price: 1_100, category: '간편식' },
      { id: 'gs-egg-sandwich', name: '에그 샐러드 샌드위치', option: null, price: 2_900, category: '간편식' }, // 어림
      { id: 'gs-jeyuk-dosirak', name: '제육볶음 도시락', option: null, price: 5_500, category: '간편식' }, // 어림
      { id: 'gs-cup-ramen', name: '농심 신라면 작은컵', option: '65g', price: 1_250, category: '간편식' },
      { id: 'gs-kimbap', name: '참치마요 김밥', option: null, price: 3_300, category: '간편식' }, // 어림
      { id: 'gs-rose-tteokbokki', name: '로제 떡볶이', option: null, price: 3_900, category: '간편식' }, // 어림
      { id: 'gs-water', name: '롯데 아이시스 8.0', option: '500ml', price: 1_100, category: '음료' },
      { id: 'gs-banana-milk', name: '빙그레 바나나맛우유', option: '240ml', price: 1_700, category: '음료' }, // 어림
      { id: 'gs-cola', name: '코카콜라', option: '350ml', price: 2_100, category: '음료' },
      { id: 'gs-chips', name: '오리온 포카칩 오리지널', option: '66g', price: 1_700, category: '과자' },
      { id: 'gs-cookie', name: '오리온 촉촉한 초코칩', option: null, price: 1_700, category: '과자' }, // 어림
      { id: 'gs-jelly', name: '하리보 골드베렌', option: '100g', price: 2_000, category: '과자' }, // 어림
    ],
  },
}

const images = import.meta.glob<string>('../assets/menu/*.webp', { eager: true, import: 'default' })

export function menuImage(item: MenuItem): string | undefined {
  return images[`../assets/menu/${item.id}.webp`]
}
