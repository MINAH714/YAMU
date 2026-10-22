# BE 로컬 개발 환경 (MySQL·Redis)

BE를 내 PC에서 개발할 때 필요한 MySQL 8과 Redis 7을 Docker로 띄우고, BE를 거기에 연결하는 방법입니다.

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
| Java 서버 기본 시간대 | UTC (`YamuBeApplication`) |
| JSON 날짜 변환 | UTC (`application.yml`의 `spring.jackson.time-zone`) |
| JDBC 접속 | `serverTimezone=UTC` (`application-local.yml`) |
| 로컬 MySQL 컨테이너 | 지정 안 함 = UTC |

- 개발 PC가 한국 시간이어도 서버 안에서 `LocalDateTime.now()`는 UTC입니다. DBeaver에서 `NOW()`를 봐도 한국보다 9시간 이릅니다.
- **실적 월, 청구 마감일, 출금일, 영업일처럼 한국 날짜가 기준인 계산은 반드시 `ZoneId.of("Asia/Seoul")`을 명시합니다.** 예: 11월 1일 00:30(한국)은 UTC로 10월 31일 15:30이라, 그대로 월을 뽑으면 10월 실적으로 잘못 잡힙니다.
- 응답 JSON의 시각 형식(UTC 표시 방식)은 FE와 맞춰서 규칙 문서에 정합니다.

## 테스트

`.\gradlew.bat test`는 DB·Redis가 꺼져 있어도 통과합니다. 테스트는 `test` 프로필(`src/test/resources/application-test.yml`)로 돌아서 개인 `application-local.yml`을 읽지 않습니다.

## 아직 정하지 않은 것

- **MyBatis / JPA:** 팀 결정 후 추가합니다. 지금은 DB 접속 기본 통로(JDBC)와 MySQL 드라이버만 들어 있습니다.
- **테이블 생성:** ERD 확정 후 스키마 관리 방식(SQL 파일 / Flyway / JPA 자동 생성)과 함께 정합니다.
- **운영 설정(`application-prod.yml`):** INFRA 작업(Story 3.2·3.3)과 함께 진행합니다.
