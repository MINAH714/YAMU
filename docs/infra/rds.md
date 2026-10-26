# RDS MySQL 연결 및 이전

관련 Task: S15P31E103-59. 기존 Lightsail Managed Database 계획을 RDS로 변경한다.

## 구성과 비용

- 서울 `ap-northeast-2`, MySQL 8.4.11, `db.t4g.micro`, Single-AZ, gp3 20GiB, 저장 암호화.
- SSAFY EC2와 개인 AWS 계정이 달라 공인 엔드포인트로 연결한다. 보안 그룹 인바운드는 SSAFY EC2의 현재 공인 IP 한 개(`/32`), TCP 3306만 허용한다. 개인 PC와 CI에는 운영 DB 접근을 허용하지 않는다.
- 공인 IP가 고정 할당인지 SSAFY 계정에서 확인하지 못했다. IP 변경 시 연결이 차단되며, 서버 신원을 확인한 뒤 규칙을 교체한다. `0.0.0.0/0`으로 우회하지 않는다.
- `require_secure_transport=ON`. JDBC `sslMode=VERIFY_IDENTITY`로 암호화·CA·호스트명을 검증한다. 암호화만 켜고 인증서 검증을 생략하지 않는다.
- 관리자 비밀번호는 RDS 관리형 Secrets Manager에 저장한다. 앱에는 별도 계정을 사용하고 `yamu.*` 권한과 `REQUIRE SSL`만 부여한다. Flyway가 스키마를 관리하므로 앱 계정에는 해당 DB의 DDL 권한도 필요하다.
- 삭제 방지 활성화, 저장소 자동 확장 미설정, Enhanced Monitoring·Performance Insights 미사용. T4g CPU 버스트 사용료와 전송량은 추가될 수 있다.
- 자동 백업은 **1일 보관**이다. 현재 FREE 계정 플랜이 7일 보관 요청을 거부했다. 이는 임시 운영 제약이며, 7일 이상 복구 범위가 필요하면 계정 플랜과 비용을 검토한다. 백업·유지보수 창은 각각 03:00–03:30 KST, 월요일 04:00–04:30 KST.

2026-10-26 AWS Price List API 확인, 42일·1,008시간 기준(크레딧 할인 전):

| 항목 | 단가 | 6주 예상 |
| --- | --- | --- |
| 인스턴스 | $0.025/시간 | $25.20 |
| gp3 20GiB | $0.131/GiB·월 | 약 $3.62 (730시간 환산) |
| 공인 IPv4 1개 | $0.005/시간 | $5.04 |
| 합계 | | 약 $33.86 |

Secrets Manager, 추가 백업·전송·버스트·세금은 별도다. 전체 추가 AWS 예산은 **6주 $100 미만**, SSAFY EC2 비용 제외다. 조회 당시 FREE 플랜 크레딧은 $45.24, 플랜 만료는 2027-01-16이었다. 크레딧은 사용에 따라 달라지며 FREE 플랜 종료 조건을 별도 확인한다. 예산은 하드 상한이 아니므로 사용량을 확인한다. 2026-12-07은 6주 뒤 리소스 정리 검토일이며 태그만 설정했고 자동 삭제는 예약하지 않았다.

공식 근거: [RDS 요금](https://aws.amazon.com/rds/mysql/pricing/), [IPv4 요금](https://aws.amazon.com/vpc/pricing/), [TLS 강제](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/mysql-ssl-connections.require-ssl.html).

## 인증서와 환경 파일

`infra/rds/prepare-truststore.sh`는 AWS 서울 리전 CA 묶음을 HTTPS로 받고 인증서 유효기간을 확인한 뒤 PKCS12 truststore를 생성한다. Linux의 curl·openssl·JDK keytool이 필요하다. JDK 컨테이너에 스크립트와 출력 폴더만 마운트해서 실행해도 된다. 기존 truststore는 덮어쓰지 않는다.

```bash
sudo install -d -m 755 -o "$(id -u)" -g "$(id -g)" /opt/yamu/certs
sh infra/rds/prepare-truststore.sh /opt/yamu/certs/rds-truststore.p12
```

truststore에는 공개 CA만 있으므로 `changeit`은 비밀이 아니다. 파일은 644, 상위 디렉터리는 755 등 앱 UID 10001이 접근할 수 있어야 한다. 비밀번호 파일은 이 디렉터리 밖의 700 디렉터리에 600으로 저장한다. `infra/.env.rds.example`을 서버의 제외된 `.env.rds`로 복사하고 호스트·앱 계정·기존 이미지 태그를 채운다. 실제 값은 출력하거나 커밋하지 않는다.

## 이전 순서

1. 기존 DB의 버전·문자셋·테이블·Flyway 이력을 확인한다. MySQL 8.0 → 8.4 호환성을 별도 검증한다. 기존 CI의 MySQL 8.0 테스트 성공만으로 8.4 검증을 대신하지 않는다.
2. 기존 MySQL에서 `--single-transaction --set-gtid-purged=OFF --no-tablespaces`로 DB 단위 dump를 생성하고 권한 600으로 저장한다. 시스템 DB·계정 테이블은 복사하지 않는다.
3. RDS에 복원하고 테이블·행 수·Flyway checksum을 비교한다. 비밀번호를 명령 인자로 넘기지 않고 권한 제한된 MySQL option 파일을 사용한다.
4. 앱 계정으로 인증서 검증 연결, TLS cipher, 잘못된 CA·비TLS 연결 거부를 확인한다. 별도 백엔드 컨테이너에서 Flyway·JPA validate·health를 확인한다. 운영 포트는 바인딩하지 않는다.
5. 리뷰 후 전환 시 API 쓰기를 중지하고 최종 dump·복원을 반복한다. 이 문서의 사전 복원만으로 이후 변경분이 동기화되지는 않는다.
6. 아래 Compose 설정으로 기존 이미지의 백엔드만 교체하고 health 및 실제 API를 확인한다. 기존 MySQL 데이터·볼륨은 삭제하지 않는다.

Compose >= 2.24.4 필요. 기존 `.env.prod`를 먼저 읽고 RDS 설정으로 덮어쓴다. base 파일의 root 비밀번호 검사 때문에 기존 환경 파일도 필요하다.

```bash
docker compose --env-file infra/.env.prod --env-file infra/.env.rds \
  -f infra/compose.prod.yml -f infra/compose.rds.yml config --quiet
docker compose --env-file infra/.env.prod --env-file infra/.env.rds \
  -f infra/compose.prod.yml -f infra/compose.rds.yml up -d --no-deps --wait backend
curl --fail http://127.0.0.1:8080/actuator/health
```

overlay는 로컬 MySQL을 `legacy-db` 프로필로 분리하고 backend의 MySQL 시작 의존성을 제거한다. Redis 의존성과 기존 내부 네트워크는 유지한다. `config`는 반드시 `--quiet`로 실행한다. CA 파일이 없으면 빈 디렉터리를 자동 생성하지 않고 실패한다.

## 복구

전환 후 **RDS에서 쓰기가 발생하기 전** 실패했다면 기존 `.env.prod`와 `compose.prod.yml`만 사용해 백엔드를 다시 기동한다. RDS 쓰기가 발생한 뒤에는 기존 DB로 단순 복귀하면 데이터가 유실되므로 먼저 쓰기를 중지하고 RDS 변경분을 보존·대조한 뒤 복구 대상을 결정한다. 자동 역방향 동기화는 없다.

`down -v`, 기존 MySQL 볼륨 삭제, 무검증 DB 덮어쓰기는 하지 않는다. 수동 snapshot과 dump는 삭제 전 보관 필요성을 판단한다. snapshot에서 실제 새 RDS를 복원하는 재해 복구 검증과 장기 부하 테스트는 별도 작업이다.

## 검증 기록

2026-10-26:

- `yamu-mysql` available, 암호화·삭제 방지 활성화, 파라미터 그룹 in-sync, point-in-time 복구 가능 시각 조회 확인.
- 앱 계정의 `VERIFY_IDENTITY` 연결에서 MySQL 8.4.11·`require_secure_transport=1`·`utf8mb4`·`TLS_AES_128_GCM_SHA256` 확인.
- 임시 테이블의 쓰기·롤백(행 수 1 → 0) 성공. 비TLS 앱 연결은 MySQL 1045, 무관한 CA 연결은 2026 오류로 거부.
- 기존 MySQL 8.0.46에서 2,419바이트 dump를 생성해 빈 RDS로 복원. 테이블 목록·내용 비교 성공. **당시 데이터는 `flyway_schema_history` 1개 테이블·0행이며 업무 데이터는 없었다.** 대규모·실제 업무 데이터 이전을 검증한 것은 아니다.
- 기존 운영 이미지 `yamu-backend:manual`을 운영 포트 없이 실행해 RDS·Redis 연결, Flyway·JPA 초기화 및 `/actuator/health`의 `UP` 확인.
- 분리된 임시 스키마에서 기존 `be/src/test/resources/db/testmigration/V0_1__create_test_audit_sample.sql`을 실제 Flyway로 적용. version 0.1·success=1·테이블 생성 확인. 재기동 후 재적용 없이 up to date 및 health UP 확인.
- **기존 운영 이미지의 Flyway가 MySQL 8.4를 검증된 지원 범위보다 새 버전으로 판단하는 경고가 남는다.** 위 적용·재기동 검증은 통과했지만, 운영 전환 전 BE 담당자와 Flyway 지원 버전 및 향후 업무 마이그레이션 호환성을 검토한다. 이 MR에서 BE 의존성을 임의로 변경하지 않았다.
- 임시 컨테이너·스키마·권한과 서버의 임시 관리자 비밀번호 파일 정리. 앱 환경/클라이언트 설정과 dump는 `/home/ubuntu/.config/yamu/rds/`에 600, 상위 폴더 700으로 보관. CA truststore는 `/opt/yamu/certs/`에 보관. 관리자 원본은 AWS 관리형 secret에 유지한다.
- Compose 필수값 누락 거부 및 정상값으로 config 검증, MySQL `legacy-db` 프로필·backend의 Redis 단독 시작 의존성·CA read-only 마운트 확인. 인증서 준비 스크립트 실제 실행으로 CA 3개 가져오기 및 shell 구문 검사 통과.
- **운영 백엔드의 DB 연결은 아직 기존 MySQL이다.** 기존 backend·MySQL·Redis·Jenkins healthy 유지. 리뷰·Merge와 Flyway 검토 후 최종 dump·연결 전환을 진행한다.
