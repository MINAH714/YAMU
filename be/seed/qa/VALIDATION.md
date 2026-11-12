# 검증 기록 — S15P31E103-442

- 기준: develop `30edd5da`, Java 17 대상으로 컴파일, MySQL 8.4.11 Testcontainers.
- 검증일: 2026-11-12 (한국 날짜).
- 아래 명령 성공. JUnit XML 확인: **16개, 실패 0, 오류 0, 생략 0**.
  - QaHistoryPlanTest: 4개.
  - QaSeedServiceTest: 12개.

```powershell
# be 폴더
.\gradlew.bat -I seed/qa/qa.init.gradle seedQa '-PqaCommand=plan' '-PqaFile=seed/qa/personas.example.json' test --tests 'com.yamu.be.seed.qa.*' --no-daemon
```

## 확인한 내용

- 6개월 생성의 재현성, 연초·윤년·월초 경계, 미래 승인 날짜 없음.
- empty 거래 0건, 전월 299,999/300,000원 경계, 월납 한도 3,000원 소진.
- 승인/취소/거절과 90만 원 할부 3회 원금 합계.
- 동일 입력 재실행 중복 없음, 입력 변경 시 reset/purge 거절, 기존 사용자 편입 거절.
- 가입 후 보강의 원자성: 뒤 페르소나 준비 실패 시 앞 페르소나 보강도 롤백.
- 초기화 후 PENDING 복원, ID 재사용 없음, 정상 재검증.
- QA 중 추가된 멱등성 기록까지 삭제; 다른 사용자와 공용 상품·가맹점 보존.
- 결과 불명 금융 요청, 다른 사용자 알림 참조가 있으면 삭제 전체 중단.
- DB advisory lock이 잡힌 동안 두 번째 CLI 실행 거절.
- 실적·한도 합계 불일치와 실적 행 누락 탐지.
- 실제 MockMvc 조회: 카드 3장, 거래 페이지 hasNext, 미읽음 알림 12개, 청구·가계부·거래 상세 HTTP 200.
- 다른 사용자의 거래 상세 HTTP 404.
- 읽기 전용 inspect 실행과 종속 데이터 수량/기기 ID 출력.

## 견본 생성량

기준일 2026-11-12, 사용자 5명, 카드 15장, 금융망 계좌 준비 요청 10개.

| 페르소나 | 모의 거래 수 |
|---|---:|
| rich | 598 |
| empty | 0 |
| boundary | 598 |
| shortfall | 598 |
| states | 601 |
| 합계 | 2,395 |

계정/카드는 apply에서 PENDING으로 생성된다. 실제 계좌 연결·PIN·기기 등록을 생성기가 대체하지 않는다.
가입/등록 후 enrich로 예정 내역 20개, 첫 발생 건 20개, 알림 96개와 당월/할부 청구를 추가한다.

## 검증하지 않은 것

- 실제 SSAFY 회원/계좌 생성·연결·잔액, RDS 적용, 실제 본인인증·기기 서명·PIN 로그인.
- 실기기 UI, 이미지 CDN 접근, AI 예보 수치, POS 승인/취소 E2E.
- 테스트의 인증 principal과 가입 완료 데이터는 격리 테스트용 전제다. 운영 인증 우회를 추가하지 않았다.
- BE 전체 회귀 테스트는 실행하지 않았다. 모든 테스트 소스는 compileTestJava로 컴파일했고 이번 시드 범위만 실행했다.
- 금융망·Redis 정리는 인프라 후속 작업. 이 CLI는 DB 종속 데이터만 정리한다.
