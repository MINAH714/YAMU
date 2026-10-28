# JPA · Flyway 규칙

DB 테이블 구조는 **Flyway SQL 파일**이 정하고, 코드에서 DB를 읽고 쓰는 건 **JPA**가 합니다.

```
Flyway : 테이블을 만든다/바꾼다   src/main/resources/db/migration/*.sql   ← 테이블 구조의 원본
JPA    : 테이블을 읽고 쓴다       @Entity + Repository
         ddl-auto: validate      ← 엔티티와 테이블이 맞는지 검사만. 다르면 서버가 안 뜸
```

## 1. 테이블 바꾸기 (Flyway)

### 파일 이름

```
V{날짜}_{그날 순번}__{설명}.sql

V20261022_1__create_user_tables.sql
V20261022_2__create_card_tables.sql
V20261105_1__add_fee_amount_to_bill_item.sql
```

- `V` 대문자, 버전과 설명 사이는 **밑줄 두 개**(`__`)입니다.
- 날짜 번호를 쓰는 이유: 여러 명이 동시에 만들어도 번호가 잘 안 겹칩니다(`V3`를 둘이 만드는 사고 방지).
- 위치: `be/src/main/resources/db/migration/`

### 지켜야 할 것

| 규칙 | 이유 |
|---|---|
| **이미 develop에 Merge된 SQL 파일은 절대 고치지 않는다** | Flyway가 파일 내용(체크섬)을 기억합니다. 고치면 모든 사람 서버가 "체크섬 불일치"로 안 뜹니다. 바꾸려면 새 파일을 추가합니다. |
| 컬럼 추가·변경은 `ALTER TABLE` 새 파일로 | 운영 DB는 지우고 다시 만들 수 없습니다. |
| Merge 전에 develop을 받아서 내 파일 번호가 가장 큰지 확인 | 남이 더 늦은 날짜 파일을 먼저 올렸으면 Flyway가 순서 문제로 멈춥니다. 내 파일 이름의 날짜·순번을 올려서 맞춥니다. |
| 내 PC에서만 고치던 중이라면 | 아직 Merge 전이면 파일을 고쳐도 됩니다. 단 내 DB에 이미 적용됐다면 `docker compose down -v`로 초기화하고 다시 띄웁니다. |

### 확인

서버를 켜면 로그에 `Migrating schema ... to version ...`이 나오고, DB의 `flyway_schema_history` 테이블에 적용 기록이 남습니다.

## 2. 엔티티 작성 (JPA)

```java
@Entity
@Table(name = "user_card")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)     // JPA용 빈 생성자. 밖에서는 못 씀
public class UserCard extends BaseTimeEntity {          // created_at·updated_at 자동

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // MySQL AUTO_INCREMENT
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)                    // 연관관계는 항상 LAZY
    @JoinColumn(name = "card_product_id", nullable = false)
    private CardProduct cardProduct;

    @Enumerated(EnumType.STRING)                          // enum은 항상 STRING
    @Column(nullable = false, length = 20)
    private UserCardStatus status;

    public void changePaymentDay(int day) { ... }         // Setter 대신 의도가 드러나는 메서드
}
```

| 규칙 | 이유 |
|---|---|
| **Setter 만들지 않기** (`@Setter`, `@Data` 금지) | 결제 상태 같은 값이 아무 데서나 바뀌면 추적이 안 됩니다. `payment.approve()`처럼 이름 있는 메서드로만 바꿉니다. |
| `@NoArgsConstructor(access = PROTECTED)` | JPA는 빈 생성자가 필요하지만, 우리 코드에서 빈 객체를 만들면 안 됩니다. |
| 연관관계 `FetchType.LAZY` | `@ManyToOne` 기본값은 EAGER(항상 같이 조회)입니다. 필요 없는 조회와 N+1의 원인이라 직접 LAZY로 바꿉니다. |
| `@Enumerated(EnumType.STRING)` | 기본값(ORDINAL)은 enum 순서 번호로 저장해서, 중간에 값을 하나 추가하면 기존 데이터가 엉뚱한 값이 됩니다. |
| created_at만 있는 테이블 → `BaseCreatedTimeEntity` 상속<br>updated_at도 있는 테이블 → `BaseTimeEntity` 상속 | ERD 공통 규칙. 시간은 JPA Auditing이 UTC로 채우니 직접 넣지 않습니다. |
| 동시 수정이 있는 테이블(`MONTHLY_LIMIT_USAGE` 등) → `@Version` | 두 요청이 동시에 한도를 깎으면 늦은 쪽이 실패합니다(낙관적 락). 덮어쓰기 사고 방지. |
| 금액은 `Long` (BIGINT) | ERD 공통 규칙. `int`는 21억에서 넘칩니다. |

## 3. 조회할 때 (N+1 피하기)

목록을 가져온 뒤 반복문에서 연관 엔티티를 꺼내면 쿼리가 N번 더 나갑니다.

```java
// 나쁨: 카드 6장이면 SELECT 7번
for (UserCard c : userCardRepository.findByUserId(userId)) { c.getCardProduct().getName(); }

// 좋음: 같이 쓸 게 확실하면 fetch join으로 한 번에
@Query("SELECT uc FROM UserCard uc JOIN FETCH uc.cardProduct WHERE uc.user.id = :userId")
List<UserCard> findWithProductByUserId(Long userId);
```

- 전역으로 `default_batch_fetch_size: 100`이 켜져 있어, 놓쳐도 N번이 `IN (...)` 몇 번으로 줄어듭니다(안전망일 뿐 fetch join이 기본).
- 로컬에서는 실행된 SQL이 콘솔에 찍힙니다. 같은 SELECT가 줄줄이 나오면 N+1입니다.

## 4. 트랜잭션과 응답

- `open-in-view: false`입니다. 트랜잭션이 끝난 뒤(Controller, JSON 변환)에는 LAZY 연관 엔티티를 꺼낼 수 없습니다(`LazyInitializationException`).
- 그래서 **Service의 `@Transactional` 안에서 응답 DTO로 옮겨 담아 반환**합니다. Controller는 엔티티를 그대로 반환하지 않습니다.
- 조회만 하는 메서드는 `@Transactional(readOnly = true)`.

## 5. 시간

- DB에 저장되는 시간은 전부 UTC입니다.
- 실적 월, 청구 마감일, 출금일, 영업일처럼 **한국 날짜가 기준인 계산은 `ZoneId.of("Asia/Seoul")`을 명시**합니다.

## 6. 테스트

| 종류 | 방법 | DB |
|---|---|---|
| 일반 테스트 (컨트롤러, 서비스 로직) | 그냥 작성 (`test` 프로필) | 필요 없음. Flyway·스키마 검사 꺼짐 |
| DB가 필요한 테스트 (Repository, 쿼리, Flyway) | `MySqlTestSupport`를 상속 | Testcontainers가 MySQL 8.4.11을 잠깐 띄움 (Docker 필요) |

```java
class UserCardRepositoryTest extends MySqlTestSupport {
    @Autowired UserCardRepository userCardRepository;
    @Test void ...() { ... }
}
```

- DB 테스트는 진짜 Flyway 마이그레이션이 적용된 MySQL에서 돌고, 엔티티와 테이블이 다르면 실패합니다.
- Docker가 꺼진 PC에서는 이 테스트들만 건너뜁니다(실패가 아님).
- 테스트 전용 테이블은 `src/test/resources/db/testmigration/`에 둡니다(서버에는 안 들어감).
