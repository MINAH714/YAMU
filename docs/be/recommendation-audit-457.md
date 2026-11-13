# 모의 구매 765개 조합 재현 (S15P31E103-457)

Docker를 실행하고 `be` 폴더에서 아래 명령을 실행한다. 개인 DB나 금융망 계정은 필요하지 않다. 기존 Testcontainers MySQL·Redis 및 저장소의 시드 SQL을 사용한다.

```powershell
# 진단 클래스만 실행
.\gradlew.bat cleanTest test --tests '*RecommendationZeroAuditTest'

# MR 관련 회귀 테스트까지 실행
.\gradlew.bat cleanTest test --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.card.RecommendationPerformanceAssemblerTest'

# 제외 업종 리뷰 보완: forecast와 업종 API까지 포함
.\gradlew.bat cleanTest test --tests 'com.yamu.be.forecast.*' --tests 'com.yamu.be.merchant.*' --tests 'com.yamu.be.recommendation.*' --tests 'com.yamu.be.payment.recommend.*' --tests 'com.yamu.be.card.RecommendationPerformanceAssemblerTest'
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

## 제외 업종 입력 차단 (2026-11-13 리뷰 보완)

기존 공용 업종 목록에는 전기요금·대학·택시가 포함되어 있었고, 이번 MR의 일반 구매 기본값이 이 입력에도 적용되는 회귀가 있었다. 처음 검증한 463개에는 forecast가 포함되지 않았다. 추가 확인에서 EngineSimulationRecommenderTest 13개 중 2개가 실패해 입력 경계와 사실 보완 범위를 함께 수정했다.

- `PurchaseSimulationCategories`에서 기존 PosPurchaseFacts 미확인 업종 14종을 공유한다: 택시·시내버스·시외버스·고속버스·지하철·철도, 전기·수도·도시가스, 대학·초중고·유치원·학원·자동차학원.
- `GET /api/merchant-categories`는 공용 사전을 유지하고 각 항목에 `simulationSupported`를 제공한다. 고객 앱 모의 구매 목록은 true인 업종만 표시·선택한다. 누락된 구버전 응답은 허용하지 않으므로 BE를 먼저 배포해야 한다.
- `POST /api/forecasts/simulations`는 제외 업종 입력을 HTTP 400 `INVALID_REQUEST` 및 `merchantCategoryCode` fieldErrors로 거절한다. 브랜드만 지정해도 DB에서 확인한 업종을 다시 검사하며 추천·혜택·예보 계산을 호출하지 않는다.
- 내부 호출에서도 BasicPurchaseSimulation과 신한 처음 전용 보완은 제외 업종의 원본 거래를 그대로 반환한다. 기존 명시 사실을 뒤집거나 제외 업종의 계산을 새로 구현하지 않는다.
- 일반 업종만 선택한 거래에는 승인된 기본값을 적용한다. POS 전용 사실 전체를 복사하는 것은 아니며, 제외 업종의 브랜드 유무 두 경로는 모두 기본값이 없는지 검사한다.

Android는 `fe/android`에서 `gradlew.bat testCustomerDebugUnitTest --tests 'com.yamu.app.feature.forecast.*' --tests 'com.yamu.app.data.FakeForecastSimulationTest'`로 업종 응답 변환, 목록·선택 차단과 기존 forecast 회귀를 실행한다. JDK는 Android Studio 내장 jbr, Android SDK가 필요하다. debug 가짜 데이터의 제외 업종도 같은 표시·입력 차단을 적용했다.

### 최종 재검증 결과 (2026-11-13)

| 범위 | 통과 | 실패·오류·생략 |
|---|---:|---:|
| BE: forecast·업종 API·추천·결제 추천·실적 조립 | 870 | 0 |
| 위 BE 중 forecast | 373 | 0 |
| Android: forecast·가짜 모의 구매 | 207 | 0 |

같은 765개 조합은 계산 불가 0개, 양수 혜택 293개, 0원 472개로 유지됐고 기대 금액 291개 조합도 일치했다. 제외 업종 14종의 기본값 미공급·직접 입력 거절 및 전기요금/대학/택시의 브랜드만 지정한 우회 요청을 검사했다. 앱은 지원 여부 응답 변환, 미지원 목록 숨김·선택 거절·목록 재조회 시 선택 해제를 검사했다. `git diff --check`도 통과했다. 이는 로컬 테스트 결과이며 운영 배포·실기기 E2E 확인은 포함하지 않는다.
