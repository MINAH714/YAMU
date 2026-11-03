# 모의 POS 웹

매장 태블릿에서 쓰는 POS 화면입니다(가로 1180×820 기준, 최소 1024×768). 매장을 고르고 메뉴를 담은 뒤, 고객 앱의 YAMU 결제 QR을 읽어 결제를 요청하고 고객 승인 결과를 영수증으로 보여 줍니다.
설치와 BE 연결 확인은 [FE 개발 및 테스트 안내](../README.md)를 따르고, 여기에는 결제 화면을 쓰는 데 필요한 것만 적습니다.

## 화면 흐름

매장 선택 → 주문(메뉴판·주문 내역) → 결제수단 → 결제 QR 스캔 → 고객 승인 대기 → 영수증

- **결제수단**: YAMU페이(일시불·할부)만 서버로 결제를 요청합니다. 현금은 받은 금액·거스름돈을 화면에서만 계산하고 서버에 남기지 않습니다. 카드·기타 간편결제는 고를 수 없습니다.
- **주문 잠금**: 결제하기를 누른 순간의 주문으로 금액이 정해집니다. 결제 요청을 보낸 뒤에는 주문 화면으로 돌아갈 수 없고, 결제가 끝나거나 "새 주문"을 고르면 장바구니가 비워집니다.
- **품목**: 결제 요청 API는 금액만 받으므로 영수증의 품목 목록은 POS 화면에만 있습니다.
- **메뉴 데이터**: `src/store/catalog.ts`. 가격은 공개된 가격 정리 글·기사를 보고 정한 값이라 실제 매장 가격과 다를 수 있습니다(출처와 어림값 표시는 파일 주석). 상품 사진은 생성 이미지를 400px webp로 줄인 것(`src/assets/menu`)입니다.

## 설정

`.env.example`을 보고 `.env.local`에 값을 넣습니다. `.env.local`은 커밋하지 않습니다.

| 이름 | 값 |
|---|---|
| `VITE_POS_KEY` | BE `application-local.yml`의 `yamu.pos.key`와 같은 값. 브라우저 번들에 그대로 들어가므로 모의 POS 전용 키만 씁니다 |
| `VITE_STARBUCKS_POS_CODE`, `VITE_GS25_POS_CODE` | 매장별 posCode. BE `application.yml`의 `yamu.pos.terminals[].pos-code` 중 하나(`POS-STARBUCKS-GENERAL`, `POS-GS25-GENERAL`) |
| `VITE_STARBUCKS_MERCHANT_ID`, `VITE_GS25_MERCHANT_ID` | 위 posCode의 brand-code에 해당하는 `merchant.merchant_id` |
| `VITE_POS_MOCK_RESULT` | `true`면 결과 조회를 화면 안의 Mock으로 대신합니다(BE 없이 화면만 볼 때). 결제 요청 생성은 항상 실제 BE를 부릅니다 |

매장 posCode·merchantId가 빈 매장은 매장 선택 화면에서 고를 수 없습니다.

## 배포 전제: BE와 같은 출처로 열기

POS는 BE와 **같은 출처**(scheme·host·port)에서 열려야 합니다. 개발 중에는 Vite 개발 서버의 `/api` 프록시(`vite.config.ts`)가 이 역할을 하고, 배포할 때는 리버스 프록시가 `/api`를 BE로 넘겨야 합니다.

- BE에 CORS 설정이 없어서, 다른 출처에서 열면 API 호출 자체가 막힙니다.
- 승인 마감 카운트다운은 응답의 `Date` 헤더로 POS 시계와 서버 시계의 차이를 보정합니다(`App.tsx`의 `localDeadline`). 다른 출처에서 CORS만 열면 `Date` 헤더를 읽을 수 없으므로(`Access-Control-Expose-Headers`에 넣어야 함), 보정 없이 POS 시계를 그대로 씁니다. 남은 시간은 0~60초로 자르므로 시계가 틀려도 60초보다 길게 기다리지는 않습니다.

## 결과 조회 동작과 한계

- 결제 요청이 접수되면 `GET /api/pos/payment-requests/{paymentId}?posCode=…`를 응답이 온 뒤 2초마다 다시 부릅니다(조회가 겹치지 않습니다). 화면을 떠나면 멈춥니다.
- `APPROVED`·`EXPIRED`·`CANCELED`는 바로 영수증을 띄웁니다. `DECLINED`는 고객이 다른 카드로 다시 승인할 수 있어서 마감 전에는 기다리고, 서버가 `retryable: false`를 주면 바로 거절 영수증을 띄웁니다. 거절 영수증에는 서버의 `failure.message`(POS 영수증용 사유별 문구)를 보여 줍니다.
- 사유가 출금 미확인(`WITHDRAWAL_NOT_CONFIRMED`)이면 `DECLINED`·`EXPIRED`라도 실패·만료로 띄우지 않고 "결제 결과를 확인하지 못했습니다"로 띄웁니다. BE 복구 작업이 늦게 출금을 확인하면 `APPROVED`로 바뀔 수 있어서, 바로 다시 결제하면 두 번 결제될 수 있기 때문입니다. 직원은 고객 앱의 결제 내역을 확인한 뒤 다시 결제합니다.
- `retryable`은 노션 API 명세 「POS 결제 결과 조회」에 반영됐습니다(11/3, 명세 Q1). 값이 없으면 마감까지 기다리므로 안전하게 동작합니다.
- **임시**: `posCode` 쿼리는 BE(S15P31E103-229)와 맞춘 값이고 명세에는 아직 없습니다. 명세가 바뀌면 `api/pos.ts`의 `getPaymentResult`도 함께 바꿉니다.
- **한계**: 승인 처리 중(`PROCESSING`)이면 금융망 결과를 기다리므로 마감이 지나도 **상한 없이** 계속 조회합니다. BE 복구 작업이 결과를 확정해 준다는 전제입니다. 그 밖의 상태는 마감 10초 뒤 조회를 멈추고, 직원이 "결과 다시 확인"을 누르거나 "기다리지 않고 새 결제"로 넘어갈 수 있습니다.
