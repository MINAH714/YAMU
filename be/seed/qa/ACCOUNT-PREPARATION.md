# LIFE 세 계정의 오프라인 준비 — S15P31E103-550

이 도구는 이미 적재된 `qa-550-life-1/2/3`의 최인국·남윤주·박준수만 대상으로 합니다. 정상 가입의 1원 인증을 실행하지 않고, 승인된 QA 바인딩과 실제 기기의 서명으로 준비합니다. 일반 사용자 가입 정책을 바꾸거나 인증 성공 API·토큰을 추가하지 않습니다. `identity_verified_at`은 이 QA 준비 시각이며 실제 본인인증/1원 송금의 증거가 아닙니다. `user_device_event.reason=QA550_OFFLINE_PREP:<bindingDigest>`로 예외 출처를 구분합니다.

김경린 님의 BE 검토와 운영 작업 승인을 받은 뒤 실행합니다. CLI 추가만으로 운영 계정이 바뀌지 않습니다. 배치 환경은 **`BILLING_JOBS_ENABLED=false`를 유지**합니다. 이 문서에서는 실제 금융망 조회·회원/계좌 생성·입금·출금·이체·청구 출금·환급 배치를 실행하지 않습니다. 시작 자금 225만 원을 넣거나 보정거래를 만들지 않습니다. 실제 잔액·원본 입출금 이력의 준비는 별도 작업입니다.

## 변경 범위와 보호 조건

- 세 원본 example 파일을 기존 운영 키로 암호화된 바인딩에 결합합니다. 기존 `qa_seed_dataset.input_hash`, 소유권, 이름·생년월일·휴대폰 해시·금융망 식별자, 4장 카드의 상품/발급 계좌가 모두 일치해야 합니다. 원본 변경 시 중단하며 `reset/purge`로 우회하지 않습니다.
- 처음에는 세 사용자 모두 PENDING, PIN/본인인증 시각 없음, 연결 계좌/기기 없음, 4장 카드 PENDING, enrich 이전이어야 합니다. 진행 중인 정상 가입이 있으면 담당자가 먼저 상태를 정리합니다. 기존 PIN·계좌·기기를 덮어쓰지 않습니다.
- 실제 설치 3개의 서로 다른 P-256 공개키·설치 UUID·대상 사용자 번호에 대한 서명을 검증합니다. 새 등록 서명은 발급 후 24시간 이내이며 미래 오차는 120초까지입니다. `plan`으로 대상과 공개키 지문을 확인합니다.
- 동일 QA advisory lock과 사용자/카드/계좌 행 잠금을 사용합니다. 세 사용자 모두 사전 검사한 후 한 트랜잭션에서 계좌 9개, 기존 카드 12장 연결, 기기 3개, PIN 해시와 ACTIVE 상태를 만듭니다. 기존 구매·혜택·한도·가입일은 보존합니다. 오류 시 세 사용자 모두 롤백합니다.
- 재실행은 원래 기기·준비 이벤트·계좌·카드·동일 PIN을 확인하고 쓰기를 생략합니다. 바뀐 PIN 또는 기기는 거절합니다. 준비 후 `verify`와 복구에는 만료된 원래 증명도 사용할 수 있습니다. 기기 교체/계정 초기화 명령은 없습니다.
- 금융망 클라이언트나 Spring 앱을 시작하지 않는 명시적 JDBC CLI입니다. 일반 `test/check/build`에서는 실행되지 않습니다. 정상 로그인에서는 기존 PIN·일회용 challenge·기기 서명 검증을 그대로 사용합니다.

## 실제 휴대폰 준비

기기 자료는 [Android 도구 안내](../../../fe/android/tools/qa-account-setup/README.md)에 따라 준비합니다. 에뮬레이터의 키를 운영에 등록하지 않습니다. PIN·개인키·운영 암호화 키를 JSON 증명에 넣지 않습니다. 증명에는 대상 계정 번호, 설치 UUID, 공개키, 일회용 nonce, 시각, 서명만 들어갑니다.

각 사용자는 별도의 실제 휴대폰 설치 또는 독립적인 Android 사용자/업무 프로필을 사용합니다. 앱은 설치당 한 기기 번호를 저장하므로 로그아웃만으로 다른 사람의 계정으로 전환할 수 없습니다. 한 설치를 초기화해 세 사람을 번갈아 등록하지 않습니다.

공개 자료 세 개를 저장한 뒤 다음 형태의 `devices.local.json`을 권한 제한된 작업 폴더에 만듭니다. `devices`에는 각 파일의 JSON 객체를 수정 없이 하나씩 넣습니다.

```json
{"version":1,"devices":["각 휴대폰에서 저장한 JSON 객체 3개로 대체"]}
```

위 코드는 구조 안내이며 실행용 증명이 아닙니다. 사용자 번호는 실행 직전 기존 `qa_seed_member` 조회 결과로 대조합니다. 현재 운영 적재 번호는 11·12·13이지만 고정값으로 추정해서 다른 DB에 사용하지 않습니다.

## 실행 전 점검

1. 대상 세 계정의 앱 접속·정상 가입·POS 구매를 잠시 중단하고 다른 세션의 변경을 확인합니다. 일반 운영 데이터는 수정하지 않습니다. 출금 배치는 끈 상태로 유지합니다.
2. 현재 코드/스키마/카탈로그와 기존 데이터 건수·기준일을 조회하고 승인된 복구용 일관성 백업을 보관합니다. 기존 세 계정의 구매·혜택·카드 ID를 기록합니다. 전체 백업을 자동 복원하는 동작은 없습니다.
3. `environment.example.txt`와 같은 기존 운영 암호화/HMAC/PIN pepper 및 DB 접속 환경변수를 메모리에서 공급합니다. 키를 새로 만들지 않습니다. `QA_SEED_ENCRYPTED_BINDINGS_FILE`은 해당 운영 키로 검증된 파일을 가리켜야 합니다. 금융망 비밀값·PIN은 명령행/로그에 넣지 않습니다.
4. 폴더 700, 파일 600의 승인된 작업 경로를 사용합니다. 새 receipt 경로를 지정합니다. 기존 파일·심볼릭 링크·공유 권한 폴더는 거절합니다. Windows에서는 작업 폴더의 사용자 ACL을 별도로 제한합니다.

## plan → prepare → enrich → verify

`be` 폴더 기준 명령입니다. Linux에서는 `./gradlew`를 사용합니다. 운영에서는 승인된 JAR의 runtime classpath로 `com.yamu.be.seed.qa.QaAccountPrepareCli`를 직접 실행할 수도 있습니다. Gradle에 운영 비밀값을 인자로 넘기지 않습니다.

```powershell
# 읽기 전용: 대상 번호, 공개키 지문, manifestDigest 확인
.\gradlew.bat -I seed/qa/qa.init.gradle prepareQaAccounts -PqaPrepareCommand=plan '-PqaDeviceManifest=<보호된 manifest 경로>'

# 승인 후에만: QA_SEED_MAINTENANCE=true,
# QA_ACCOUNT_PREPARE_APPROVAL=<검토한 plan 결과의 manifestDigest>, BILLING_JOBS_ENABLED=false
.\gradlew.bat -I seed/qa/qa.init.gradle prepareQaAccounts -PqaPrepareCommand=prepare '-PqaDeviceManifest=<동일 manifest 경로>' '-PqaPrepareReceipt=<새 보호된 receipt 경로>'

# 준비 상태 읽기 전용 검사. PIN 입력 없음
.\gradlew.bat -I seed/qa/qa.init.gradle prepareQaAccounts -PqaPrepareCommand=verify '-PqaDeviceManifest=<동일 manifest 경로>'
```

PIN은 실제 Java 콘솔에서는 표시 없는 `readPassword`로 세 번 입력합니다. Gradle/컨테이너에서 콘솔이 없으면 프로세스의 **표준입력 파이프**로만 세 dataset→PIN의 JSON을 전달하고 `QA_ACCOUNT_PINS_STDIN=true`를 명시합니다. 입력 도구는 보안 프롬프트를 사용하고, 출력/임시 파일/셸 기록/명령행/환경변수에 PIN을 넣지 않아야 합니다. 파이프는 JSON 전송 후 EOF로 닫습니다. 기존 6자리 PIN 정책(반복·연속 숫자 거절)을 공유합니다. 채팅으로 PIN을 받지 않습니다.

PowerShell 7의 `prepare-accounts.ps1 -JarPath <검토한 bootJar> -DeviceManifest <manifest> -ReceiptPath <새 receipt>`는 PIN을 확인 입력까지 `Read-Host -AsSecureString`으로 받고, 자식 Java 프로세스의 표준입력으로만 전달합니다. DB/암호화 환경은 승인된 접속 구성을 상속합니다. 이 스크립트는 SSH 전송·운영 자격증명 획득을 대신하지 않습니다. 실행 PC가 승인된 운영 DB 경로를 사용하지 않는다면 운영 서버의 Java 콘솔 등 별도 승인된 입력 경로를 사용합니다.

`prepare`는 필수 receipt에 기기 번호와 공개키 지문만 씁니다. DB 커밋 뒤 파일 저장이 실패할 수 있으므로 실패했다고 다시 초기화하지 않습니다. `verify`에 **다른 새 receipt 경로**를 지정해 원래 기기 매핑을 복구하고 검사합니다. stdout에도 비밀값 없는 결과가 나오지만 운영 로그 접근은 제한합니다. 오류는 코드/예외 종류만 출력합니다.

준비 후 같은 원본+바인딩으로 기존 `seedQa`의 `enrich`, `verify`를 **각 dataset마다** 실행합니다([LIFE-HANDOFF](LIFE-HANDOFF.md)). 이 단계도 maintenance와 배치 OFF를 유지합니다. 운영 접속 환경에서 다른 seed/reset/purge/asset-funding 명령을 섞지 않습니다. `enrich`는 사용자별 트랜잭션이므로 일부 실패 시 성공한 사용자를 지우지 말고 실패한 사용자만 원인 확인 후 기존 멱등 명령으로 재개합니다.

최종 검사는 사용자별 카드 4장·연결 계좌 3개, 11월 확정 청구 896,500원·12월 초기 누적 청구 420,000원, 운영 문구 알림, 구매·혜택 보존, 타 사용자 소유권 혼입 0건입니다. 이후 실제 앱에서 새로 구매하면 청구가 추가되므로 초기 합계와 분리해 확인합니다. 이 도구의 준비 완료는 실제 금융망 잔액이나 모든 앱 화면의 검증 완료를 뜻하지 않습니다.

receipt를 해당 실제 설치에 가져온 후 정상 PIN 로그인을 수행합니다. 최종 APK로 업데이트하고 홈·잔고예보·소비 분석·실적 관리·마이페이지를 각각 확인합니다. 금융망 원본/잔액이나 AI 등 미연결 상태가 있으면 별도로 기록하고 임의 데이터로 숨기지 않습니다. 출금 배치를 다시 켜는 단계는 없습니다.
