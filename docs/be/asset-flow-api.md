# 월별 자산 흐름 API — S15P31E103-488

## 요청

`GET /api/asset-flows` (로그인 필요)

| 파라미터 | 형식 | 기본값·검증 |
|---|---|---|
| yearMonth | YYYY-MM | 한국 날짜 이번 달. 이번 달의 11개월 전부터 다음 달까지 |
| accountIds | 반복 또는 쉼표 구분 정수 | 생략 시 연결된 기준 계좌 1개. 기준 계좌가 없으면 빈 목록. 명시한 빈 목록/0/음수/20개 초과는 INVALID_REQUEST |
| refresh | boolean | false. true는 기존 잔액 갱신 한도 적용 |

조회 월 선택·날짜/그래프 드래그는 이 월별 응답을 재사용한다. 날짜마다 재조회하지 않는다. 모든 계좌의 소유권·연결 상태를 외부 호출 전에 검사하며 잘못된 계좌는 RESOURCE_NOT_FOUND로 전체 거절한다.

예: `/api/asset-flows?yearMonth=2026-11&accountIds=1,2&refresh=false`.

## 응답 계약

자정 경계로 예보 시작일과 요청 기준일이 달라지면 HTTP409 `FORECAST_DATE_CHANGED`를 반환한다. 입력 오류가 아니므로 ‘날짜가 변경되어 다시 조회해 주세요’로 안내하고 새 한국 날짜로 한 번 재조회한다. 일반 입력 오류는 기존400 `INVALID_REQUEST`다.

미래 구간은 `ForecastService.preview`로 계산한다. 잔고예보 상세와 같은 결과를 사용하지만 생활비·신용 예측 평가 이력을 기록하지 않는다. 반복 지출 후보 갱신은 기존대로 수행한다.

공통 ApiResponse 봉투를 사용한다. 금액 단위 원, 날짜는 한국 날짜, 시각은 UTC ISO-8601이다.

```json
{"success":true,"data":{"yearMonth":"2026-11","accountIds":[1],"currentTotalBalance":3000001,"balanceAsOf":"2026-11-13T05:00:00Z","series":[{"date":"2026-11-12","kind":"ACTUAL","dataStatus":"AVAILABLE","displayBalance":3100001,"actualBalance":3100001,"forecast":null,"events":[]},{"date":"2026-11-13","kind":"CURRENT","dataStatus":"AVAILABLE","displayBalance":3000001,"actualBalance":3000001,"forecast":null,"events":[]},{"date":"2026-11-14","kind":"FORECAST","dataStatus":"AVAILABLE","displayBalance":2900001,"actualBalance":null,"forecast":{"confirmed":3000001,"expected":2950001,"conservative":2900001},"events":[]}],"forecastFrom":"2026-11-14","forecastTo":"2026-12-13","partialErrors":[],"calculatedAt":"2026-11-13T05:00:01Z"},"error":null}
```

위 series는 설명용 발췌이며 실제로는 선택 월의 모든 날짜를 반환한다.

- ACTUAL: 어제 이전의 일말 잔고. CURRENT: 오늘의 현재 잔고. FORECAST: 내일 이후 계산값.
- 미래 표시선은 conservative 우선, 없으면 expected. confirmed도 계산값이며 실측이 아니다.
- dataStatus는 AVAILABLE / NO_DATA / ERROR. 자료가 없으면 금액은 null이며 실제 0원과 구분한다.
- 미래는 기존의 오늘+30일까지 제공한다. 범위 밖은 NO_DATA. forecastFrom/To는 예보 가능 범위이며 월말까지 늘리지 않는다.
- balanceAsOf는 선택 계좌 중 가장 오래된 잔액 조회 시각이다. 은행의 원자적 스냅샷 시각이 아니다. 캐시의 원래 조회 시각을 보존한다.
- 현재와 미래는 동일한 기존 예보 결과의 시작 잔액을 쓴다. 과거는 거래 후 잔고로 독립 복원한다. 서로 다른 시점을 원자적으로 연결했다고 보장하지 않는다. FE는 결측 구간과 실제/현재/예측 경계를 임의 보간해 사실처럼 표시하지 않는다.
- 오늘 일말 예보는 섞지 않고, 오늘 거래를 재계산·재차감하지 않는다. 기존 예보의 미래 날짜 점을 그대로 사용한다.
- 다계좌는 모든 선택 계좌가 조회된 날짜만 합산한다. 일부만 합친 값을 전체로 표시하지 않는다.
- 기준 계좌가 없으면 accountIds=[], currentTotalBalance/balanceAsOf=null, 전 날짜 NO_DATA. 첫 계좌를 자동 선택하지 않는다.
- partialErrors는 accountId/from/to/reason을 담는다. 인증 정보·계좌번호·거래 적요는 포함하지 않는다.

## 과거 조회와 실패 처리

월 단위 전체 입출금을 조회하고 COUNT_MISMATCH일 때만 날짜로 이분해 재조회한다. 최대 61회/계좌이며 전체 10초의 호출 시작 기한을 둔다. 한 날짜 안에서 잘린 목록은 미확인으로 남긴다. 외부 클라이언트 기존 타임아웃을 사용하고 기한 뒤 추가 호출은 하지 않는다. 진행 중인 한 호출의 타임아웃 시간만큼 전체 응답은 10초를 넘을 수 있다.

중복 ID·보고 건수 없음·범위 밖 거래·동일 시각 순서 불명·잔고 불일치는 추정하지 않는다. 완전한 무거래 목록에 과거 기준 잔고가 없으면 NO_ANCHOR다. 현재 잔고를 과거 기준으로 사용하지 않는다. 과거 외부 조회 실패는 해당 구간 ERROR이며 현재·미래는 유지할 수 있다. 현재 잔고/예보 조회 실패는 기존 에러로 전체 실패한다. 예상하지 못한 내부 예외는 숨기지 않는다.

DB/Flyway 변경·금융 거래 쓰기·예보 기간 확장은 없다. 선행 487 MR !314에 의존한다.

## 사유와 화면 처리

| partialErrors.reason | 의미 | 날짜 자료 상태 |
|---|---|---|
| HISTORY_INCOMPLETE | 건수·중복·방향·범위 검사 실패 | NO_DATA |
| FILTERED_HISTORY | 전체 입출금이 아닌 자료 | NO_DATA |
| NO_ANCHOR | 거래도 확인된 과거 기준 잔고도 없음 | NO_DATA |
| AMBIGUOUS_ORDER | 같은 초 거래의 순서를 확인할 수 없음 | NO_DATA |
| BALANCE_MISMATCH | 거래별 잔고 연결 불일치 | NO_DATA |
| ARITHMETIC_OVERFLOW | 안전한 원화 합산 범위 초과 | NO_DATA |
| FIN_API_UNAVAILABLE / FIN_API_ERROR / UPSTREAM_TIMEOUT | 과거 금융망 조회 실패 | ERROR |

이번 구현에서 과거 오류 범위는 해당 계좌의 선택 월 과거 구간 전체다. 정상 날짜만 분리해서 부분 복원하지 않는다. 예보 범위 밖의 NO_DATA에는 partialErrors를 추가하지 않는다.

## 검증

Java 17에서 다음 검사로 101개 통과(실패·오류·생략 0):

```powershell
.\gradlew.bat test --tests '*assetflow*' --tests '*SsafyTransferApiTest' --tests '*ForecastCalculatorTest'
```

새 488 테스트 25개, 선행 487 테스트 62개, 기존 예보 계산 회귀 14개다. 외부 금융망은 대체하며 실제 RDS/인증 필터를 포함한 서버 통합·기기 연동·배포 검증은 이 결과에 포함하지 않는다. API 인증은 기존 SecurityConfig의 공개 경로 외 인증 필수 규칙을 따른다.
