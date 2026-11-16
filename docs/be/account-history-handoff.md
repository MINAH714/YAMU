# 계좌 입출금 저장·QA 자산 이력 실행 안내 — S15P31E103-517

## 범위

GET /api/asset-flows의 과거 구간은 금융망 조회 → 검증 저장 → DB 원장 재계산 순서로 읽는다. 외부 호출 중 DB 트랜잭션은 유지하지 않는다. 현재 잔액과 예보는 기존 경로를 유지한다. refresh도 현재 잔액 갱신 의미를 유지한다.

첫 실제 거래 이전 날짜를 0원으로 확정하지 않는다. 무거래 기간은 완전 조회와 바로 전 날짜의 유효한 잔액 근거가 있어야 표시한다. ID 내용 충돌·기존 거래 누락은 원본을 보존하고 해당 기간 및 후속 잔액 근거를 무효화한다. 외부 실패·불완전 응답은 기존 확정 데이터를 지우지 않으며 partialErrors로 보고한다. 누락 구간의 계좌 일부 합계를 전체 자산으로 표시하지 않는다.

QA 구간은 SIMULATED, actualBalance=null, displayBalance=합성 잔고다. Android는 그래프·선택 날짜·접근성 문구에서 QA 합성 이력을 안내한다. 일반 사용자는 SSAFY만 읽는다. QA 지정 구간은 실제 이력으로 자동 대체하거나 두 원장을 합산하지 않는다. 실제 경계 근거가 무효화되면 합성 구간도 표시하지 않는다.

## 동기화 운영

- 화면 조회는 선택 월을 동기화한 뒤 저장 자료를 읽는다. 신규 입출금은 과거 날짜이면 재조회 시 반영된다. 오늘은 기존 현재 잔액을 표시하고 일말은 다음 날부터 확정한다.
- 기존 운영 수동 작업 API에 jobName=account-history-sync를 추가했다. userId 필터·dryRun을 지원한다.
- 정기 실행은 기본 비활성이다. 인프라에서 yamu.account-history.sync.enabled=true로 활성화한다.
- 기술 기본 cron은 한국 시각 매일 03:15(설정 yamu.account-history.sync.cron). 표시 가능한 과거 12개월을 월별 재검증한다. 금융망 호출량·지연·사용자 수를 확인한 뒤 주기를 조정한다.
- 기존 Redis JobLocks와 DB 실행 세대 검사로 겹친 작업을 제어한다. 개별 계좌 실패는 다른 계좌 진행을 막지 않는다.
- 계좌 연결 직후 무조건 12개월을 가져오는 이벤트 훅은 없다. 최초 화면 조회 또는 운영 작업이 적재를 시작한다.
- 본 변경은 금융 거래를 발생시키지 않는다. 결제·청구·환급을 다시 차감하는 계산 경로가 없다.

## 인프라 적용 순서

1. 기존 DB 백업과 마이그레이션 적용 대상 확인. 새 SQL만 검토·적용한다. 서버 시작은 Flyway를 실행하므로 개발자가 실행 중 QA 서버를 임의 교체하지 않는다.
2. BE와 Android를 함께 배포한 뒤 대상 QA 계좌의 실제 이력을 동기화한다.
3. 최초 실제 거래 날짜와 그 거래 전 잔액을 확인한다. 현재 잔액이 아니라 실제 coverage의 opening_balance와 FIRST_TRANSACTION 근거를 사용한다.
4. 아래 plan의 건수·시작/입금/출금/종료 합계 및 음수 여부를 검토한 뒤 apply, verify를 실행한다.
5. 앱의 과거 합성 안내, 실제 전환 날짜, 오늘 잔액, 예보를 확인한다.

실제 RDS 적용, 실제 QA 적재, 금융망 경계의 현장 검증은 이 코드 작성 과정에서 수행하지 않았다. 검증 근거를 확보하지 못하면 CLI는 실패하며 적재 완료로 보고하지 않는다.

## QA 자산 전용 CLI

기존 QA dataset/persona가 소유한 연결 계좌만 가능하다. 사용자 번호를 하드코딩하지 않는다.
be/seed/qa/assets.example.json을 로컬 입력 파일로 복사해 datasetKey, personaKey, accountId, 기간을 채운다. 예제 금액은 합성 시나리오 입력이며 실제 급여·월세가 아니다.

- 기존 APPROVED 결제 중 체크는 승인 금액을 합성 출금으로 참고한다.
- 신용 결제는 다음 달 카드 결제일에 납부하는 QA 시나리오다. 실제 청구 산정 재현을 주장하지 않으며 실출금 배치용 card_bill을 생성하지 않는다.
- 월별 합성 수입·월세는 입력값으로 지정한다. extraEvents에는 이체·환급 시나리오를 명시할 수 있다. 계좌 간 이체는 두 계좌에 같은 금액의 반대 방향을 입력해 각각 검증한다.
- 합성 종료 잔액은 to 다음 날의 실제 첫 거래 전 검증 잔액이다. 첫 실제 입금은 삭제하지 않는다.
- 시작 잔액을 역산하되 중간 음수나 음수 시작은 거절한다. 보정 입금·임의 경계 이동으로 숨기지 않는다.
- 동일 입력 apply는 재실행 가능하다. 입력이 바뀌면 자산 전용 reset 후 재생성한다. 현재 잔액이 변해도 과거를 자동 재작성하지 않는다.

BE 폴더에서 실행한다. DB 환경 변수는 QA_SEED_JDBC_URL, QA_SEED_DB_USERNAME, QA_SEED_DB_PASSWORD를 사용하며 콘솔에 출력하지 않는다.

~~~powershell
.\gradlew.bat -I seed/qa/qa.init.gradle seedQaAssets -PqaCommand=plan -PqaFile=seed/qa/assets.local.json
# 유지보수 시간에 QA_SEED_MAINTENANCE=true를 설정한 뒤:
.\gradlew.bat -I seed/qa/qa.init.gradle seedQaAssets -PqaCommand=apply -PqaFile=seed/qa/assets.local.json
.\gradlew.bat -I seed/qa/qa.init.gradle seedQaAssets -PqaCommand=verify -PqaFile=seed/qa/assets.local.json
~~~

plan은 실제 DB의 경계 근거와 소비 시드를 읽는 읽기 작업이다. 출력은 건수와 시작/입금/출금/종료 합계다. 연결 키·전체 계좌번호는 필요하지 않다.

## 되돌리기·보존

seedQaAssets의 reset/purge는 지정 dataset/persona/account의 QA_SYNTHETIC stream과 그 종속 자료만 제거한다. 가입·기기·PIN·계좌·기존 소비·SSAFY 원장을 보존한다. 모두 한 트랜잭션으로 처리하며 FK 검사를 끄지 않는다. 다시 apply로 적재할 수 있다.

기존 전체 사용자 reset/purge는 이력 stream이 있으면 삭제 시작 전에 거절한다. 실제 이력을 지우는 확장은 자동 승인 검토에서 거절되어 적용하지 않았다. 자산 전용 reset을 사용한다. 실제 이력이 남은 사용자의 전체 삭제를 지원하려면 별도 명시적 보존/삭제 정책 승인이 필요하다.

마이그레이션 롤백을 위해 DROP을 자동 실행하지 않는다. 기능 비활성화는 정기 동기화 중지와 QA 자산 전용 reset으로 제한한다. 원장을 삭제해도 금융망 원본은 초기화되지 않는다.

## 검증

격리 MySQL 테스트에서 원장 중복, 소유권, 무거래 기준 잔액, 이전 실행 결과 거절, 충돌 시 원본 보존, 외부 실패, 합성 0원 경계, 자산 전용 reset 보존을 확인한다. 테스트 결과와 미검증 범위는 MR 전달 문서에 기록한다.

## 앵커 테이블 제외

사용자 검토 승인에 따라 저장 테이블은 stream, transaction, sync_run, coverage의 4개다. 시작 잔액의 근거는 coverage의 opening_evidence와 opening_transaction_id/opening_coverage_id로 보존한다. 종료 잔액은 원장으로 재계산한다. 신규 마이그레이션은 아직 Merge·실제 환경 적용 전이므로 초안에서 앵커 생성을 제거했다. 기존 운영 테이블을 DROP하거나 데이터를 삭제한 변경은 없다.
