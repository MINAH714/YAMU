# AI 서비스 개발 및 테스트 안내

`ai/`는 소비 예측을 담당하는 독립 FastAPI 서비스입니다. 구현된 API는 상태 확인 `GET /health`, 잔고예보용 생활비 예측 `POST /forecast/living-cost`, 신용카드 마감 전 사용 추정용 신용 생활 소비 예측 `POST /forecast/credit-spending`(2026-11-10, S15P31E103-404)입니다. BE 잔고예보가 이 API를 부릅니다(2026-11-05, S15P31E103-255). LLM 연동은 아직 없습니다.

생활비 예측 모델은 한국지능정보사회진흥원의 사업결과인 AI-Hub 「이상 판별을 위한 금융거래 정보 및 사용자 패턴 합성데이터」를 이용해 학습했습니다. 자세한 내용은 [models/README.md](models/README.md)에 있습니다.

## 폴더 구성

| 경로 | 역할 |
|---|---|
| `app/main.py` | FastAPI 앱, 상태 확인·생활비 예측 API, 로컬 실행 진입점 |
| `app/living_cost.py` | 생활비 예측 모델 읽기, 특징 계산, 예측 |
| `models/living-cost/` | 생활비 예측 모델(7·14·30일)과 설명. 출처는 `models/README.md` |
| `models/credit-spending/` | 신용 생활 소비 예측 모델(7·14·30일)과 설명(S15P31E103-404) |
| `tests/` | 테스트와 학습 저장소가 만든 기대값(`golden_living_cost.json`, `golden_credit_spending.json`) |
| `requirements.txt` | 실행에 필요한 패키지 버전 고정 |
| `requirements-dev.txt` | 테스트에 더 필요한 패키지(`httpx`) |
| `Dockerfile`, `.dockerignore` | 운영 이미지(5절) |
| `.env.example` | 선택적 `AI_PORT`·`AI_LIVING_COST_MODEL_DIR`·`AI_CREDIT_SPENDING_MODEL_DIR` 값의 예시 |
| `.venv/` | 개발자 PC에 생성되는 가상환경. Git 추적 대상 아님 |

## 1. 설치와 버전 확인

1. Windows에 **Python 3.12**를 설치합니다. [Python 3.12 Windows 설치 파일](https://www.python.org/downloads/release/python-31210/)을 사용할 수 있습니다. 설치 시 Python을 PATH에 추가하고, 설치 후 PowerShell을 새로 엽니다.
2. 다음 명령으로 `Python 3.12.x`가 출력되는지 확인합니다.

   ```powershell
   python --version
   ```

3. 저장소의 `S15P31E103` 폴더에서 PowerShell을 열고 AI 폴더로 이동합니다. 가상환경을 만들고 필요한 패키지를 설치합니다. 최초 설치에는 인터넷 연결이 필요합니다.

   ```powershell
   Set-Location .\ai
   python -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r requirements.txt
   .\.venv\Scripts\python.exe -m pip check
   ```

   마지막 명령에서 `No broken requirements found.`가 표시되면 설치된 패키지의 의존성이 맞습니다. 이후 명령은 가상환경의 Python을 직접 사용하므로 활성화 명령은 필요하지 않습니다.

## 2. 서버 실행과 API 확인

1. AI 폴더 PowerShell에서 다음 명령을 실행하고 터미널을 열린 상태로 둡니다.

   ```powershell
   .\.venv\Scripts\python.exe -m app.main
   ```

   `Uvicorn running on http://0.0.0.0:8001` 메시지가 나오면 기동된 것입니다. `0.0.0.0`은 서버의 수신 주소이므로 같은 PC에서 테스트할 때는 아래처럼 `localhost`를 사용합니다.

2. **새 PowerShell**에서 상태 확인 요청을 보냅니다. 어느 폴더에서 실행해도 됩니다.

   ```powershell
   (Invoke-RestMethod http://localhost:8001/health).status
   ```

   `ok`가 출력되면 통과입니다. 브라우저에서 `http://localhost:8001/health`를 열면 아래 응답을 볼 수 있습니다. `livingCostModel.loaded`(체크 생활비)나 `creditSpendingModel.loaded`(신용)가 `false`면 그 모델을 읽지 못한 것이고, 서버 터미널에 이유가 찍힙니다(그 예측만 503, 서버와 다른 쪽 예측은 동작).

   ```json
   {"status":"ok","livingCostModel":{"loaded":true,"horizons":[7,14,30],"modelVersion":{"7":"two-stage-tree-20261111-fraud-large1000000-recurring_once-bemedian","14":"two-stage-tree-20261111-fraud-large1000000-recurring_once-bemedian","30":"two-stage-tree-20261111-fraud-large1000000-recurring_once-bemedian"}},"creditSpendingModel":{"loaded":true,"horizons":[7,14,30],"modelVersion":{"7":"two-stage-tree-credit-20261111-fraud-large1000000-recurring_once-bemedian","14":"two-stage-tree-credit-20261111-fraud-large1000000-recurring_once-bemedian","30":"two-stage-tree-credit-20261111-fraud-large1000000-recurring_once-bemedian"}}}
   ```

## 3. 생활비 예측 API

BE의 잔고예보만 부르는 내부 API입니다. 계좌별 최근 90일의 일별 체크카드 생활 소비를 받아 다음 7일·14일·30일 중 하나의 합계를 예측합니다([models/README.md](models/README.md) 「평가 결과」). 예보 기간이 7·14·30일이 아닌 N일이면 BE가 (0일, 0원)·(7일, 이 API의 7일 예측)·(14일, 14일 예측)·(30일, 30일 예측)을 직선으로 잇고(앞 점보다 낮은 점은 올림), 30일보다 길면 30일 하루 평균 × N을 씁니다. 기간을 늘려도 생활비가 줄지 않게 하려는 규칙입니다([잔고예보 문서](../docs/be/forecast.md) 「큰 일회성 지출」 단락). 원천 정제(정기 지출·큰 일회성 지출 제외 등)는 BE가 한 뒤 넘깁니다([잔고예보 문서](../docs/be/forecast.md) 「생활비 원천 계약」).

- **7일(2026-11-11 다시 판정)**: 7일 모델은 2026-11-06에 더했고(S15P31E103-304) BE 연동(-255)은 그날부터 7일 점도 이 API로 계산합니다. BE 큰 결제 규칙(직전 3개월 생활 소비 중앙값의 10배 이상)까지 뺀 서비스 조건으로 다시 학습·판정한 결과(S15P31E103-407) 7일은 BE가 대신 쓰는 고정값 TSB보다 평균 0.9%·1.0% 덜 틀리지만 95% 상한이 +0.3%·+0.1%로 실행 전에 정한 판정을 통과하지 못했습니다. 나쁘다는 근거도 없어 7일도 이 모델을 계속 씁니다(2026-11-11 BE 4 김용석 결정, 규칙에서 벗어난 결정, S15P31E103-410, [models/README.md](models/README.md) 「7일」). BE는 필요한 기간만 7 → 14 → 30일 순서로 부릅니다(한 번이라도 실패하면 그 요청 전체 TSB). **이 BE를 7일 모델이 없는 옛 AI 서버와 함께 배포하면** 7일 요청이 400이라 모든 요청이 TSB가 됩니다(14·30일 AI도 못 씀). 그동안에도 서버는 세 기간 모델이 모두 있어야 읽으므로, `h7`이 빠지거나 깨지면 14·30일도 503입니다(BE는 TSB로 대체, [models/README.md](models/README.md) 「배포할 때」).

- **넘기는 값**: BE 생활비 원천(`LivingCostReader`)이 주는 날짜별 `checkCardAmount`(그날 YAMU 체크카드 LIVING 결제 합계)입니다. 모델이 체크카드 거래로만 학습했으므로 계좌 출금 등 다른 소비를 더해 넘기지 않습니다.
- **부르는 계좌**: BE 연동(-255)은 기록(첫 줄부터 어제까지)이 14일 이상이고 90일 안에 체크카드 사용이 있는 계좌만 부릅니다. 기록 시작 전 날은 0원으로 채워 90칸을 보냅니다(2026-11-05 결정, 기록을 14·30·60·90일로 잘라 잰 결과는 [models/README.md](models/README.md) 「평가 결과」). 나머지는 BE가 TSB로 계산합니다. 기록 14·30일에서는 30일 예측이 생활비를 9~15% 적게 보는 치우침이 있어, BE는 기록 60일 미만 계좌의 30일 점을 max(이 API의 30일 예측, TSB × 30일)로 씁니다(2026-11-06 결정, 2026-11-11 서비스 조건으로 다시 재고 유지, [잔고예보 문서](../docs/be/forecast.md) 「예상 생활비」). API는 기록이 짧거나 사용이 없는 입력도 거절하지 않고 값을 냅니다(예: 90일 모두 0원이어도 7일 953원, 14일 2,803원, 30일 9,776원).
- **아주 큰 입력**: 트리 모델이라 학습한 범위 밖으로 값을 늘리지 못합니다(예: 하루 1조 원씩 넣어도 30일 예측 약 79만 원). BE는 값의 크기로 거르지 않고 계약만 검사합니다(2026-11-05 결정). 다만 예측이 하루 입력 상한(1조 원) × 예측 일수를 넘으면 계약 위반으로 보고 TSB로 계산합니다(뒤의 계산이 넘치지 않게).

`POST /forecast/living-cost`

```json
{ "asOf": "2026-11-02", "horizonDays": 30,
  "accounts": [ { "key": "a1", "dailySpending": [0, 0, 5000, "… 90개, 마지막이 asOf 당일"] } ] }
```

| 필드 | 규칙 |
|---|---|
| `asOf` | 기록의 마지막 날(`YYYY-MM-DD` 문자열) |
| `horizonDays` | 7·14·30 중 하나(정수). 다른 값은 400. 예측은 asOf 다음 날부터 |
| `accounts` | 1~20개 |
| `accounts[].key` | BE가 정한 임의 구분값(문자열 1~64자, 요청 안에서 겹치지 않음). 사용자 ID·계좌번호는 넣지 않음 |
| `accounts[].dailySpending` | 정확히 90개의 정수(원, 0 이상, 하루 1조 원 이하). 문자열·소수·true/false는 거절 |

응답

```json
{ "modelVersion": "two-stage-tree-20261111-fraud-large1000000-recurring_once-bemedian", "horizonDays": 30,
  "predictions": [ { "key": "a1", "amount": 135834 } ] }
```

- `amount`: 다음 `horizonDays`일 합계 예측(원 단위 반올림, 0 이상). 순서는 요청의 `accounts` 순서와 같습니다.

응답 모양은 BE 공통 형식(`success`·`error.code`)과 다릅니다. 내부 전용이라 BE 클라이언트(-255)가 아래 모양을 그대로 읽습니다.

| 상태 | 본문 | 언제 |
|---|---|---|
| 200 | `{"modelVersion":"...","horizonDays":30,"predictions":[{"key":"a1","amount":135834}]}` | 정상 |
| 400 | `{"error":"INVALID_REQUEST","fieldErrors":[{"loc":["body","horizonDays"],"msg":"..."}]}` | 잘못된 입력. 입력값은 되돌려 주지 않음 |
| 413 | `{"error":"PAYLOAD_TOO_LARGE"}` | 본문 64KB 초과(JSON을 읽기 전). 가장 큰 정상 요청(계좌 20개 × 90일 × 하루 1조 원)도 약 30KB |
| 503 | `{"error":"MODEL_MISSING"}` | 모델을 읽지 못함. BE는 이때와 시간 초과(연결·응답 각 1초)·계약 위반일 때 그 요청을 TSB로 계산하고, 시간 초과·연결 실패 뒤 30초 동안은 두 API 모두, 5xx 뒤 30초 동안은 그 API만 부르지 않음(4xx·읽을 수 없는 본문은 그 요청만 TSB, 2026-11-11 S15P31E103-405) |

- **`asOf`는 어제입니다(2026-11-05 결정, 박강원 님 !97 리뷰)**: 학습은 하루가 끝난 날 기준이라, 덜 찬 오늘 칸을 넣지 않고 어제까지 90일을 보냅니다. 그러면 예측 구간이 오늘부터라, BE는 오늘을 포함한 N+1일 예측에서 오늘 이미 쓴 금액을 뺀 뒤 내일부터 N일로 나눕니다([잔고예보 문서](../docs/be/forecast.md) 「예상 생활비」).
- 모델은 서버를 시작할 때 한 번 읽습니다. 로그에는 예측 일수·계좌 수·처리 시간만 남기고 요청 본문은 남기지 않습니다.
- 읽기 전에 모델 파일의 SHA-256이 설명 파일의 `sha256`과 같은지 봅니다. 모델의 숫자 배열 부분이 바뀌면 joblib은 오류 없이 다른 가중치를 읽기 때문입니다(2026-11-04 측정: 가운데 64바이트를 바꾼 46가지 중 37가지가 오류 없이 읽힘). 읽다가 어떤 예외가 나도(망가진 파일은 `KeyError` 등도 냄) 서버는 뜨고 오류는 로그·`/health`·503으로 드러납니다.
- **배포 조건**: 서비스 인증이 없으므로 BE만 닿는 내부 네트워크에만 엽니다(외부 공개 금지). 모델 폴더는 읽기 전용으로 두고, 감시는 `/health`의 `livingCostModel.loaded`와 `creditSpendingModel.loaded`로 합니다(`models/README.md`).

### 신용 생활 소비 예측 API (S15P31E103-404, 2026-11-10)

`POST /forecast/credit-spending`. 잔고예보가 **이번 이용기간 마감 전 신용카드 사용**을 추정할 때 부릅니다(BE 연동은 S15P31E103-405). 신용카드별 최근 90일 일별 신용 생활 소비(BE `CreditSpendingReader`의 LIVING 결제)를 받아 다음 7·14·30일 중 하나의 합계를 예측합니다. 체크 생활비 모델과 같은 형식(TWO_STAGE_TREE, 특징 15개)이고 신용 거래로 따로 학습했습니다. 학습 데이터에서 BE 큰 결제 규칙(직전 3개월 LIVING 중앙값의 10배 이상)에 걸린 결제도 뺐습니다(Codex 검토, 2026-11-11)([models/README.md](models/README.md) 「`credit-spending/`」).

- **요청·응답·오류 모양은 위 생활비 예측 API와 같습니다.** `accounts[].key`는 신용카드를 구분하는 임의 값입니다(카드 번호·사용자 ID는 넣지 않음). `asOf`도 어제입니다.
- **체크 모델과 따로 읽습니다.** 신용 모델을 읽지 못하면 이 API만 503이고 `/forecast/living-cost`는 그대로 동작합니다. 반대도 같습니다.
- BE는 체크 생활비와 같은 규칙으로 부릅니다(2026-11-10 BE 4 김용석 결정): 기록 14일 이상이고 90일 안에 신용 사용이 있는 카드만, 기록 60일 미만은 30일 점을 max(AI, TSB × 30)로, 나머지와 실패는 TSB.

## 4. 테스트

이 PC에 Python 3.12가 없으면 Docker로 돌립니다. 저장소의 `S15P31E103` 폴더에서 실행합니다. 폴더를 읽기 전용으로 붙이고 컨테이너 안에 복사해서 돌리므로 저장소에 파일이 생기지 않습니다.

```powershell
docker run --rm -v "${PWD}\ai:/src:ro" python:3.12 sh -c "mkdir /work && cd /src && tar --exclude=.venv --exclude=__pycache__ -cf - . | tar -xf - -C /work && cd /work && pip install -q -r requirements-dev.txt && python -m unittest -v"
```

Python 3.12 가상환경이 있으면 AI 폴더에서 바로 돌립니다.

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m unittest -v
```

`tests/test_credit_spending.py`가 확인하는 것: 커밋된 신용 모델의 예측이 학습 저장소와 같음(golden), 신용 골든의 특징이 체크 골든과 같음, 신용 API 응답·입력 검증·로그, 한쪽 모델이 없어도 다른 쪽 API는 동작.

`tests/test_living_cost.py`가 확인하는 것: 특징 15개가 학습 코드와 마지막 자리까지 같음(golden), 커밋된 모델의 예측이 학습 저장소와 같음, 모델 파일·설명이 맞지 않으면 읽지 않음(1바이트만 바뀐 모델 파일·지문 없음 포함), 입력 검증 400과 입력값 미노출, 본문 64KB 초과 413(chunked 포함), 로그에 요청 값 없음, 모델이 없거나 읽다가 예상 밖 예외가 나도 서버는 뜨고 예측 503.

## 5. 운영 이미지

`Dockerfile`로 운영 이미지를 만듭니다(S15P31E103-256). 저장소의 `S15P31E103` 폴더에서 실행합니다.

```powershell
docker build -t yamu-ai:manual ai
```

- 기반 이미지는 `python:3.12.16-slim`입니다. 빌드 안에서 위 테스트를 돌리고, **테스트가 하나라도 실패하면 이미지가 만들어지지 않습니다**(실행 단계가 테스트를 통과한 코드·모델을 복사). 지금 Jenkins CI는 `ai/` 테스트를 돌리지 않습니다.
- `requirements.txt`는 직접 쓰는 패키지와 그 하위 패키지까지 버전을 고정합니다. 같은 커밋을 다시 빌드해도 같은 패키지가 깔리게 하려는 것입니다. 기반 이미지는 `3.12.16` 태그까지만 고정하고 digest(이미지 지문)는 고정하지 않으므로, 데비안 보안 패치는 바뀔 수 있습니다.
- 서버는 root가 아닌 `app`(uid 10001)으로 돌고, 코드·모델 파일은 root 소유라 서버가 고칠 수 없습니다.
- 이미지에 `HEALTHCHECK`가 있어 체크·신용 모델을 모두 읽어야(`livingCostModel.loaded`·`creditSpendingModel.loaded` 모두 `true`) healthy가 됩니다. 하나라도 못 읽으면 컨테이너는 돌지만 unhealthy입니다(신용 모델은 2026-11-10부터, S15P31E103-404). 모델은 이미지에 고정돼 있어 하나라도 깨졌다면 이미지를 다시 만들어야 하기 때문입니다. BE는 두 경우 모두 TSB로 대체합니다. **이 HEALTHCHECK를 트래픽 진입이나 다른 서비스의 시작 조건(`depends_on: condition: service_healthy`)으로 쓰면, 신용 모델만 깨져도 체크 생활비 예측까지 막힙니다.** 2026-11-10 기준 운영 compose(`infra/compose.prod.yml`)에는 AI 서비스가 없고 CD는 backend healthy만 확인해 이런 연결은 없습니다. 연결하게 되면 체크 모델만 보는 검사로 바꾸고 신용 모델은 `/health`로 따로 감시합니다(Codex 검토).
- 운영에서 띄울 때는 호스트 포트를 열지 않고 BE와 같은 내부 전용 네트워크에만 붙입니다(3절 「배포 조건」). BE에는 `AI_BASE_URL=http://<서비스 이름>:8001`을 넣습니다.

2026-11-05 이 PC(Docker 29.7.2)에서 확인한 것:

- 빌드 약 50초, 테스트 11개 통과, 이미지 크기 677MB(`docker image ls` 기준).
- `--read-only --tmpfs /tmp --init`, 인터넷이 없는 내부 네트워크(`--internal`)로 띄워 healthy가 됐습니다. `/app` 쓰기는 거부되고, 외부 주소 조회는 실패했습니다.
- 같은 네트워크의 다른 컨테이너에서 예측을 부르면 200이 옵니다. 계좌 20개 요청은 14·30일 모두 약 7~10ms 걸렸습니다.
- 예측할 때마다 컨테이너 로그에 `app.main living-cost horizon=14 accounts=1 ms=18.1`처럼 한 줄이 찍힙니다. 입력한 key와 금액은 로그에 나오지 않았습니다.
- 메모리는 예측 뒤 약 130MiB입니다. `AI_LIVING_COST_MODEL_DIR`을 없는 폴더로 주면 unhealthy가 됩니다.
- 테스트 하나를 일부러 실패시키면 빌드가 멈추고 이미지가 만들어지지 않았습니다.
- 운영 서버(EC2)에서는 아직 띄워 보지 않았습니다.

2026-11-10(S15P31E103-404) 이 PC에서 확인한 것: 테스트 22개 통과, 실행 이미지의 `/health`에 두 모델이 모두 `loaded: true`, HEALTHCHECK 명령 종료 코드 0. `AI_CREDIT_SPENDING_MODEL_DIR`을 없는 폴더로 주면 종료 코드 1(unhealthy). `/app/models/credit-spending` 쓰기는 거부됩니다.

## 환경변수와 다른 서비스

| 변수 | 기본값 | 용도 |
|---|---|---|
| `AI_PORT` | `8001` | AI 서버 포트 |
| `AI_LIVING_COST_MODEL_DIR` | `ai/models/living-cost` | 생활비 예측 모델 폴더 |
| `AI_CREDIT_SPENDING_MODEL_DIR` | `ai/models/credit-spending` | 신용 생활 소비 예측 모델 폴더(S15P31E103-404) |
| `AI_DOCS_ENABLED` | 꺼짐 | `true`이면 FastAPI 기본 API 문서(`/docs`·`/redoc`·`/openapi.json`)를 엽니다. 운영에서는 넣지 않습니다(S15P31E103-355, 점검 Q-10). 로컬에서 볼 때만 켭니다 |

`.env.example`은 값의 **예시**이며 현재 앱은 `.env` 파일을 자동으로 읽지 않습니다. 포트를 바꾸려면 서버를 실행할 **같은 PowerShell**에서 설정합니다.

```powershell
$env:AI_PORT = '8002'
.\.venv\Scripts\python.exe -m app.main
```

이때 확인 URL은 `http://localhost:8002/health`입니다. BE는 `AI_BASE_URL`(예: `http://localhost:8001`)이 있을 때만 이 서비스를 부르고, 없거나 실패하면 TSB로 계산하므로 AI 서비스는 단독으로 기동과 응답을 검사할 수 있습니다. FE는 호출하지 않습니다.

## 문제 해결과 종료

- `python`을 찾지 못하거나 버전이 3.12가 아니면 Python 설치·PATH를 확인하고 PowerShell을 새로 엽니다.
- `No module named fastapi`(또는 `sklearn`)가 나오면 AI 폴더에서 가상환경 Python으로 `-m pip install -r requirements.txt`를 다시 실행합니다.
- 포트 사용 중 오류가 나오면 해당 포트의 다른 서버를 종료하거나 `AI_PORT`를 바꿉니다.
- `Invoke-RestMethod`가 연결 실패를 반환하면 Uvicorn 터미널이 실행 중인지와 포트 번호를 확인합니다.

테스트가 끝나면 서버 터미널에서 `Ctrl+C`로 종료합니다.
