# 알림 만들기 (Task 9.3.2, S15P31E103-377)

이 문서는 서버가 `notification` 표에 앱 내 알림을 쌓는 규칙입니다. 목록·읽음·설정 API는 Task 9.3.1(S15P31E103-376)이 맡습니다.
푸시는 보내지 않습니다. 앱이 알림 목록을 열 때 보입니다.

## 무엇을 언제 쌓나

| 알림 | 유형(`type`) | 언제 | 중복 방지 키 | 앱 이동(`refType`/`refId`) | 설정 묶음 |
|---|---|---|---|---|---|
| 결제 승인 | `PAYMENT_RESULT` | 결제가 APPROVED로 저장된 뒤(승인·결과 불명 복구·늦은 출금 확인) | `PAYMENT_RESULT:{결제}:APPROVED` | `PAYMENT_TRANSACTION`/결제 | 거래 |
| 결제 취소 | `PAYMENT_RESULT` | 결제가 CANCELED로 저장된 뒤(신용·체크) | `PAYMENT_RESULT:{결제}:CANCELED` | `PAYMENT_TRANSACTION`/결제 | 거래 |
| 청구 확정 | `BILL_CONFIRMED` | 청구서 확정 뒤(0원이면 "출금 없이 마감") | `BILL_CONFIRMED:{청구서}` | `CARD_BILL`/청구서 | 거래 |
| 출금 완료 | `BILL_WITHDRAWN` | 청구 출금 성공 뒤 | `BILL_WITHDRAWN:{청구서}` | `CARD_BILL`/청구서 | 거래 |
| 미납 | `BILL_UNPAID` | 은행이 청구 출금을 거절해 UNPAID가 된 뒤 | `BILL_UNPAID:{청구서}` | `CARD_BILL`/청구서 | 거래 |
| 환급 입금 | `REFUND_COMPLETED` | 신용카드 취소 환급 입금 확인 뒤 | `REFUND_COMPLETED:{환급}` | `PAYMENT_TRANSACTION`/원래 결제 | 거래 |
| 잔고 부족 예상 | `BALANCE_RISK` | 매일 잔고 알림 시각(`balanceTime`, 기본 08:30)에 판정 | `BALANCE_RISK:{위험 구간 첫날}` | `BALANCE`/계좌 | 잔고 위험 |

- 앱은 `CARD_BILL`·`BALANCE`를 잔고예보 화면으로 보냅니다(`MyModels.kt`). `BALANCE`의 번호는 위험한 계좌, 통합 위험이면 기준 계좌입니다.
- 만들지 않는 것: 결제 거절(DECLINED, 다른 카드로 다시 시도할 수 있는 중간 상태이고 결제 화면에 바로 보임), 출금 예정(`BILL_DUE_SOON`), 실적 부족(`PERFORMANCE_SHORTFALL`, Task 9.2.1이 머지된 뒤 후속).
- 제목·내용은 만들 때의 값으로 고정됩니다. 가맹점·카드 이름이 길면 칸 길이(제목 150자, 내용 1000자)에 맞춰 자릅니다. 카드 이름은 별칭, 없으면 상품명입니다.

## 어떻게 쌓나

### 거래 알림: 사건 → 커밋 뒤 리스너

```
결제·청구 모듈 트랜잭션 ─ 상태 저장 + 사건 발행 ─ 커밋
                                                   └─ AFTER_COMMIT 리스너(TransactionNotificationListener)
                                                        └─ 새 트랜잭션: 설정 확인 → 문구 읽기 → INSERT
```

- 결제 승인·취소 사건(`PaymentApprovedEvent`·`PaymentCanceledEvent`)은 `PaymentTransaction.approve()`·`confirmLateWithdrawal()`·`cancel()`이 남기고, 리포지토리 `save`/`saveAndFlush`가 발행합니다(Spring Data `@DomainEvents`). 승인·취소를 저장하는 곳이 승인·복구·취소 서비스 다섯 군데라 엔티티에서 한 번에 남깁니다.
- 청구 사건(`BillConfirmedEvent`·`BillWithdrawnEvent`·`BillUnpaidEvent`·`RefundCompletedEvent`)은 Task 5.4.x에서 이미 발행하고 있었습니다(받는 곳이 없었음).

### 잔고 위험: 매분 깨는 정기 작업 `balance-risk-notify`

- 매분 깨어, 잔고 알림 시각이 지났고 오늘 아직 판정하지 않은 사용자(가입 완료·잔고 위험 알림 켬)를 판정합니다. 하루 한 번입니다. 서버가 그 시각에 꺼져 있었으면 다음 실행이 따라잡습니다("오늘 판정함"은 Redis 표시, 2일 유지).
- 판정은 잔고예보와 같은 재료·계산입니다(`forecast.BalanceRiskCheck`). 통합 선과 계좌별 선 중 가장 이른 위험 구간을 고릅니다. 잔고예보 최종 상태와 같은 기준입니다(통합은 안전해도 한 계좌가 위험하면 알림).
- 판정할 계좌는 연결된 계좌 전체입니다(기준 계좌 먼저, 최대 20개). **임시**: 앱이 잔고예보에서 고른 계좌는 서버에 저장되지 않습니다.
- 잔고예보 조회 API를 부르지 않는 이유: 조회는 예상 생활비 이력을 남기는데, 알림 작업이 매일 이력을 쌓으면 "사용자가 본 예측" 이력이 흐려집니다.
- 수동 실행 `POST /api/admin/jobs/balance-risk-notify/run`은 시각·"오늘 판정함"과 상관없이 지금 판정합니다(시연용). `userId`로 한 사용자만, `dryRun`이면 대상만 셉니다. `asOf`는 쓰지 않습니다(잔고예보는 늘 실제 오늘부터 계산).
- 정기 실행은 `yamu.notification.jobs-enabled=true`(운영 환경변수 `NOTIFICATION_JOBS_ENABLED=true`)일 때만 돕니다. 기본은 꺼짐입니다.

## 깨지는 곳과 막은 방법

| 상황 | 처리 |
|---|---|
| 같은 사건이 두 번 옴(재시도·복구와 승인이 겹침·작업 재실행) | `uk_notification_dedup(user_id, deduplication_key)`. 먼저 읽지 않고 INSERT하고 중복 키 실패는 `DUPLICATE`로 끝냄(읽고 넣는 사이 경쟁 없음). 8개 동시 쓰기 테스트 |
| 결제·청구가 롤백됨 | 커밋 뒤에만 받으므로 알리지 않음 |
| 알림 저장 실패(설정 저장값 깨짐·DB 오류) | 리스너가 모든 예외를 잡고 경고 로그만 남김. 커밋 뒤 콜백 예외는 결제 API 호출자에게 전해져 끝난 승인이 실패로 보이기 때문 |
| 커밋 뒤 콜백에서 그냥 DB에 쓰면 커밋되지 않음 | 리스너는 `REQUIRES_NEW`로 새 트랜잭션을 엶 |
| 사용자가 그 묶음을 끔 | 쌓는 순간 설정을 읽어 `DISABLED`. 실적·잔고 위험·거래 3묶음, 반복 지출 후보·혜택 변경·보안은 설정과 상관없음 |
| 다른 사용자의 행으로 문구를 만듦 | 문구를 읽는 SQL마다 사건의 사용자 번호를 조건으로 넣음. 맞는 행이 없으면 쌓지 않음 |
| 이미 안전 잔액 아래인 구간(첫날이 매일 "오늘"로 바뀜) | 어제도 위험이었으면(Redis 표시, 3일 유지) 이어지는 구간으로 보고 다시 알리지 않음(`CONTINUING`) |
| 잔고 판정 중 금융망 실패 | 그 사용자만 `FAILED`, 다음 사용자는 계속. 그날은 다시 부르지 않음(실패한 금융망을 매분 부르지 않게) |
| 앞 실행이 1분을 넘김 | 작업 잠금으로 겹치지 않고, 다음 실행이 "시각이 지났고 아직 안 한" 사용자를 따라잡음 |

## 결정 (2026-11-10)

| 결정 | 이유 | 고르지 않은 것 |
|---|---|---|
| 이번 범위는 거래 + 잔고 위험, 실적 부족은 후속 | 실적 판정(AT_RISK)은 Task 9.2.1 코드에 기댐 | 거래만, 셋 다(9.2.1 머지까지 막힘) |
| 결제 결과는 승인·취소만 | 거절은 재시도 가능한 중간 상태라 연타·재시도마다 알림이 쌓임 | 거절도 매번, 마감 지난 최종 실패만 추가 |
| 잔고 위험은 위험 구간마다 한 번(키 = 구간 첫날) | 같은 위험을 매일 알리지 않음. 새 구간이면 다시 알림 | 위험한 날 매일, 수준이 바뀔 때 |

## 한계

- 커밋과 알림 저장 사이에 서버가 꺼지면 그 알림은 쌓이지 않습니다. 다시 만드는 작업은 없습니다(임시).
- 예보가 바뀌어 위험 구간 첫날이 달라지면(새 지출로 앞당겨지거나 뒤로 밀림) 새 구간으로 보고 다시 알립니다.
- 잔고 판정마다 연결 계좌 수만큼 금융망 잔액 조회(10분 캐시)와 생활비 추정이 나갑니다. 같은 시각을 고른 사용자가 많으면 그 분의 실행이 길어집니다. 시연 규모(수십 명)를 전제로 합니다.
- 결제 승인 응답은 알림을 쌓은 뒤 돌아갑니다(커밋 뒤 같은 스레드). INSERT 한 번이라 몇 ms입니다.

## 테스트

| 테스트 | 수 | 보는 것 |
|---|---|---|
| `NotificationWriterTest` | 7 | DB. 쌓기·같은 키 다시 쌓지 않음·8개 동시 쓰기에서 하나만, 사용자별 키, 묶음별 설정, 없는 사용자 404, 긴 문구 자르기, 키·이동 대상 모양 검사 |
| `TransactionNotificationIntegrationTest` | 10 | DB. 승인 커밋 뒤 한 건·롤백이면 없음·취소 알림, 같은 사건 두 번, 거래 알림 끔, 저장 실패해도 승인 커밋·예외 없음, 남의 결제 번호, 청구 확정·출금·미납 문구, 0원 청구, 환급 |
| `BalanceRiskNotifyJobTest` | 16 | 시각 전·시각·따라잡기·하루 한 번·사용자 시각, 끈 사용자, 실패 격리, 깨진 설정, 위험 없음·이어지는 구간·오늘부터 새 구간·미래 구간, 계좌 하나의 위험, 수동 실행·dryRun, 계좌 없음 |
| `BalanceRiskCheckTest` | 4 | 명세 예시(통합 안전·B 계좌 DANGER), 첫날이 같으면 통합, 가장 이른 구간, 위험 없음 |

결제·청구 통합 테스트 10개(`PaymentApproval`·`CreditApproval`·`CheckCardApproval`·`PaymentCancel`·`CheckCardCancel`·`PaymentReject`·`PosPaymentResult`·`BillConfirm`·`BillWithdraw`·`RefundDeposit`)는 매 테스트 전에 `app_user`를 지웁니다. 승인·취소·청구 사건이 이제 알림을 쌓아 사용자를 참조하므로, 그 앞에서 `notification`을 먼저 지우게 한 줄씩 넣었습니다.
