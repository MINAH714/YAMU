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
git fetch origin
git switch --track origin/chore/infra/S15P31E103-107-jenkins-tailscale
tailscale ip -4
sudo ss -lntp 'sport = :8081'
```

8081이 이미 사용 중이면 기존 서비스를 확인하고 설치를 중단한다. 기존 서비스를 임의로 종료하지 않는다.
브랜치가 이미 있으면 `git switch chore/infra/S15P31E103-107-jenkins-tailscale`을 사용한다.

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
EC2 설치·관리자 로그인·공인 접근 차단·재시작 보존은 서버 실행 후 결과를 기록한다. 현재는 위 EC2 검증이 완료되지 않았다.

참고: [Jenkins Docker 설치](https://www.jenkins.io/doc/book/installing/docker/), [공식 Docker 이미지](https://github.com/jenkinsci/docker).
