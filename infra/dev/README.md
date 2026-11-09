# 공용 개발 BE·Redis 준비 — S15P31E103-249

이 구성은 운영 `yamu`와 CD 검증 환경을 유지하면서 공용 `yamu_dev`를 사용할 **별도 개발 환경의 실행 파일**을 준비한다.
파일 추가만으로 배포하거나 DB에 접속하지 않는다. 실제 키 생성·AWS Secret 생성·마이그레이션·배포는 이번 준비 단계에서 실행하지 않았다.

## 구성과 변경 범위

| 항목 | 개발 구성 |
|---|---|
| Compose | `infra/compose.dev.yml`, 단독 사용, 프로젝트 `yamu-dev` |
| BE 이미지 | `YAMU_DEV_BACKEND_IMAGE`에 리뷰된 이미지 명시. 빌드·배포 자동 실행 없음 |
| DB | 기존 RDS의 `/yamu_dev`, 전용 계정 `yamu_dev`, `VERIFY_IDENTITY` 및 공개 CA truststore |
| Redis | 전용 `yamu-dev_data` 내부 네트워크와 `yamu-dev_redis_data` 볼륨, 호스트 포트 없음 |
| BE 포트 | `127.0.0.1:18080` 기본 제안. 실제 배포 전 서버 사용 여부 확인. 외부 공개·Nginx 변경 없음 |
| 스키마 | Flyway 자동 실행 비활성, JPA `validate`. 승인된 마이그레이션이 끝나기 전이면 시작 실패를 그대로 확인 |
| 비밀 주입 | Compose 파일 Secret → `/run/secrets/` → Spring `configtree` |

기존 prod Compose·CD 도구·Seed Loader·BE 업무 코드는 수정하지 않는다. 기존 Compose에 개발 DB 값만 넣어 재사용하지 않는다.
Redis는 전용 네트워크 안에서 BE만 접근하며 계좌·결제 원장은 RDS에 둔다. Redis 재시작과 데이터 보존 검증은 실제 배포 단계의 별도 확인 항목이다.

## 비밀 파일 계약

실제 파일은 저장소 밖 `/home/ubuntu/.config/yamu/dev/secrets`에 둔다.
부모 및 Secret 디렉터리는 root 소유 `0700`, 각 Secret 파일은 `root:10001`, `0440`이다.
기존 BE 이미지의 UID/GID `10001`이 컨테이너 마운트 파일을 읽는다. 파일에 실제 키·비밀번호가 있으므로 관리자만 원본 경로를 관리한다.
일반 Compose 파일 Secret은 호스트 파일을 bind mount하므로 Compose의 `uid/gid/mode` 옵션만 믿지 않고 실제 파일 권한을 준비한다.

| 파일명·Spring 속성 | 의미 |
|---|---|
| `spring.datasource.password` | 기존 개발 DB 비밀번호. DB 계정이나 비밀번호를 바꾸지 않고 안전한 기존 파일에서 읽음 |
| `yamu.crypto.data-key-id` | 현재 준비한 개발 데이터 키 버전 1 |
| `yamu.crypto.data-keys[1]` | AES-256 데이터 키 |
| `yamu.crypto.hmac-key` | 사용자·계좌·카드 조회 해시 키 |
| `yamu.crypto.pin-pepper` | PIN 해시의 서버 비밀값 |
| `yamu.jwt.secret` | 개발 JWT 서명 키 |

암호화·HMAC·PIN·JWT는 독립된 Base64 키다. 값·지문·원문 해시는 stdout, 로그, Git, MR 또는 명령 인자로 출력하지 않는다.
환경변수에는 공개 연결 설정과 파일 경로만 전달한다. AWS 인증 정보는 BE 컨테이너에 넣지 않는다.
개발 키는 운영·CD 검증 키와 구분하며, 실제 공유 사용자·계좌·보유 카드 Seed를 생성하는 담당자와 개발 BE는 **같은 개발 키**를 사용해야 한다.
카드 상품·혜택·이미지 카탈로그 자체는 개인정보 암호화 키를 사용하지 않는다.

## 키 관리 도구 — 실제 실행은 후속 승인 단계

`dev_keys.py`는 Linux 서버의 root 운영자용이다. Windows 파일 권한으로 운영 Secret을 만들지 않는다.
고정된 개발 경로와 AWS 계정 `329995585547`·서울 리전·`yamu/dev/backend-keys`만 사용한다.
실제 사용 전 리뷰와 대상 계정을 확인한다. 도구는 BE·Redis를 시작하거나 DB에 접속하지 않는다.

| 명령 | 수행 내용 |
|---|---|
| `init` | 기존 개발 DB 설정 `rds-dev/db.env`를 검증해 비밀번호를 복사하고 개발 키를 새로 만들어 원자적으로 파일 설치 |
| `check` | 파일 소유권·권한·형식·키 중복과 기존 개발 DB 비밀번호 일치 확인. 값은 출력하지 않음 |
| `backup` | 기존 운영자 AWS 프로필로 Secrets Manager에 개발 키만 암호화 보관. 이미 같은 키면 비교만 하고 다르면 중단 |
| `restore` | 기존 개발 키 백업을 메모리에서 읽어 파일 복원. 기존 Secret 디렉터리가 있으면 중단 |
| `seed-env` | 같은 AES 키의 `YAMU_CRYPTO_DATA_KEY`·`YAMU_CRYPTO_DATA_KEY_1` 별칭과 다른 앱 키를 root 전용 `.env.seed.keys.local`로 내보냄 |

기존 파일·기존 클라우드 백업·내보낸 Seed 키 파일을 자동으로 덮어쓰거나 회전하지 않는다. 중복 준비는 파일 잠금으로 거절한다.
일부 파일 기록에 실패하면 이 실행에서 만든 준비 디렉터리만 제거하고 부분 설정을 설치하지 않는다.
이미 백업이 있는 환경은 `init`으로 다른 키를 만들지 말고 `restore`를 선택한다. `init` 후에는 데이터 사용 전 `backup`의 성공을 확인한다.
버전 1 이외의 새 키·기존 데이터 키 삭제·DB 비밀번호 교체는 이 도구의 범위 밖이다.

아래는 **배포 승인을 받은 이후** 운영자가 선택해 실행할 명령이다. 이번 작업에서는 실행하지 않는다.

```bash
# Linux root 운영자, 저장소 루트 기준. init과 restore 중 상황에 맞는 하나만 선택한다.
sudo python3 infra/dev/dev_keys.py init
sudo python3 infra/dev/dev_keys.py check
```

`backup/restore`는 추가로 `requirements.txt`의 boto3와 **도구를 실행하는 안전한 운영자 환경에 이미 준비된 AWS 프로필**이 필요하다.
AWS 인증 정보 파일을 저장소·Seed 담당자 공유 자료·BE 컨테이너로 복사하지 않는다. IAM 사용자를 만들거나 권한을 자동으로 넓히지 않는다.
예를 들어 별도 root 관리 Python 가상환경에 SDK를 설치한 뒤, 해당 운영자 환경에서 `backup --profile yamu` 또는 `restore --profile yamu`를 선택한다.
필요한 권한은 지정 Secret의 조회·최초 생성이며 기존 버전 덮어쓰기·삭제·정책 변경 권한은 사용하지 않는다. 다른 AWS 계정이면 Secret 접근 전에 중단한다.
Secrets Manager 전송은 SDK 메모리 호출로 처리하고 CLI 인자나 임시 JSON 파일에 키를 넣지 않는다.

Seed 담당자는 내보낸 파일을 승인된 비밀 전달 경로로 받아 키만 읽는다. 금융망 사용자·계좌·테스트 PIN은 별도로 준비한다.
기존 `LocalSeedLoader`는 localhost DB 전용이므로 이 파일을 받았다고 공용 RDS에 직접 실행되는 것은 아니다.
실제 공유 사용자 Seed 적재 방식은 데이터 담당자와 별도로 확인한다. 기존 로컬 데이터의 키를 공용 개발 키로 무작정 교체하지 않는다.

## 비배포 설정 검증

리뷰된 이미지와 공개 경로를 `infra/.env.dev.example`에서 저장소 밖 개인 환경 파일로 준비한다.
키·DB 비밀번호는 이 환경 파일에 넣지 않는다. 실제 배포에는 기존 공개 truststore가 있어야 한다.

```bash
sudo python3 infra/dev/validate_compose.py --env-file /관리자/경로/.env.dev
```

이 명령은 `docker compose config`의 결과를 메모리에서만 검증하고 서비스·DB에 접속하지 않는다.
현재 서버의 Compose 플러그인은 root 운영자 환경에서 확인했다. 일반 SSH 사용자에서 플러그인이 없다고 환경을 재설치하지 말고 위 sudo 경로를 사용한다.
프로젝트·DB 대상·TLS·포트·전용 네트워크/볼륨·Secret 경로·Flyway 비활성·JPA validate를 확인한다.
기본 Compose 명령을 직접 쓸 때도 비밀값이 노출될 수 있는 전체 `config` 출력은 피한다.
파일 검증만으로 컨테이너의 권한·RDS 실접속·실제 복호화·Redis 영속성이 입증되지는 않는다.

## 테스트와 후속 적용 조건

- `test_validate_compose.py`: 임시 비밀 파일 경로로 실제 Compose CLI를 렌더링한다. Docker 엔진·RDS 접속 없이 실행 가능하다.
- `test_dev_keys.py`: Linux의 격리 컨테이너 root에서 임시 파일과 가짜 AWS 클라이언트만 사용한다. 권한·원자적 설치·기존 키 보존·잠금·백업/복구·계정 제한·출력 억제를 확인한다.
- `DevConfigTreeBindingTest`: DB 없이 실제 Spring Boot 3.5에 파일을 바인딩하고, 같은 테스트 키로 만든 Seed 암호문을 복호화한다.

```bash
python3 -m unittest discover -s infra/dev -p 'test_*.py'
# BE 폴더: ./gradlew test --tests com.yamu.be.global.config.DevConfigTreeBindingTest
```

실제 적용 전에는 승인된 MR 머지, 개발 키 보관/복구 확인, 적재 담당자와 키 기준 합의, `yamu_dev` 백업 및 미적용 마이그레이션 반영이 필요하다.
그 후 별도 승인으로 개발 환경을 배포하고 health·RDS/Redis 연결·사용자 Seed 복호화를 확인한다. 이번 준비 과정에서는 해당 작업을 실행하지 않는다.

2026-11-02 검증 기록: Compose 설정 10개(로컬 CLI v5.3.0 및 서버 root CLI v5.6.1),
Linux 키 도구 16개(네트워크 차단 Rootless 임시 컨테이너·가짜 AWS 클라이언트),
Java 17의 실제 configtree 바인딩·동일 키 Seed 복호화 1개가 통과했다. 최종 검증 항목 27개에서 실패·오류·생략은 없다.
기존 개발 DB 설정 파일의 권한·필드·대상 검증도 값 출력 없이 통과했고, 제안 포트 18080은 점검 시 비어 있었다.
실제 키 파일 설치·Secret 생성/갱신·앱/Redis 배포·DB 변경·현재 데이터 복호화 검증은 실행하지 않았다.

근거: [Spring Boot 3.5 configtree](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files.configtree),
[Compose Secret 파일 주입](https://docs.docker.com/compose/how-tos/use-secrets/),
[파일 Secret 권한 주의](https://docs.docker.com/reference/compose-file/services/#secrets),
[Secrets Manager SDK](https://docs.aws.amazon.com/boto3/latest/reference/services/secretsmanager/client/create_secret.html).
