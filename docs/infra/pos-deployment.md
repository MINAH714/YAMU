# POS 정적 파일 수동 배포

관련 Task: S15P31E103-105. 기존 POS 소스는 수정하지 않고 빌드 산출물을 배포한다.

## 구성

브라우저 → HTTPS Nginx → `/`는 POS 정적 파일, `/api/`는 백엔드 `127.0.0.1:8080`으로 연결된다.
Vite 개발 서버는 운영에서 실행하지 않는다. Vite의 개발용 proxy 설정은 빌드 결과에 포함되지 않으므로 운영 프록시는 Nginx가 담당한다.
모바일 앱·AI 서비스는 이 배포에 포함되지 않는다.

## Windows에서 빌드

저장소 루트에서 실행한다. Node 버전은 POS 의존성이 요구하는 버전을 사용하며 최초 확인은 Node 24 환경에서 진행했다.
각 단계가 성공한 경우에만 다음 단계로 넘어간다.

```powershell
Set-Location fe/pos
npm ci
npm run lint
$env:VITE_API_BASE_URL = '/api'
npm run build
Set-Location ../..
```

`VITE_API_BASE_URL`은 빌드 시 정적 파일에 포함된다. 배포 이후 서버의 환경변수를 바꿔도 기존 파일은 바뀌지 않으며 다시 빌드해야 한다. 공개되는 값이므로 비밀값을 넣지 않는다.

## 업로드 및 파일 배치

EC2에서 업로드 폴더를 만든다.

```bash
mkdir -p ~/deploy/pos-upload
```

Windows 저장소 루트에서 업로드한다. `ec2`는 각 PC의 SSH config에 등록한 서버 별칭이다.

```powershell
scp -r .\fe\pos\dist ec2:~/deploy/pos-upload/
```

이후 EC2에서 파일을 확인하고 Nginx용 경로에 복사한다.

```bash
ls -la ~/deploy/pos-upload/dist
sudo mkdir -p /var/www/yamu-pos
sudo cp -r ~/deploy/pos-upload/dist/. /var/www/yamu-pos/
sudo chown -R root:www-data /var/www/yamu-pos
sudo find /var/www/yamu-pos -type d -exec chmod 755 {} \;
sudo find /var/www/yamu-pos -type f -exec chmod 644 {} \;
```

업로드 폴더의 디렉터리 권한이 700이면 Nginx가 읽을 수 없다. 홈 전체 권한을 바꾸지 않고 전용 웹 경로의 디렉터리·파일 권한을 설정한다.

## 기존 HTTPS 사이트 수정

최초 POS 전환 전에 기존 설정을 백업한다. 아래 백업 파일이 이미 있으면 덮어쓰지 않는다.

```bash
sudo test -e /etc/nginx/sites-available/yamu.before-pos || sudo cp /etc/nginx/sites-available/yamu /etc/nginx/sites-available/yamu.before-pos
sudo nano /etc/nginx/sites-available/yamu
```

HTTPS 서버 블록에서 임시 텍스트를 반환하던 `location /`를 아래 설정으로 바꾼다.
기존 `/api/` 프록시·인증서 설정·HTTP→HTTPS 리다이렉트는 유지한다.

```nginx
root /var/www/yamu-pos;
index index.html;

location / {
    try_files $uri $uri/ /index.html;
}
```

`try_files`는 실제 파일·디렉터리를 먼저 찾고 없으면 SPA 진입 파일을 제공한다. `/api/` 요청은 기존의 더 구체적인 프록시 location으로 전달된다.

```bash
sudo nginx -t
```

검사가 성공한 경우에만 적용한다.

```bash
sudo systemctl reload nginx
curl --fail --show-error https://yamu.it.kr/api/health
```

브라우저에서 `https://yamu.it.kr`을 열고 강력 새로고침 후 POS 화면과 `BE 연결 상태: 연결됨`을 확인한다.

## 확인 결과와 수동 배포의 한계

2026-10-23 로컬 `npm ci`, lint, build가 성공했다.
사용자가 제공한 EC2 출력에서 업로드된 index.html과 assets, Nginx 설정 검사 성공과 reload를 확인했다.
사용자가 제공한 HTTPS 화면에 `YAMU 모의 POS`와 `BE 연결 상태: 연결됨`이 표시됐다.
현재 화면은 시작 화면·상태 조회 기능이며 결제 기능 전체를 검증한 것은 아니다.

현재 복사 방식은 최초 수동 배포 절차다. 재배포 중 파일이 섞일 수 있고 이전 assets가 남을 수 있다.
재배포 전 현재 파일 전체를 별도 보관하고, 실패 시 보관한 파일을 복원한 뒤 화면·API를 재확인한다.
Nginx 설정 백업은 정적 파일의 백업을 대신하지 않는다. 현재 배포에는 자동 롤백이 없다.
Jenkins 구성 시 버전별 산출물 보관·배포 전환·복구 절차를 별도로 구현한다.
