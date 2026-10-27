# Jenkins BE 승인 배포

관련 Task: S15P31E103-109. POS·모바일·AI 배포는 포함하지 않는다.
운영은 현재 수동 배포된 c6e77d4와 RDS를 사용한다. 이 문서를 추가했다고 CD가 활성화된 것은 아니다.

## 배포 계약

1. 별도 `yamu/ci-master` Job이 `*/master`의 기존 `Jenkinsfile`로 전체 CI를 실행한다.
2. CI 마지막 단계가 커밋·단일 boot JAR 경로·SHA-256을 기록한다. 실패·skip 게이트 뒤에만 산출물을 보관한다.
3. `yamu/cd`는 `Jenkinsfile.cd`로 ci-master를 실행하고 성공한 빌드 번호를 고정한다.
4. Jenkins `yamu-release-managers`에 속한 사람이 승인한다. 30분 내 승인하지 않으면 중단한다. 해당 이름은 실제 권한 영역에 등록해야 하며 Jenkins 관리자도 승인할 수 있다.
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
- `yamu/cd`: Pipeline script from SCM, Branch `*/master`, Script Path `Jenkinsfile.cd`. 자동 trigger는 없으며 Build Now로 시작한다.
- 기존 `yamu/ci`의 develop CI는 유지한다. CD Job은 동시에 한 번만 실행하고 호스트 파일 잠금으로 다른 Job·운영자 동시 배포도 거부한다.
- master에 파일이 반영되기 전 Job을 활성화하지 않는다. CD 코드 변경도 운영 권한이므로 반드시 리뷰한다.

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
