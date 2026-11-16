# EXP-004 혜택표 수동 선택 비교

실행 구분: scale-10-cards-3

합성 구매 24건·30900원. 2026년 11월, 전월 실적 각 400,000원.

| 전략 | 총 원화 환산 혜택 | 전략−YAMU |
|---|---:|---:|
| FIXED_FIRST | 1545.000000 | -6435.000000 |
| FIXED_TOKTOK | 1900 | -6080.000000 |
| FIXED_LOCA | 7020 | -960.000000 |
| CATEGORY | 7600.000000 | -380.000000 |
| YAMU | 7980.000000 | 0.000000 |
| MANUAL_TABLE | 7980.000000 | 0.000000 |
| RANDOM 평균 | 3442.45 | -4537.55 |

랜덤: java.util.Random, 고정 시드 0~99. 표준편차는 이 100회 결과의 모집단 표준편차. 백분위는 nearest rank.

{mean=3442.45, populationStdDev=1001.8476668136728, min=485.0, p05=1755.0, median=3425.0, p95=4935.0, max=6165.0, lessThanYamu=100, equalToYamu=0, greaterThanYamu=0}

초기·전체 소진·개별 한도 부분/완전 소진 검사 통과. 본 실행 계산 불가 0건. 검사 수는 validation.json 참고. 대표 수식 검산 통과. YAMU 재실행 결과 동일.

할인과 포인트는 분리된 보상이며 처음의 등록 환산값 1P=1원으로만 합산한다. 승인·청구·현금 지급 검증은 아니다. 신규 유예 없음·일반 직접 구매의 합성 사실을 명시했다. 선택하지 않은 카드의 상태는 갱신하지 않는다.

업종 규칙은 effective-input.json에 사전 고정했다. 한도 소진은 limits.csv, 선택별 차이는 transactions.csv에서 확인한다. 승리 여부는 테스트 조건이 아니다. 카드·합성 소비에 한정하며 실제 사용자 평균이나 월간 최적 보장을 뜻하지 않는다.

재현: be에서 `./gradlew.bat test --tests com.yamu.be.recommendation.CardSelectionManualTableTest --rerun-tasks` 실행. Docker 필요. skipped는 성공이 아니다. 상위 status.txt와 JUnit 실행 건수를 확인한다. manifest.json은 미커밋 변경 포함 코드·정책·입력 SHA-256이다. 원본 및 다른 실행을 덮어쓰지 않는다.
