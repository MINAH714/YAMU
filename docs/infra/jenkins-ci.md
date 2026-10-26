# GitLab 연동 및 Jenkins CI

관련 Task: S15P31E103-108. CI만 구성하며 운영 배포는 Task 109에서 별도로 다룬다.

## 실행 흐름

`Jenkinsfile`은 GitLab 소스를 체크아웃하고 아래 작업을 순서대로 실행한다.

1. Java 17·Node 24·Python 3·Docker 사용 가능 여부 검사.
2. BE `clean test`. Testcontainers가 테스트용 MySQL을 생성한다.
3. JUnit XML 검사: 결과 없음·실행 테스트 0개·실패·건너뜀 중 하나라도 있으면 실패.
4. BE `bootJar`.
5. POS `npm ci`, lint, build. API 주소는 `/api`.
6. 모바일 `npm ci`, TypeScript 검사. APK·Expo 배포는 포함하지 않는다.
7. 소스 커밋 SHA·JAR·POS dist를 Jenkins 산출물로 보관.

실패 뒤 단계는 실행하지 않으며 운영 서버·RDS에 배포하지 않는다. 테스트 결과는 Jenkins JUnit 화면에 보관한다.
AI는 현재 자동 테스트가 없는 상태여서 이 CI에 포함하지 않는다. Python은 JUnit XML 검사용이며 AI의 Python 3.12 실행 환경을 뜻하지 않는다.
현재 Git에 저장된 Gradle wrapper는 CRLF이므로 CI 작업공간에서만 LF로 정규화한다. BE 원본 파일은 변경하지 않는다.

## 에이전트 구성과 권한

컨트롤러는 기존 `infra/compose.jenkins.yml`을 유지한다.
별도 일반 계정 `yamu-ci`의 Rootless Docker에서 `infra/compose.ci-agent.yml`로 에이전트를 실행한다.
에이전트는 서버의 Tailscale 주소를 통해 기존 Jenkins에 연결한다. 두 Docker 엔진의 네트워크는 공유하지 않는다.
WebSocket을 사용하므로 호스트에 50000 포트나 새 관리자 포트를 열지 않는다.

- Jenkins remoting: Java 21. 애플리케이션 빌드·테스트: Java 17.
- Node 24, npm, Git, Docker CLI, Python 3을 이미지에 포함한다.
- 에이전트 메모리 4GiB·CPU 2개, 실행기 1개, 같은 Job의 빌드는 대기열로 직렬화한다.
- DB 테스트용 MySQL·Ryuk은 CI 전용 Rootless Docker가 생성한다. 에이전트의 4GiB 제한 밖이지만 CI 사용자 전체의 6GiB·CPU 200%·TasksMax 4096 제한에는 포함된다.
- 에이전트에 운영 `.env.prod`, SSH 키, Jenkins 설정 볼륨을 직접 마운트하지 않는다.
- CI 계정에는 sudo·docker 그룹 권한을 주지 않는다. 운영 `/var/run/docker.sock` 대신 CI 계정의 `/run/user/<UID>/docker.sock`만 전달한다.
- 에이전트는 Rootless Docker 안에서 UID 0으로 실행한다. 이 UID는 호스트의 일반 CI 계정에 매핑되며 호스트 root가 아니다. 자기 계정의 Docker·파일에는 접근할 수 있다.
- 신뢰하는 팀 저장소의 검토된 코드만 실행한다. 외부 fork/MR의 코드를 자동 실행하지 않는다. 같은 서버의 커널·네트워크·디스크는 공유하므로 별도 VM 수준의 격리는 아니다.

실행 래퍼와 CI 사전 검사는 Rootless 여부를 검사하고 운영 Docker 사용을 거부한다. 소켓이 없거나 DB 테스트가 skip되면 성공시키지 않는다.
래퍼는 서버 Tailscale IPv4도 자동 조회한다. Testcontainers의 호스트는 이 주소로, Ryuk이 마운트할 소켓은 CI 사용자 소켓의 실제 호스트 경로로 지정한다.
Rootless 환경에서 `host.docker.internal:host-gateway`와 운영 소켓 기본 경로를 그대로 사용하면 각각 테스트 포트 접속 실패와 Ryuk 종료가 발생한다. Ryuk을 비활성화해 우회하지 않는다.
Testcontainers 테스트 포트는 임시 호스트 포트를 사용한다. 서버 보안 그룹에서 외부에 해당 포트 범위를 열지 말고 실제 외부 접근 차단을 확인한다.

## Jenkins 노드 준비

Tailscale로 기존 Jenkins에 로그인한다. 초기 비밀번호나 관리자 계정을 채팅에 공유하지 않는다.

1. Manage Jenkins → Nodes → New Node → `yamu-ci`, Permanent Agent.
2. Executors: `1`, Remote root: `/home/jenkins/agent`, Labels: `yamu-ci`.
3. Usage: 이 노드에 맞는 label이 지정된 Job만 실행.
4. Launch method: `Launch agent by connecting it to the controller`.
5. 저장 후 노드 화면에서 secret을 확인한다. 비밀값은 서버 환경 파일에 직접 입력한다.
6. Manage Jenkins → Nodes → Built-In Node의 실행기를 `0`으로 설정한다. 빌드는 전용 에이전트에서만 실행한다.

## Rootless Docker 최초 준비

Ubuntu 24.04와 cgroup v2 기준이다. 검토한 저장소에서 다음을 실행한다.

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends uidmap slirp4netns
sudo sh infra/ci/setup-rootless.sh
```

기존 Docker 패키지에 `docker-ce-rootless-extras`와 `dbus-user-session`이 필요하다. 없으면 설치 계획을 먼저 확인한다.
스크립트는 CI 계정과 subordinate UID/GID, 사용자 Docker 서비스, CI 전용 자원 제한을 준비한다.
`--force`는 기존 운영 Docker와 병행 설치하기 위한 옵션이며 운영 서비스를 정지·교체하지 않는다.
Ubuntu AppArmor의 user namespace 제한을 전역 해제하지 않는다. 공식 deb 패키지의 RootlessKit 프로필을 사용한다.
로그인 유지(linger)와 사용자 Docker 서비스 enable을 설정하지만 실제 서버 재부팅 검증은 별도다.

## EC2 에이전트 실행

리뷰한 CI 소스가 서버에 준비되어 있어야 한다. 새 브랜치를 사용할 때는 GitLab에 해당 브랜치가 있는지 먼저 확인한다.
CI 소스는 CI 계정이 소유하는 디렉터리에 준비한다. 운영 `.env`·SSH 키를 복사하지 않는다.
다음 명령은 `sudo -iu yamu-ci`로 전환한 뒤 CI 소스 루트에서 실행한다. 기존 환경 파일을 덮어쓰지 않는다.

```bash
umask 077
test -e infra/.env.ci-agent || cp infra/.env.ci-agent.example infra/.env.ci-agent
nano infra/.env.ci-agent
chmod 600 infra/.env.ci-agent
git check-ignore infra/.env.ci-agent
```

`JENKINS_AGENT_SECRET`에는 노드 화면의 secret, `JENKINS_URL`에는 `http://서버-Tailscale-IP:8081`을 입력한다.
소켓 경로는 실행 래퍼가 현재 CI 계정의 UID로 설정하므로 환경 파일에 넣지 않는다.
GitLab 토큰·DB 비밀번호는 이 파일에 넣지 않는다. 파일을 채팅으로 보내지 않는다.

```bash
sh infra/ci/rootless-compose.sh config --quiet
sh infra/ci/rootless-compose.sh build agent
sh infra/ci/rootless-compose.sh up -d agent
sh infra/ci/rootless-compose.sh ps
```

각 단계 성공 후 다음 단계로 진행한다. `config` 전체 출력은 secret을 노출하므로 `--quiet`를 유지한다.
Jenkins Nodes에서 `yamu-ci`가 Online인지 확인한다. `ps`의 실행 중 표시는 Jenkins 연결 성공을 의미하지 않는다.
컨트롤러 이미지·설정 볼륨과 운영 Compose는 이 명령으로 교체되지 않는다.

이미지는 계열 태그를 사용한다. 최초 빌드에서 실제 digest·도구 버전을 기록하고, 업데이트 때 재검증한다.
에이전트의 작업 볼륨에는 Gradle·npm 캐시가 남는다. 빌드 시작·종료 때 작업공간을 정리하지만 캐시 용량은 별도 관리한다.
운영 중 Docker 전체 prune이나 볼륨 일괄 삭제는 하지 않는다.

## GitLab Credentials와 Job

필요 플러그인: Pipeline(Declarative 포함), Git, Credentials, JUnit, Timestamper. 추천 플러그인 설치 여부와 활성 상태를 확인한다.
GitLab 전용 Webhook 플러그인은 이 방식에 필요하지 않다.

1. GitLab에서 저장소 읽기 전용 `read_repository` 토큰을 준비한다. 프로젝트 토큰이면 저장소를 읽을 수 있는 역할도 필요하다.
2. Jenkins의 이 Job용 Folder에 Credentials를 추가한다. 종류 `Username with password`, ID 예시 `gitlab-yamu-read`.
3. Username은 발급한 토큰 유형에 맞는 사용자명, Password는 토큰을 직접 입력한다. 토큰을 URL·Jenkinsfile·명령 인자에 넣지 않는다.
4. `yamu` Folder에 일반 **Pipeline** Job `ci`를 만든다(전체 이름 `yamu/ci`). Multibranch/Fork 자동 검색은 이번 구성에 포함하지 않는다.
5. Definition: `Pipeline script from SCM`, SCM: Git.
6. Repository URL: `https://lab.ssafy.com/s15-final/S15P31E103.git`, Credentials: 위 ID.
7. 검증 단계 Branch Specifier: `*/chore/infra/S15P31E103-108-jenkins-ci`, Script Path: `Jenkinsfile`.
8. 최초에는 Build Now로 수동 실행한다. Jenkinsfile이 한 번 실행된 이후 `H/5 * * * *` SCM polling이 등록된다.
9. MR 리뷰·Merge 후 Branch Specifier를 `*/develop`으로 변경한다. Merge 전에는 develop에 Jenkinsfile이 없으므로 변경하지 않는다.

폴링은 Jenkins에서 GitLab로 나가는 연결이다. Tailscale 관리자 화면을 공인망에 공개할 필요가 없다.
SCM polling은 Push 이벤트 즉시 실행이 아니며 최초 Job 실행 전에는 Jenkinsfile의 trigger도 등록되지 않는다.
허용된 작업 브랜치 검증과 develop만 실행하고, 운영 master 배포 조건은 CD 작업에서 확정한다.

## 완료 확인

- Jenkins 빌드가 SUCCESS이고 모든 Stage가 통과해야 한다.
- JUnit의 전체 테스트 수가 0보다 크며 실패·skip이 0이어야 한다. Flyway·실제 MySQL 테스트도 포함한다.
- 보관 산출물에 커밋 SHA, BE JAR, POS index.html/assets가 있어야 한다.
- 다음 변경을 감지한 폴링 실행, 빌드 연타 시 직렬 실행을 확인한다.
- 검증용 브랜치에서 의도적인 테스트/검사 실패를 넣고 산출물 보관 단계까지 가지 않는지 확인한 후 해당 변경을 되돌린다.
- 이 CI의 성공은 운영 배포·RDS 전환·AI 동작 검증을 뜻하지 않는다.

## 검증 기록

2026-10-26, 최신 `develop`의 `b6bac1f`를 기준으로 로컬 Docker Desktop에서 확인했다.

- 에이전트 이미지 빌드 성공. 애플리케이션 Java 17.0.20.1, Node 24.22.1, npm 11.19.1, XML 검사용 Python 3.13.5.
- 최초 운영 소켓 공유 초안의 Compose 설정 검사는 성공했다. 이후 Rootless 방식으로 변경했으므로 아래 서버 검증과 구분한다.
- 결과 게이트의 통과·skip·실패·오류·빈 결과·깨진 XML·누락·다른 XML의 skip 전파 시나리오 검증 성공.
- BE `clean test bootJar` 성공. 22개 테스트, 실패 0, skip 0. 실제 MySQL 기반 BaseTimeEntityTest 3개 포함.
- POS lint 경고·오류 0, TypeScript 및 Vite 운영 빌드 성공.
- 모바일 `npm ci` 및 TypeScript `--noEmit` 검사 성공.
- EC2에서 기존 `yamu-ci_default` 네트워크와 Git·Declarative Pipeline·JUnit·Timestamper 플러그인 파일의 존재 확인. 서버 Docker 29.9.2. 플러그인 파일 존재만으로 Jenkins 기능 실행까지 검증한 것은 아니다.

로컬 검증은 Jenkins Job에서 실행한 결과가 아니다. 노드 연결·SCM Credentials·Jenkinsfile 문법 검사·Job 최초 실행·폴링·연타·실패 경로의 실제 Jenkins 검증은 서버 적용 후 확인해야 한다.

같은 날 EC2에서 Rootless 구성을 적용했다.

- `yamu-ci` 일반 계정 생성. sudo·docker 그룹 미가입, 운영 Docker 소켓 접근 거부 확인.
- Docker 29.9.2 Rootless 실행, `SecurityOptions`의 `name=rootless`와 systemd cgroup 드라이버 확인.
- CI 사용자 slice의 MemoryMax 6GiB, CPUQuota 200%, TasksMax 4096 확인.
- linger와 사용자 Docker 자동 시작 설정. 실제 호스트 재부팅은 미검증.
- Rootless 에이전트 이미지 빌드 성공, 에이전트 내부에서 기존 Jenkins 로그인 경로 HTTP 응답 확인.
- 운영 Jenkins·BE·Redis·MySQL은 기존 컨테이너가 계속 healthy인 것을 확인했다.
- Rootless 환경에서 BE 22개 테스트 모두 통과(skip 0), JAR 생성, POS lint·빌드, 모바일 타입 검사 성공.
- 최초에는 Ryuk의 소켓 경로와 테스트 포트 접속 주소 차이로 DB 테스트 3개가 skip됐다. 실제 Rootless 소켓 경로와 서버 Tailscale IPv4를 지정한 뒤 통과했다. API 버전 강제 지정이나 Ryuk 비활성화는 사용하지 않는다.
- Rootless Compose의 정상 환경값 허용과 필수값 누락 거부 확인.
- 사용자 승인 후 `yamu-ci` 노드를 등록하고 WebSocket으로 연결했다. 에이전트 로그의 Connected와 Jenkins Nodes의 online을 확인했다. 실행기 1개, 라벨 일치 작업만 허용한다.
- 노드 인증키는 서버 CI 계정 소유의 600 권한 환경 파일에 저장했고 전송용 임시 파일은 삭제했다. 인증키는 저장소·채팅·명령 인자에 포함하지 않았다.
- Built-In Node 실행기를 0으로 저장했다. `yamu/ci` Job의 GitLab URL·작업 브랜치·Jenkinsfile 경로와 동시 실행 제한을 설정했다.
- Job은 비활성 상태로 보관했다. GitLab read_repository Credentials 등록, CI 코드 커밋·Push, Job Credentials 선택·활성화·첫 실행이 남아 있다.

## 참고 자료

- [Jenkins Pipeline 문법](https://www.jenkins.io/doc/book/pipeline/syntax/)
- [공식 inbound agent](https://github.com/jenkinsci/docker-agents/blob/master/README_inbound-agent.md)
- [Testcontainers 컨테이너 내부 실행](https://java.testcontainers.org/supported_docker_environment/continuous_integration/dind_patterns/)
- [Docker Rootless 설치](https://docs.docker.com/engine/security/rootless/)
- [Rootless 자원 제한](https://docs.docker.com/engine/security/rootless/tips/)
