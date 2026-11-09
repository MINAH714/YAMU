# 팀용 운영 상태 화면 · S15P31E103-343

주소는 `https://yamu.it.kr/status/`다. 기존 Grafana 로그인 상태를 사용한다.
로그인하지 않았다면 같은 `yamu-team` 계정으로 로그인한다. 로그인 POST는 기존 Grafana로 직접 전송하며
이 서비스는 비밀번호·운영 키·별도 사용자 DB를 저장하지 않는다.

## 구조

- `/status/`: 반응형 한국어 HTML/CSS/JS. API 상태·실제 배포 커밋·Nginx 요청 지표·호스트 자원·Jenkins 결과·검색 로그.
- `/monitoring/status-api/`: Grafana 쿠키 경로를 유지하는 조회 전용 중계 API.
  매 요청에서 Grafana `/api/user`를 확인하고 Org1만 허용한다. 쿠키 갱신은 원래 응답으로 전달한다.
- 중계기는 고정된 Grafana datasource UID에 고정 PromQL/LogQL만 GET으로 호출한다.
  URL·임의 쿼리·관리 API·POST/PUT/PATCH/DELETE는 제공하지 않는다.
- 기존 Grafana·수집기·POS·BE·AI·RDS·Jenkins 설정을 변경하지 않는다. Nginx에 새 경로 include만 추가한다.
- UI 코드와 로그인 페이지는 공개 정적 shell이며 실제 데이터 API는 로그인 전401이다.
  브라우저는 Prometheus/Loki 주소나 서비스 자격정보를 받지 않는다.

## 표시 기준

- 컨테이너 health·수집 신선도를 함께 판단한다. 수집 지연·부분 장애·빈 데이터를 정상/0으로 대체하지 않는다.
- API 누계는 수집 시작 이후 값이고 RPS·p95는5분 기준이다. 차트·로그는15분/1시간/6시간으로 제한한다.
- 빌드 성공과 운영 배포를 구분한다. 운영 반영은 실제 컨테이너 커밋으로 확인한다.
- 로그는 최대200줄, 검색100자, Jenkins 빌드 번호는숫자9자리 이내로 제한한다.
  검색어는 JSON 문자열로 이스케이프해 LogQL의 literal filter로만 사용한다.
- 서버는 최대16개의 동시 처리·요청 읽기15초·소스 호출4초/전체12초 예산을 둔다.
  Nginx는 새 조회 API만 IP별5r/s·burst20으로 제한한다.
- 비밀번호·쿠키·검색어를 access log에 쓰지 않으며 응답은 no-store, CSP·nosniff·frame 차단을 적용한다.
- 갱신은15초이며 탭이 숨겨지면 멈춘다. 새 필터 응답보다 늦은 이전 응답은 화면에 반영하지 않는다.

## 검사·배포

```bash
python3 -m unittest discover -s infra/status -p 'test_*.py' -v
node --check infra/status/web/app.js
```

별도 Compose `yamu-status`를 사용한다. Python 공식 이미지는 다이제스트로 고정하고 외부 패키지 설치가 없다.
컨테이너는 UID65532, read-only FS·capability 제거·192MB/CPU0.5 제한이며 호스트에는127.0.0.1:13010만 연다.
기존 `yamu-monitoring_monitor` 네트워크로 Grafana에 접근하며 Docker 소켓·운영 파일·호스트 볼륨을 받지 않는다.

설치 순서는 검토 소스 복사 → 테스트/빌드 → loopback readiness/인증 확인 → Nginx include 문법 검사 → graceful reload →
공개401·실제 Grafana 쿠키로 조회·기존 서비스 동일성 검증이다. 기존 HTTPS 설정은 먼저 백업한다.
다른 변경이 들어갔으면 백업 전체를 덮어쓰지 않고 새 include만 제거한다.
BE·RDS 마이그레이션이나 실제 배포·롤백을 실행하는 버튼은 없다.

이 프론트는 기존 S15P31E103-342 모니터링 설치가 있어야 동작한다. 없는 경우 가짜 데이터로 대체하지 않고 연결 오류를 표시한다.

## 2026-11-08 검증 결과

- Python 단위·HTTP 계약 검사 11개와 JavaScript 구문 검사를 통과했다.
- 별도 임시 Compose에서 실제 Grafana Viewer 로그인·Prometheus·Loki를 사용하는 13개 검사를 통과했다.
  컨테이너 재시작 후에도 같은 검사를 통과했고 임시 자원은 제거했다.
- 공개 도메인에서도 13개 검사를 통과했다. 비로그인 401, 임의 upstream/쿼리 거부,
  쓰기 405, 검색어의 LogQL literal 처리, 실제 지표·Jenkins 결과·API 로그를 확인했다.
- 실제 Prometheus의 `exported_job` 라벨을 처리한다. 서로 다른 이름의 무라벨 지표는
  `or` 결합에서 일부가 탈락하므로 `__name__` 선택자로 함께 읽는다.
- 배포 전후 BE·AI 컨테이너 ID와 시작 시각이 동일했다. RDS·Jenkins 설정은 변경하지 않았다.
- 브라우저에서 공개 로그인 화면을 확인했다. 해당 브라우저 세션은 만료되어 로그인 후
  대시보드의 시각 검증은 남아 있다. 인증된 데이터 조회는 위 실제 API 검사로 확인했다.
- 서버 검증 기록: `/opt/yamu/status-bootstrap-343/verification.json`, `deployment.json`.
  소스 버전: `343-a0f761244316d649`. 이 파일들에는 키·비밀번호·세션 값을 기록하지 않는다.
