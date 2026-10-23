# 호스트 Nginx 구성 및 확인

관련 작업: S15P31E103-105 (Nginx), S15P31E103-106 (HTTPS).

## 구조와 현재 상태

- Nginx는 Ubuntu 호스트에서 실행한다.
- 공개 도메인: `yamu.it.kr`. DNS A 레코드는 현재 서버 공인 IPv4를 가리켜야 한다.
- `/`는 POS 배포 전 임시 텍스트 응답이다.
- `/api/`는 `127.0.0.1:8080`으로 전달한다. `proxy_pass`에 URI 부분이 없어 `/api/health` 경로가 그대로 전달된다.
- 백엔드는 아직 실행되지 않아 API 정상 응답은 미검증이다. 이 상태의 `/api/`는 502가 예상된다.
- 백엔드 Docker 배포 시 호스트 포트를 `127.0.0.1:8080:8080`으로 제한한다. 공인 8080 포트를 열지 않는다.

## 새 서버의 HTTP 초기 구성

아래 명령은 SSH로 접속한 Ubuntu에서 실행한다. 기존 사이트가 있다면 먼저 내용을 확인하고 백업한다.

```bash
sudo apt update
sudo apt install -y nginx
sudo systemctl enable --now nginx
```

저장소의 `infra/nginx/yamu.http.conf`를 `/etc/nginx/sites-available/yamu`로 복사한다. 복사는 서버에 저장소가 있고 저장소 루트에서 실행하는 경우에만 아래 명령을 사용한다.

```bash
sudo cp infra/nginx/yamu.http.conf /etc/nginx/sites-available/yamu
```

기존 서버에서 이미 Certbot을 적용했다면 이 HTTP 파일로 덮어쓰지 않는다. HTTPS 설정과 리다이렉트가 사라진다. 이 파일은 인증서 발급 전 초기 구성용이다.

아직 활성화 링크가 없을 때만 생성한다.

```bash
sudo ln -s /etc/nginx/sites-available/yamu /etc/nginx/sites-enabled/yamu
sudo nginx -t
```

검사가 성공한 경우에만 다음을 실행한다.

```bash
sudo systemctl reload nginx
sudo ufw allow 80/tcp
curl -i -H 'Host: yamu.it.kr' http://127.0.0.1/
```

기존 SSH 허용 규칙은 유지한다. 기본 사이트가 있어도 정확한 `server_name`이 도메인 요청에 선택된다. 상위 방화벽 정책이 있으면 함께 확인한다.

## Certbot 적용 후 운영 상태

현재 서버는 Certbot 적용을 완료했다. Nginx에는 HTTPS 서버 블록과 HTTP 리다이렉트 블록이 생성됐다.

- 인증서 체인 경로: `/etc/letsencrypt/live/yamu.it.kr/fullchain.pem`
- 개인 키 경로: `/etc/letsencrypt/live/yamu.it.kr/privkey.pem`
- Certbot 보조 설정: `/etc/letsencrypt/options-ssl-nginx.conf`, `/etc/letsencrypt/ssl-dhparams.pem`

경로는 참조 정보이며 인증서·개인 키 파일 자체를 저장소에 복사하지 않는다. 위 파일들은 새 서버에서 Certbot 설치·발급 절차로 생성해야 한다.

HTTP 블록의 `if ($host = ...)`는 해당 도메인 요청을 HTTPS로 전환한다. 그 아래 `return 404`는 앞의 조건이 적용되지 않은 요청을 위한 처리이며, 모든 요청이 404가 된다는 뜻이 아니다.

Ubuntu 패키지 방식으로 진행한 명령:

```bash
sudo cp -n /etc/nginx/sites-available/yamu /etc/nginx/sites-available/yamu.before-https
sudo apt install -y certbot python3-certbot-nginx
sudo ufw allow 443/tcp
sudo certbot --nginx -d yamu.it.kr --redirect
```

인증 시 관리자 이메일과 약관을 직접 확인한다. HTTP-01 검증·갱신을 위해 80번 접근을 유지한다. DNS를 구성하지 않은 `www` 등 다른 이름은 발급 명령에 임의로 추가하지 않는다.

## 검증

```bash
sudo nginx -t
curl -I http://yamu.it.kr
curl -i https://yamu.it.kr
sudo certbot renew --dry-run
systemctl list-timers --all | grep certbot
```

확인 결과 (2026-10-22, 사용자가 제공한 서버 출력 기준):

- Nginx 설정 검사 성공.
- HTTP 요청: 301, `Location: https://yamu.it.kr/`.
- HTTPS 요청: 인증서 검증을 생략하지 않고 200, `Web server is ready.`.
- 갱신 모의 실행: `all simulated renewals succeeded`.
- Certbot 출력상 갱신 스케줄 등록. 실제 타이머 상태는 위 명령으로 추가 확인한다.
- 실백엔드 API 프록시, POS 정적 배포, 공인 백엔드 포트 제한은 미검증.

갱신 테스트 성공은 미래의 갱신을 보장하지 않는다. 서버 운영 중 타이머·로그·인증서 만료 상태를 확인한다.

## 오류 확인 및 설정 변경

```bash
sudo systemctl status nginx --no-pager
sudo tail -n 50 /var/log/nginx/error.log
```

- 502: 백엔드 실행 여부, 로컬 8080 응답, 포트 바인딩을 확인한다.
- 연결 시간 초과: DNS, 80/443 리스닝, 호스트·상위 방화벽을 확인한다.
- 설정 변경 시 백업 → 수정 → `nginx -t` → 성공 시 reload 순서를 따른다. 실패 시 reload하지 않고 마지막 정상 설정으로 복원 후 재검사한다.
- POS 배포 시 임시 `return`을 제거하고 실제 `dist/`를 제공하도록 별도 변경·검증한다.
- HTTPS의 `X-Forwarded-Proto`를 백엔드가 해석하는 방법은 운영 프로필 구성 시 검토한다.

팀 규칙상 테스트·리뷰·MR Merge 및 미완료 검증까지 마친 뒤 Jira Task를 완료 처리한다.
