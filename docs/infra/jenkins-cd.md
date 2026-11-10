# Jenkins BE 승인 배포

관련 Task: S15P31E103-109. POS·모바일·AI 배포는 포함하지 않는다.
운영은 현재 수동 배포된 c6e77d4와 RDS를 사용한다. 이 문서를 추가했다고 CD가 활성화된 것은 아니다.

## 배포 계약

1. 별도 `yamu/ci-master` Job이 `*/master`의 기존 `Jenkinsfile`로 전체 CI를 실행한다.
2. CI 마지막 단계가 커밋·단일 boot JAR 경로·SHA-256을 기록한다. 실패·skip 게이트 뒤에만 산출물을 보관한다.
3. `yamu/cd`는 master 변경을 5분 폴링으로 감지해 `Jenkinsfile.cd`로 ci-master를 실행하고 성공한 빌드 번호를 고정한다. ci-master는 독립 폴링을 하지 않는다.
4. 사용자가 지정한 Jenkins `yamu` 계정이 승인한다. 30분 내 승인하지 않으면 중단한다. Jenkins 관리자도 승인할 수 있다. 다른 승인자를 추가하려면 코드 리뷰와 권한 검토가 필요하다.
5. CI 에이전트는 제한된 SSH 키로 빌드 번호만 전달한다. 운영 Docker 소켓·환경 파일은 전달하지 않는다.
6. 호스트의 root 소유 도구가 Jenkins 로컬 산출물과 SUCCESS, Git 원격의 현재 master, SHA-256을 검증한다. 준비 중 master가 바뀌어도 배포를 거부한다.
7. 현재 앱과 후보 JAR의 모든 Flyway SQL 리소스 이름·내용 해시가 같아야 한다. 마이그레이션 추가·변경·삭제는 별도 DB 배포 검토가 필요하다.
8. 동일 Java 런타임 이미지 ID에 검증한 JAR만 복사하고 네트워크 없이 빌드한다. RDS overlay와 고정된 Compose로 BE만 교체한다. Redis·MySQL 볼륨은 조작하지 않는다.
9. Compose health, 내부 actuator UP, 공개 HTTPS API ok를 확인한다. 실패하면 이전 이미지 ID로 BE를 재생성한다. 복구도 실패하면 Job을 실패로 종료하고 운영자가 조사한다. DB 역마이그레이션은 수행하지 않는다.
10. 성공한 이미지 ID를 `/etc/yamu-cd/active-image.json`에 원자적으로 저장한다. 이후 재기동에도 이 override가 필요하다.

## 최초 설치: 리뷰된 소스만 운영자 권한으로 설치

현재 master는 초기 커밋이므로 바로 배포할 수 없다. 본 MR과 필요한 앱 변경을 사람이 develop에 Merge하고, 별도 릴리스 절차로 master에 반영해야 한다. AI는 Merge하지 않는다.

운영자 Ubuntu에서 다음을 준비한다. 설치 전 기존 파일·계정·sudoers·authorized_keys 유무를 확인하고 덮어쓰지 않는다.

- `/opt/yamu/cd` 및 staging: root 소유, 일반 사용자 쓰기 금지, staging 700.
- 고정 Compose 두 파일은 리뷰된 `infra/compose.prod.yml`, `infra/compose.rds.yml`을 복사한다. 후보 저장소에서 임의 Compose·Dockerfile을 실행하지 않는다.
- root 소유 읽기 전용 저장소 `/opt/yamu/cd/repository`: GitLab origin을 설정하고 저장소 읽기 인증을 비밀 파일로 준비한다. `git ls-remote origin refs/heads/master`가 대화식 입력 없이 성공해야 한다. URL·명령 인자에 토큰을 넣지 않는다.
- `deploy.py` → `/usr/local/sbin/yamu-cd` (root:root, 755), `trigger.sh` → `/usr/local/sbin/yamu-cd-trigger` (root:root, 755).
- 설정 견본 → `/etc/yamu-cd/config.json` (root:root, 600). 경로·컨트롤러 이름을 실제 서버와 대조한다.
- 별도 SSH 사용자 `yamu-deploy`: 비밀번호 잠금, docker 그룹·일반 sudo 권한 없음. SSH 키는 운영자가 직접 만든다. 개인 키를 응답·로그·Git에 출력하지 않는다.
- root 관리 authorized_keys 경로를 sshd에 설정한다. 일반 사용자가 키 제한을 수정할 수 없어야 한다. 키 행은 `restrict,command="/usr/local/sbin/yamu-cd-trigger"`를 사용한다. 이 키는 인증된 ci-master 후보 배포 권한이 있으므로 운영 자격정보로 관리한다.
- sudoers에는 이 사용자에게 **인자 없는** `/usr/local/sbin/yamu-cd`만 NOPASSWD 허용한다. `SSH_ORIGINAL_COMMAND`만 env_keep로 전달한다. 예: `yamu-deploy ALL=(root) NOPASSWD: /usr/local/sbin/yamu-cd ""`. 반드시 `visudo -cf`로 확인한다. 셸·docker·임의 Python 실행을 허용하지 않는다.
- 에이전트의 `/etc/yamu-cd/known_hosts`에는 운영자가 별도 확인한 서버 호스트 키를 root 소유로 배치한다. StrictHostKeyChecking을 끄지 않는다.
- Jenkins SSH Credentials ID `yamu-cd-trigger`에 개인 키를 직접 등록한다. 운영 비밀번호·SSH 키를 채팅으로 주고받지 않는다.

CI 에이전트의 Jenkins 계정은 원래 신뢰된 팀 코드만 실행한다. 이 키를 읽을 수 있는 Jenkins 관리자·Job 편집자는 배포 권한을 가진 것으로 취급한다. 강제 명령이 임의 서버 명령을 막지만 Jenkins 승인 UI를 별도 보안 경계로 보장하지는 않는다. 승인 대상 Job/자격정보 편집 권한을 제한해야 한다.

## Jenkins Job

- `yamu/ci-master`: Pipeline script from SCM, 기존 Git URL·읽기 credentials, Branch `*/master`, Script Path `Jenkinsfile`. trusted master만 실행한다.
- `yamu/cd`: Pipeline script from SCM, Branch `*/master`, Script Path `Jenkinsfile.cd`. 활성화·최초 실행 후 `H/5 * * * *` SCM polling을 사용한다. 자동 시작 후에도 운영 배포는 승인 전 실행하지 않는다.
- 기존 `yamu/ci`의 develop CI는 유지한다. CD Job은 동시에 한 번만 실행하고 호스트 파일 잠금으로 다른 Job·운영자 동시 배포도 거부한다.
- master에 파일이 반영되기 전 Job을 활성화하지 않는다. CD 코드 변경도 운영 권한이므로 반드시 리뷰한다.
- 최초 실행은 CI와 승인 대기까지 진행하므로 사람이 릴리스 커밋을 확인한 뒤 수행한다. master에 알림 도우미까지 포함돼 있어야 한다. 최초 실행 전에는 Jenkinsfile의 폴링이 자동 등록되지 않는다.
- Mattermost 알림·비밀값 등록·검증은 [jenkins-notifications.md](jenkins-notifications.md)를 따른다.

## 재기동

기존 RDS 문서의 환경 파일 두 개와 고정 Compose 두 개 뒤에 `-f /etc/yamu-cd/active-image.json`을 추가한다. `config --quiet`로 검증한 후 `up -d --no-deps --no-build --pull never --wait backend`를 실행한다. 이 파일 없이 원래 APP_VERSION으로 재생성하면 이전 버전으로 돌아갈 수 있다. 현재 수동 릴리스 포인터는 CD 설치만으로 변경되지 않는다.

## 검증과 완료 기준

- 로컬 `python -m unittest discover -s infra/cd -p 'test_*.py'`: 커밋 불일치·JAR 변조·경로 탈출 거부, 마이그레이션 차이 탐지.
- Linux에서 `python3 -m py_compile infra/cd/*.py`, `sh -n infra/cd/trigger.sh`.
- Jenkins Declarative 검증, master CI 성공, 승인 타임아웃·거부 시 운영 미변경 확인.
- 잘못된 빌드 번호·실패한 CI·오래된 master·잘못된 해시·마이그레이션 변경·동시 호출 거부 확인.
- 별도 검증 환경에서 기동 실패→이전 이미지 복구·데이터 유지 검증 후, 사람이 승인한 master 릴리스로 실제 운영 배포한다.
- 완료 기록에는 후보 커밋·CI 번호·승인자·이미지 ID·health·복구 결과를 남긴다. 키·DB 환경·전체 명령 오류 출력은 남기지 않는다.

위 운영 설치·Jenkins 설정·실패 복구 실증이 끝나기 전 Task를 완료로 표시하지 않는다. 자동 배포 시 DB 변경을 금지하는 초기 범위이며 무중단 배포·새 Java 런타임 업데이트·POS 원자적 교체는 후속 작업이다.

## 시연 APK와 운영 BE 커밋 대조

1. 시연 릴리스의 `master` 커밋을 확정하고 같은 커밋으로 실연동 APK와 `ci-master` JAR을 빌드한다.
2. APK의 `build-manifest.json`에서 `variant=customerRelease`, `mock_only=false`, `source_revision`을 확인한다. 다운로드한 APK의 SHA-256과 크기를 매니페스트와 대조한다.
3. `ci-master` 성공 빌드의 `ci-manifest.json`에 있는 `revision`과 `ci-revision.txt`, APK의 `source_revision`, 승인한 `master` SHA가 모두 같은지 확인한다. 값이 다르면 시연 후보 확정을 보류하고 승인 커밋에서 해당 산출물을 다시 만든다.
4. 배포 후 운영 BE 이미지의 `org.opencontainers.image.revision`을 같은 SHA와 대조한다. 릴리스 기록에 APK 파일명과 SHA-256, `ci-master` 빌드 번호, 운영 BE 커밋을 남긴다.

2026-11-09 점검에서 확인한 실연동 APK는 `YAMU-demo-live-customer-66d3e61a.apk`였다.
S3 객체의 SHA-256과 크기가 매니페스트와 일치했다. APK 소스 `66d3e61`과 운영 BE `7251c16`은
커밋이 다르며, 두 커밋 사이 `fe/android` 소스 차이는 없었다. 이 APK의 실기기 실행 검증은 매니페스트에 기록되어 있지 않다.

## Q-11 공개 헬스체크와 배포 커밋 확인

공개 `GET /api/health`는 기존 `{"status":"ok"}` 응답을 유지한다. 버전과 커밋은 공개 응답에 추가하지 않는다.
배포 후 운영자는 [운영 대시보드](https://yamu.it.kr/monitoring/)의 `현재 배포 이미지 / 커밋` 패널에서
BE 컨테이너의 `revision`을 확인한다. 이 값은 컨테이너의 `org.opencontainers.image.revision` 라벨에서 수집한다.
표시된 SHA를 승인한 `master` SHA와 대조하고 OPS-08 기록에 남긴다. 값이 비어 있거나 다르면 배포 커밋 확인을 보류한다.

## OPS-08 배포 후 회귀 검증 기록

배포마다 운영 커밋, Jenkins 빌드 번호, 검증 시각과 각 결과를 남긴다. 결제 설정 저장까지 확인할 때는
지정 테스트 계정의 기존 값을 기록하고, 허용된 값으로 변경한 뒤 조회 결과를 확인하고 원래 값으로 복원한다.
인증 정보, 결제 식별자, 응답 전문은 기록에 포함하지 않는다.

### 이전 APK 실기기 결과 (2026-11-08~09)

팀원 보고 기준이다. 갤럭시 S10(SM-G973N), Android 12에서 11/8 `develop` 빌드인 `com.yamu.app` release `0.1.0`을 사용했다. 설치 시각은 11/8 18:03 KST이며 APK 파일명과 빌드 SHA는 미확인이다. 시험 시각은 11/8 21:50~23:20, 11/9 09:20 및 11:00~12:00 KST였다.

| 검증 | 이전 APK 결과 |
| --- | --- |
| 로그인 | 운영 테스트 계정으로 PIN 로그인 후 홈 화면에 진입했다 |
| 결제 카드 자동 선택 표시 | 카드 관리 화면에서 켜짐을 확인했다 |
| 결제 카드 자동 선택 저장 | 11/9 09:20에 `PATCH /api/users/me/payment-settings`가 HTTP 404로 실패했다. `7251c16b` 재배포 뒤 11:00~12:00 재시험에서 끄기 저장이 성공했고 기본 카드가 없을 때 안내가 표시됐다. 시험 직후 다시 켰으며 화면 재진입 후 값 유지는 미확인이다 |
| 가계부 등록·조회 | 매월 말일 반복 예정 지출과 1,000억 원 경계값을 등록하고 목록을 다시 열어 항목과 금액을 확인했다. 0원과 1,000억 원 초과는 차단됐고 시험 항목은 삭제했다 |

### 2026-11-09 운영 릴리스 검증

| 검증 | 2026-11-09 운영 결과 |
| --- | --- |
| 배포 출처 | `7251c16bd70f170036c2ed8034e2dd8ec3231d9d`, `ci-master` #1. 운영 BE 컨테이너 시작 시각 11:08:34 KST |
| 내부·공개 헬스체크 | `/actuator/health` HTTP 200, `UP`. 공개 `/api/health` HTTP 200, `ok` |
| Flyway 및 자동 선택 | `20261106.3` 성공 이력 1건, DB 기본값 켜짐, 배포 직후 기존 사용자 2명 모두 켜짐 |
| 결제 설정 인증 경계 | 미인증 `GET /api/users/me/payment-settings` HTTP 401 |
| POS 인증 경계 | 운영 BE에서 키 없는 조회는 HTTP 401. 운영 키를 넣고 존재하지 않는 시나리오로 조회하면 HTTP 404 |
| 인증 사용자 결제 설정 | 테스트 계정의 인증된 조회 HTTP 200 및 `data.autoSelect` 확인, 저장 후 재조회는 검증 대기 |

### 2026-11-10 운영 릴리스 `72679689`

| 검증 | 운영 결과 |
| --- | --- |
| 배포 출처 | 승인한 `master` `72679689edc2969d6be67dd44bc15bcd102c48f7`, `ci-master` #3. JAR SHA-256 `c5124b011bbd8d0e009a80e9eec04f7c1df37064d82168e5082de83d36868ce4`를 대조했다. 09:30:34 KST 전환 시작, 09:31:21 KST 배포 검증 완료 |
| RDS 복구 지점 | 전환 직전 09:30:31 KST에 `yamu-mysql`이 `available`, 자동 백업 보존 기간이 7일임을 확인했다. 최신 복구 가능 시각은 09:27:51 KST였다. 수동 스냅샷 `yamu-before-72679689-20261110`도 `available`이었다 |
| 롤백 준비 | 이전 `7251c16b` 이미지와 JAR을 보관하고 이전 운영 설정을 담은 롤백 Compose 구성을 검증했다. 격리 MySQL에서 신규 스키마로 이전 JAR 기동, 배포 전 논리 백업 복원, 신규 3건 재적용을 확인했다. AWS 시점 복원 실행은 미검증이다 |
| 헬스체크 | 운영 BE 컨테이너 `healthy`, 내부 `/actuator/health` `UP`, 공개 HTTPS `/api/health` `ok`. 두 경로 모두 HTTP 2xx |
| Flyway | 운영 RDS 성공 이력 23 → 26건. 기존 23건의 버전·파일명·체크섬·성공 상태를 대조했고 신규 `20261109.1`, `20261109.2`, `20261109.3`은 각각 성공 이력 1건씩 확인했다 |
| 운영 대시보드 | 09:31:34 KST에 `현재 배포 이미지 / 커밋` 패널의 데이터 원본 `yamu_service_info{service="backend"}`에서 `revision`이 승인한 `master` SHA와 일치했다 |

후보 BE는 `SPRING_FLYWAY_OUT_OF_ORDER=false`로 순차 적용했다. 롤백 설정은 전환 전 컨테이너의 값을 보존했다. 키와 외부 연동 설정을 대조했고 AI·Redis 컨테이너 ID도 유지됐다.

#### `72679689` 시연 APK 실기기 검증

팀원 보고 기준이며, 기기 모델·Android 버전·시험 시각은 보고에 포함되지 않았다.

| 검증 | 실기기 결과 |
| --- | --- |
| 운영 로그인 | 운영 테스트 계정으로 PIN 로그인 후 홈 화면에 진입했다 |
| 결제 카드 자동 선택 | 카드 관리 화면에서 켜짐을 확인했다. 설정 변경·저장 후 재조회는 미확인이다 |
| 가계부 등록·조회 | 항목 등록 후 목록에서 등록한 항목과 금액을 다시 확인했다 |

### 2026-11-08 16:14 KST POS 인증 점검

`INVALID_POS_KEY`는 `3490fc0` 배포 후 POS 인증을 확인하는 과정에서 발생한 예상된 HTTP 401로 판단했다.

- Nginx 접근 로그에서 16:14:54와 16:14:55 KST에 `GET /api/pos/payment-requests/{id}`가 각각 `401 → 404`를 반환했다. 각 쌍은 동일한 호출 출처와 동일한 요청 경로였고, 네 요청의 User-Agent는 모두 `Python-urllib`이었다.
- 당일 릴리스 검증 기록 `result.json`의 `api_keys_match`는 `true`였다. 키를 생략한 요청은 401, 설정된 키로 존재하지 않는 결제 요청을 조회했을 때는 404였다. 404는 POS 인증을 통과한 뒤 조회 대상이 없을 때의 응답이다.
- 당시 릴리스 설정과 롤백용 설정 사본 모두 POS 키가 정의된 환경 파일을 참조했다. 11/10 점검에서도 현재 운영 BE의 키와 해당 파일의 값이 일치했다.

접근 로그에는 `X-POS-Key` 헤더 값이 없어 16:14의 개별 401이 키 누락과 불일치 중 어느 쪽인지는 확인할 수 없다. 같은 경로의 후속 404와 배포 검증 기록을 근거로 운영 키 설정 장애의 증거는 확인되지 않았다.

## FSC-08 · FSC-09 운영 호스트 점검과 재부팅

2026-11-09 16:13 KST 점검 기준이다. **재부팅은 점검 시간 확정 후 진행한다.**

- 운영 키 파일은 root만 읽을 수 있고 `yamu-deploy`는 Docker 그룹에 속하지 않는다.
  운영 BE·AI와 Jenkins 컨트롤러에 호스트 Docker 소켓을 마운트하지 않았다.
- Jenkins는 Tailscale 주소에서 수신하고, 운영 BE와 모니터링의 호스트 포트는 루프백에 바인딩되어 있다.
  운영 RDS의 3306 인바운드는 단일 IPv4 주소로 제한되어 있다.
- 실행 중인 커널은 `7.0.0-1012-aws`이고, 새 커널 `7.0.0-1014-aws`가 설치되어 있다.
  `/run/reboot-required.pkgs`에는 새 커널, `linux-base`, `libc6`가 기록되어 있다.
- 현재 APT 목록에서 Ubuntu 보안 저장소의 미적용 패치는 0건이었다.
  전체 업그레이드 후보는 Ubuntu 35건, Docker 4건, Tailscale 1건이다.
  Docker·Tailscale 후보의 보안 공지는 별도 확인이 필요하다.

호스트 재부팅 중에는 운영 API·POS 웹·AI·Redis·Jenkins·모니터링과 SSH 접속이 일시 중단된다.
Docker·Nginx·Tailscale·모니터링 수집기는 부팅 시 시작하며 운영 컨테이너의 재시작 정책은 `unless-stopped`다.
운영 Redis에는 AOF와 데이터 볼륨이 설정되어 있다. POS 리허설 컨테이너 2개는 자동 재시작 대상에서 빠져 있다.

1. 점검 시간을 확정하고 진행 중인 결제·출금과 Jenkins 빌드·배포가 끝났는지 확인한다.
   SSH로 다시 접속할 수 없을 때 사용할 EC2 콘솔 접근 경로를 확보한다.
2. APT 목록을 갱신해 보안 업데이트를 재확인한다. Docker·Tailscale 업데이트는 데몬과 관리 접속에 미치는 영향을 별도로 검토한다.
   RDS의 최신 복구 가능 시각, 운영 이미지 리비전, Redis 상태를 기록한다.
3. 재부팅 후 `uname -r`로 새 커널이 실행 중인지, `/run/reboot-required`가 해소됐는지 확인한다.
   Docker·Nginx·Tailscale, 운영 BE·AI·Redis, Jenkins 에이전트, 모니터링의 복구 상태를 확인한다.
4. 공개 `/api/health`, POS 웹 접속, 테스트 계정의 결제 설정 조회를 검증한다.
   재부팅 전후 커널과 운영 리비전, 실제 중단 시간, 사용자 경로 검증 결과를 점검 기록에 남긴다.

## 2026-10-27 컨테이너 복구 검증

`test_compose_recovery.py`를 EC2 Linux에서 실행했다. 임의 UUID를 붙인 별도 Compose 프로젝트에서 포트 공개 없이 기존 Redis 이미지만 사용했다. 정상 후보 기동과 상태 저장을 확인한 뒤 후보 프로세스를 의도적으로 exit 42로 종료시켰다. 같은 `switch_backend` 흐름이 이전 설정을 복원했고 PONG 확인에 성공했다. 실패한 후보의 상태 저장은 실행되지 않았으며, 별도 저장 서비스의 컨테이너 ID와 표식 데이터가 유지됐다. 생성한 임시 프로젝트와 그 볼륨만 정리했다.

이는 실제 Docker Compose 기동 실패와 복구를 검증한 하네스다. 운영 BE·RDS는 변경하지 않았으며 Spring Boot/RDS를 포함한 전체 배포 도구와 Jenkins 승인·SSH 연결의 통합 검증은 남아 있다.

## 2026-10-27 Jenkins·제한 SSH 설정

MR !25가 develop의 `b40cb9d`로 Merge된 뒤 사용자의 승인을 받아 설정했다. master는 `9557739` 초기 커밋이므로 실제 배포를 실행하지 않았다.

- Jenkins 권한을 Project-based Matrix로 변경했다. `yamu`에 Overall/Administer를 유지하고 anonymous와 authenticated 전체 권한은 부여하지 않았다. 사용자 가입 허용은 꺼져 있다.
- 승인자는 사용자가 지정한 `yamu`다. 현재 자체 사용자 DB에 `yamu-release-managers` 그룹을 만들지는 않았다. 기존 Jenkinsfile.cd의 관리자 승인 예외로 `yamu`가 승인할 수 있다. 다른 승인자를 추가하려면 실제 그룹 연동 또는 리뷰된 submitter 변경이 필요하다.
- `yamu/ci-master`와 `yamu/cd`를 각각 `*/master`, `Jenkinsfile`과 `Jenkinsfile.cd`, 기존 저장소 읽기 인증으로 생성했다. 두 Job은 비활성이며 자동 trigger는 없다. 기존 develop CI는 활성 상태를 유지한다.
- root 소유 `/opt/yamu/cd/repository`와 저장소 읽기 전용 credential helper를 준비했다. 인증은 root 전용 600 파일에 저장하며 원격 URL과 명령 인자에는 포함하지 않는다. 원격 master·develop 조회에 성공했다.
- `yamu-deploy`는 비밀번호 잠금, sudo·docker 그룹 미가입이다. root 관리 authorized_keys와 강제 명령, publickey 전용 인증, TTY·포워딩 차단을 적용했다. sudo는 인자 없는 설치된 배포 도구만 허용하며 `visudo`와 `sshd -t`를 통과했다.
- 공개 authorized_keys는 root 소유 644, 상위 디렉터리는 755로 설정했다. 공개 키 파일을 SSH 사용자가 읽을 수 있어야 하며 쓰기는 허용하지 않는다. 개인 키·저장소 인증·CD 설정은 root 소유 600이다.
- 서버 자체 SSH 공개 호스트 키를 신뢰된 기존 SSH 연결에서 읽어 known_hosts를 만들었다. Rootless Docker의 분리된 `/etc` 경로 대신 `/opt/yamu/cd/known_hosts`를 에이전트의 `/etc/yamu-cd/known_hosts`에 읽기 전용으로 마운트했다. StrictHostKeyChecking은 유지한다.
- 마운트는 서버의 `/home/yamu-ci/source/infra/compose.ci-agent.yml`에 추가했다. 변경 전 파일은 root 전용 `/opt/yamu/cd/compose.ci-agent.before-cd.yml`에 보관했다. 향후 CI Compose를 저장소 원본으로 교체할 때 이 마운트를 유지해야 한다. 저장소 기본 CI 구성은 CD 설치 전에도 사용할 수 있도록 변경하지 않았다.
- 에이전트가 유휴임을 확인하고 일시적으로 작업 배정을 차단한 뒤 CI Rootless 에이전트만 재생성했다. root의 Docker Compose CLI에 CI 전용 소켓을 명시하고 `name=rootless`를 확인했다. CI 계정 환경에서는 Compose 플러그인이 발견되지 않아 기존 래퍼를 통한 재생성은 실패했으며, 이 경로의 추가 정비는 별도다. 이후 Jenkins Online과 작업 배정 복귀를 확인했다.

실제 SSH를 통해 임의 명령, 0번 빌드, 존재하지 않는 CI 빌드, 호스트 잠금 중 동시 호출의 거부를 확인했다. 배포 사용자의 Docker sudo와 인자가 추가된 sudo도 거부됐다. CI 에이전트에서 동일 키의 인증·호스트 키 검사·강제 명령 거부까지 확인했다. 검증용 에이전트 개인 키는 즉시 삭제했다.

운영 BE의 컨테이너 ID·이미지 ID·시작 시각이 작업 전과 같고 healthy임을 확인했다. RDS 설정이나 데이터에는 변경을 실행하지 않았다.

### Jenkins 인증과 비배포 통합 검증

사용자가 서버 내부 등록 스크립트를 직접 실행해 `yamu` 폴더의 SSH Credentials `yamu-cd-trigger`를 등록했다. 등록용 임시 개인 키의 삭제를 확인했다. 개인 키는 로그·채팅·Git에 출력하지 않았다.

설치된 Jenkins Declarative 파서로 Merge된 `Jenkinsfile.cd`의 검증을 통과했다. 별도 [yamu/cd-verification 빌드 #1](http://100.102.125.41:8081/job/yamu/job/cd-verification/1/console)은 SUCCESS다.

- 실제 `yamu-ci` 에이전트에서 `withCredentials(sshUserPrivateKey)`로 등록 키를 바인딩하고, StrictHostKeyChecking을 켠 SSH 요청을 실행했다. `id`, `0`, 존재하지 않는 `99999999` 빌드 요청이 각각 기대한 거부 경로에 도달했다.
- 별도 검증 파이프라인에서 5초 승인 타임아웃과 `yamu` 세션의 Abort를 확인했다. 두 경우 모두 승인 뒤 작업으로 진행하지 않았다. 이는 입력 중단 동작의 검증이며, 실제 CD Job의 30분 대기나 master CI를 포함한 전체 실행을 검증한 것은 아니다.
- 가상의 일반 authenticated principal에 Jenkins Administer와 CD Job Configure 권한이 없음을 Jenkins 권한 API로 확인했다. 실제 별도 사용자 로그인 검증은 아니다.
- 검증 후 `ci-master`, `cd`, `cd-verification`은 모두 비활성 상태다. 두 운영용 Job은 아직 빌드 이력이 없고, 검증 Job과 SUCCESS 이력은 증거로 보관했다. develop CI는 활성, 에이전트는 Online이다.
- 인증 등록 스크립트와 파서 검증용 임시 파일을 삭제했다. 최종 점검에서도 운영 BE ID·이미지·시작 시각은 동일하고, 내부 health는 UP, 공개 API는 ok다.

남은 릴리스 조건은 사람이 필요한 앱·CD 코드를 master에 반영한 뒤 master CI 성공, 실제 CD 승인 흐름, 검토된 후보의 BE 배포·health 확인을 수행하는 것이다. 실제 운영 배포와 RDS를 포함한 전체 경로는 미검증이므로 Task를 완료 처리하지 않는다. AI는 MR을 승인하거나 Merge하지 않았다.

## 2026-11-08 후속 준비: master 승인형 CD (S15P31E103-337)

아래 변경은 준비·격리 검증 단계다. 운영 배포와 Job 활성화는 하지 않았다.
현재 master는 초기 커밋이므로, 사람이 릴리스 MR로 검토한 파일을 master에 반영해야 한다.

### 실행 흐름과 승인

`master 변경 → yamu/cd(최대 약 5분 주기 감지) → yamu/ci-master 전체 검사 → yamu 승인 대기(30분) → BE 교체 → health → 완료/자동 복구`.
ci-master는 독립 polling을 하지 않는다. CD가 FULL_CI=true와 체크아웃한 40자리 RELEASE_REVISION을 전달한다.
ci-master의 RELEASE_REVISION이 비어 있거나 체크아웃 커밋과 다르면 테스트 시작 전에 실패한다.
성공 후보 산출물은 keepLog로 고정하며 승인 화면에 커밋과 빌드 번호를 표시한다.
관리자는 배포·복구 검증이 끝나거나 승인 요청이 취소·만료된 뒤, 진행 중인 CD가 참조하지 않는 후보의 보관을 해제한다.
대기 중 master가 바뀌면 호스트 검증에서 배포를 거절하므로 새 CD 후보를 검증·승인한다.
취소·승인 시간 초과·CI 실패 시 배포 단계에 도달하지 않는다. Jenkins 관리자는 일반 input 권한의 예외이므로
관리자 계정을 팀원에게 공유하지 않는다.

### 운영 키와 AI 보존

신규 호스트 도구는 active-image.json 전체를 깊은 복사하고 backend.image만 바꾼다.
env_file 경로, AI_BASE_URL, 기타 backend 환경변수, ai 서비스의 기존 이미지·옵션과 내부 네트워크를 보존한다.
키 원문은 JSON·로그·Git에 저장하지 않는다. 배포 전후 API·암호화·JWT 키와 AI 주소를 메모리 안에서만 비교하며
차이가 있으면 실패 처리한다. 실패 복구도 같은 설정에 이전 backend 이미지를 적용한다.
AI를 새로 빌드하거나 교체하지 않는다. AI 변경 배포는 별도 작업이다.
현재 키 원본은 Secrets Manager이고, BE는 비공개 staged env 파일을 통해 받는다.
Secrets Manager에서 키를 교체할 때 운영자가 staged env 파일을 갱신하고, 활성 override를 적용해 현재 BE를 먼저 재기동한다.
헬스체크와 키를 사용하는 연동을 확인한 뒤 CD를 시작한다. CD는 배포 전 컨테이너의 값을 비교 기준으로 사용한다.

active override가 없거나 잘못된 JSON이면 배포를 중단한다. 준비 도중 파일 내용이 달라져도 중단한다.
정상 기동과 내부·외부 health를 모두 확인한 후에만 새 전체 override를 원자적으로 확정한다.
DB 마이그레이션 파일 집합·내용이 현재 운영 JAR과 다르면 자동 CD를 거절한다.
DB 변경을 함께 승인하는 버튼이 아니며, 별도 백업·마이그레이션 절차가 필요하다.

### 사람 리뷰·머지 이후 최초 활성화 순서

1. 이 MR을 develop에 반영하고, 필요한 릴리스 변경을 사람이 master에 머지한다.
2. 검토·고정한 커밋의 infra/cd/deploy.py를 서버에서 확인한 후 기존 root 소유
   /usr/local/sbin/yamu-cd에 `sudo install -o root -g root -m 0755`로 설치한다.
   CI 작업 공간에서 root 도구를 자동 교체하지 않는다. 기존 도구 백업을 먼저 남긴다.
   /etc/yamu-cd/config.json, 전체 active-image.json, 키 파일은 그대로 유지한다.
3. 같은 검토 커밋의 infra/cd/activate-master.groovy를 Jenkins 관리자가 스크립트 콘솔에서 실행한다.
   기본 실행은 사전 확인만 하며 Job 설정을 바꾸지 않는다. master에 필요한 Jenkinsfile이 없으면 거절한다.
4. 사전 확인 후 콘솔에서 `binding.setVariable('APPLY_MASTER_CD', true)`를 먼저 설정하고
   같은 스크립트를 실행하면 기존 ci-master와 cd만 활성화한다. cd에 SCM polling을 등록하며 빌드를 직접 예약하지 않는다.
   다른 Job·자격정보·기존 SCM URL·브랜치는 바꾸지 않는다.
5. 첫 후보의 전체 CI 성공과 승인 대기 표시를 확인한다. Deploy는 운영 배포를 수행하므로 사람이 배포 시점에만 누른다.

스크립트는 master에 RELEASE_REVISION 연결 코드가 있는지, 두 Job의 경로·master 브랜치·진행/예약 여부를
먼저 확인한다. 관리자 콘솔 실행은 샌드박스 외 관리 작업이다. 파이프라인의 input 승인과는 별개다.
중간 설정 저장 실패 시 두 Job을 다시 비활성화하고 설정을 확인한 후 재실행한다.

### 준비 단계 검증 기록

- Python 배포 게이트 7개 통과: 후보 해시·커밋·경로, 마이그레이션 차이, 실패 복구,
  전체 override 보존, 비정상 설정 거절, 보호 환경변수 비교.
- 기존 변경 경로 CI 회귀 7개 통과.
- EC2의 무작위 임시 Compose 프로젝트에서 정상 기동·강제 실패·복구 통과.
  합성 키 3개와 AI 주소 유지, 내부 AI 연결 유지, 기존 AI·저장소 컨테이너 및 데이터 유지 확인.
  테스트 자원은 종료·삭제했다. 실제 API 키·RDS는 사용하지 않았다.
- Jenkins에 설치된 Groovy로 Jenkinsfile과 Jenkinsfile.cd 구문 검사 통과.
  Declarative 실행·승인 권한·polling의 실제 Job 검증은 활성화 후 필요하다.
- 운영 컨테이너 재시작, 운영 DB 변경, 운영 Job 활성화, 승인·머지는 실행하지 않았다.
