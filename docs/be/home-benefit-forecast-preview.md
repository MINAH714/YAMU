# 홈 혜택·30일 예보 요약 — S15P31E103-490

## 범위와 변경 방식

`GET /api/home`에 `benefitSummary`, `forecastPreview`를 추가한다. 기존 HomeResponse 필드는 이름·금액 기준·설정 기간을 유지한다. HomeService에서 489 ReportService와 기존 ForecastService를 연결하며 금융망을 호출할 수 있으므로 홈 전체를 DB 트랜잭션으로 묶지 않는다. 신규 집계나 AI 모델, DB 스키마는 만들지 않는다. 선행 489 MR !316에 의존한다.

## 혜택 요약

- 한국 날짜 이번 달을 명시해 `reports.benefits(userId, 이번 달, YAMU)`를 호출한다. 리포트의 지난달 기본값을 사용하지 않는다.
- `yearMonth`, `source=YAMU`, `status`, `totalBenefit`, `confirmedBenefit`, `unconfirmedBenefit`, `unconvertedBenefitCount`, `byType`을 전달한다.
- totalBenefit은 리포트 expectedBenefit과 같으며 확정액을 포함한다. confirmedBenefit을 다시 더하지 않는다. 미환산 건수가 있으면 ‘원화로 확인 가능한 혜택’으로 표시한다.
- 이는 이용월에 발생한 혜택이며 실제 지급월 합계가 아니다. 기존 monthSummary.totalBenefit은 ALL 기준이므로 새 혜택 카드에는 benefitSummary를 사용한다.

## 30일 최저 잔고

- 기존 기준 계좌 하나를 사용한다. 없으면 forecastPreview=null이며 오류가 아니다. 첫 계좌나 전체 계좌로 대체하지 않는다.
- DAYS/30 고정으로 조회한다. 기존 balanceForecast는 사용자 설정 기간을 유지한다. 기존 결과가 같은 계좌·오늘 기준 DAYS/30이면 그 결과를 재사용해 중복 예보 조회를 줄인다.
- 설정 기간이 다르면 기존 예보와 30일 예보를 각각 조회한다. 기존 ForecastService의 반복 지출 후보 갱신·예측 이력 기록 동작은 유지된다.
- `from=내일`, `to=오늘+30일`, `horizonDays=30`. 기존 예보의 오늘 포함 31점 중 내일부터의 30점을 사용한다. 오늘 일말 예측을 현재 잔고로 간주하거나 최저 잔고 후보에 섞지 않는다.
- 월별 자산 흐름(488)과 동일하게 각 날짜에서 conservative가 있으면 그 값, 없으면 expected를 표시선으로 쓴다. 같은 계좌·기준 시각·기간의 그래프와 비교한다. 홈은 그 30일 전체를, 월별 그래프는 선택 월 구간만 표시하므로 여러 달에 걸친 최저점은 해당 월에서 확인한다.
- `minBalanceDate`는 표시선 최저일(동률이면 가장 이른 날짜), `nextRiskDate`는 표시선이 alertThreshold 미만이 되는 첫날이다. 두 날짜는 다를 수 있다. 음수 잔고도 그대로 반환한다.
- accountId/currentBalance/from/to/horizonDays/minBalance/minBalanceDate/nextRiskDate/alertThreshold/balanceAsOf/calculatedAt/isEstimate/isProvisional을 제공한다.
- balanceAsOf는 해당 계좌 잔액의 원래 UTC 조회 시각이다. 캐시를 사용해도 원래 시각을 보존한다. calculatedAt은 예보 계산 시각이다. 현재 잔고·실측·예측은 구분한다.
- 30일 날짜 점이 누락되거나 중복되면 최저값을 추정하지 않고 해당 영역을 실패 처리한다.

## 부분 실패와 갱신

- 기존처럼 HTTP 200과 partialErrors를 유지한다. 신규 영역 실패는 area=benefitSummary 또는 forecastPreview에 기록하고 해당 값은 null이다. 실패를 0원으로 바꾸지 않는다. 두 예보 영역이 모두 실패하면 각각 기록한다.
- 기존 예보만 실패하고 30일 예보가 성공하면 forecastPreview를 반환한다. 이때 기존 dataSyncedAt은 null일 수 있으므로 새 영역은 자신의 balanceAsOf를 사용한다.
- 결제 승인/취소 확정 후 홈과 혜택 리포트를 다시 조회한다. DB 혜택은 조회 시 집계한다. 진행 중이거나 결과 불명인 결제를 승인/취소 완료로 간주하지 않는다.
- 예정 수입·지출 추가/수정/삭제, 기준 계좌/안전 잔액 변경 후 홈과 상세 예보를 다시 조회한다. 예보 금액을 FE에서 별도로 증감하지 않는다.
- GET home은 기존 잔액 캐시를 사용한다. 금융망 최신 잔액이 필요하면 POST /api/sync 결과와 실패 계좌를 확인한 뒤 홈을 재조회한다. sync는 기존 30초 결과 재사용·동시 요청 제한을 유지한다.
- 별도 조회 시점 사이 결제·예정 항목이 변경되면 홈과 그래프 숫자가 달라질 수 있다. 계좌·기간·기준 시각을 맞춰 비교한다. 각 영역 전체를 하나의 은행/DB 원자적 스냅샷이라고 보장하지 않는다.

## 추가 응답 예시

기존 필드를 생략한 추가 필드 예시다. 시각은 UTC이며 금액 단위는 원이다.

```json
{
  "benefitSummary": {
    "yearMonth":"2026-11","source":"YAMU","status":"PARTIAL",
    "totalBenefit":1500,"confirmedBenefit":1000,"unconfirmedBenefit":500,"unconvertedBenefitCount":0,
    "byType":[
      {"type":"DISCOUNT","expectedBenefit":1000,"confirmedBenefit":1000,"unconfirmedBenefit":0,"unconvertedBenefitCount":0},
      {"type":"CASHBACK","expectedBenefit":500,"confirmedBenefit":0,"unconfirmedBenefit":500,"unconvertedBenefitCount":0},
      {"type":"POINT","expectedBenefit":0,"confirmedBenefit":0,"unconfirmedBenefit":0,"unconvertedBenefitCount":0}
    ]
  },
  "forecastPreview": {
    "accountId":1,"currentBalance":3000001,"from":"2026-11-14","to":"2026-12-13","horizonDays":30,
    "minBalance":920000,"minBalanceDate":"2026-11-25","nextRiskDate":null,"alertThreshold":100000,
    "balanceAsOf":"2026-11-13T05:00:00Z","calculatedAt":"2026-11-13T05:00:01Z",
    "isEstimate":true,"isProvisional":false
  }
}
```

## 검증 결과

Java 17, 로컬 MySQL 8.4.11·Redis Testcontainers로 검증했다.

```powershell
# be 폴더
.\gradlew.bat test --tests '*home*' --tests '*report*' --console=plain
.\gradlew.bat test --tests '*HomeHighlightsTest' --console=plain
```

홈·리포트 전체 64개 통과 후 예보 영역의 독립 실패 테스트를 추가하고 HomeHighlightsTest 7개를 다시 실행해 모두 통과했다. 고유 테스트는 총65개(순수 테스트30, MySQL/Redis 기반 API 통합35)이며 실패·오류·생략은 없다. 신규490 테스트16개와 선행489/기존 홈 회귀49개다.

검증 내용: YAMU 이용 혜택과 리포트 종류별 금액 일치, 기존 ALL 합계 유지, 기본 계좌 없음, 7일 설정 호환, 30일 결과 재사용, 날짜 누락/중복 거부, 오늘 일말 제외, 첫 위험일과 최저일 분리, 보수적/예상 대표선·동률 최저일, 한국월 경계, 각 신규 영역 부분 실패와 조회 시각 보존. 기존 SyncService의 중복/동시 요청 검증도 포함된다.

실제 금융망과 운영/개발 RDS는 호출하지 않았으며 배포하지 않았다. 월별 자산 흐름 API 자체는 선행488 범위이고, 이 브랜치에서는 공유 원천인 기존 예보 API와 홈의 같은 조건 결과를 대조했다.
