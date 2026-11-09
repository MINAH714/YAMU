# 개인정보 암호화·조회 해시·PIN 해시

휴대폰·계좌·카드번호·SSAFY userKey·PIN을 DB에 저장할 때 쓰는 공통 모듈(`com.yamu.be.global.crypto`)의 사용법입니다.
**값이 서버 키로 만들어지므로 SQL로 직접 넣지 않고 반드시 이 모듈을 거칩니다.** MOCK 사용자 시드도 마찬가지입니다.

## 무엇을 쓰나

| 저장할 것 | 컬럼 | 쓰는 것 |
|---|---|---|
| 휴대폰 원문 | `app_user.phone_enc` | `PersonalDataCipher.encrypt(PersonalDataNormalizer.phone(raw), CipherField.APP_USER_PHONE)` |
| 휴대폰 조회 해시 | `app_user.phone_hash` | `LookupHasher.phone(raw)` |
| SSAFY userKey | `app_user.ssafy_user_key` | `PersonalDataCipher.encrypt(userKey, CipherField.APP_USER_SSAFY_USER_KEY)` |
| PIN | `app_user.pin_hash` | `PinHasher.hash(pin)` / 확인은 `PinHasher.matches(pin, pinHash)` |
| 계좌번호 원문 | `linked_account.account_no_enc` | `PersonalDataCipher.encrypt(PersonalDataNormalizer.accountNo(raw), CipherField.LINKED_ACCOUNT_NO)` |
| 계좌 조회 해시 | `linked_account.account_no_hash` | `LookupHasher.account(bankCode, raw)` |
| 카드번호 원문 | `user_card.card_no_enc` | `PersonalDataCipher.encrypt(PersonalDataNormalizer.cardNo(raw), CipherField.USER_CARD_NO)` |
| 카드 조회 해시 | `user_card.card_no_hash` | `LookupHasher.card(raw)` |

- `LookupHasher`는 안에서 정규화하므로 받은 값을 그대로 넘깁니다. 암호화할 원문은 위 표처럼 정규화한 값을 넘겨 화면 표시·SSAFY 호출에 같은 모양을 씁니다.
- 형식이 틀린 입력(휴대폰이 아닌 번호 등)은 `400 INVALID_REQUEST`입니다. 복호화 실패(변조·다른 컬럼 값·키 불일치)는 데이터 문제라 `500`입니다.

```java
@RequiredArgsConstructor
class SignupService {
    private final PersonalDataCipher cipher;
    private final LookupHasher lookupHasher;

    void signup(String rawPhone, ...) {
        byte[] phoneHash = lookupHasher.phone(rawPhone);   // 이어받기·중복 확인: findByPhoneHashAndBirthDate
        byte[] phoneEnc = cipher.encrypt(PersonalDataNormalizer.phone(rawPhone), CipherField.APP_USER_PHONE);
        ...
    }
}
```

## 어떻게 저장되나

| 종류 | 방식 | 결과 |
|---|---|---|
| 암호화 `*_enc` | AES-256-GCM. AAD에 컬럼 이름(`CipherField`)을 넣어 다른 컬럼으로 옮기면 열리지 않음 | 봉투 `[버전 1B][키 번호 1B][nonce 12B][암호문][tag 16B]`, 길이 = 30 + 평문 바이트, 최대 2048 |
| 조회 해시 `*_hash` | HMAC-SHA256(hmac-key, 정규화 값) | 항상 같은 32바이트 |
| PIN `pin_hash` | BCrypt(비용 10)(Base64(HMAC-SHA256(pin-pepper, PIN))) | `$2a$10$…` 60자 |

정규화 규칙

| 값 | 규칙 | 예 |
|---|---|---|
| 휴대폰 | 숫자만, 국가번호 `82`는 국내 형식으로(`+82 10…`, `+82 010…` 모두), `010`은 11자리·`011/016~019`는 10~11자리 | `+82 10-1234-5678` → `01012345678` |
| 계좌 조회 키 | `은행코드(영문·숫자 20자 이내):숫자만(8~20자리)` | `088:0888224489693615` |
| 카드번호 | 숫자만(14~19자리) | `9410-1234-1234-5678` → `9410123412345678` |

기술 선택 이유와 그림 설명: 「YAMU 개인정보 암호화 설명서」(팀 공유 링크는 BE 1에게).

## 키 설정

| 설정 | 환경변수(운영) | 내용 |
|---|---|---|
| `yamu.crypto.data-key-id` | `YAMU_CRYPTO_DATA_KEY_ID` (기본 1) | 새로 암호화할 때 쓸 키 번호(1~255) |
| `yamu.crypto.data-keys[1]` | `YAMU_CRYPTO_DATA_KEY_1` | AES-256 키, 32바이트 Base64 |
| `yamu.crypto.hmac-key` | `YAMU_CRYPTO_HMAC_KEY` | 32바이트 이상 Base64 |
| `yamu.crypto.pin-pepper` | `YAMU_CRYPTO_PIN_PEPPER` | 32바이트 이상 Base64 |

```bash
openssl rand -base64 32   # 키 하나 만들기. 네 값 모두 서로 다른 키로
```

- **로컬**: `application-local.yml.example`의 `yamu.crypto` 부분을 내 `application-local.yml`에 복사하고, 빈 칸 네 개에 `openssl rand -base64 32` 결과를 하나씩 넣습니다. 키는 깃에 올리지 않습니다(AGENTS.md 규칙 4). 키를 바꾸면 로컬 DB의 암호문·해시가 맞지 않으니 `docker compose down -v`로 초기화합니다.
- **테스트**: `src/test/resources/application-test.yml`의 테스트 전용 키를 씁니다. 실제 데이터에는 쓰지 않습니다.
- **운영**: 위 환경변수 중 키 3개(`DATA_KEY_1`, `HMAC_KEY`, `PIN_PEPPER`)를 넣어야 서버가 뜹니다. 키 번호는 기본 1입니다. 비어 있거나, 길이가 틀리거나, 서로 같은 값이면 시작할 때 실패합니다(`CryptoKeys`). 키가 없는 채로 master에 배포되면 백엔드가 뜨지 않으므로, `infra/compose.prod.yml`의 backend `environment`에 3개를 먼저 추가해야 합니다.
- 키는 DB·깃(로컬 전용 제외)·로그에 넣지 않습니다. **운영 키를 잃으면 암호화된 값은 다시 열 수 없으니** 팀이 안전한 곳에 백업합니다.
- `hmac-key`·`pin-pepper`를 바꾸면 기존 해시가 전부 맞지 않으므로 사실상 고정입니다.
- 데이터 키를 바꿀 때는 새 번호로 추가하고 `data-key-id`만 올립니다. 옛 번호 키는 지우지 않습니다(옛 봉투를 열어야 하므로).

## 시드(MOCK 사용자)

- 시드 코드는 이 모듈을 주입받아 값을 만듭니다. SQL 파일에 바이트를 적어 넣으면 해시·복호화가 맞지 않습니다.
- 키가 환경마다 다르므로 **시드는 환경마다 그 환경의 키로 다시 실행**합니다. 로컬에서 만든 행을 다른 DB로 복사하지 않습니다.
- 휴대폰+생년월일 조합은 사람마다 달라야 합니다(`uk_user_phone_birth`). 카드번호는 DB가 사람 사이 중복을 막지 않으므로 시드에서 전체 중복을 확인합니다.

## 무엇을 평문으로 두고, 탈퇴 뒤 언제 지우나 (임시 정책, 2026-11-09)

운영 점검 Q-6에서 정했습니다(S15P31E103-380). 시연용 모의 서비스 기준이며, 실서비스로 가면 다시 정합니다.

| 값 | 저장 | 이유 |
|---|---|---|
| 휴대폰 | 암호화 + 조회 해시 | |
| PIN | BCrypt 해시 | 비밀번호는 법적 암호화 대상 |
| 카드번호·계좌번호 | 암호화 + 조회 해시 | 법적 암호화 대상 |
| 이름 | **평문(임시)** | 법적 암호화 대상(고유식별정보·비밀번호·생체·카드·계좌번호)이 아님. 로그에는 넣지 않는다(로그 가림 장치가 모양으로 알아보지 못함) |
| 생년월일 | 평문 | 재가입 찾기(`uk_user_phone_birth`)에 쓴다. 이름 없이는 사람을 알아보기 어렵다 |

탈퇴하면

- **바로 지움:** 이름, 휴대폰 암호문, PIN, 기기 키·이름, 계좌번호(무작위로 덮음), 본인 인증 기록의 IP·User-Agent, 잔고예보 설정, Redis의 토큰·캐시
- **30일 보관 후 지움**(정기 작업 `withdrawn-key-purge`, 매일 03:30 KST): 재가입 열쇠인 phone_hash·birth_date·withdrawn_name_hash·ssafy_member_id·ssafy_user_key, 카드 행의 카드번호(무작위로 덮음)·카드사 계좌 해시. 30일 안에 같은 사람이 다시 가입하면 이어받고(`RejoinService`), 지난 뒤에는 새 회원이 된다. SSAFY 계정은 지우는 API가 없어 SSAFY 쪽에 남는다.
- **남김:** 결제·청구·환급 이력(옛 user_id에 연결). 운영 로그(Loki)는 7일 뒤 자동 삭제.
  - **사용자가 직접 쓴 글은 탈퇴 때도 30일 뒤에도 지우지 않는다(한계):** 결제 메모(`payment_transaction.memo`), 예정 수입·지출의 제목·메모(`cashflow_item`), 회차 메모, 구매 계획 메모·상품명. 이 칸들은 "민감정보 입력 주의" 칸이라 사람을 알아볼 수 있는 내용이 들어 있을 수 있다. 지우려면 각 기능(BE 2·BE 4) 쪽과 정해야 한다.
- 보관 기간은 `yamu.user.withdrawn-key-purge.retention`(기본 30d). 약관에 "탈퇴 후 재가입을 위해 30일 보관"을 적어야 한다(약관 문안 B-04).
