# RDS MySQL 연결 및 이전

관련 Task: S15P31E103-59. 기존 Lightsail Managed Database 계획을 RDS로 변경한다.

## 구성과 비용

- 실제 프로젝트 계정은 **329995585547**, AWS CLI 프로필은 **yamu**다. AWS 명령에는 `--profile yamu`를 명시하고 STS 계정 ID를 대조한다. 기본 프로필을 대상 계정으로 추정하지 않는다.
- 서울 `ap-northeast-2`, MySQL 8.4.11, `db.t4g.micro`, Single-AZ, gp3 20GiB, 저장 암호화.
- SSAFY EC2와 개인 AWS 계정이 달라 공인 엔드포인트로 연결한다. 보안 그룹 인바운드는 SSAFY EC2의 현재 공인 IP 한 개(`/32`), TCP 3306만 허용한다. 개인 PC와 CI에는 운영 DB 접근을 허용하지 않는다.
- 공인 IP가 고정 할당인지 SSAFY 계정에서 확인하지 못했다. IP 변경 시 연결이 차단되며, 서버 신원을 확인한 뒤 규칙을 교체한다. `0.0.0.0/0`으로 우회하지 않는다.
- `require_secure_transport=ON`. JDBC `sslMode=VERIFY_IDENTITY`로 암호화·CA·호스트명을 검증한다. 암호화만 켜고 인증서 검증을 생략하지 않는다.
- 관리자 비밀번호는 RDS 관리형 Secrets Manager에 저장한다. 앱에는 별도 계정을 사용하고 `yamu.*` 권한과 `REQUIRE SSL`만 부여한다. Flyway가 스키마를 관리하므로 앱 계정에는 해당 DB의 DDL 권한도 필요하다.
- 삭제 방지 활성화, 저장소 자동 확장 미설정, Enhanced Monitoring·Performance Insights 미사용. T4g CPU 버스트 사용료와 전송량은 추가될 수 있다.
- 실제 계정의 자동 백업은 **7일 보관**이다. 백업·유지보수 창은 각각 03:00–03:30 KST, 월요일 04:00–04:30 KST.
- 고급 기능 활성화 후 남은 리전 제한은 사용자 승인으로 분리했다. `YamuSeoulRegionAccess`(p-wlsrxruh)를 조직 Root에 연결하고 기존 p-onfe7jpx에서는 이관한 RegionFloor만 제거했다. YAMU 계정만 서울을 추가 허용하며, 다른 계정의 제한과 기존 NotAction 예외는 유지한다. SCP 원본·변경안은 작업 산출물로 보관했다.

2026-10-26 AWS Price List API 확인, 42일·1,008시간 기준(크레딧 할인 전):

| 항목 | 단가 | 6주 예상 |
| --- | --- | --- |
| 인스턴스 | $0.025/시간 | $25.20 |
| gp3 20GiB | $0.131/GiB·월 | 약 $3.62 (730시간 환산) |
| 공인 IPv4 1개 | $0.005/시간 | $5.04 |
| 합계 | | 약 $33.86 |

Secrets Manager, 추가 백업·전송·버스트·세금은 별도다. 전체 추가 AWS 예산은 **6주 $100 미만**, SSAFY EC2 비용 제외다. 실제 계정은 Paid 플랜이며 고급 기능 전환 이후 Free Tier API가 크레딧 데이터를 반환하지 않아 잔액은 확인하지 못했다. 과거 다른 계정에서 조회한 크레딧은 이 예산에 포함하지 않는다. 고급 기능 활성화로 지출 한도의 자동 중지 기능은 제거됐으며 이 견적은 하드 상한이 아니다. 2026-12-08은 재생성일부터 6주 뒤 리소스 정리 검토일이며 태그만 설정했고 자동 삭제는 예약하지 않았다.

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

## OPS-05 · FSC-17 논리 백업 복원 검증

2026-11-09 운영 RDS(MySQL 8.4.11)에서 `mysqldump --single-transaction`으로 논리 백업을 만들고,
별도 MySQL 8.4.11 테스트 컨테이너에 복원했다. 테스트 DB는 네트워크와 공개 포트 없이 실행했다.
덤프는 메모리에서 복원 프로세스로 전달했고 검증 후 테스트 컨테이너와 데이터 볼륨을 제거했다.

| 검증 항목 | 결과 |
| --- | --- |
| 스키마 | 테이블 33개. 컬럼, 인덱스, 제약조건, 외래 키, 테이블 옵션이 운영과 일치 |
| 행 데이터 | 원본 백업과 복원본의 전체 `INSERT` 데이터 일치. 사용자 2건, 사용자 카드 4건, 결제 725건 |
| Flyway | 성공 23건, 실패 0건. `20261106.3` 적용 및 `payment_auto_select` 기본값 켜짐 확인 |
| AWS 자동 백업 시점 복원(PITR) | **미검증.** 복구 가능 시각은 조회했으며, AWS 자동 백업에서 별도 RDS를 생성하는 검증은 수행하지 않았다 |

복원본을 다시 덤프한 전체 SQL의 SHA-256은 원본과 달랐다. 행 데이터와 MySQL 스키마 메타데이터를
별도로 비교해 위 결과를 확인했다.

## 복구

전환 후 **RDS에서 쓰기가 발생하기 전** 실패했다면 기존 `.env.prod`와 `compose.prod.yml`만 사용해 백엔드를 다시 기동한다. RDS 쓰기가 발생한 뒤에는 기존 DB로 단순 복귀하면 데이터가 유실되므로 먼저 쓰기를 중지하고 RDS 변경분을 보존·대조한 뒤 복구 대상을 결정한다. 자동 역방향 동기화는 없다.

현재 기존 MySQL 컨테이너는 중지 상태다. 쓰기 발생 전 복구가 필요하면 먼저 `docker start yamu-prod-mysql-1`로 시작해 healthy를 확인하고, 원래 `/home/ubuntu/apps/S15P31E103/infra/`의 Compose·환경 파일을 사용한다. 현재 배포 경로의 RDS overlay를 섞지 않는다.

`down -v`, 기존 MySQL 볼륨 삭제, 무검증 DB 덮어쓰기는 하지 않는다. 수동 snapshot과 dump는 삭제 전 보관 필요성을 판단한다. snapshot에서 실제 새 RDS를 복원하는 재해 복구 검증과 장기 부하 테스트는 별도 작업이다.

## 검증 기록

### 2026-10-26 사전 검증 및 잘못 선택한 계정의 폐기 기록

아래 기록은 CLI 기본 프로필의 계정을 대상 계정으로 잘못 추정했던 당시 결과다. 해당 계정의 RDS·자동 백업·관리형 secret·전용 SG·파라미터 그룹과 서버 자격정보는 2026-10-27 사용자 지시로 모두 삭제했다. 운영 연결은 전환하지 않았다. 이 기록의 1일 백업 등은 현재 구성과 다르다.

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

### 2026-10-27 실제 계정 재생성 및 운영 전환 완료

- 사용자 확인 계정 **329995585547**, IAM `yamu-infra`, 전용 CLI 프로필 `yamu`. 생성·조회마다 이 프로필과 STS 계정을 대조했다.
- RDS `yamu-mysql`, VPC `vpc-01c1af69ca3d2fa86`, 전용 SG `sg-001c12eeac7a8ceac`, subnet group `yamu-mysql-subnets`, parameter group `yamu-mysql84`. SG는 `52.79.224.143/32`의 TCP 3306만 허용한다.
- RDS available, 7일 자동 백업, 저장 암호화·삭제 방지, 파라미터 in-sync 및 복구 가능 시각 확인.
- 사람에 의해 Merge된 `c6e77d4e538085b4c1d8f45938a5b1d5ed18bd1f`를 배포했다. Jenkins `yamu/ci` #4에서 **38개 테스트, 실패·skip 0** 및 BE/POS/모바일 검사 성공. 초기 스키마 검증 16개도 포함한다.
- CI #4 JAR를 기존 Java 17 런타임 이미지에 복사해 `yamu-backend:c6e77d4`로 패키징했다. OCI revision 라벨과 CI revision을 대조했다. 빌드 시 네트워크를 사용하지 않았으며 앱 소스 재컴파일로 다른 JAR를 생성하지 않았다.
- 기존 DB는 빈 `flyway_schema_history` 1개 테이블·0행이었다. 생성 전 백업과 백엔드 중지 후 최종 dump(권한 600)를 보관했다. 새 RDS에 첫 dump를 복원한 뒤 초기 마이그레이션을 적용했다. 최종 상태도 기존 DB가 여전히 비어 있음을 확인했으므로, RDS의 새 Flyway 이력을 빈 이전 이력으로 다시 덮어쓰지 않았다.
- RDS에 **23개 업무 테이블 + Flyway 이력**, **34개 FK**, `20261026.1 / success=1` 확인. 새 JAR의 Flyway 11.20.3 적용 후 MySQL 8.4 지원 경고 없음.
- 실제 운영 Compose를 RDS overlay로 교체했다. 중지부터 헬스/API 확인까지 약 13초. 내부 `/actuator/health`는 UP, 내부·공개 HTTPS `/api/health`는 `{"status":"ok"}`다.
- `VERIFY_IDENTITY`·CA read-only 마운트로 연결했다. TLS cipher는 `TLS_AES_128_GCM_SHA256`. 앱의 비TLS 연결은 1045, 무관한 CA 연결은 2026 오류로 거부됐다.
- 기존 MySQL은 **중지**, 컨테이너·볼륨 보존. backend·Redis·Jenkins는 healthy 유지. 검증용 컨테이너와 임시 관리자 비밀번호 파일은 정리했다. 관리자 원본은 RDS 관리형 Secrets Manager에만 보관한다.
- 이 검증은 당시 빈 업무 DB 기준이다. 실제 업무 데이터 대량 이전, snapshot 새 인스턴스 복원, 서버 전체 재부팅 및 장기 부하 검증은 수행하지 않았다.

현재 배포 포인터:

| 경로 | 용도 |
| --- | --- |
| `/home/ubuntu/apps/yamu-current` | 검증된 릴리스 `/home/ubuntu/apps/releases/c6e77d4` 링크 |
| `/home/ubuntu/.config/yamu/rds/active.env` | 현재 RDS 환경 파일 링크, 실제 파일 권한 600 |
| `/home/ubuntu/.config/yamu/rds/app.cnf` | 운영자 MySQL 클라이언트 설정, 권한 600 |
| `/home/ubuntu/.config/yamu/rds/deployment-current.json` | 계정·커밋·이미지·DB·스키마 상태 기록, 비밀번호 없음 |

현재 배포 상태를 확인하거나 동일 설정으로 백엔드를 다시 생성할 때:

```bash
docker compose \
  --env-file /home/ubuntu/apps/S15P31E103/infra/.env.prod \
  --env-file /home/ubuntu/.config/yamu/rds/active.env \
  -f /home/ubuntu/apps/yamu-current/infra/compose.prod.yml \
  -f /home/ubuntu/apps/yamu-current/infra/compose.rds.yml config --quiet
```

위 인자를 그대로 사용해 `config --quiet` 대신 `ps` 또는 `up -d --no-deps --wait backend`를 실행한다. 기존 Compose 단독 실행은 이전 MySQL 연결로 바뀔 수 있으므로, 정상 배포에는 두 파일과 현재 환경을 함께 사용한다.
