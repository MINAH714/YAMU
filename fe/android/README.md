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
| 5 계좌 · 카드 연결 + 첫 분석 | ✅ `feature/link` — 기관 선택 · 본인 확인 · 동의(1원 문자 자동 입력) · 목록에서 고르기 · 연결 완료 · 월급 · 고정 지출 · 첫 분석 · 알림 권한, 카드 추가(C-11~C-14), 공통 본인 인증(`feature/auth/StepUp` · 간편 비밀번호 화면), 단위 테스트 36개 |
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
- **가짜 인증 시험값**: 가입 dup@ssafy.com(이미 가입) · fin@ssafy.com(금융망 이메일) · 간편 비밀번호 기본 135790(5번 틀리면 30분 잠김) · 로그인 때 000000은 서버에서 기기가 없어진 경우(첫 화면으로). 시작 상태 '로그인 · 연결 전 · 끝'은 이 폰을 기기 7번으로 등록해 둔 상태
- **연결 상태 (가짜 데이터)**: 정상(계좌 1 · 카드 6) · 내 명의 상품 없음 · 본인 계좌 이미 확인함 · 연결 확정 때 금융망 오류
- **결제 상태 (가짜 데이터)**: 코드를 열고 3초 뒤 가짜 가맹점이 찍음(카페 15,000원 · 마트 90,000원 할부) · 1순위 체크카드 잔액 부족 · 아무도 안 찍음(20초 코드). 고객 앱과 POS 앱을 이어서 결제하는 건 실제 서버로만
- **가짜 실적 장부**: 가짜 결제가 승인되면 실적에 더해진다 — 카페 15,000원을 신한카드로 두 번 결제하면 신한카드(270,000 / 300,000)가 '달성'으로 옮겨 간다
- **가짜 소비분석 · 결제 내역**: 생활 소비 842,000 · 받은 혜택 32,400 · 결제 67건 2,164,200원(쪽마다 20건). 이번 실행에서 가짜 결제를 하면 결제 내역 맨 위 · 합계 · 알림함에 더해진다. 오전 10시 8분 전에는 '오늘' 줄이 아직 오지 않은 시각이라 내역 전체가 하루 앞으로 간다
- **가짜 마이 · 알림**: 설정(자동 선택 · 알림 종류 · 받는 시각 · 결제 코드 인증)은 앱을 끄면 처음 값, 자동 잠금 시간만 기기에 저장. 다른 기기 'Chrome · Windows' 1대, 알림 5개(안 읽음 3 — 홈 종 숫자도 같은 값)
- **앱 자동 잠금 시험**: 마이 → 보안 · 인증 → 앱 자동 잠금 '바로' → 홈 버튼 → 다시 열면 잠금 해제 화면(풀면 보던 화면)
- **가짜 잔고예보**: 잔액 950,000 · 예정 지출 4건 450,000 · 하루 생활비 6,000 → 월급 전 350,000(월급 = 오늘 + 25일). 이거 사도 될까? 400,000원은 '미루는 게 좋아요', 129,000원은 '괜찮아요'. 계획을 저장하면 예보에 같이 계산된다
- **모의 POS 가짜 시험값**([번호로 넣기]): EXPIRED가 들어가면 만료 · USED는 이미 쓴 코드 · DECLINE은 거절 · REJECT는 고객 거절, 그 밖은 5초 뒤 승인
- **가짜 연결 시험값**: 1원 숫자 1004 · 0000은 시간 지남 · 그 밖은 틀림. 에뮬레이터 문자 자동 입력: `adb emu sms send 15880000 "[KB국민은행] *1234 입금 1원 YAMU 1004"`(Google Play 이미지에서만)
- **화면 바로 열기**, **컴포넌트 갤러리**(다크 모드 · 글자 1.3배 · 청록 미리보기)
- **서버 연결**: 지금 주소와 진짜로 쓰는 기능을 보여 주고, '서버 연결 확인'으로 `GET /api/health`를 불러 본다

## 실서버 연결 (debug)
`local.properties`에 아래처럼 적고 다시 빌드한다. 비워 두면 로컬 BE 주소에 전부 가짜라서 지금까지와 같다.

```properties
yamu.baseUrl=http://localhost:8080/api/
yamu.realApi=cashflow,link
```

- `yamu.baseUrl`은 `/`로 끝나야 한다. 로컬 BE는 `adb reverse tcp:8080 tcp:8080` 뒤 `localhost`, 에뮬레이터는 `http://10.0.2.2:8080/api/`도 된다.
- `yamu.realApi`는 Repository 단위다. 쓸 수 있는 이름은 `auth` · `link` · `cashflow` · `home` · `payment` · `performance` · `forecast` · `report` · `my` · `pos`이고 `all`이면 전부, 모르는 이름이면 빌드가 멈춘다.
- 진짜 API는 로그인 토큰이 필요하다. `auth`를 가짜로 두고 다른 기능만 켜면 가짜 토큰이 실려 401이 나므로, 가입 · 로그인을 BE 방식으로 바꾸기 전까지는 '서버 연결 확인'까지만 볼 수 있다.

BE(develop, 2026-11-01)와 맞춰 본 결과는 이렇다.

| 이름 | 앱이 부르는 API | BE |
|---|---|---|
| `auth` | 가입 · 로그인 · PIN · 기기 등록 · 본인 인증 · 재발급 · 로그아웃 | 로그인 · 본인 확인 · 기기 등록 · 재발급 · 로그아웃은 맞춤(235). 가입은 방식이 다름(아래) |
| `link` | 기관 목록 · 연결 세션 · 계좌 · 카드 목록 | 계좌 · 카드 목록만 맞음. 연결은 `accounts/candidates` · `accounts/one-won-auth` · `cards/lookup`으로 바뀜 |
| `cashflow` | `cashflow-items` 조회 · 수정, `forecasts/settings` · `forecasts/balance` | `cashflow-items`만 있음 |
| `home` · `payment` · `performance` · `forecast` · `report` · `my` · `pos` | 홈 · 결제 · 추천 · 실적 · 혜택 한도 · 예보 · 소비 판단 · 리포트 · 알림 · 마이 · 모의 POS | 아직 없음 |

로그인은 Task 8.2.8(S15P31E103-235)에서 BE 방식으로 바꿨다. 이 폰에 기기 번호가 있으면 앱을 열 때와 자동 잠금 때 같은 PIN · 지문 로그인 화면이 뜨고(`login/challenge` → `login`, 매번 새 토큰), 로그아웃은 토큰만 지운다. 기기 키는 둘이다 — 기기 키(지문 없이, PIN 로그인 · PIN 본인 확인 서명)와 지문 키(지문을 통과해야 서명). 서명 대상은 challenge 문자열의 UTF-8 바이트다.

BE에는 비밀번호가 없다. 가입은 휴대폰 본인인증(이름 · 생년월일 · 통신사 · 번호 → 6자리)으로 가입 진행 토큰을 받고, 그 토큰으로 계좌 목록에서 골라 1원 인증 → PIN → 기기 등록 순서로 가며 기기 등록 응답에 토큰이 온다. 로그인은 기기 번호로 challenge를 받아 기기 키로 서명하고 PIN이나 지문을 같이 보낸다. 가입은 BE 시드(`be/seed/PERSONA.md`)에 있는 사람만 할 수 있다. 앱을 이 방식으로 바꾸는 일은 따로 Task로 나눈다.

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
