# MySQL · Redis · 백엔드 운영 배포

관련 Task: S15P31E103-71. 기존 `be/docker-compose.yml`은 로컬 전용이며 변경하지 않는다.

## 구성

- 호스트 Nginx → `127.0.0.1:8080` → 백엔드 컨테이너.
- MySQL 8.0과 Redis 7은 포트를 호스트에 공개하지 않고 내부 `database` 네트워크에서만 통신한다.
- 백엔드는 외부 API 호출을 위한 기본 네트워크와 DB 네트워크 양쪽에 연결한다.
- 백엔드가 사용하는 DB 주소는 `mysql`, Redis 주소는 `redis`이다. 컨테이너 내부의 `localhost`로 다른 컨테이너에 접속하지 않는다.
- MySQL·Redis가 healthy가 된 뒤 백엔드를 시작한다. 백엔드는 `/actuator/health`로 DB·Redis를 포함한 준비 상태를 검사한다.
- 운영 프로필은 `prod`, 연결값은 Compose의 environment로 전달한다. 별도 `application-prod.yml` 없이 공통 설정을 사용한다. `.env.prod`를 Spring이 직접 읽는 구조가 아니다.
- Flyway와 `ddl-auto: validate`는 기존 설정을 유지한다. 테이블 검증 오류를 `update`로 우회하지 않는다.
- Redis는 AOF everysec과 볼륨을 사용한다. 갑작스러운 장애 시 최근 쓰기의 손실 가능성이 있어 영구 원장은 MySQL에 보관한다. Redis 인증은 현재 미설정이고 내부 전용이다. 다른 컨테이너를 DB 네트워크에 임의로 추가하지 않는다.
- 운영 Swagger/API docs는 공개하지 않는 **임시 배포 설정**이다. 별도 정책 합의 후 변경한다. 인증 기능 자체를 추가한 것은 아니다.
- 이미지 태그는 메이저 계열 기준이다. 실제 배포 전 pull 결과를 검토하고 추후 검증된 digest로 고정한다.

## 로컬 PC에서 서버로 준비 파일 전달

Git 브랜치·리뷰는 Windows 프로젝트에서 진행한다. 아래 실행 절차는 EC2 Ubuntu 기준이다.
현재 작업 브랜치가 원격에 Push되기 전에는 서버가 이 파일을 `git pull`로 받을 수 없다.
리뷰된 브랜치를 서버에서 체크아웃하거나, 승인된 소스를 서버의 별도 배포 디렉터리에 전달한 뒤 진행한다.
실행에는 `be/`와 `infra/` 양쪽이 필요하다. 서버의 실제 저장소 경로를 먼저 확인한다.
운영 브랜치는 `master`이며, 미검토 작업을 운영에 자동 배포하지 않는다.

## 서버 환경 파일 준비

서버의 저장소 루트에서 처음 한 번만 실행한다. 기존 `.env.prod`가 있다면 덮어쓰지 않는다.

```bash
umask 077
test -e infra/.env.prod || cp infra/.env.prod.example infra/.env.prod
nano infra/.env.prod
chmod 600 infra/.env.prod
```

`DB_PASSWORD`, `DB_ROOT_PASSWORD`에 서로 다른 충분히 긴 무작위 값을 직접 입력한다.
견본에는 실제 비밀번호가 없으며, 빈 값이면 실행이 거부된다. 비밀값을 채팅·터미널 명령 인자·스크린샷에 넣지 않는다.
초기에는 영문·숫자 기반 무작위 값을 쓰면 dotenv 특수문자 해석 문제를 피하기 쉽다.
`APP_VERSION`은 배포할 소스의 커밋 식별자로 바꾼다.

환경 파일의 제외 여부는 값을 출력하지 않고 다음으로 확인한다.

```bash
git check-ignore infra/.env.prod
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml config --quiet
```

`config`를 옵션 없이 출력하면 비밀값이 표시될 수 있다. 반드시 `--quiet`를 사용한다.
Docker 빌드 컨텍스트에는 전용 dockerignore로 `.env`와 개인 local 설정을 제외한다.

## 빌드 및 실행

백엔드는 Docker의 Java 17 빌드 단계에서 `clean test bootJar`를 수행한다. 호스트 JDK는 필요 없다.
Docker 빌드 단계에는 Docker 소켓이 없어 Testcontainers 테스트가 건너뛰어질 수 있다. 이것을 DB 통합 테스트 통과로 해석하지 않는다.

```bash
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml build backend
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml up -d --wait --wait-timeout 300
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml ps
```

빌드·테스트가 실패하면 실행 단계로 넘어가지 않는다. 첫 MySQL 초기화·이미지 다운로드에는 시간이 걸릴 수 있다.
`--wait` 실패가 자동 롤백이나 컨테이너 제거를 의미하지는 않는다. 상태와 로그를 확인한다.

## 검증

```bash
curl --fail --show-error http://127.0.0.1:8080/api/health
curl --fail --show-error http://127.0.0.1:8080/actuator/health
curl --fail --show-error https://yamu.it.kr/api/health
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml exec -T redis redis-cli ping
sudo ss -lntp
```

- `/api/health`: `{"status":"ok"}`.
- `/actuator/health`: `UP`. DB·Redis의 연결까지 반영하지만 세부정보는 노출하지 않는다. Nginx는 `/actuator/`를 프록시하지 않는다.
- Redis: `PONG`.
- `ps`에서 MySQL·Redis·백엔드가 healthy이고 백엔드만 `127.0.0.1:8080`에 공개돼야 한다.
- 공인 IP의 8080·3306·6379에는 외부에서 접속되지 않아야 한다. 별도 PC에서 확인한다.

컨테이너 재시작 및 호스트 재부팅 후 상태도 점검한다. Compose의 healthcheck는 상태를 표시하며 unhealthy만으로 자동 재시작하지 않는다.
restart 정책은 프로세스 종료에 반응한다. 서버 재부팅 뒤 Compose의 의존성 대기 순서가 보장되는 것도 아니므로 재접속·재시작 상태를 확인한다.

## 데이터 유지와 장애 확인

```bash
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml logs --tail=100 backend
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml logs --tail=100 mysql
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml restart
sudo docker compose --env-file infra/.env.prod -f infra/compose.prod.yml ps
```

로그를 공유하기 전 비밀값이나 개인정보를 가린다. JSON 로그는 서비스마다 10MB × 3개로 제한한다.
볼륨은 `yamu-prod` 프로젝트에 묶이며 로컬 개발 볼륨과 분리된다. `down`은 볼륨을 유지하지만 `down -v`는 데이터를 삭제하므로 운영에서는 사용하지 않는다.
볼륨은 백업이 아니다. 데이터 보존 검증에는 실제 생성한 테스트 레코드를 재시작 전후 조회하는 과정이 필요하며, 백업·복원 절차는 별도 작업이다.
MySQL 최초 초기화 이후 환경 파일의 비밀번호만 바꿔도 DB 계정 비밀번호는 바뀌지 않는다. 데이터 삭제로 해결하지 말고 DB 계정 변경과 환경변수를 함께 반영한다.
이전 이미지 재배포만으로 Flyway 스키마 변경이 되돌아가지는 않는다. 배포 전 DB 백업과 마이그레이션 호환성을 별도로 검토한다.

## 검증 범위

2026-10-23 로컬 Docker Desktop에서 `docker build -f infra/docker/backend.Dockerfile -t yamu-backend:validation be` 성공.
빌드 단계의 `clean test bootJar`가 `BUILD SUCCESSFUL`로 종료됐다. Docker 소켓을 전달하지 않았으므로 Testcontainers DB 테스트까지 성공했다는 의미는 아니다.

같은 날 별도 `yamu-validation` Compose 프로젝트에서 빌드한 이미지를 사용해 다음을 확인했다. 검증용 자격정보는 Git 제외 파일에만 저장했다.

- MySQL·Redis·백엔드 모두 healthy.
- `/api/health`: `{"status":"ok"}`, `/actuator/health`: `{"status":"UP"}`.
- MySQL 별도 검증 테이블과 Redis 검증 키에 표식 값을 저장한 뒤 세 컨테이너를 재시작했다. 두 값이 유지되고 백엔드도 다시 healthy/UP으로 복귀했다.
- Docker inspect에서 MySQL·Redis의 PortBindings는 비어 있고, 백엔드는 `127.0.0.1:8080`만 바인딩됨을 확인했다.
- 이는 실제 로컬 컨테이너 연동 검증이며, Testcontainers 테스트를 별도로 실행했다는 의미는 아니다. 재시작 데이터 유지 검증은 컨테이너 재생성·호스트 재부팅·백업 복원 검증과 구분한다.

같은 날 사용자가 제공한 EC2 출력에서 운영 MySQL·Redis·백엔드 모두 healthy이고 백엔드가 `127.0.0.1:8080`에 바인딩된 것을 확인했다.
공개 HTTPS `/api/health`에서 200 및 `{"status":"ok"}` 응답을 확인했다.
컨테이너 healthcheck 결과는 DB·Redis 연결을 포함한다. 실서버 데이터 보존·재부팅·백업 복원과 공인 내부 포트 차단의 별도 PC 검사는 아직 미검증이다.

POS는 호스트 Nginx의 정적 파일로 별도 배포했다. [POS 배포 절차](pos-deployment.md)를 참고한다.
AI·Jenkins는 이번 Compose 구성에 포함하지 않았다.
