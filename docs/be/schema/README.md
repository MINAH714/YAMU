# YAMU DB 스키마 안내

스키마 문서는 이 폴더에서 관리한다. 처음 리뷰할 때는 이 문서로 테이블의 역할을 파악하고, 필요한 컬럼만 사전에서 확인하면 된다.

## 어떤 문서를 읽나요?

| 문서 | 독자와 목적 |
|---|---|
| [컬럼 사전](dictionary.md) | 사람·AI 공통. 23개 테이블의 컬럼·타입·NULL·기본값·키·제약과 컬럼별 설명 |
| [구현 지침](implementation.md) | 구현하는 개발자·AI. 서비스 검증, 동시성·재시도, 미확인 사항과 검증 방법 |
| [이전 검토 기록](archive/initial-review.md) | 과거 논의가 필요한 경우에만 조회. 현행 구현 기준으로 사용하지 않음 |

DB에서 실행할 구조는 [Flyway SQL](../../../be/src/main/resources/db/migration/V20261026_1__create_initial_schema.sql)이 원본이다. 기능 정책은 Notion, 요청·응답 형식은 API 명세서에서 관리하며 이 폴더에서는 해당 위치를 연결한다. 미결정 정책을 문서 분리 과정에서 새로 확정하지 않는다.

## 테이블을 읽는 순서

| 영역 | 테이블 | 무엇을 관리하나요? |
|---|---|---|
| 사용자·계좌 | `app_user`, `linked_account` | 사용자와 연결 계좌. 계좌 잔액 원장은 금융망에서 관리 |
| 카드·정책 | `card_product`, `user_card`, `merchant`, `card_benefit`, `benefit_target`, `limit_group`, `benefit_limit_link`, `tier_limit` | 카드 상품·보유 카드, 브랜드, 혜택 적용 대상과 한도 규칙 |
| 결제·집계 | `payment_transaction`, `payment_benefit`, `payment_limit_impact`, `card_monthly_performance`, `monthly_limit_usage` | 결제 상태, 실제 혜택, 한도 사용 내역과 월간 합계 |
| 청구·환급 | `card_bill`, `bill_item`, `refund` | 신용카드 청구서, 일시불·할부 회차, 취소 후 환급 |
| 예정 내역·알림 | `cashflow_item`, `notification` | 예정 수입·지출과 앱 내 알림 |
| 기기·인증 기록 | `user_device`, `user_device_event`, `step_up_audit_log` | 등록 기기, 기기 변경과 인증 결과 |

## 헷갈리기 쉬운 관계

`payment_transaction`은 결제 한 건의 같은 행을 요청·처리 중·승인·거절·취소에 따라 갱신한다. 요청용 행과 승인용 행을 따로 만들지 않는다. 상태별 이력을 매번 추가하는 테이블도 아니다.

| 기록 | 예시 |
|---|---|
| `payment_benefit` — 실제 받은 혜택 | 결제 A에서 300원 할인 |
| `payment_limit_impact` — 결제별 한도 사용 | 결제 A가 금액 한도 300원과 횟수 한도 1회 차감. 서로 다른 한도 그룹이므로 두 행 |
| `monthly_limit_usage` — 월간 한도 사용 합계 | 같은 카드·한도 그룹의 10월 사용량 총 800원 |
| `card_monthly_performance` — 월간 실적 합계 | 같은 카드의 10월 실적 총 35만원 |

취소하면 해당 결제의 한도 사용량만 한 번 복구하고 월간 합계도 조정한다. 혜택 결과와 한도 사용량을 더해 같은 금액으로 계산하지 않는다.

체크카드는 결제 처리 과정에서 계좌에서 출금한다. 성공한 출금의 금융망 거래 번호는 `payment_transaction.fin_transaction_no`에 저장한다. 신용카드는 일시불·할부 모두 승인 시 계좌에서 출금하지 않으며, 결제일에 청구서 기준으로 출금한 번호를 `card_bill`에 저장한다. 가맹점 정산은 구현 범위에 없다.

`card_bill`은 청구서 한 장, `bill_item`은 그 안의 일시불 또는 할부 회차다. 청구서의 이용기간은 어느 날짜의 결제를 청구하는지 표시한다. `installment_months`는 일시불 1, 할부는 총 개월 수이며 현재 회차 번호가 아니다.

카드 등록은 seed 실적·한도 초기화까지 성공해야 완료된다. 신규 카드·확인된 무거래는 0으로 시작하며, 필수 seed 누락은 등록 실패로 처리한다. 별도 초기화 상태 컬럼은 두지 않는다. `user_card.status`의 `PENDING`은 사용자 등록 전 카드라는 별도 의미다.

## 리뷰할 때 볼 것

1. 필요한 데이터가 알맞은 테이블에 있는가? 특히 결제·혜택·한도·청구의 역할을 확인한다.
2. 해당 컬럼이 언제 채워지고, NULL과 0이 각각 무엇을 뜻하는가?
3. 중복·다른 사용자 데이터 연결은 DB가 막는가, 서비스가 검사하는가? 구현 책임은 [구현 지침](implementation.md)에 구분했다.

전체 타입과 제약을 외우기보다 담당 기능의 테이블을 [컬럼 사전](dictionary.md)에서 찾아 확인한다.

## 정책 원본 위치

| 찾을 내용 | Notion 위치 |
|---|---|
| 전체 관계와 설계 이유 | PJT 산출물 → [ERD](https://app.notion.com/p/3f731720f35880c999f9d7966e628681), 하위 「ERD 설계 핵심 판단 근거」 |
| 혜택 결과·한도 사용·계산 근거 | 요구사항 정의서 → V2 → 요구사항 정의서 V2 → [DEC-005](https://app.notion.com/p/d7531720f358838e99690172f32e130d), 본문 「혜택 결과·한도 사용·당시 계산 근거」 |
| 요일·시간 조건, 시설 코드 | 같은 요구사항 표 → [FR-014](https://app.notion.com/p/e0431720f35882188d5101733ebd9269)의 상세 요구사항과 본문 |
| 카드 등록 초기화 | 같은 요구사항 표 → [POL-008](https://app.notion.com/p/aae31720f35882e09084814f6bdd216e), [FR-070](https://app.notion.com/p/7d831720f35883b4ac48818138c04ed4) |
| 업종 카테고리 미정 사항 | 같은 요구사항 표 → [POL-004](https://app.notion.com/p/f5731720f3588254860601b04ec14dd7) |
| 요청·응답 계약 | [API 명세서 → V2 → API 목록](https://app.notion.com/p/d1931720f35883f59b330134198b35e9). 「카드 등록」「POS 결제 요청 생성」「결제 승인 요청」 등 해당 API 페이지 본문 |

## 확인된 범위

2026-10-27 기준 23개 테이블·286개 컬럼·FK 34개의 MD/SQL 정의를 대조했고, 계좌번호 끝 네 자리의 문서 이름을 SQL과 같은 `account_last4`로 통일했다. 최근 격리 MySQL 스키마 테스트는 16개 통과, 실패·오류·생략 0개다. 서비스 전체 구현이나 운영 DB 적용을 완료했다는 의미는 아니다. 실행 명령과 검증 범위는 [구현 지침](implementation.md)을 참고한다.
