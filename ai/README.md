# AI 서비스 개발 및 테스트 안내

`ai/`는 소비 예측을 담당하는 독립 FastAPI 서비스입니다. 구현된 API는 상태 확인 `GET /health`와 잔고예보용 생활비 예측 `POST /forecast/living-cost`입니다. LLM과 BE 연동은 아직 없습니다.

생활비 예측 모델은 한국지능정보사회진흥원의 사업결과인 AI-Hub 「이상 판별을 위한 금융거래 정보 및 사용자 패턴 합성데이터」를 이용해 학습했습니다. 자세한 내용은 [models/README.md](models/README.md)에 있습니다.

## 폴더 구성

| 경로 | 역할 |
|---|---|
| `app/main.py` | FastAPI 앱, 상태 확인·생활비 예측 API, 로컬 실행 진입점 |
| `app/living_cost.py` | 생활비 예측 모델 읽기, 특징 계산, 예측 |
| `models/living-cost/` | 생활비 예측 모델(14·30일)과 설명. 출처는 `models/README.md` |
| `tests/` | 테스트와 학습 저장소가 만든 기대값(`golden_living_cost.json`) |
| `requirements.txt` | 실행에 필요한 패키지 버전 고정 |
| `requirements-dev.txt` | 테스트에 더 필요한 패키지(`httpx`) |
| `.env.example` | 선택적 `AI_PORT`·`AI_LIVING_COST_MODEL_DIR` 값의 예시 |
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

   `ok`가 출력되면 통과입니다. 브라우저에서 `http://localhost:8001/health`를 열면 아래 응답을 볼 수 있습니다. `livingCostModel.loaded`가 `false`면 모델을 읽지 못한 것이고, 서버 터미널에 이유가 찍힙니다(예측만 503, 서버는 동작).

   ```json
   {"status":"ok","livingCostModel":{"loaded":true,"horizons":[14,30],"modelVersion":{"14":"two-stage-tree-20261103-fraud-large1000000-recurring","30":"two-stage-tree-20261103-fraud-large1000000-recurring"}}}
   ```

## 3. 생활비 예측 API

BE의 잔고예보만 부르는 내부 API입니다. 계좌별 최근 90일의 일별 체크카드 생활 소비를 받아 다음 14일 또는 30일 합계를 예측합니다. 7일은 AI 모델이 단순 방식(TSB)보다 낫지 않아 BE가 TSB로 계산합니다([models/README.md](models/README.md) 「평가 결과」). 예보 기간이 7·14·30일이 아닌 N일이면 BE가 (0일, 0원)·(7일, TSB)·(14일, 이 API의 14일 예측)·(30일, 30일 예측)을 직선으로 잇고(앞 점보다 낮은 점은 올림), 30일보다 길면 30일 하루 평균 × N을 씁니다. 기간을 늘려도 생활비가 줄지 않게 하려는 규칙입니다([잔고예보 문서](../docs/be/forecast.md) 「큰 일회성 지출」 단락). 원천 정제(정기 지출·큰 일회성 지출 제외 등)는 BE가 한 뒤 넘깁니다([잔고예보 문서](../docs/be/forecast.md) 「생활비 원천 계약」).

- **넘기는 값**: BE 생활비 원천(`LivingCostReader`)이 주는 날짜별 `checkCardAmount`(그날 YAMU 체크카드 LIVING 결제 합계)입니다. 모델이 체크카드 거래로만 학습했으므로 계좌 출금 등 다른 소비를 더해 넘기지 않습니다.
- **부르지 않는 계좌**: 기록이 90일 미만이거나 최근 90일에 체크카드 사용이 없는 계좌는 학습·평가 범위 밖이라 BE가 부르지 않고 TSB로 계산합니다. API는 이런 입력도 거절하지 않고 값을 냅니다(예: 90일 모두 0원이어도 14일 3,611원, 30일 11,162원).
- **아주 큰 입력**: 트리 모델이라 학습한 범위 밖으로 값을 늘리지 못합니다(예: 하루 1조 원씩 넣어도 30일 예측 약 78만 원). 예측이 입력에 비해 터무니없이 작으면 이상값으로 보고 TSB로 바꿀지 BE 연동 때 기준을 정합니다.

`POST /forecast/living-cost`

```json
{ "asOf": "2026-11-02", "horizonDays": 30,
  "accounts": [ { "key": "a1", "dailySpending": [0, 0, 5000, "… 90개, 마지막이 asOf 당일"] } ] }
```

| 필드 | 규칙 |
|---|---|
| `asOf` | 기록의 마지막 날(`YYYY-MM-DD` 문자열) |
| `horizonDays` | 14 또는 30(정수). 7 등 다른 값은 400. 예측은 asOf 다음 날부터 |
| `accounts` | 1~20개 |
| `accounts[].key` | BE가 정한 임의 구분값(문자열 1~64자, 요청 안에서 겹치지 않음). 사용자 ID·계좌번호는 넣지 않음 |
| `accounts[].dailySpending` | 정확히 90개의 정수(원, 0 이상, 하루 1조 원 이하). 문자열·소수·true/false는 거절 |

응답

```json
{ "modelVersion": "two-stage-tree-20261103-fraud-large1000000-recurring", "horizonDays": 30,
  "predictions": [ { "key": "a1", "amount": 135834 } ] }
```

- `amount`: 다음 `horizonDays`일 합계 예측(원 단위 반올림, 0 이상). 순서는 요청의 `accounts` 순서와 같습니다.
- 잘못된 입력은 400 `{"error":"INVALID_REQUEST","fieldErrors":[{"loc":[...],"msg":"..."}]}`입니다. 입력값은 응답에 되돌려 주지 않습니다.
- 요청 본문이 64KB를 넘으면 JSON을 읽기 전에 413 `{"error":"PAYLOAD_TOO_LARGE"}`입니다. 가장 큰 정상 요청(계좌 20개 × 90일 × 하루 1조 원)도 약 30KB입니다.
- 모델을 읽지 못했으면 503 `{"error":"MODEL_MISSING"}`입니다. BE는 이때와 시간 초과·이상값일 때 TSB로 대체 계산합니다(연결은 후속 작업).
- 모델은 서버를 시작할 때 한 번 읽습니다. 로그에는 예측 일수·계좌 수·처리 시간만 남기고 요청 본문은 남기지 않습니다.
- **배포 조건**: 서비스 인증이 없으므로 BE만 닿는 내부 네트워크에만 엽니다(외부 공개 금지). 모델 폴더는 읽기 전용으로 두고, 감시는 `/health`의 `livingCostModel.loaded`로 합니다(`models/README.md`).

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

`tests/test_living_cost.py`가 확인하는 것: 특징 15개가 학습 코드와 마지막 자리까지 같음(golden), 커밋된 모델의 예측이 학습 저장소와 같음, 모델 파일·설명이 맞지 않으면 읽지 않음, 입력 검증 400과 입력값 미노출, 본문 64KB 초과 413(chunked 포함), 로그에 요청 값 없음, 모델이 없을 때 서버는 뜨고 예측 503.

## 환경변수와 다른 서비스

| 변수 | 기본값 | 용도 |
|---|---|---|
| `AI_PORT` | `8001` | AI 서버 포트 |
| `AI_LIVING_COST_MODEL_DIR` | `ai/models/living-cost` | 생활비 예측 모델 폴더 |

`.env.example`은 값의 **예시**이며 현재 앱은 `.env` 파일을 자동으로 읽지 않습니다. 포트를 바꾸려면 서버를 실행할 **같은 PowerShell**에서 설정합니다.

```powershell
$env:AI_PORT = '8002'
.\.venv\Scripts\python.exe -m app.main
```

이때 확인 URL은 `http://localhost:8002/health`입니다. 현재 [BE](../be/README.md)와 [FE](../fe/README.md)는 AI 서비스를 호출하지 않으므로, AI 서비스는 단독으로 기동과 응답을 검사할 수 있습니다.

## 문제 해결과 종료

- `python`을 찾지 못하거나 버전이 3.12가 아니면 Python 설치·PATH를 확인하고 PowerShell을 새로 엽니다.
- `No module named fastapi`(또는 `sklearn`)가 나오면 AI 폴더에서 가상환경 Python으로 `-m pip install -r requirements.txt`를 다시 실행합니다.
- 포트 사용 중 오류가 나오면 해당 포트의 다른 서버를 종료하거나 `AI_PORT`를 바꿉니다.
- `Invoke-RestMethod`가 연결 실패를 반환하면 Uvicorn 터미널이 실행 중인지와 포트 번호를 확인합니다.

테스트가 끝나면 서버 터미널에서 `Ctrl+C`로 종료합니다.
