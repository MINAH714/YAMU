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
# 2026-11-08 후속 준비: master 승인형 CD (S15P31E103-337)

아래 변경은 준비·격리 검증 단계다. 운영 배포와 Job 활성화는 하지 않았다.
현재 master는 초기 커밋이므로, 사람이 릴리스 MR로 검토한 파일을 master에 반영해야 한다.

## 실행 흐름과 승인

`master 변경 → yamu/cd(최대 약 5분 주기 감지) → yamu/ci-master 전체 검사 → yamu 승인 대기(30분) → BE 교체 → health → 완료/자동 복구`.
ci-master는 독립 polling을 하지 않는다. CD가 FULL_CI=true와 체크아웃한 40자리 RELEASE_REVISION을 전달한다.
실제 ci-master 체크아웃 커밋이 다르면 테스트 시작 전에 실패한다. 성공 후보 산출물은 keepLog로 고정하며,
승인 화면에 커밋과 빌드 번호를 표시한다. 보관된 후보는 사용 종료 후 관리자가 보관 해제한다.
대기 중 master가 바뀌면 호스트 검증에서 배포를 거절하므로 새 CD 후보를 검증·승인한다.
취소·승인 시간 초과·CI 실패 시 배포 단계에 도달하지 않는다. Jenkins 관리자는 일반 input 권한의 예외이므로
관리자 계정을 팀원에게 공유하지 않는다.

## 운영 키와 AI 보존

신규 호스트 도구는 active-image.json 전체를 깊은 복사하고 backend.image만 바꾼다.
env_file 경로, AI_BASE_URL, 기타 backend 환경변수, ai 서비스의 기존 이미지·옵션과 내부 네트워크를 보존한다.
키 원문은 JSON·로그·Git에 저장하지 않는다. 배포 전후 API·암호화·JWT 키와 AI 주소를 메모리 안에서만 비교하며
차이가 있으면 실패 처리한다. 실패 복구도 같은 설정에 이전 backend 이미지를 적용한다.
AI를 새로 빌드하거나 교체하지 않는다. AI 변경 배포는 별도 작업이다.
현재 키 원본은 Secrets Manager이고, BE는 비공개 staged env 파일을 통해 받는다.
이 작업은 Secrets Manager 값을 자동 갱신하는 기능을 추가하지 않는다.

active override가 없거나 잘못된 JSON이면 배포를 중단한다. 준비 도중 파일 내용이 달라져도 중단한다.
정상 기동과 내부·외부 health를 모두 확인한 후에만 새 전체 override를 원자적으로 확정한다.
DB 마이그레이션 파일 집합·내용이 현재 운영 JAR과 다르면 자동 CD를 거절한다.
DB 변경을 함께 승인하는 버튼이 아니며, 별도 백업·마이그레이션 절차가 필요하다.

## 사람 리뷰·머지 이후 최초 활성화 순서

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

## 준비 단계 검증 기록

- Python 배포 게이트 7개 통과: 후보 해시·커밋·경로, 마이그레이션 차이, 실패 복구,
  전체 override 보존, 비정상 설정 거절, 보호 환경변수 비교.
- 기존 변경 경로 CI 회귀 7개 통과.
- EC2의 무작위 임시 Compose 프로젝트에서 정상 기동·강제 실패·복구 통과.
  합성 키 3개와 AI 주소 유지, 내부 AI 연결 유지, 기존 AI·저장소 컨테이너 및 데이터 유지 확인.
  테스트 자원은 종료·삭제했다. 실제 API 키·RDS는 사용하지 않았다.
- Jenkins에 설치된 Groovy로 Jenkinsfile과 Jenkinsfile.cd 구문 검사 통과.
  Declarative 실행·승인 권한·polling의 실제 Job 검증은 활성화 후 필요하다.
- 운영 컨테이너 재시작, 운영 DB 변경, 운영 Job 활성화, 승인·머지는 실행하지 않았다.
