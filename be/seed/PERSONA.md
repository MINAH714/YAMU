# 가입 시연용 페르소나 시드

회원가입은 **시드에 있는 사람만** 할 수 있습니다(10/30 결정). 앱에서 본인인증 → 계좌 고르기·1원 인증 → PIN → 기기 등록까지 가입을 시연하려면, 가입 직전 상태(PENDING)의 페르소나가 DB에 미리 있어야 합니다. 이 로더가 그 페르소나를 넣습니다.

카드 상품·혜택과 이미 가입을 마친 테스트 사용자는 [README.md](README.md)의 시드(`load.ps1`)가 넣습니다. 이 로더는 그 위에 **가입 전 페르소나만** 더합니다.

## 넣는 것과 넣지 않는 것

| 표 | 넣는 값 |
|---|---|
| `app_user` | `PENDING`, 이름·생년월일·휴대폰(암호문·조회 해시)·SSAFY 회원 ID·userKey(암호문). PIN·본인인증 시각은 비움 |
| `user_card` | `PENDING`, 출금 계좌 없음. 카드 확인 → 등록을 시연할 카드 |
| 넣지 않음 | `linked_account`(가입 중 SSAFY 계좌 목록에서 골라 연결), `user_device`(가입의 마지막 단계) |

계좌번호는 파일에 넣지 않습니다. 계좌는 SSAFY에만 두면 가입할 때 목록에 나옵니다.

## 왜 SQL 파일이 아닌가

- 휴대폰·userKey·카드번호 칸은 **서버의 암호화 키**로 만든 암호문과 조회 해시가 들어갑니다. 키는 환경마다 다르고 깃에 올릴 수 없어서, 서버 설정을 그대로 읽는 코드로 넣습니다.
- SSAFY 회원·계좌는 금융망에만 있어, 넣기 전에 userKey로 계좌 목록을 읽어 실제로 있는지 확인합니다.

## 준비

1. 로컬 DB가 떠 있고 카드 카탈로그가 들어가 있어야 합니다(`docker compose up -d`, [README.md](README.md)의 시드).
2. 페르소나마다 **SSAFY에서 회원과 수시입출금 계좌(잔액 포함)를 미리 만듭니다.** 받은 회원 ID와 userKey를 파일에 적습니다.
3. SSAFY API 키가 앱 설정에 있어야 합니다(`application-local.yml`의 `yamu.fin-api.api-key` 또는 환경변수 `SSAFY_API_KEY`).
4. 견본을 복사해 값을 채웁니다. `persona.local.json`은 깃에 올라가지 않습니다.

   ```powershell
   cd be
   Copy-Item seed\persona.example.json seed\persona.local.json
   ```

### 파일 항목

| 항목 | 필수 | 설명 |
|---|---|---|
| `label` | | 로그에 쓸 이름표(예: `A 직장인`). 개인정보를 넣지 않습니다 |
| `name` · `birthDate` · `phone` | ✅ | 앱 본인인증에 넣을 값과 같아야 합니다. 번호는 숫자 10~11자리 |
| `ssafyMemberId` · `ssafyUserKey` | ✅ | SSAFY에서 만든 회원 |
| `cards[].productName` | ✅ | `card_product.product_name`. 이름이 여러 카드사·종류에 겹치면 `issuerCode`·`cardType`도 넣습니다 |
| `cards[].cardNo` | ✅ | 숫자 16자리. 카드 확인 화면에 넣을 번호 |
| `cards[].expiry` | | `YYYY-MM`. 저장할 칸이 없어 형식만 확인합니다 |
| `cards[].issuerStatus` | | `NORMAL`(기본)·`SUSPENDED`·`EXPIRED`. 정지·만료 카드 시연용 |

## 실행 (be 폴더에서)

```powershell
# 넣기. 같은 사람(휴대폰·생년월일)이 이미 있으면 건너뜁니다.
.\gradlew.bat bootRun --args="--spring.profiles.active=local,persona-seed"

# 시연을 처음부터 다시: 아직 PENDING인 페르소나만 지우고 다시 넣습니다.
.\gradlew.bat bootRun --args="--spring.profiles.active=local,persona-seed --reset"
```

- 웹 서버는 뜨지 않고, 다 넣으면 종료합니다. 실행 중인 서버가 있어도 괜찮습니다.
- 끝에 페르소나마다 결과가 한 줄씩 나옵니다: `CREATED`·`RESET`·`SKIPPED_EXISTS`·`SKIPPED_NOT_PENDING`·`FAILED`.
- 종료 코드: `0` 모두 성공·건너뜀, `1` 실패한 페르소나가 있음, `2` 파일을 읽지 못함.

## 동작 규칙

| 상황 | 결과 |
|---|---|
| 처음 넣음 | `CREATED` |
| 이미 있음(초기화 안 함) | `SKIPPED_EXISTS`. 아무것도 바꾸지 않습니다 |
| 초기화 + 아직 PENDING | `RESET`. 가입 중 연결한 계좌·카드까지 지우고 다시 넣습니다(새 user_id) |
| 초기화 + 가입을 마침(ACTIVE)·탈퇴 | `SKIPPED_NOT_PENDING`. 지우지 않습니다 |
| SSAFY 계좌 0개, 키 오류, 금융망 실패 | `FAILED`. 그 페르소나만 넣지 않습니다 |
| 카드 상품을 못 찾음·이름이 겹침 | `FAILED`. 카드 카탈로그 시드와 `issuerCode`·`cardType`을 확인합니다 |
| 입력 형식 오류, 같은 파일에 같은 사람 두 번 | `FAILED` |
| 두 실행이 동시에 같은 사람을 넣음 | 한쪽은 `CREATED`, 다른 쪽은 `SKIPPED_EXISTS` (DB UNIQUE가 막음) |

- 페르소나마다 트랜잭션이 따로라 한 명이 실패해도 나머지는 들어갑니다.
- 로그에는 순번·이름표·결과만 남깁니다. 이름·번호·userKey·카드번호는 남기지 않습니다.
- SSAFY 회원·계좌는 지울 수 없어서 초기화해도 그대로 둡니다. 같은 회원으로 다시 넣으면 됩니다.

## 시연 순서 예

1. 로더로 페르소나를 넣습니다.
2. 앱에서 그 페르소나의 이름·생년월일·번호로 본인인증 → 화면의 인증번호 입력.
3. 계좌 목록에서 고르고 1원 인증 → SSAFY 거래내역에서 코드를 확인해 입력.
4. PIN 설정 → 기기 등록 → 가입 완료(ACTIVE).
5. 다시 하려면 `--reset`. 가입을 마친 사람은 초기화되지 않으니, 처음부터 보여 줄 페르소나는 따로 두거나 DB를 초기화합니다.
