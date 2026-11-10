# YAMU 팀 모니터링 · S15P31E103-342

팀원은 `https://yamu.it.kr/monitoring/`에서 공용 `yamu-team` Viewer 계정으로
API 지표·서비스 로그·배포 결과를 확인한다. 관리자 `yamu-admin`은 인프라 담당자가 보관한다.
비밀번호는 `/etc/yamu-monitoring/credentials.json`의 root 전용 파일로만 보관하며 Git·로그·MR에 넣지 않는다.
RDS·AWS·SSH·Jenkins 관리자 권한을 팀원에게 제공할 필요가 없다.

## 수집 범위

| 화면 | 데이터 원본 | 의미 |
|---|---|---|
| API 요청·상태별 비율·p95 | Nginx의 별도 정규화 JSON 로그 → 호스트 수집기 → Prometheus | 프록시 전체 응답시간. Spring 내부 처리시간과는 다름 |
| BE·AI 상태·현재 이미지 | 고정된 운영 컨테이너 두 개의 선택된 inspect 필드 | 컨테이너 상태/health 관측. 실제 사용자 E2E 성공을 의미하지 않음 |
| 실행 로그 | 두 컨테이너의 json-file 로그 → 마스킹 → Alloy → Loki | 수집 시작 시 마지막 일부부터 읽음. 전체 과거 로그를 복구하지 않음 |
| Jenkins 결과·소요시간·로그 | 고정된 ci/cd/cd-verification의 로컬 build.xml·콘솔 파일 | 미완료/해석 불가는 UNKNOWN_OR_RUNNING. 성공으로 추정하지 않음 |
| 배포 이벤트 | 컨테이너 ID·이미지·health 변경 관측 | 최초 관측은 배포 성공 이벤트가 아님. 수동 배포도 이후 변경부터 관측 |
| 호스트 자원 | /proc/stat·meminfo·디스크 여유 공간 | 해당 EC2의 자원. RDS 인스턴스 지표는 포함하지 않음 |

BE·AI 코드, 실제 DB, 운영 키, 기존 Job 설정을 변경하지 않는다.
Spring Actuator Prometheus 레지스트리가 현재 BE에 없으므로 JVM·Hikari 지표는 후속 BE 작업으로 분리한다.
애플리케이션 요청/응답 본문, Authorization 헤더, 실제 URI·쿼리·IP는 API 로그에 수집하지 않는다.
경로는 `routes.json`에 고정된 템플릿만 사용하고 나머지는 `/api/other`로 묶어 개인정보와 시계열 폭증을 막는다.

## 권한과 저장

- Grafana만 별도 frontend 네트워크 및 `127.0.0.1:13000` 포트를 사용하고 기존 HTTPS Nginx가 프록시한다.
- Prometheus·Loki·Alloy·textfile exporter는 internal 네트워크에만 있으며 호스트 포트를 열지 않는다.
- 웹 컨테이너에 Docker 소켓·운영 환경 파일·Jenkins 홈을 마운트하지 않는다.
- root 소유의 고정 소스 수집기만 Docker의 조회 명령과 로컬 로그/메타데이터 파일을 읽는다. 네트워크 수신 포트가 없다.
- 원본 위치·커서·누적 카운터는 `/var/lib/yamu-monitoring/private`에 0700/0600으로 보관한다.
  컨테이너에는 `public/logs`와 `public/metrics`만 읽기 전용으로 제공한다.
- 실행 로그는 알려진 토큰/키/비밀번호·카드 및 계좌 번호 패턴·휴대폰·이메일을 최선의 방식으로 마스킹한다.
  새 로그 형식이나 임의의 개인정보를 완전히 판별하지 못하므로 애플리케이션에서 민감정보를 출력하지 않는 규칙도 유지한다.
- Prometheus는 7일·1GB 상한, Loki는 7일 보관한다. Loki의 7일 보관은 바이트 상한이 아니므로 디스크 여유를 함께 확인한다.
  중간 로그 spool은 서비스별 10MB 파일과 3개 회전 파일로 제한한다.
- root 수집기는 128MB/CPU25%, 컨테이너는 각각 메모리·CPU 상한을 둔다. 저장 볼륨은 재시작 후 유지한다.
- 로그는 at-least-once다. 커서 저장 전 중단은 중복을 만들 수 있고 압축/삭제된 회전 파일은 복구하지 못한다.
  `yamu_collector_log_gaps_total`과 수집 신선도를 확인한다. 5분 rate·p95는 요청과 scrape 샘플이 누적되어야 보인다.

## 검사와 설치

```bash
python3 -m unittest discover -s infra/monitoring -p 'test_*.py' -v
python3 infra/monitoring/generate.py
```

`generate.py`는 현재 Controller의 경로 템플릿·정적 대시보드를 재생성한다.
이미지 태그는 공식 배포본을 내려받은 시점의 `images.lock.json` 다이제스트로 고정한다.
업데이트 시 다이제스트와 실제 이미지 검증을 함께 갱신한다.

호스트 설치는 검토된 소스를 root 소유·다른 사용자 쓰기 불가인 준비 디렉터리에 복사한 뒤 실행한다.
`install.py verify`는 임시 Compose와 합성 API 로그를 사용해 실제 Viewer 권한·데이터 소스·지표·로그·재시작을 검증하고
생성한 스택과 볼륨을 정리한다. 운영 Nginx 연결은 바꾸지 않는다.
`install.py deploy`는 성공한 검증과 소스 해시 일치를 요구한다. 같은 관리 소스의 비공개 준비 단계는 재개할 수 있지만,
기존 공개 연결이나 알 수 없는 설치를 덮어쓰지 않는다.
실제 HTTPS 설정은 백업 → `nginx -t` → graceful reload 순서로 반영하며 공개 경로 확인 실패 시 원복한다.
BE·AI 컨테이너의 ID·이미지·시작시간이 바뀌지 않았는지도 확인한다.

수집기는 `/usr/local/lib/yamu-monitoring/collector.py`, 설정은 `/etc/yamu-monitoring/config.json`,
systemd 서비스는 `yamu-monitoring.service`이다. `ProtectSystem=strict`·`ProtectHome=true`를 적용한다.
Grafana 관리자 파일은 컨테이너 UID/GID472가 읽을 수 있도록 별도 0440 파일로 제공한다.
Alloy는 데이터 디렉터리 소유자 UID/GID473으로 실행한다. 컨테이너 capability는 모두 제거한다.

## `/api/other`의 404 추적

1. 대시보드에서 `/api/other`의 404가 발생한 시간 범위를 확인한다. 재현한 경우 HTTP 메서드와 시각도 함께 기록한다.
2. 인프라 담당자가 운영 호스트의 Nginx 접근 로그 `/var/log/nginx/access.log`에서 같은 시간 범위, 메서드, HTTP 404를 찾는다.
   날짜가 지난 건은 `access.log.1`과 `access.log.*.gz`도 확인한다. 로그 타임스탬프에 포함된 UTC 오프셋을 기준으로
   대시보드 시각과 맞춘다. `yamu-api-monitor.log`에는 정규화된 경로만 들어 있다.
3. 일치하는 요청의 경로를 해당 배포 커밋의 Controller 매핑과 운영에 설치된 경로 템플릿에 대조한다.
   Controller에 경로가 없다면 클라이언트가 호출한 메서드·경로와 배포 버전을 확인한다.
   Controller에는 있고 템플릿만 빠졌다면 배포 대상 소스에서 `generate.py`로 생성한 변경을 검토하고
   [검사와 설치](#검사와-설치) 절차로 반영한다.
4. 기록에는 시간대, 메서드, HTTP 상태, 확인한 경로 템플릿과 배포 커밋만 남긴다.
   원본 로그에는 IP, 쿼리, 경로 식별자가 포함될 수 있으므로 운영 호스트에서만 확인하고 공유 전에 가린다.
   해당 시각의 접근 로그가 남아 있지 않으면 경로를 확정할 수 없는 것으로 기록한다.

## 장애 시

1. Grafana의 수집 신선도와 서비스 상태를 먼저 확인한다. 지표가 없다고 정상으로 판단하지 않는다.
2. `systemctl status yamu-monitoring` 및 모니터링 Compose 로그를 인프라 담당자가 확인한다.
3. API 로그가 비면 Nginx의 `yamu-api-monitor.log` 및 include 설정을 확인한다.
4. 중단 시 모니터링 collector와 해당 Compose만 내린다. 공유 Docker prune·운영 Compose down은 사용하지 않는다.
5. 공개 연결을 제거하려면 준비 디렉터리의 nginx-before-monitoring.conf 백업과 현재 설정의 차이를 먼저 검토한다.
   이후에 다른 변경이 들어갔다면 백업 전체로 덮어쓰지 않고 모니터링 include·access_log만 제거한다.

공식 문서: [Alloy 파일 로그 수집](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/),
[Loki 파일 저장 구성](https://grafana.com/docs/loki/latest/configure/examples/configuration-examples/),
[Spring Prometheus 지표](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).
