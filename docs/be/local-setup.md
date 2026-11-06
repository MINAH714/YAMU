# BE 로컬 개발 환경 (MySQL·Redis)

BE를 내 PC에서 개발할 때 필요한 MySQL 8.4.11과 Redis 7을 Docker로 띄우고, BE를 거기에 연결하는 방법입니다. MySQL 버전은 운영 RDS·테스트 컨테이너와 맞춥니다.

- **Docker로 띄우는 것:** MySQL, Redis (`be/docker-compose.yml`)
- **그냥 실행하는 것:** BE(Spring Boot). 지금처럼 `bootRun`이나 IntelliJ로 실행합니다.
- **운영 서버와의 관계:** 이 설정은 로컬 전용입니다. 운영은 Lightsail Managed MySQL을 쓰고 설정도 따로 합니다.

## 처음 한 번만

1. **Docker Desktop**을 설치하고 실행합니다. 작업 표시줄에 고래 아이콘이 떠 있어야 합니다.
2. 로컬 설정 견본을 복사합니다. 복사본은 깃에 올라가지 않습니다.

   ```powershell
   cd be
   Copy-Item src\main\resources\application-local.yml.example src\main\resources\application-local.yml
   ```

## 매일 쓰는 명령 (be 폴더에서)

| 하고 싶은 것 | 명령 |
|---|---|
| DB 켜기 | `docker compose up -d` |
| 상태 보기 | `docker compose ps` → 두 줄 모두 `(healthy)`면 준비 완료 |
| DB 끄기 (데이터 유지) | `docker compose stop` |
| 다시 켜기 | `docker compose start` |
| 컨테이너 삭제 (데이터 유지) | `docker compose down` |
| **DB 완전 초기화** (데이터 삭제) | `docker compose down -v` |
| 로그 보기 | `docker compose logs -f mysql` |

처음 `up`은 이미지를 받고 DB를 초기화하느라 30초~1분 걸립니다. `(health: starting)`이면 조금 기다리세요.

## BE 실행과 연결 확인

```powershell
cd be
.\gradlew.bat bootRun
```

브라우저에서 http://localhost:8080/actuator/health 를 엽니다.

```json
{"status":"UP","components":{"db":{"status":"UP", ...},"redis":{"status":"UP", ...}, ...}}
```

`db`와 `redis`가 둘 다 `UP`이면 연결된 것입니다. 로컬에서만 이렇게 자세히 나오고, 운영에서는 `{"status":"UP"}`만 나옵니다.

## 접속 정보 (기본값)

DBeaver·Workbench·IntelliJ Database 창에서 붙을 때 씁니다.

| 항목 | MySQL | Redis |
|---|---|---|
| 호스트 | `localhost` | `localhost` |
| 포트 | `3306` | `6379` |
| DB 이름 | `yamu` | - |
| 계정 / 비밀번호 | `yamu` / `yamu_local` | 없음 |
| root 비밀번호 | `root_local` | - |

- 로컬 전용 값이라 깃에 올라가 있어도 괜찮습니다. 포트가 `127.0.0.1`에만 열려 있어 다른 기기에서는 접속할 수 없습니다.
- 운영 비밀번호는 절대 여기나 다른 파일에 적지 않습니다.

## 포트·비밀번호 바꾸기

PC에 MySQL이 이미 깔려 있거나 다른 프로젝트 컨테이너가 3306을 쓰고 있으면 `port is already allocated` 오류가 납니다.

1. `be/.env` 파일을 만들고 바꿀 값만 적습니다. `.env`는 깃에 올라가지 않습니다.

   ```
   DB_PORT=3307
   REDIS_PORT=6380
   ```

2. `application-local.yml`에서 같은 값의 기본값(`:` 뒤)도 바꿉니다. 예: `${DB_PORT:3307}`
   - Spring은 `.env` 파일을 읽지 않아서 양쪽을 따로 맞춰야 합니다.
3. `docker compose up -d --force-recreate`로 다시 띄웁니다.

쓸 수 있는 변수는 `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `REDIS_PORT`입니다. 기본값은 `be/docker-compose.yml`에 있습니다.

## 문제 해결

| 증상 | 원인과 해결 |
|---|---|
| `bootRun`이 `Failed to configure a DataSource: 'url' attribute is not specified`로 멈춤 | `application-local.yml`이 없습니다. "처음 한 번만" 2번을 하세요. |
| `/actuator/health`에서 `db`가 `DOWN` | DB 컨테이너가 꺼져 있습니다. `docker compose ps`로 확인하고 `docker compose up -d`. |
| `port is already allocated` | 포트가 겹칩니다. `docker ps`로 무엇이 쓰는지 보고 멈추거나, 위의 "포트·비밀번호 바꾸기"를 하세요. |
| `.env`에서 비밀번호를 바꿨는데 접속이 안 됨 | MySQL은 **처음 만들 때만** 계정을 만듭니다. `docker compose down -v`로 초기화한 뒤 다시 `up` 하세요(데이터 삭제됨). |
| `failed to connect to the docker API` | Docker Desktop이 꺼져 있습니다. 실행하고 고래 아이콘이 멈출 때까지 기다리세요. |

## 시간대: UTC로 통일

로컬과 운영이 같은 기준으로 시간을 저장하도록 전부 UTC입니다. 운영 Lightsail Managed MySQL의 기본값이 UTC이기 때문입니다.

| 위치 | 값 |
|---|---|
| Java 서버 기본 시간대 | UTC (`UtcTimeZoneEnvironmentPostProcessor`). 빈·DB 연결을 만들기 전에 바꾼다 |
| JSON 날짜 변환 | UTC (`application.yml`의 `spring.jackson.time-zone`). `LocalDateTime`은 끝에 `Z`를 붙임 (`UtcLocalDateTimeSerializer`) |
| JDBC 접속 | `serverTimezone=UTC` (`application-local.yml`) |
| 로컬 MySQL 컨테이너 | 지정 안 함 = UTC |

- JVM 시간대는 DB 연결 풀이 열리기 전에 바꿔야 합니다. MySQL 드라이버가 연결을 만들 때의 시간대를 저장해 두고 DATE를 읽을 때 쓰기 때문에, 늦게 바꾸면 KST PC에서 날짜가 하루 이르게 읽힙니다(S15P31E103-179). `@PostConstruct` 같은 빈 단계에서 바꾸지 마세요.
- 개발 PC가 한국 시간이어도 서버 안에서 `LocalDateTime.now()`는 UTC입니다. DBeaver에서 `NOW()`를 봐도 한국보다 9시간 이릅니다.
- **실적 월, 청구 마감일, 출금일, 영업일처럼 한국 날짜가 기준인 계산은 반드시 `ZoneId.of("Asia/Seoul")`을 명시합니다.** 예: 11월 1일 00:30(한국)은 UTC로 10월 31일 15:30이라, 그대로 월을 뽑으면 10월 실적으로 잘못 잡힙니다.
- 응답 JSON의 시각은 UTC에 `Z`를 붙여 `"2026-10-22T05:30:00Z"`로 내려갑니다. FE는 `new Date(...)`로 읽으면 기기 시각(한국)으로 바뀌어 표시됩니다. 요청에 보내는 시각도 같은 형식입니다.
- 그래서 `LocalDateTime`에는 UTC 시각만 담습니다. 결제일·청구일처럼 한국 날짜가 기준인 값은 `LocalDate`(`"2026-10-25"`)로 내보냅니다.

## 테스트

`.\gradlew.bat test`는 `test` 프로필(`src/test/resources/application-test.yml`)로 돌아서 개인 `application-local.yml`을 읽지 않습니다.

- 일반 테스트는 DB·Redis가 꺼져 있어도 통과합니다.
- DB가 필요한 테스트(`MySqlTestSupport` 상속)는 Docker Desktop이 켜져 있으면 Testcontainers가 테스트용 MySQL을 따로 띄워서 돌립니다. 위의 `yamu-mysql` 컨테이너와는 별개라 내 로컬 데이터는 건드리지 않습니다.

### DB 테스트가 MySQL을 쓰는 방식

- MySQL 컨테이너는 테스트 실행 한 번에 **하나만** 띄우고 모든 DB 테스트 클래스가 같이 씁니다.
- 스프링 테스트 컨텍스트를 새로 만들 때마다 **빈 데이터베이스**(`test_1`, `test_2`, ...)를 새로 만들고, Flyway가 거기에 마이그레이션을 처음부터 적용합니다. 그래서 각 테스트 클래스는 다른 클래스가 남긴 행을 보지 않습니다. 단, `@DirtiesContext`가 없고 설정이 같은 클래스끼리는 컨텍스트를 캐시로 같이 쓰므로 데이터베이스도 같이 씁니다.
- 컨텍스트가 닫히면(`@DirtiesContext` 등) 그 데이터베이스를 지웁니다. 지우지 않으면 데이터베이스가 쌓여 MySQL 메모리가 DB 테스트 클래스 수에 비례해 늘어납니다.
- MySQL 데이터 폴더는 메모리 디스크(tmpfs)에 둡니다. Windows Docker Desktop에서는 디스크에 쓸 때마다 확정(fsync)하는 비용이 커서 Flyway가 클래스마다 10초 넘게 걸렸습니다. SQL 동작은 같습니다.
- 테스트 SQL에서 데이터베이스 이름이 필요하면 `test` 같은 이름을 적지 말고 `DATABASE()`를 씁니다.

전후 시간(전체 `.\gradlew.bat cleanTest test`, Windows · Docker Desktop, 2026-11-06):

| | 바꾸기 전 (클래스마다 MySQL) | 바꾼 뒤 |
|---|---|---|
| 전체 | 29분 52초 (1595개) | 5분 23초 (1597개) |
| MySQL 시작 | 57번 × 평균 14.5초 | 1번 × 6.3초 |
| Flyway | 58번 × 평균 9.5초 | 58번 × 평균 0.9초 |

한계:

- 데이터베이스를 지워도 MySQL 컨테이너 메모리가 조금씩 늘어납니다. 데이터(tmpfs)는 120~165MB로 일정하지만, MySQL이 지운 테이블의 내부 캐시 메모리를 바로 돌려주지 않는 것으로 보입니다. 전체 실행에서 시작 약 410MB, 최대 633MB였습니다(지우기 전에는 875MB). CI는 MySQL·Redis 컨테이너와 Jenkins 에이전트를 합쳐 6GiB 안에서 돕니다([jenkins-ci.md](../infra/jenkins-ci.md) 「에이전트 구성과 권한」).
- 테스트 클래스마다 컨텍스트와 Flyway를 새로 만드는 비용(클래스당 약 3초)은 그대로입니다. 대부분의 DB 테스트 클래스가 `@DirtiesContext`로 컨텍스트 재사용을 끄고 있기 때문입니다.

## DB 접근과 테이블 관리

JPA + Flyway를 씁니다. 규칙은 [jpa-flyway.md](jpa-flyway.md)에 있습니다.

- 서버를 켜면 Flyway가 `db/migration`의 SQL 중 아직 안 한 것만 내 DB에 적용합니다. 팀원이 테이블을 바꿔도 `git pull` 후 서버만 켜면 됩니다.
- 로컬에서는 실행되는 SQL이 콘솔에 찍힙니다.

## 아직 정하지 않은 것

- **운영 설정(`application-prod.yml`):** INFRA 작업(Story 3.2·3.3)과 함께 진행합니다.
