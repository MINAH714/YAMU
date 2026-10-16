# AI 서비스 개발 및 테스트 안내

`ai/`는 향후 소비 예측을 담당할 독립 FastAPI 서비스입니다. 현재 구현된 API는 `GET /health` 하나이며 응답은 `{"status":"ok"}`입니다. 예측 모델·LLM·BE 연동은 아직 없습니다.

## 폴더 구성

| 경로 | 역할 |
|---|---|
| `app/main.py` | FastAPI 앱, 상태 확인 API, 로컬 실행 진입점 |
| `requirements.txt` | FastAPI와 Uvicorn 버전 고정 |
| `.env.example` | 선택적 `AI_PORT` 값의 예시 |
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

   `ok`가 출력되면 통과입니다. 브라우저에서 `http://localhost:8001/health`를 열면 `{"status":"ok"}`를 볼 수 있습니다.

## 환경변수와 다른 서비스

| 변수 | 기본값 | 용도 |
|---|---|---|
| `AI_PORT` | `8001` | AI 서버 포트 |

`.env.example`은 값의 **예시**이며 현재 앱은 `.env` 파일을 자동으로 읽지 않습니다. 포트를 바꾸려면 서버를 실행할 **같은 PowerShell**에서 설정합니다.

```powershell
$env:AI_PORT = '8002'
.\.venv\Scripts\python.exe -m app.main
```

이때 확인 URL은 `http://localhost:8002/health`입니다. 현재 [BE](../be/README.md)와 [FE](../fe/README.md)는 AI 서비스를 호출하지 않으므로, AI 서비스는 단독으로 기동과 응답을 검사할 수 있습니다.

## 문제 해결과 종료

- `python`을 찾지 못하거나 버전이 3.12가 아니면 Python 설치·PATH를 확인하고 PowerShell을 새로 엽니다.
- `No module named fastapi`가 나오면 AI 폴더에서 가상환경 Python으로 `-m pip install -r requirements.txt`를 다시 실행합니다.
- 포트 사용 중 오류가 나오면 해당 포트의 다른 서버를 종료하거나 `AI_PORT`를 바꿉니다.
- `Invoke-RestMethod`가 연결 실패를 반환하면 Uvicorn 터미널이 실행 중인지와 포트 번호를 확인합니다.

테스트가 끝나면 서버 터미널에서 `Ctrl+C`로 종료합니다.
