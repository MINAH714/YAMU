# AGENTS.md

Claude Code, Codex, Cursor 같은 AI 코딩 도구가 YAMU 저장소에서 작업할 때 따르는 규칙입니다. 사람도 같은 규칙을 따릅니다.
규칙이 바뀌면 이 파일을 고치는 MR을 올립니다. `CLAUDE.md`는 이 파일을 불러오기만 하므로 내용은 여기에만 씁니다.

## 프로젝트

- **YAMU(야무)**: 통합 바코드로 결제하면 보유 카드 중 혜택이 가장 큰 카드를 규칙 기반으로 골라 모의 결제하고, 실적·잔고예보·소비 리포트를 제공하는 앱입니다.
- SSAFY 15기 최종 프로젝트 `S15P31E103`, 6명, 8주.
- 실제 결제는 하지 않습니다. 승인은 모의 승인 엔진, 계좌·잔액·입출금 원본은 SSAFY 금융망입니다.
- 기획 원본: 노션(요구사항 정의서, ERD, 결정된 정책 사항), Jira `ssafy.atlassian.net` 프로젝트 `S15P31E103`.

## 폴더

| 경로 | 내용 |
|---|---|
| `be/` | Spring Boot 3.5 · Java 17 API 서버 |
| `fe/mobile/` | Expo · React Native 사용자 앱 |
| `fe/pos/` | Vite · React 모의 POS 웹 |
| `ai/` | FastAPI AI 서비스 (Python 3.12) |
| `docs/` | 기획·BE 문서 |
| `.gitlab/merge_request_templates/` | MR 템플릿 |

## 실행·검사 명령

자세한 설치 방법은 각 폴더의 `README.md`에 있습니다.

```powershell
# BE (be 폴더). 처음 한 번: application-local.yml.example을 application-local.yml로 복사
docker compose up -d          # 로컬 MySQL 8.4.11 + Redis 7
.\gradlew.bat bootRun         # http://localhost:8080/swagger-ui.html
.\gradlew.bat test

# FE 모바일 (fe/mobile)
npm ci; npm start
npx tsc --noEmit

# FE POS (fe/pos)
npm ci; npm run lint; npm run build; npm run dev

# AI (ai)
.\.venv\Scripts\python.exe -m app.main
```

## 작업 원칙

### 1. 코드를 쓰기 전에 빈칸부터 찾는다

- 기능 요청을 받으면 바로 코드를 쓰지 않습니다. 요구사항 정의서·ERD·결정된 정책에 **적혀 있지 않은 규칙**을 먼저 찾아 질문합니다.
  - 예: 같은 결제 요청이 두 번 오면? 취소하면 실적·혜택 한도를 되돌리나? 잔액이 부족하면? 바코드가 만료되면?
- 바꿀 파일, 구현 방식, 정해야 할 것을 계획으로 보여주고 **승인받은 뒤** 코드를 씁니다.
- 미결정 사항(`DEC-001` 인증 방식 등)은 임의로 정하지 않습니다. 진행을 위해 임시로 정해야 하면 "임시"라고 표시하고 MR과 Jira에 남깁니다.

### 2. 돌아가는 것보다 깨지는 곳을 먼저 의심한다

- 기능을 만들면 아래 상황에서 어디가 깨지는지 짚고, 어떻게 막았는지 설명합니다.
  - **동시 요청**: 같은 카드의 혜택 한도를 두 결제가 동시에 깎는 경우, 같은 버튼 연타
  - **재시도**: 같은 결제 요청이 다시 오는 경우(멱등성 키로 한 번만 처리)
  - **잘못된 입력**: 음수·0원 금액, 없는 카드, **다른 사용자의 데이터** 접근
  - **외부 실패**: 금융망·모의 승인의 실패·시간 초과(실패와 결과 불명은 다르게 처리)
- 왜 그 방식을 골랐는지, 한계는 무엇인지 MR 설명에 적습니다.
- 위 상황은 가능한 한 테스트로 남깁니다.

### 3. 다른 사람이 만든 파일은 묻고 고친다

- 요청 없이 남의 파일을 고치지 않습니다. 주석 한 줄도 마찬가지입니다. 필요하면 먼저 묻고, 아니면 MR 설명이나 문서에 적습니다.
- 요청 범위 밖의 리팩터링, 이름 변경, 포맷 정리를 하지 않습니다.

### 4. 비밀 정보는 커밋하지도 출력하지도 않는다

- `.env`, `application-local.yml`, API 키, 토큰, 운영 비밀번호는 커밋하지 않고 로그·응답·명령어에 넣지 않습니다.
- 공유가 필요한 값은 `.example` 견본에 자리만 만들어 둡니다.

## Git 규칙

### 브랜치

| 브랜치 | 용도 |
|---|---|
| `master` | 배포 브랜치(protected). 노션 컨벤션에는 `main`으로 적혀 있지만 실제 이름은 `master`입니다. |
| `develop` | 통합 브랜치(protected, 기본 브랜치). 모든 MR의 대상입니다. |
| 작업 브랜치 | Jira Task 하나당 하나. 항상 최신 `develop`에서 분기합니다. |

작업 브랜치 이름: `타입/파트/S15P31E103-이슈번호-요약` (요약은 영문 소문자와 하이픈)

```
feat/be/S15P31E103-98-jpa-flyway
fix/be/S15P31E103-31-card-registration-error
chore/infra/S15P31E103-10-docker-compose
docs/common/S15P31E103-100-agents-md
```

- **타입**: `feat` 기능 · `fix` 버그 · `refactor` 구조 개선 · `style` 포맷·CSS · `docs` 문서 · `test` 테스트 · `chore` 빌드·의존성·환경
- **파트**: `fe` · `be` · `ai` · `data` · `infra` · `common` (소문자. Jira Label과 별개)
- **금지**: `master`·`develop`에 직접 push, 공유 브랜치 force push, 다른 사람 브랜치 rebase
- Merge가 끝난 작업 브랜치는 삭제합니다(MR 생성 시 "소스 브랜치 삭제" 체크).

### 커밋

```
[S15P31E103-이슈번호] 타입(파트): 작업 내용 요약

[S15P31E103-85] feat(be): 공통 응답·에러 코드·전역 예외 처리·Swagger 구성
```

- 이슈 키는 브랜치 이름에서 가져옵니다. 브랜치에 키가 없으면 커밋하기 전에 확인합니다.
- 한 커밋에는 한 가지 목적만 담습니다.

### MR

- 대상은 `develop`, 제목은 커밋 형식과 같습니다.
- `.gitlab/merge_request_templates/default.md` 템플릿을 채우고, 파트 Label(`FE`, `BE`, `AI` 등)을 붙입니다.
- 리뷰 승인 1명 이상을 받은 뒤 Merge합니다. **AI는 MR을 승인하거나 Merge하지 않습니다.**
- Merge되면 연결된 Jira Task를 완료로 옮깁니다.

### Jira

- 계층: Epic `X` → Story `X.Y` → Task `X.Y.Z`. 제목은 `Task X.Y.Z: 구체적인 작업`, 파트는 제목이 아니라 Label로 표시합니다.
- Label: `FE` · `BE` · `INFRA` · `AI` · `기획` · `디자인` · `설계` · `공통`
- 본문: 작업 개요 / 할 일 / 완료 조건. 스토리 포인트는 1pt = 1시간, Task 하나는 최대 8pt.
- 작업을 시작하면 진행 중, MR이 Merge되면 완료로 옮깁니다.

## 파트별 규칙

파트별 규칙은 그 폴더의 `AGENTS.md`에 둡니다. 그 폴더의 파일을 다룰 때는 이 파일과 함께 따릅니다.

| 파트 | 규칙 파일 |
|---|---|
| BE | `be/AGENTS.md` (응답·에러 코드·입력값·시간·JPA·Flyway·테스트) |
| FE · AI | 아직 없음. 각 폴더의 `README.md`를 따르고, 규칙이 정해지면 `fe/AGENTS.md`·`ai/AGENTS.md`와 한 줄짜리 `CLAUDE.md`(`@AGENTS.md`)를 추가합니다. |
