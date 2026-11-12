# 모의 구매 765개 조합 재현 (S15P31E103-457)

Docker를 실행하고 `be` 폴더에서 아래 명령을 실행한다. 개인 DB나 금융망 계정은 필요하지 않다. 기존 Testcontainers MySQL·Redis 및 저장소의 시드 SQL을 사용한다.

```powershell
# 진단 클래스만 실행
.\gradlew.bat cleanTest test --tests '*RecommendationZeroAuditTest'

# MR 관련 회귀 테스트까지 실행
.\gradlew.bat cleanTest test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.card.RecommendationPerformanceAssemblerTest'
```

`RecommendationZeroAuditTest`는 일반 `src/test/java`에 포함되어 있다. 별도 init script나 ZIP은 필요하지 않다. 기존 시드 통합 테스트 클래스를 상속하므로 상속된 테스트도 함께 실행된다. 이전 안내의 “추가 진단 15개”는 잘못된 분류였으며 독립된 새 사례 15개를 뜻하지 않는다. 현재 XML 기준 진단 클래스는 765조합 검사 1개와 상속 사례 38개로 총 39개다.

2026-11-12 재실행 결과: 관련 테스트 463개(다른 클래스 424개 + 진단 클래스 39개) 통과, 실패·오류·생략 0개. 별도 init script 없이 위 두 번째 명령으로 실행했으며 `BUILD SUCCESSFUL`을 확인했다.

## 조합과 검증

- 17개 카드 × 전월실적 3종(0 / 299,999 / 1,000,000원) × 가맹점 5종(GS25 / 스타벅스 / 일반 음식점 / 쿠팡 / 이마트) × 결제액 3종(1,000 / 10,000 / 100,000원) = 765개.
- 거래 시각은 2026-11-12 12:00 KST, 일시불, 한도 사용량 0이다. 쿠팡은 온라인, 나머지는 일반 매장 결제다.
- 계산 불가 0개, 양수 혜택 293개, 0원 472개를 검사한다. 등록 정책으로 별도 산출한 291개 조합의 기대 금액도 대조한다.
- 원본 결과는 `be/build/reports/recommendation-zero-coupang-audit.tsv`, JUnit 결과는 `be/build/test-results/test/`, HTML은 `be/build/reports/tests/test/index.html`에 생성된다. 재실행하면 덮어쓴다.
- Docker가 없으면 DB 테스트가 생략될 수 있으므로 `BUILD SUCCESSFUL`뿐 아니라 XML의 `skipped=0`도 확인한다.

## 범위

실제 시드·조회·조립·계산기를 사용하지만 고객 실적·한도·결제 가능 상태는 모의 값이다. 이 진단은 앱 화면이나 실제 금융망 검증이 아니다. 삼성 taptap O·iD SIMPLE의 일부 혜택은 이 작업 이전부터 시드에 미적재되어 있으며, 0원은 등록된 혜택 범위의 결과다. 미적재 혜택을 추가하거나 삭제하지 않는다.
