# Jenkins 설치 및 Tailscale 접속

관련 Task: S15P31E103-107. GitLab 빌드·배포 파이프라인은 후속 Task 108·109에서 구성한다.

## 구성

- 별도 Compose 프로젝트 `yamu-ci`에서 공식 `jenkins/jenkins:lts-jdk21`을 실행한다.
- 서버 Tailscale IPv4의 8081만 컨테이너 8080에 연결한다. 기존 백엔드 loopback 8080과 충돌하지 않는다.
- `/var/jenkins_home`은 명명 볼륨에 보관한다. `down -v`는 설정·작업·자격정보를 삭제하므로 사용하지 않는다. 볼륨은 백업을 대신하지 않는다.
- 컨테이너 메모리 최대 2GB, JVM heap 최대 1GB, 로그는 10MB × 3개로 제한한다.
- Docker 소켓·운영 환경 파일·배포 디렉터리를 마운트하지 않는다. 빌드 에이전트와 배포 권한은 후속 작업에서 구성한다.
- 관리자 접속은 Tailscale 터널을 통해 전달된다. 허용 사용자 범위는 tailnet 접근 정책에도 따른다.
- LTS 태그는 업데이트될 수 있다. 검증한 이미지 digest와 Jenkins 버전을 기록하고 업데이트 전 볼륨 백업·복구를 준비한다.

## EC2에서 준비

저장소에 이 작업 브랜치가 올라온 뒤 서버 저장소에서 실행한다. `.env.prod`는 Git 제외 파일이므로 유지된다.
작업 트리에 변경이 있다면 먼저 확인하고 브랜치 전환을 진행한다.

```bash
cd ~/apps/S15P31E103
git status --short
git fetch origin chore/infra/S15P31E103-107-jenkins-tailscale:refs/remotes/origin/chore/infra/S15P31E103-107-jenkins-tailscale
git switch --no-track -c chore/infra/S15P31E103-107-jenkins-tailscale origin/chore/infra/S15P31E103-107-jenkins-tailscale
tailscale ip -4
sudo ss -lntp 'sport = :8081'
```

8081이 이미 사용 중이면 기존 서비스를 확인하고 설치를 중단한다. 기존 서비스를 임의로 종료하지 않는다.
브랜치가 이미 있으면 `git switch chore/infra/S15P31E103-107-jenkins-tailscale`을 사용한다.
위 명령은 `develop`만 받는 single-branch 복제에서도 작업 브랜치를 명시적으로 가져온다. 추적 설정을 만들지 않았으므로 후속 갱신도 해당 원격 브랜치를 명시해 fetch하고 변경 내용을 확인한다.
HTTPS 인증에는 이 GitLab 인스턴스에서 발급한 `read_repository` 권한 토큰을 비밀번호 입력란에 직접 입력한다. 프로젝트 토큰은 저장소 조회가 가능한 역할(예: Reporter)도 필요하다. 토큰을 원격 URL이나 명령 인자에 넣지 않는다.

```bash
umask 077
test -e infra/.env.jenkins || cp infra/.env.jenkins.example infra/.env.jenkins
nano infra/.env.jenkins
chmod 600 infra/.env.jenkins
```

`JENKINS_BIND_IP`에 `tailscale ip -4`로 확인한 서버 IPv4를 입력한다. `0.0.0.0`·공인 IP·PC의 Tailscale IP를 넣지 않는다.
필수값 검사는 빈 값만 검출하므로 운영자가 실제 Tailscale IP인지 확인해야 한다.

```bash
git check-ignore infra/.env.jenkins
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml config --quiet
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml pull
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml up -d --wait --wait-timeout 600
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml ps
```

`cannot assign requested address`는 지정한 IP가 서버에 없는 경우다. Tailscale 상태와 환경 파일을 확인한다.
재부팅 시 Tailscale 주소 준비보다 컨테이너 시작이 앞설 수 있다. 주소가 준비된 뒤 위 up 명령으로 다시 확인한다.

## 초기 관리자 설정

Tailscale에 연결된 Windows PC에서 `http://서버-Tailscale-IP:8081`을 연다.
초기 해제 비밀번호는 EC2에서 다음 명령으로 개인 터미널에서만 확인한다. 출력·스크린샷을 채팅이나 저장소에 공유하지 않는다.

```bash
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml exec -T jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

1. Unlock Jenkins에 초기 비밀번호를 직접 입력한다.
2. Install suggested plugins를 선택하고 설치 결과를 확인한다.
3. 개인 터미널에서 확인한 초기 비밀번호와 다른 관리자 비밀번호로 계정을 만든다. 자격정보는 팀의 비밀정보 보관 수단에 저장한다.
4. Jenkins URL을 접속에 사용한 Tailscale 주소와 8081 포트로 지정한다.
5. 로그인 후 설정을 확인한다. 이 단계에서는 GitLab 토큰이나 운영 DB 비밀번호를 등록할 필요가 없다.

## 플러그인 다운로드 실패 복구

실제 설치 중 `variant` 다운로드의 `Connect timed out`, `display-url-api`의 `Connection reset`이 발생했고 의존 플러그인이 연쇄적으로 로딩되지 않았다.
응답 경로는 `updates.jenkins.io` → `get.jenkins.io` → `mirror.ossplanet.net`이었으며 마지막 미러에서 IPv4 지정 여부와 관계없이 시간 초과됐다.
응답의 대체 미러인 일본 Yamagata 경로에서 같은 variant 파일에 HTTP 200을 확인했다. 모든 설치 실패를 같은 원인으로 가정하지 말고 최초 오류와 실제 리다이렉트 경로부터 확인한다.

아래는 이번 장애에서 사용한 복구 절차다. 브라우저 설치 재시도를 멈추고 Jenkins를 정지한 뒤 같은 설정 볼륨에 공식 설치 도구로 플러그인을 받는다.
미러는 당시 연결된 경로이며 추후에도 연결된다고 보장하지 않는다. HTTPS 인증과 기본 체크섬 검증을 유지하고, 기존 구성 서버에서 플러그인을 갱신할 때는 먼저 볼륨을 백업한다.

```bash
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml stop jenkins
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml run --rm --no-deps \
  -e JENKINS_UC_DOWNLOAD_URL=https://ftp.yz.yamagata-u.ac.jp/pub/misc/jenkins/plugins \
  jenkins jenkins-plugin-cli \
  --plugin-download-directory /var/jenkins_home/plugins \
  --plugins cloudbees-folder antisamy-markup-formatter build-timeout credentials-binding timestamper ws-cleanup ant gradle workflow-aggregator github-branch-source pipeline-github-lib pipeline-graph-view git ssh-slaves matrix-auth ldap email-ext mailer dark-theme
```

`Done`과 정상 종료를 확인한 경우에만 다음을 실행한다. 오류가 있으면 마지막 오류부터 확인하며 볼륨을 삭제하지 않는다.

```bash
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml up -d --wait --wait-timeout 600
```

브라우저를 새로고침하고 관리자 계정 생성·Jenkins URL 설정을 완료한다.
이 환경변수는 위 일회성 CLI 다운로드에만 적용되며, Jenkins 웹 화면의 이후 다운로드 경로를 영구 변경하지 않는다.
이 명령은 추천 플러그인 복구용이며 GitLab 연동 플러그인·파이프라인 구성은 별도다.

## 접근·데이터 유지 검증

`ps`의 포트가 서버 Tailscale IP의 `8081->8080/tcp`인지 확인한다.
`0.0.0.0:8081`이나 `[::]:8081`이면 잘못된 공개 바인딩이다. 설정을 고치고 다시 적용한다.
Docker 공개 포트는 UFW와 다르게 처리될 수 있으므로 UFW만으로 차단을 판단하지 않는다. 공인 8081 허용 규칙을 추가하지 않는다.

Windows에서 실제 서버 주소를 넣어 각각 실행한다.

```powershell
Test-NetConnection <서버-Tailscale-IP> -Port 8081
Test-NetConnection <서버-공인-IP> -Port 8081
```

첫 결과는 `TcpTestSucceeded: True`, 두 번째는 `False`여야 한다.
Tailscale이 없는 외부 PC에서도 공인 IP 접근이 실패하는지 확인한다. tailnet 사용자별 제한은 별도 접근 정책으로 검증한다.

관리자 설정 후 재시작한다.

```bash
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml restart jenkins
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml up -d --wait --wait-timeout 600
sudo docker compose --env-file infra/.env.jenkins -f infra/compose.jenkins.yml ps
```

재접속 시 초기 설정 마법사 대신 기존 계정으로 로그인되는지 확인한다.
로그에는 초기 비밀번호가 포함될 수 있으므로 로그 전체를 공유하지 않는다.
상태가 unhealthy라고 자동 재시작되는 것은 아니다. healthcheck는 HTTP 로그인 화면 응답만 확인하며 플러그인·빌드·배포 성공을 뜻하지 않는다.

## 검증 기록

2026-10-23 로컬에서 명시적인 Tailscale IPv4를 설정한 `docker compose config --quiet`가 성공했고, 빈 IP 견본을 사용하면 필수값 오류로 거부되는 것을 확인했다.
같은 날 사용자가 제공한 서버 출력과 확인 결과:

- Compose config 검사와 이미지 pull 성공, `yamu-ci-jenkins-1` healthy.
- 포트는 서버 Tailscale IPv4의 `8081->8080/tcp`에 바인딩됨. `50000/tcp` 표시는 컨테이너 포트 정보이며 호스트 공개 바인딩은 없다.
- 플러그인 미러 연결 실패 후 위 일회성 CLI 복구 명령이 `Done`으로 종료됨.
- 사용자 확인으로 관리자 초기 설정과 Jenkins URL 저장 완료.
- Windows의 Tailscale 인터페이스에서 8081 TCP 접속 성공, Wi-Fi 인터페이스를 통한 서버 공인 IPv4의 8081 TCP 접속 실패.
- 사용자가 Jenkins 재시작 후 기존 관리자 계정의 정상 로그인을 확인함. 이는 컨테이너 재시작 후 설정 보존 확인이며 컨테이너 재생성·서버 재부팅·백업 복원 검증은 아니다.

Tailscale을 설치하지 않은 별도 외부 PC 검사와 tailnet 사용자별 정책 검증은 아직 수행하지 않았다.
모든 개별 플러그인의 기능·GitLab 빌드·자동 배포는 이번 검증 범위에 포함하지 않는다. Jenkins 버전·이미지 digest는 별도 수집이 필요하다.

참고: [Jenkins Docker 설치](https://www.jenkins.io/doc/book/installing/docker/), [공식 Docker 이미지](https://github.com/jenkinsci/docker).
미러 지정 옵션: [공식 Plugin Installation Manager](https://github.com/jenkinsci/plugin-installation-manager-tool).
