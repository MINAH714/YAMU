# 잔고예보 시연 데이터 넣기

시연 계정의 잔고예보에 **"카드값이 결제일에 빠진다"**와 **"월급이 들어온다"** 장면이 보이도록, 가계부에 예정 지출·수입을 미리 넣는 방법입니다(S15P31E103-340). 계산 규칙은 [forecast.md](forecast.md)에 있습니다.

## 왜 넣어야 하나

- 시연 페르소나 시드([be/seed/PERSONA.md](../../be/seed/PERSONA.md))는 카드 등록 전 6개월 모의 결제(`source = SEED`)만 넣고 **카드 청구서(`card_bill`)는 만들지 않습니다.**
- 잔고예보는 신용카드 청구를 청구서에서만 읽습니다. 그래서 시드만으로는 지난달 신용카드 사용이 결제일에 빠지는 장면이 없습니다. 월급도 사용자가 가계부에 넣고 설정에서 지정해야 보입니다.
- 시드는 바꾸지 않고, 시연 계정 가계부에 **카드값(예정 지출)과 월급(예정 수입)을 넣어** 보여 줍니다(2026-11-06 결정).

## 준비

1. **페르소나 시드는 시연 당일에 넣습니다.** SEED 결제는 넣은 날 전날까지라, 일찍 넣으면 그 사이가 소비 0원인 날로 셈되어 생활비가 낮아집니다([PERSONA.md](../../be/seed/PERSONA.md) 「등록 전 모의 소비」).
2. 앱에서 가입(본인인증 → 계좌 연결 → PIN → 기기 등록)과 **카드 등록(체크·신용 모두)**을 마칩니다.
3. 앱으로 로그인해 받은 **access token**을 준비합니다. 가계부 API는 로그인 토큰이 필요하고, 로그인에는 기기 키 서명이 들어가 Swagger에서 손으로 토큰을 만들 수 없습니다.
   - **앱에서 토큰을 꺼내는 방법은 FE와 정해 이 칸에 채웁니다(2026-11-08 기준 미정).**
4. 아래 예시는 `curl`입니다. `BASE`는 서버 주소, `TOKEN`은 3번 토큰입니다. 토큰은 문서·채팅·로그에 붙여 넣지 않습니다.

```bash
BASE=https://<서버 주소>
TOKEN=<앱에서 꺼낸 access token>
```

## 1. 계좌·카드 확인

```bash
curl -s "$BASE/api/accounts" -H "Authorization: Bearer $TOKEN"
curl -s "$BASE/api/cards" -H "Authorization: Bearer $TOKEN"
curl -s "$BASE/api/cards/<신용카드 userCardId>" -H "Authorization: Bearer $TOKEN"
```

- 계좌 목록의 `accounts[].accountId`를 적어 둡니다(시연 페르소나는 계좌 1개).
- 신용카드 상세의 `billing.paymentDay`(결제일)와 `billing.settlementAccountId`(결제계좌)를 확인합니다. 카드값은 이 결제계좌에서 빠집니다.

## 2. 카드값 넣기 (예정 지출)

**계좌 지정 1회 지출**로 넣습니다.

```bash
curl -s -X POST "$BASE/api/cashflow-items" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{ "type": "EXPENSE", "title": "신한 Mr.Life 카드값", "amount": 450000, "date": "2026-11-16", "accountId": <결제계좌 accountId> }'
```

| 칸 | 넣는 값 |
|---|---|
| `date` | **다음 출금일.** 결제일이 주말·공휴일이면 다음 영업일입니다(예: 결제일 14일, 2026-11-14 토요일 → 11-16 월요일). 가계부에는 휴일 규칙이 없어 직접 옮겨 넣습니다 |
| `amount` | 그 청구의 사용 금액. 시연 신용카드는 결제일 14일·마감 일수 14일이라 **청구 하나가 지난달 1일~말일 사용분**입니다. SEED와 맞추려면 카드 등록 화면의 지난달 "등록 전 실적"을 쓰고, 시드의 90만 원 3개월 할부(구매 다음 달부터 30만 원씩 3번)가 그 청구에 걸리면 30만 원을 더합니다. 정확히 맞추지 않아도 장면은 같습니다 |
| `accountId` | 1번의 결제계좌 |
| `recurrence` | 넣지 않습니다(1회). 이유는 아래 「다음 달 카드값」 |

- **`userCardId`(신용카드 지정)로 넣지 않습니다.** 신용카드 지정 지출은 "그 카드로 살 물건"으로 읽혀, 예정일이 아니라 그 사용분의 청구 출금일(다음 달 이후)에 빠집니다.
- `date`는 오늘 이후만 됩니다(지난 날짜는 400). 오늘 날짜로 넣으면 오늘 빠지는 것으로 계산합니다.

### 다음 달 카드값 (선택)

예보 기간에 다음 출금일(위 예에서 12-14)까지 들어가면 하나 더 넣을 수 있습니다. 이때 금액은 **이번 달 1일부터 어제까지 사용분만** 넣습니다. 시드를 넣은 날이면 SEED가 어제까지라, 카드 목록(`GET /api/cards`)의 그 카드 `performance.recognizedAmount`(이번 달 실적)와 같습니다.

- 잔고예보는 내일부터 이번 이용기간 마감일까지 쓸 신용카드 금액을 이미 추정해, 그 출금일에 보수적 선에서만 뺍니다(`CARD_SPENDING_ESTIMATE`, [forecast.md](forecast.md) 「마감 전 신용카드 사용 추정」). 한 달 치를 다 넣으면 남은 기간이 두 번 빠집니다.
- 같은 이유로 매월 반복으로 넣지 않습니다. 반복으로 넣으면 다음 달 회차가 추정과 겹칩니다.

## 3. 월급 넣기 (예정 수입)

**매월 반복 계좌 수입**으로 넣고, 잔고예보 설정에서 월급으로 지정합니다.

```bash
curl -s -X POST "$BASE/api/cashflow-items" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{ "type": "INCOME", "title": "월급", "amount": 3000000, "date": "2026-11-25",
        "recurrence": { "frequency": "MONTHLY", "dayOfMonth": 25 }, "accountId": <계좌 accountId> }'

curl -s -X PATCH "$BASE/api/forecasts/settings" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{ "incomeCashflowItemId": <등록 응답의 data.cashflowItemId> }'
```

- `date`는 **내일 이후**의 다음 월급일, `dayOfMonth`는 그 날짜의 날입니다. 오늘 날짜 수입은 잔액에 이미 들어왔는지 몰라 예보에 더하지 않습니다(`excluded`의 `TODAY_INCOME_UNVERIFIED`).
- 첫 회차가 오늘부터 62일 안이어야 월급으로 지정됩니다. 설정 수정 응답의 `income`이 null이 아니면 지정된 것입니다.
- 숫자 칸은 정수로, enum 칸(`type`·`frequency`)은 이름 문자열로 보냅니다. 숫자(`0`)를 보내면 S15P31E103-339 머지 전에는 순번으로 읽혀 다른 값(`0` → `INCOME`)이 되고, 머지 뒤에는 400입니다([cashflow.md](cashflow.md) 「에러」).

## 4. 잔고예보에서 보이는 것

```bash
# 기본(설정 NEXT_INCOME): 오늘부터 다음 월급일 전날까지
curl -s "$BASE/api/forecasts/balance" -H "Authorization: Bearer $TOKEN"
# 월급이 들어오는 날까지 보려면 30일
curl -s "$BASE/api/forecasts/balance?until=DAYS&days=30" -H "Authorization: Bearer $TOKEN"
```

| 장면 | 어디서 보나 |
|---|---|
| 카드값이 빠짐 | 카드값 날짜의 `series[].events[]`에 `type: SCHEDULED_OUTFLOW`, `title: 신한 Mr.Life 카드값`, `confidence: CONFIRMED`. 그날부터 확정·예상·보수적 선이 모두 그만큼 내려갑니다 |
| 월급이 들어옴 | **30일 조회에서만** 보입니다. 기본 조회(NEXT_INCOME)는 다음 월급일 **전날까지**라 월급 날이 기간 밖입니다. 30일 조회에서 그날 `type: SCHEDULED_INCOME`으로 선이 올라갑니다 |
| 기간이 월급일 기준 | 기본 조회의 `horizon.type`이 `NEXT_INCOME`, `horizon.to`가 월급일 전날 |
| 남은 신용카드 사용 추정 | 다음 출금일이 기간 안이면 그날 `type: CARD_SPENDING_ESTIMATE`, `confidence: CONSERVATIVE`(보수적 선만) |

### 예: 시연일 2026-11-10(화)

| 넣는 것 | 값 |
|---|---|
| 카드값 | 2026-11-16, 450,000원(10월 사용분), 결제계좌 |
| 월급 | 2026-11-25부터 매월 25일, 3,000,000원, 설정에서 지정 |

- 기본 조회: 11-10 ~ 11-24. 11-16에 45만 원이 빠집니다. 12-14 출금은 기간 밖입니다.
- 30일 조회: 11-10 ~ 12-10. 11-16에 빠지고 11-25에 300만 원이 들어옵니다.
- 잔액 150만 원에 이 금액이면 안전 잔액(기본 10만 원)까지 내려가지 않아 위험 구간은 나오지 않습니다. 부족 위험 장면은 가상 구매 시뮬레이션이나 더 큰 카드값으로 보여 줍니다.

## 주의

- **시연 중 그 신용카드로 바코드 결제를 하면** 진행 중 청구서가 생겨, 그 결제의 출금일(위 예에서 12-14)에 `type: CARD_BILLING`(예상 선)이 함께 나옵니다. 11-16 카드값과는 출금일이 달라 겹치지 않습니다. 12-14 카드값도 위 규칙(이번 달 1일~어제 사용분)대로 넣었다면 오늘 결제와 겹치지 않습니다. 한 달 치를 통째로 넣었다면 그 결제 금액이 두 번 빠집니다.
- 넣은 항목을 지우려면 `DELETE $BASE/api/cashflow-items/<cashflowItemId>`입니다(전체 삭제가 기본).
- 다음 시연을 위해 페르소나를 `--reset`하면 새 사용자가 되므로 가계부 항목도 다시 넣습니다.
