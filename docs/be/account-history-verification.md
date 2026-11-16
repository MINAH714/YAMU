# 계좌 이력 저장 검증 — S15P31E103-517

## 결과

2026-11-16 전용 worktree에서 구현과 로컬 검증을 완료했다. 별도 앵커 엔티티·테이블은 없으며 stream, transaction, sync_run, coverage의 4개 테이블을 사용한다. 시작 잔액과 근거 참조는 coverage에 저장한다.

| 검증 | 결과 | 범위 |
|---|---|---|
| BE 전체 회귀 재검증 | 232개 클래스, 2,683개 통과, 실패·오류·건너뜀 0 | 최종 구현 커밋 c15d387a, 앵커 제거 반영 후 전체 재실행 |
| 최종 BE 변경 범위 | 9개 클래스, 120개 통과, 실패·오류·건너뜀 0 | 앵커 제거 및 추가 테스트 반영 후 실행 |
| Android 관련 단위 테스트 재검증 | 4개 클래스, 66개 통과, 실패·오류·건너뜀 0 | 최종 구현 커밋 c15d387a, SIMULATED 표시·변환 및 예보 공통 계산 |

MR 병합 전 재검증 요청에 따라 2026-11-16 두 명령에 `--rerun-tasks`를 적용해 재컴파일·재실행했다. BE는 13분 23초, Android는 3분 20초에 BUILD SUCCESSFUL로 종료했다. 재검증 과정에서 구현 코드는 수정하지 않았다. 결과는 해당 커밋 기준이며 이후 다른 MR이 병합된 develop과의 통합 결과까지 검증한 것은 아니다.

최종 BE 테스트는 Testcontainers의 격리 MySQL 8.4.11에서 신규 Flyway와 JPA 매핑을 함께 검증했다. 앵커 테이블 부재, 시작 잔액 근거 CHECK, 인접 무거래 구간 연속 복원, 미조회 날짜를 건너뛴 잔액 이월 거절, 소유권, 동시 실행 세대, 재시도 중복, 충돌 시 원본 보존·근거 무효화, 외부 실패, QA 0원 경계와 무거래 합성 구간, QA 전용 초기화 후 실제 이력 보존을 확인했다.

## 최신 develop 통합 검증

2026-11-16 origin/develop c511c5a7까지 작업 브랜치에 통합했다(통합 커밋 ecbe4b02). AssetFlowCard.kt의 문구 충돌 1건은 develop의 '오늘 잔고' 문구와 QA 합성 표시를 모두 유지해 해결했다. 추가 금액 입력 변경은 자동 통합됐다.

- 통합 후 BE 12개 클래스 144개 테스트 통과: 계좌 이력·자산 흐름·QA 자산, 금융망 이체·예보 계산, 최신 가입 인증·스케줄 설정.
- 최종 Android 관련 77개 테스트 통과: 자산 흐름·예보 공통 계산 및 추가 금액 입력 테스트.
- 두 실행 모두 실패·오류·건너뜀 0건. BE 전체 2,683개 결과는 develop 통합 전 커밋에 대한 결과이며, 통합 후에는 위 범위를 재검증했다.
- 사용자 요청에 따라 전용 브랜치를 push하고 develop 대상 MR을 제출한다. AI는 MR 승인·merge를 하지 않는다.

## 재현 명령

BE 폴더:

```powershell
.\gradlew.bat test --rerun-tasks --console=plain
.\gradlew.bat test --tests '*accounthistory*' --tests '*assetflow*' --tests '*QaAssetHistory*' --tests '*SsafyTransferApiTest' --tests '*ForecastCalculatorTest' --console=plain
```

Android 폴더에서 설치된 SDK를 ANDROID_HOME으로 지정한 뒤:

```powershell
.\gradlew.bat :app:testCustomerDebugUnitTest --tests '*AssetFlow*' --tests '*ForecastChart*' --tests '*ForecastDaySync*' --rerun-tasks --console=plain
```

## 실행하지 않은 범위

실제 금융망 조회·금융 거래, RDS 마이그레이션, 실제 QA 계정 적재, 서버 배포, 실기기 화면 검증, push·MR 생성·승인·merge는 수행하지 않았다. 현재 잔액을 과거 기준으로 소급하지 않으며 실제 QA 적재는 [실행 안내](account-history-handoff.md)의 경계 검증을 거쳐야 한다. Jira는 MR merge 전까지 진행 중으로 유지한다.

실제 이력 삭제를 포함하는 전체 사용자 정리 확장은 자동 승인 검토에서 보존 요구와 충돌한다는 사유로 거절되어 적용하지 않았다. 이력 stream이 있으면 전체 reset/purge를 사전 거절하며 자산 전용 reset/purge는 합성 이력만 제거한다.
