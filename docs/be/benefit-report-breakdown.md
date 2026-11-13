# 월간 혜택 집계 확장 — S15P31E103-489

## 구현 계약

`GET /api/reports/monthly/benefits?yearMonth=2026-11&source=YAMU`

- yearMonth 생략 시 지난달, 허용 범위 2000-01~2100-12를 유지한다.
- source는 ALL(기본값) / YAMU. ALL은 기존처럼 SEED를 포함한 전체 승인 결제, YAMU는 YAMU 결제만 포함한다. 다른 값은 INVALID_REQUEST다. 소비 리포트 API는 변경하지 않는다.
- 월은 requested_at의 한국 날짜 이용월이다. 실제 지급월 집계가 아니다. 문구는 ‘11월 이용으로 발생한 혜택’으로 표시한다.
- expectedBenefit은 확정액을 포함한 원화 환산 가능 총액이다. confirmedBenefit을 다시 더하지 않는다. unconfirmedBenefit = expectedBenefit - confirmedBenefit.
- summary.byType은 DISCOUNT / CASHBACK / POINT 고정 순서로 반환한다. 각 항목의 금액도 원이며 POINT의 원래 포인트 수량을 원으로 취급하지 않는다.
- summary.unconvertedBenefitCount는 benefit_value>0이지만 krw_amount가 NULL인 혜택 줄 수다. 이 값이 1 이상이면 총액은 환산 가능한 혜택만 집계한 금액이다. 미환산 혜택을 0원 혜택이라고 표시하지 않는다.
- cards는 userCardId별 집계다. 동일 상품 카드도 합치지 않는다. 승인 거래가 있는 카드는 혜택 0원도 포함하며 혜택 총액 내림차순, 동률은 카드 ID 순이다. 해제한 카드의 과거 승인 거래도 유지한다. null 카드 ID 원장이 있으면 식별 불가 그룹으로 보존한다.
- 카드 이름/별칭은 현재 상품명/별칭이며 결제 당시 스냅샷이 아니다. 카드번호 등 인증 정보는 반환하지 않는다.
- 기존 summary의 4개 필드, differences, byCategory, status 의미를 유지한다. 종류별·카드별·전체 모두 같은 사용자·이용월·출처·APPROVED 범위를 사용한다. 취소는 제외되어 과거 이용월 합계도 재조회 시 달라진다.
- 확정 여부는 기존 PaymentSqlFragments.CONFIRMED_LINE을 재사용한다. 캐시백·적립을 임의로 지급 확정하지 않는다. 분할 청구 확정 정책도 변경하지 않는다.

## 변경할 코드와 방법

ReportController에 선택 source를 추가하고 ReportService의 기존 2인자 메서드는 ALL로 위임한다. ReportReader의 기존 결제 행 조회는 유지하면서 출처 필터를 공통 적용하고, 혜택 줄과 카드 표시 정보는 별도 단일 쿼리로 읽는다. 청구 회차/한도 테이블을 직접 조인해 혜택이 중복되는 구조를 피한다. BenefitBreakdown에서 원화와 미환산 건수를 종류별·카드별로 함께 집계한다. BenefitReportResponse는 기존 필드를 유지한 추가 확장이다.

검증은 기존 API 통합 회귀와 신규 집계 단위 테스트, 실제 MySQL SQL 검증으로 진행한다. 권한은 모든 조회의 user_id 조건으로 제한한다. 읽기 전용 REPEATABLE_READ 트랜잭션 안의 동일 조회 스냅샷을 사용하고, 쓰기·한도 차감·지급·새 마이그레이션은 없다.

## 응답 예시

```json
{
  "success": true,
  "data": {
    "yearMonth": "2026-11",
    "status": "PARTIAL",
    "source": "YAMU",
    "summary": {
      "expectedBenefit": 1500,
      "confirmedBenefit": 1000,
      "unconfirmedBenefit": 500,
      "unconvertedBenefitCount": 1,
      "yamuPickExtra": 0,
      "missedBenefit": 0,
      "byType": [
        {"type":"DISCOUNT","expectedBenefit":1000,"confirmedBenefit":1000,"unconfirmedBenefit":0,"unconvertedBenefitCount":0},
        {"type":"CASHBACK","expectedBenefit":500,"confirmedBenefit":0,"unconfirmedBenefit":500,"unconvertedBenefitCount":0},
        {"type":"POINT","expectedBenefit":0,"confirmedBenefit":0,"unconfirmedBenefit":0,"unconvertedBenefitCount":1}
      ]
    },
    "cards": [{
      "userCardId": 1, "cardProductId": 10, "cardName": "생활비 카드", "cardAlias": null,
      "expectedBenefit":1500,"confirmedBenefit":1000,"unconfirmedBenefit":500,"unconvertedBenefitCount":1,
      "byType": [
        {"type":"DISCOUNT","expectedBenefit":1000,"confirmedBenefit":1000,"unconfirmedBenefit":0,"unconvertedBenefitCount":0},
        {"type":"CASHBACK","expectedBenefit":500,"confirmedBenefit":0,"unconfirmedBenefit":500,"unconvertedBenefitCount":0},
        {"type":"POINT","expectedBenefit":0,"confirmedBenefit":0,"unconfirmedBenefit":0,"unconvertedBenefitCount":1}
      ]
    }],
    "differences": [],
    "byCategory": [{"categoryCode":"COFFEE_SHOP","categoryName":"카페","amount":1500,"ratio":100.0}]
  },
  "error": null
}
```

## 검증 결과

Java 17에서 be 폴더에서 실행한다.

```powershell
.\gradlew.bat test --tests '*report*' --console=plain
```

최종 결과: 29개 통과, 실패·오류·생략 0. 순수 집계 10개와 실제 MySQL 8.4.11 Testcontainers 기반 API 통합 19개다. 통합에는 기존 15개 회귀와 신규 4개가 포함된다. 한국월 경계, 출처 기본값/필터, 타인 제외, 취소 제외, 원화 미환산 및 기본 지난달 응답을 확인했다. 종류별·카드별 합산과 확정액 포함 관계, 안전한 금액 합산도 검사했다. `git diff --check` 통과.

실제 운영/개발 RDS와 금융망에는 접근하지 않았고 배포도 하지 않았다. 이 작업은 487·488과 독립적으로 최신 develop 기준 브랜치에서 진행했다.
