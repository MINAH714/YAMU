# 카드 선택 실험 실행 결과

실행 구분: exp-001-reproduction

합성 구매 24건·309,000원. 2026년 11월, 전월 실적 각 400,000원.

| 전략 | 총 원화 환산 혜택 | 전략−YAMU |
|---|---:|---:|
| FIXED_FIRST | 5000.000000 | -10000.000000 |
| FIXED_TOKTOK | 10000 | -5000.000000 |
| CATEGORY | 15000.000000 | 0.000000 |
| YAMU | 15000.000000 | 0.000000 |
| RANDOM 평균 | 12736.00 | -2264.00 |

랜덤: java.util.Random, 고정 시드 0~99. 표준편차는 이 100회 결과의 모집단 표준편차. 백분위는 nearest rank.

{mean=12736.0, populationStdDev=2066.822198448623, min=4400.0, p05=7500.0, median=13475.0, p95=14800.0, max=15000.0, lessThanYamu=96, equalToYamu=4, greaterThanYamu=0}

초기·전체 소진·개별 한도 부분/완전 소진 검사 통과. 본 실행 계산 불가 0건. 검사 수는 validation.json 참고. 대표 수식 검산 통과. YAMU 재실행 결과 동일.

할인과 포인트는 분리된 보상이며 처음의 등록 환산값 1P=1원으로만 합산한다. 승인·청구·현금 지급 검증은 아니다. 신규 유예 없음·일반 직접 구매의 합성 사실을 명시했다. 선택하지 않은 카드의 상태는 갱신하지 않는다.

업종 규칙은 effective-input.json에 사전 고정했다. 한도 소진은 limits.csv, 선택별 차이는 transactions.csv에서 확인한다. 승리 여부는 테스트 조건이 아니다. 카드·합성 소비에 한정하며 실제 사용자 평균이나 월간 최적 보장을 뜻하지 않는다.

재현: be에서 `./gradlew.bat test --tests com.yamu.be.recommendation.CardSelectionExperimentTest --rerun-tasks` 실행. Docker 필요. skipped는 성공이 아니다. 상위 status.txt와 JUnit 실행 건수를 확인한다. manifest.json은 미커밋 변경 포함 코드·정책·입력 SHA-256이다. 원본 및 다른 실행을 덮어쓰지 않는다.
