# YAMU Android 앱

카드 추천 · 실적 관리 · 잔고 예보 — SSAFY 금융망 데이터와 연동되는 가상 금융 서비스의 Android 앱(Kotlin · Jetpack Compose).
설계 문서는 저장소 밖(발표 폴더 · 노션)에 있다 — 아래 'NN 문서'는 그 설계 문서 번호. 화면 기준은 프로토타입 58장.

## 열기 · 실행
1. Android Studio → **Open** → 저장소의 `fe/android` 폴더(저장소 루트가 아니라 이 폴더) → Gradle 동기화가 끝날 때까지 기다리기. 아래 명령도 이 폴더에서 실행한다.
2. 왼쪽 아래 **Build Variants**에서 앱을 고른다 — `customerDebug`(고객 앱 YAMU) 또는 `posDebug`(모의 POS 단말 YAMU 가맹점). 두 앱은 패키지가 달라 한 기기에 같이 깔린다.
3. 위쪽 기기 목록에서 에뮬레이터(Medium Phone API 37) 또는 연결한 폰을 고르고 ▶ Run.
4. 명령으로 빌드: `gradlew.bat assembleCustomerDebug assemblePosDebug` · 테스트: `gradlew.bat testCustomerDebugUnitTest` (JDK는 Android Studio 내장 `jbr`).
5. 모의 POS 단말 키: `local.properties`에 `yamu.posKey=백엔드가 발급한 키` 한 줄(가짜 데이터일 땐 없어도 됨).
6. 가짜 데이터: debug 빌드는 기본으로 전부 가짜이고, `local.properties`의 `yamu.realApi`에 적은 기능만 진짜 서버를 쓴다(아래 '실서버 연결'). release에는 가짜 코드가 들어가지 않는다(`src/debug`).
7. release(R8로 줄인 것)를 기기에서 시험: `gradlew.bat assembleCustomerRelease assemblePosRelease -Pyamu.devSign` → debug 앱을 지우고 설치(패키지가 같음). 버전 끝에 `-devsign`이 붙은 시험본이라 밖에 나눠 주지 않는다. 진짜 서명 · 확인 순서는 설계 문서 17(release 정리).

## 지금 상태 (2026-10-27)
| 단계 | 상태 |
|---|---|
| 0 새 프로젝트 | ✅ AGP 9.4.1 · Kotlin 2.4.21 · Gradle 9.8.2 · compileSdk/targetSdk 37 · minSdk 30 · Hilt · Navigation(타입 안전 경로) |
| 1 디자인 시스템 | ✅ `core/designsystem` — 테마(색 · 글자 · 간격) · 컴포넌트 30여 개 · 컴포넌트 갤러리 |
| 2 앱 뼈대 | ✅ `navigation` — 경로 약 45개 · 탭 5개 · 뒤로 가기 규칙 · 빈 화면 · 디버그 메뉴 |
| 3 네트워크 · 데이터 공통 | ✅ `core/network`(응답 · 오류 · 토큰 재발급) · `core/security`(Keystore 암호화 저장) · 홈(H-01) 실제 화면 · 단위 테스트 15개 |
| 4 가입 · 로그인 · 보안 | ✅ `feature/auth` — 첫 화면 · 약관 · 가입 정보 · 간편 비밀번호 · 가입 완료 · 로그인 · 잠금 해제, `core/security`(세션 · 기기 키 · 지문 서명), 단위 테스트 23개 |
| 5 계좌 · 카드 연결 + 첫 분석 | ✅ `feature/link` — 카드 번호로 확인 · 등록(가입 뒤 · 카드 추가 공통) · 결제 계좌 연결 · 월급 · 고정 지출 · 첫 분석 · 알림 권한, 공통 본인 인증(`feature/auth/StepUp` · 간편 비밀번호 화면), 단위 테스트 36개 |
| 6 결제 + 모의 POS 단말 | ✅ `feature/payment` — 카드추천 탭 · 결제 코드(QR · 바코드 · 3분 · 1초 확인) · 결제 요청 확인(60초 · 카드 · 할부 · 거절 · 재승인) · 완료 · 거래 상세, 모의 POS 단말 앱(`src/pos` — 가맹점 · 금액 · 카메라 찍기 · 결과), 단위 테스트 46개 |
| 7 실적 · 유불리 | ✅ `feature/performance` — 실적관리 탭(돌아올 때마다 새로 · 결제 반영 표시) · 카드 실적 상세 · 유불리 분석(판정 3가지 · 판단 기록 · 되돌리기 · 예정 지출 옮기기) · 혜택 한도, 단위 테스트 55개 |
| 8 잔고예보 · 이거 사도 될까? | ✅ `feature/forecast` — 잔고예보(이거 사도 될까? 입구 · 계단 그래프 · 자세히 · 계좌별) · 예정 수입 · 지출 · 이거 사도 될까?(금액 · 품목 · 날짜 → 판정 · 방법 비교 · 계획 저장 · 지우기), 단위 테스트 61개 |
| 9 · 10 소비분석 · 마이 | ✅ `feature/report` — 소비분석 탭 · 혜택 리포트 · 결제 내역(쪽 넘김), `feature/my` — 마이 탭 · 카드 관리 · 알림 설정 · 회원 정보(다른 기기 로그아웃) · 보안 · 인증 · 홈 알림함 · 내 카드, `navigation/AutoLock`(앱 자동 잠금), 빈 화면 0장(58장 전부 실제 화면), 단위 테스트 78개 |
| 디자인 맞추기 | ✅ 프로토타입 58장과 같은 크기로 나란히 찍어 맞춤(14 문서) — 공통 부품(목록 줄 56 · 72dp · 키–값 줄 · 상세 화면 회색 바탕 + 카드 · 탭 아이콘 Material Symbols · 마이 큰 제목 · 테두리형 입력칸 · 단계 막대 · 키패드 · 태그 · 칩 · 자간 · 한국어 어절 줄바꿈) → 화면별, 글자 위치를 재서 2dp 넘게 어긋난 간격 161곳 → 36곳(남은 건 줄 순서 · 서버 값 · 에뮬레이터 차이와 재기 잡음 — 14 문서 14-7), 라이트 · 다크 · 글자 1.3배 확인 |
| 11 통합 확인 · 배포 | 진행 중 — 데이터 상태 확인표를 가짜 데이터로 끝(디버그 '모든 기능 상태' · 빈 상태 · 오류 · 새로고침 실패 때 마지막 값과 시각, 단위 테스트 80개) · release 정리(R8 · 리소스 줄이기 · 서명 자리 · POS 64비트만) 합침 + R8 release 기기 확인 9개 통과(고객 10.0MB · POS 21.1MB) · 캐릭터 임시 그림 합침. 남은 것: 실제 서버 확인 · 캐릭터 진짜 그림 · 서명 키 |

## 디버그 메뉴 (debug 빌드만)
첫 화면(A-01) · 잠금 해제 · 마이 탭의 **'디버그 메뉴'** 버튼.
- **시작 상태** 고르기(처음 사용 / 로그인 · 연결 전 / 로그인 · 연결 끝) → '이 상태로 처음부터'
- **모든 기능 상태 (가짜 데이터)**: 정상 · 데이터 없음(새 사용자) · 금융망 오류(502) · 네트워크 없음 · 데이터 부족(422) · 느린 응답 — 화면이 처음 불러오는 값 20곳에 걸린다(04 문서 데이터 상태 확인표 · 15 문서). 정상으로 두면 아래 기능별 상태대로
- **홈 상태 (가짜 데이터)**: 정상 · 가입 첫날 · 결제 반영 직후 · 연결 전 · 지금 명세 모양 · 예상 잔액만 실패 · 금융망 오류 · 네트워크 없음 · 느린 응답
- **가짜 인증 시험값**: 본인인증 번호 01012345678은 가입 전 시드 사용자 · 01099998888은 이미 가입(이 폰 재등록) · 그 밖은 시연 사용자 아님(이름 · 생년월일은 아무거나). 인증번호 6자리와 1원 숫자는 화면 위 문자 알림을 누르면 채워진다(1원 1004 · 0000은 시간 지남). 간편 비밀번호 기본 135790(5번 틀리면 30분 잠김) · 로그인 때 000000은 서버에서 기기가 없어진 경우(첫 화면으로). 시작 상태 '로그인 · 연결 전 · 끝'은 이 폰을 기기 7번으로 등록해 둔 상태
- **연결 상태 (가짜 데이터)**: 정상 · 등록 때 카드 확인 시간 지남 · Mr.Life 등록 때 결제 계좌 미연결(409) · 계좌 연결 때 목록 만료(한 번) · 계좌 연결 때 금융망 오류
- **결제 상태 (가짜 데이터)**: 코드를 열고 3초 뒤 가짜 가맹점이 찍음(카페 15,000원 · 마트 90,000원 할부) · 1순위 체크카드 잔액 부족 · 아무도 안 찍음(20초 코드). 고객 앱과 POS 앱을 이어서 결제하는 건 실제 서버로만
- **가짜 카드**: BE 카드 시드(`be/seed` 01 · 02)에 있는 상품만 쓴다 — 톡톡 · ZERO 포인트형 · LIKIT Shop · iD ON · Mr.Life · 노리2 체크(+ 카드 추가용 트래블로그 체크). 혜택 · 실적 기준 · 월 한도도 시드 값. 혜택이 브랜드에 걸린 가맹점은 '스타벅스 역삼점' · 'GS25 역삼역점'
- **가짜 실적 장부**: 가짜 결제가 승인되면 실적에 더해진다 — 스타벅스 15,000원을 톡톡카드로 두 번 결제하면 톡톡카드(270,000 / 300,000)가 '달성'으로 옮겨 간다
- **가짜 소비분석 · 결제 내역**: 생활 소비 842,000 · 받은 혜택 32,400 · 결제 67건 2,164,200원(쪽마다 20건). 이번 실행에서 가짜 결제를 하면 결제 내역 맨 위 · 합계 · 알림함에 더해진다. 오전 10시 8분 전에는 '오늘' 줄이 아직 오지 않은 시각이라 내역 전체가 하루 앞으로 간다
- **가짜 마이 · 알림**: 설정(자동 선택 · 알림 종류 · 받는 시각 · 결제 코드 인증)은 앱을 끄면 처음 값, 자동 잠금 시간만 기기에 저장. 다른 기기 'Chrome · Windows' 1대, 알림 5개(안 읽음 3 — 홈 종 숫자도 같은 값)
- **앱 자동 잠금 시험**: 마이 → 보안 · 인증 → 앱 자동 잠금 '바로' → 홈 버튼 → 다시 열면 잠금 해제 화면(풀면 보던 화면)
- **가짜 잔고예보**: 잔액 950,000 · 예정 지출 4건 450,000 · 하루 생활비 6,000 → 월급 전 350,000(월급 = 오늘 + 25일). 이거 사도 될까? 400,000원은 '미루는 게 좋아요', 129,000원은 '괜찮아요'. 계획을 저장하면 예보에 같이 계산된다
- **예정 수입·지출 추가(F-02 +)**: 등록하면 가짜 서버가 회차를 계산해 예보 · F-02에 더한다(매월 말일은 31일이 없는 달에 그달 마지막 날). 시험 이름 `TIMEOUT`은 응답을 못 받은 경우(실제로는 등록됨), `INVALID`는 서버가 이름 칸을 거절한 경우
- **예정 수입·지출 수정 · 삭제(F-02 줄)**: 월세 · 통신비 · 구독료는 가짜 서버의 매월 반복 항목이라 눌러서 고칠 수 있다(카드대금 · 월급은 읽기 전용). 이번 회차만 · 이후 전부 · 삭제를 서버 규칙대로 흉내 낸다. 이름을 `PAID`로 바꿔 저장하면 결제된 회차(409)
- **모의 POS 가짜 시험값**([번호로 넣기]): EXPIRED가 들어가면 만료 · USED는 이미 쓴 코드 · DECLINE은 거절 · REJECT는 고객 거절, 그 밖은 5초 뒤 승인
- **가짜 카드 등록 시험값**: 카드 번호는 `9410 0000 0000` + 끝 4자리, 유효기간은 지나지 않은 아무 달이다. 끝 4자리 1021 톡톡 · 3310 ZERO · 7782 LIKIT Shop · 4410 iD ON · 8823 노리2 체크 · 5527 트래블로그 체크 · 5096 Mr.Life(결제 계좌 신한 ·5678을 먼저 연결) · 0001 카드사 만료 · 0002 카드사 정지 · 0003 이미 등록 · 그 밖은 없는 카드. 시작 상태 '로그인 · 연결 끝'이면 트래블로그만 새로 등록할 수 있다
- **화면 바로 열기**, **컴포넌트 갤러리**(다크 모드 · 글자 1.3배 · 청록 미리보기)
- **서버 연결**: 지금 주소와 진짜로 쓰는 기능을 보여 주고, '서버 연결 확인'으로 `GET /api/health`를 불러 본다(10초 안에 응답이 없으면 실패)

## 실서버 연결 (debug)
`local.properties`에 주소를 적고 다시 빌드한 뒤 디버그 메뉴의 '서버 연결 확인'까지 본다. 비워 두면 로컬 BE 주소에 전부 가짜라서 지금까지와 같다.

```properties
yamu.baseUrl=http://localhost:8080/api/
```

- `yamu.baseUrl`은 `http://`나 `https://`로 시작하고 `/`로 끝나야 한다(아니면 빌드가 멈춘다). 로컬 BE는 `adb reverse tcp:8080 tcp:8080` 뒤 `localhost`, 에뮬레이터는 `http://10.0.2.2:8080/api/`도 된다.
- http는 `network_security_config`가 `localhost`와 `10.0.2.2`만 허용한다. LAN IP(`http://192.168.…`)는 막히므로 실기기는 `adb reverse`로 `localhost`를 쓴다.
- 기능을 진짜로 켤 때는 `yamu.realApi`에 이름을 쉼표로 적는다. 쓸 수 있는 이름은 `auth` · `link` · `cashflow` · `home` · `payment` · `performance` · `forecast` · `report` · `my` · `pos`이다. `all`은 BE에 API가 있는 `auth` · `link` · `cashflow` · `payment` · `my` · `pos`만 켠다. 나머지는 이름을 직접 적어야 켜지고 지금 켜면 404가 난다. `cashflow`나 `forecast`를 켜면 `link`도 같이 켜진다(가짜 계좌 · 카드 번호가 진짜 서버로 가지 않게).
- `payment` · `my` · `pos`는 debug에서 BE에 없는 메서드만 가짜로 메운다(`src/debug/.../RealWithFakes.kt`, `src/posDebug/.../FakePosRepository.kt`). release에는 가짜가 없어 그대로 404다.
- 진짜 API는 로그인 토큰이 필요하다. `auth`를 가짜로 두고 다른 기능만 켜면 가짜 토큰이 실려 401이 나므로 다른 기능을 켤 때는 `auth`도 같이 켠다.
- 모의 POS는 `yamu.posKey`(BE `yamu.pos.key`와 같은 값), `yamu.posMerchantId`(단말의 `brand_code`와 같은 가맹점 번호, 시드마다 다름), `yamu.posCode`(기본 `POS-YAMU-CAFE`)를 적는다.

BE(develop, 2026-11-03)와 맞춰 본 결과는 이렇다.

| 이름 | 진짜로 부르는 API | BE에 없어서 남은 것 |
|---|---|---|
| `auth` | 본인인증 · 가입 중 계좌 연결 · PIN · 기기 등록 · 로그인 · 본인 확인 · 재발급 · 로그아웃 | 없음 |
| `link` | 카드 확인 · 등록 · 로그인 후 계좌 추가 · 계좌 · 카드 목록 · 계좌 별칭 · 기준 계좌 · 카드 상세 · 수정 · 해제 · 순서 | 없음 |
| `cashflow` | `cashflow-items` 등록 · 조회 · 수정 · 삭제, `forecasts/settings` 조회 · 수정 | `forecasts/balance`(실패해도 화면은 진행, 하루 생활비 제안만 빠짐). 후보 항목을 만드는 곳이 없어 B-06은 늘 비어 있음 |
| `forecast` | 켜지 않음. debug에서는 `cashflow`를 켜면 가계부 메서드만 진짜(`RealCashflowWithFakes`) | `forecasts/balance`, `spending-decisions`, 구매 계획 구분. F-01과 F-02 합계 · 카드대금 · 월급은 가짜라 가계부 줄과 숫자가 맞지 않음 |
| `payment` | 결제 코드 발급 · 상태 · 승인 | 가맹점 목록, 결제 전 추천, 거절(요청이 마감까지 남음), 결제 상세. 후보에 카드사 · 카테고리가 없어 별칭으로 보임 |
| `my` | 기기 목록 · 해제(`APP_UNLOCK` 본인 확인), 회원 탈퇴(`WITHDRAW_MEMBER` 본인 확인) | 회원 정보, 결제 · 보안 설정, 알림. release는 회원 정보를 못 읽어 M-01에서 회원 정보 · 탈퇴로 들어갈 수 없음 |
| `pos` | 결제 요청 | 가맹점 목록, 결과 조회(결과는 고객 앱에서 확인) |
| `home` · `performance` · `report` | 켜지 않음 | 전부 |

로그인은 Task 8.2.8(S15P31E103-235)에서 BE 방식으로 바꿨다. 이 폰에 기기 번호가 있으면 앱을 열 때와 자동 잠금 때 같은 PIN · 지문 로그인 화면이 뜨고(`login/challenge` → `login`, 매번 새 토큰), 로그아웃은 토큰만 지운다. 기기 키는 둘이다 — 기기 키(지문 없이, PIN 로그인 · PIN 본인 확인 서명)와 지문 키(지문을 통과해야 서명). 서명 대상은 challenge 문자열의 UTF-8 바이트다.

카드 연결은 Task 8.2.13(S15P31E103-259)에서 BE 방식으로 바꿨다. 기관 · 세션 없이 카드 번호 16자리와 유효기간으로 내 명의 카드를 찾아(`cards/lookup`, 1분 5번) 본인 인증(`CARD_REGISTER`) 뒤 등록한다(`cards`). 번호는 화면 메모리에만 두고 확인이 끝나면 지운다. 확인 토큰은 5분이고 지나면 입력 화면으로 돌아간다. 카드사가 정한 결제 계좌가 아직 연결되지 않았으면 그 자리에서 계좌를 연결한다(`accounts/candidates` → 본인 인증 `ACCOUNT_LINK` → `accounts`, 1원 없음). 카드가 등록되면 BE 로그인 응답의 `onboardingCompleted`가 true가 된다.

카드 관리는 Task 8.2.14(S15P31E103-264)에서 붙였다. 카드 상세(`cards/{id}`)에서 별칭 · 자동 선택 포함 · 기본 카드를 바꾸고, 본인 인증(`CARD_DELETE`) 뒤 해제한다. 결제일 · 결제 계좌는 보여 주기만 한다. 바뀌는 시점이 BE 코드(바로)와 명세(다음 이용기간부터)에서 달라서다. 마지막 카드를 해제해도 로그인 중에는 그대로 쓰지만, 다시 로그인하면 `onboardingCompleted`가 false라 카드 등록부터 시작한다.

회원 탈퇴는 Task 8.2.16(S15P31E103-266)에서 붙였다. 서버가 끝냈을 때만 이 폰의 토큰 · 세션 · 기기 키를 지운다. 응답을 못 받았을 때 먼저 지우면 계정은 남았는데 기기만 비어 다시 들어갈 수 없어서다. 같은 사람이 다시 가입하면 BE가 새 사용자를 만들고 SSAFY 계정을 옮긴다. 옛 이력은 옛 사용자에 남아 보이지 않는다.

가입 · 기기 재등록은 Task 8.2.9(S15P31E103-236)에서 BE 방식으로 바꿨다. 이메일 · 비밀번호는 없다. 가입은 휴대폰 본인인증(이름 · 생년월일 · 통신사 · 번호 → 6자리)으로 가입 진행 토큰(`sgt_…`, 메모리에만)을 받고, 계좌 목록에서 골라 1원 인증(계좌 연결) → PIN → 기기 등록 순서로 가며 기기 등록 응답에 토큰이 온다. 본인인증 결과가 이미 가입한 사람이면 이 폰 재등록(비밀번호 그대로/새로 · 지문 고르기 → 연결된 계좌 하나로 1원 → 1원 숫자로 기기 등록 → 로그인)으로 간다. 가입은 BE 시드(`be/seed/PERSONA.md`)에 있는 사람만 할 수 있다. 1원 숫자는 SSAFY 거래내역에만 찍힌다. 1원 응답에 시연용 `demoCode`가 오면 문자 알림으로 띄우게 해 두었지만 BE에는 아직 이 값이 없어서 지금은 직접 입력한다. 쓸 수 있는 기기는 사용자당 1대라서(BE 11/02 결정) 재등록하면 BE가 다른 기기를 모두 해제한다.

## 폴더
```
app/src/main/java/com/yamu/app/
├─ YamuApplication.kt · MainActivity.kt
├─ navigation/        Routes(경로) · TopTab(하단 탭) · NavExtensions(뒤로 가기 규칙) · YamuNavHost(화면 지도) · YamuApp(바깥 틀) · AutoLock(앱 자동 잠금)
├─ core/designsystem/ theme(BrandColor · Color · Type · Dimens · YamuTheme) · component(…) · component/gallery · icon(YamuIcons — Material Symbols)
├─ core/format/       금액 표기(15,000 · −50,000)
├─ core/session/      2단계용 가짜 세션(DevSession) — 4단계에서 토큰 저장소로
├─ core/ui/           스낵바 · 화면 캡처 막기 · 오류 문장
└─ feature/           auth(가입 · 로그인 · 본인 인증) · home · link(계좌 · 카드 연결) · payment(카드추천 · 결제) · performance(실적 · 유불리) · forecast(잔고예보 · 이거 사도 될까?) · report(소비분석 · 결제 내역) · my(마이 · 설정 · 알림함 · 내 카드) · debug(디버그 메뉴)
app/src/customer/      고객 앱 매니페스트(실행 아이콘 · 알림 권한)
app/src/pos/           모의 POS 단말 앱(PosActivity · 화면 · #27 · #63 · 카메라 권한) — 고객 앱과 섞지 않음
app/src/debug · posDebug   가짜 데이터(release에는 안 들어감)
```

## 규칙 (설계 문서 요약)
- 화면 코드에 색 · 크기 숫자를 직접 쓰지 않는다 — `YamuTheme.colors.*` · `YamuTheme.typography.*` · `YamuSpacing` · `YamuRadius`.
- **메인 색 교체는 `core/designsystem/theme/BrandColor.kt` 한 곳**(10/23 회의 — 토스 파랑에서 바꾸기, 후보 청록 `#0B8576`).
- 앱은 YAMU 백엔드 API만 부르고, 판정 · 추천 · 예측은 계산하지 않는다(서버 값 그대로).
- 서버 주소: debug 기본 `http://localhost:8080/api/`(로컬 BE, `adb reverse tcp:8080 tcp:8080`), `yamu.baseUrl`로 바꿀 수 있다. release는 `app/build.gradle.kts`의 TODO.
- `local.properties`(SDK 경로 · POS 키 `yamu.posKey` · 실서버 시험 `yamu.baseUrl` · `yamu.realApi`)는 Git에 올리지 않는다.

## 사용한 외부 자료
- Pretendard 1.3.9 (SIL OFL) — Regular · Medium · SemiBold · Bold · ExtraBold, `app/src/main/res/font`, 라이선스 `third_party/Pretendard-LICENSE.txt`
- Material Symbols Rounded (Apache 2.0, Google) — 공식 SVG 경로를 `core/designsystem/icon/YamuIcons.kt`에 옮김, 라이선스 `third_party/MaterialSymbols-LICENSE.txt`
- 야무 캐릭터 8컷(임시 그림) — `app/src/main/res/drawable-nodpi/yamu_*.webp`, 원본은 저장소 밖 프로토타입 자료
- 카드 앞면 7장 — `app/src/main/res/drawable-nodpi/card_*.webp`, 카드사 공식 사이트(KB국민 · 하나 · 현대 · 삼성 · 롯데)의 상품 그림을 WebP로 바꾼 것(비상업 프로젝트 시연용). 원본 주소 · sha256은 저장소 밖 카드 분석 자료(`card-analysis/data/cards.csv` · `sources.csv`, 리소스 이름 = `card_` + card_id), 상품명 연결은 `core/designsystem/card/CardImages.kt`
