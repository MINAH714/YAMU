# be/AGENTS.md

`be/`(Spring Boot API)에서 작업할 때 루트 `AGENTS.md`와 함께 따르는 규칙입니다.
작업 전에 아래 두 문서를 읽습니다. Claude Code는 `@` 줄을 보고 문서 내용을 직접 불러옵니다.

@../docs/be/local-setup.md
@../docs/be/jpa-flyway.md

## 명령 (be 폴더에서)

```powershell
docker compose up -d     # 로컬 MySQL 8.0 + Redis 7. 처음 한 번: application-local.yml.example → application-local.yml 복사
.\gradlew.bat bootRun    # http://localhost:8080/swagger-ui.html, /actuator/health
.\gradlew.bat test       # DB 없이 도는 테스트 + Docker가 있으면 Testcontainers DB 테스트
```

## 패키지

- 모든 기능이 같이 쓰는 코드: `com.yamu.be.global` (`common` 응답, `exception` 에러, `config` 설정, `entity` 공통 엔티티)
- 기능 코드: `com.yamu.be.{도메인}` (예: `card`, `payment`)

## 규칙

- **응답**: 모든 API는 `ApiResponse`로 감쌉니다. 형식은 API 명세서 1장을 따릅니다. 성공 `{ success: true, data, error: null }`(`ApiResponse.ok(data)`), 실패 `{ success: false, data: null, error: { code, message, details } }`. 입력값 검증 실패의 필드별 사유는 `error.details.fieldErrors`입니다. `GET /api/health`만 예외입니다.
- **실패 처리**: 에러 코드 이름은 API 명세서에 적힌 code를 그대로 `ErrorCode`에 추가하고(공통은 1장, 기능별은 각 API 페이지), Service에서 `throw new BusinessException(ErrorCode.X)`. 명세서에 `details`가 있으면 `new BusinessException(ErrorCode.X, Map.of(...))`. try-catch로 실패 응답을 직접 만들지 않습니다. 배포된 코드 이름은 FE가 쓰므로 바꾸지 않습니다.
- **입력값**: 요청 DTO는 record + `@NotBlank(message = "...")` 등, 컨트롤러에 `@Valid`. FE에 보일 문구는 `message`로 직접 적습니다.
- **JSON**: camelCase. 날짜는 ISO-8601 문자열. 시각(`LocalDateTime`)은 UTC에 `Z`를 붙여 `"2026-10-22T05:30:00Z"`로 나갑니다. 한국 날짜가 기준인 값(결제일·청구일)은 `LocalDate`로 내보냅니다.
- **시간**: 저장은 전부 UTC. 실적 월·청구 마감일·출금일·영업일처럼 한국 날짜가 기준인 계산은 `ZoneId.of("Asia/Seoul")`을 명시합니다.
- **DB**: JPA + Flyway. 테이블 변경은 `src/main/resources/db/migration`에 새 SQL 파일(`V{날짜}_{순번}__설명.sql`)로 추가하고, **Merge된 SQL 파일은 고치지 않습니다**. `ddl-auto`는 `validate`입니다.
- **엔티티**: Setter 없음, 연관관계 `LAZY`, enum은 `STRING`, 시간 컬럼은 `BaseTimeEntity`·`BaseCreatedTimeEntity` 상속, 동시 수정되는 값은 `@Version`. 컨트롤러는 엔티티를 그대로 반환하지 않고 Service 트랜잭션 안에서 DTO로 바꿉니다.
- **테스트**: DB가 필요한 테스트는 `MySqlTestSupport`를 상속합니다(Testcontainers MySQL).
