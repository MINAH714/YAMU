import logging
import os
import re
import time
from collections.abc import Mapping
from contextlib import asynccontextmanager
from datetime import date
from pathlib import Path
from typing import Annotated

import numpy as np
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

from app import living_cost

log = logging.getLogger("app.main")
DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[1] / "models" / "living-cost"
# 신용카드 마감 전 사용 추정용 신용 생활 소비 모델(S15P31E103-404). 체크 모델과 따로 읽어, 한쪽이 없어도 다른 쪽은 동작한다
DEFAULT_CREDIT_MODEL_DIR = Path(__file__).resolve().parents[1] / "models" / "credit-spending"
MAX_ACCOUNTS = 20
MAX_DAILY_AMOUNT = 1_000_000_000_000  # 하루 1조 원. 이보다 크면 잘못된 입력으로 본다
# 가장 큰 정상 요청(계좌 20개 × 90일 × 13자리)이 약 30KB라 넉넉히 두 배. 넘으면 JSON을 읽기 전에 413
MAX_BODY_BYTES = 64 * 1024
# asOf는 YYYY-MM-DD만 받는다. pydantic 날짜 해석은 "2026-11-02T00:00:00" 같은 날짜시각도 받아 준다
ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


class BodySizeLimit:
    """요청 본문이 max_bytes를 넘으면 앱에 넘기기 전에 413으로 끊는다. 계좌 수 검사는 JSON을 다 읽은 뒤라 따로 막는다."""

    def __init__(self, app, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        length = dict(scope["headers"]).get(b"content-length")
        if length is not None and length.isdigit() and int(length) > self.max_bytes:
            await self._too_large(scope, receive, send)
            return
        # Content-Length가 없는(chunked) 본문도 받은 만큼 세어 넘으면 끊는다.
        body = b""
        more = True
        while more:
            message = await receive()
            if message["type"] != "http.request":
                await self.app(scope, receive, send)
                return
            body += message.get("body", b"")
            more = message.get("more_body", False)
            if len(body) > self.max_bytes:
                await self._too_large(scope, receive, send)
                return
        replayed = False

        async def replay():
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, replay, send)

    @staticmethod
    async def _too_large(scope, receive, send) -> None:
        await JSONResponse(status_code=413, content={"error": "PAYLOAD_TOO_LARGE"})(scope, receive, send)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """체크·신용 생활 소비 모델을 시작할 때 한 번씩 따로 읽는다. 읽지 못해도 서버는 뜨고 그 예측만 503이다(BE가 TSB로 대체)."""
    app.state.living_cost_models = _load("체크 생활비", os.getenv("AI_LIVING_COST_MODEL_DIR") or DEFAULT_MODEL_DIR)
    app.state.credit_spending_models = _load("신용 생활 소비",
                                             os.getenv("AI_CREDIT_SPENDING_MODEL_DIR") or DEFAULT_CREDIT_MODEL_DIR)
    yield


def _load(name: str, model_dir: str | Path) -> dict[int, living_cost.LoadedModel]:
    """model_dir의 7·14·30일 모델. 읽지 못하면 로그를 남기고 빈 사전을 돌려준다."""
    try:
        return living_cost.load_models(Path(model_dir))
    except Exception:
        # 망가진 joblib은 KeyError 등 종류를 다 셀 수 없는 예외를 낸다. 서버는 띄우고 오류는 로그·/health(loaded=false)·503으로 드러낸다
        log.exception("%s 예측 모델을 읽지 못했습니다", name)
        return {}


def docs_enabled(env: Mapping[str, str]) -> bool:
    """AI_DOCS_ENABLED가 true(대소문자·앞뒤 공백 무시)일 때만 기본 API 문서를 연다. 없거나 다른 값이면 끈다."""
    return env.get("AI_DOCS_ENABLED", "").strip().lower() == "true"


def docs_urls(enabled: bool) -> dict[str, str | None]:
    """FastAPI 기본 API 문서(/docs·/redoc·/openapi.json) 주소. 끄면 세 주소 모두 None(404)이다."""
    if enabled:
        return {"docs_url": "/docs", "redoc_url": "/redoc", "openapi_url": "/openapi.json"}
    return {"docs_url": None, "redoc_url": None, "openapi_url": None}


# 운영에서는 끈다(S15P31E103-355). 로컬에서 보려면 AI_DOCS_ENABLED=true로 띄운다.
app = FastAPI(title="YAMU AI", lifespan=lifespan, **docs_urls(docs_enabled(os.environ)))
app.add_middleware(BodySizeLimit, max_bytes=MAX_BODY_BYTES)


class AccountHistory(BaseModel):
    """계좌(체크) 또는 신용카드 하나의 최근 90일 일별 생활 소비. BE가 정한 임의 key로만 구분한다."""

    key: Annotated[str, Field(strict=True, min_length=1, max_length=64)]
    dailySpending: Annotated[
        list[Annotated[int, Field(strict=True, ge=0, le=MAX_DAILY_AMOUNT)]],
        Field(min_length=living_cost.HISTORY_DAYS, max_length=living_cost.HISTORY_DAYS),
    ]


class LivingCostRequest(BaseModel):
    asOf: date
    horizonDays: Annotated[int, Field(strict=True)]
    accounts: Annotated[list[AccountHistory], Field(min_length=1, max_length=MAX_ACCOUNTS)]

    @field_validator("asOf", mode="before")
    @classmethod
    def iso_date_string(cls, value: object) -> object:
        if not isinstance(value, str) or not ISO_DATE.fullmatch(value):
            raise ValueError("날짜는 YYYY-MM-DD 문자열이어야 합니다")
        return value

    @field_validator("horizonDays")
    @classmethod
    def supported_horizon(cls, value: int) -> int:
        if value not in living_cost.HORIZONS:
            raise ValueError("예측 일수는 7·14·30 중 하나여야 합니다")
        return value

    @field_validator("accounts")
    @classmethod
    def unique_keys(cls, accounts: list[AccountHistory]) -> list[AccountHistory]:
        if len({account.key for account in accounts}) != len(accounts):
            raise ValueError("계좌 key가 겹칩니다")
        return accounts


@app.exception_handler(RequestValidationError)
async def invalid_request(request: Request, exc: RequestValidationError) -> JSONResponse:
    # 입력값은 응답·로그에 남기지 않는다(위치와 이유만).
    errors = [{"loc": list(error.get("loc", [])), "msg": error.get("msg", "")} for error in exc.errors()]
    return JSONResponse(status_code=400, content={"error": "INVALID_REQUEST", "fieldErrors": errors})


def _model_state(models: dict[int, living_cost.LoadedModel]) -> dict[str, object]:
    state: dict[str, object] = {"loaded": bool(models)}
    if models:
        state["horizons"] = sorted(models)
        state["modelVersion"] = {str(h): m.version for h, m in sorted(models.items())}
    return state


@app.get("/health")
def health() -> dict[str, object]:
    return {"status": "ok", "livingCostModel": _model_state(app.state.living_cost_models),
            "creditSpendingModel": _model_state(app.state.credit_spending_models)}


def _forecast(models: dict[int, living_cost.LoadedModel], body: LivingCostRequest,
              name: str) -> dict[str, object] | JSONResponse:
    """key별 최근 90일 일별 소비 → 다음 horizonDays일 합계 예측. 그 기간 모델이 없으면 503."""
    loaded = models.get(body.horizonDays)
    if loaded is None:
        return JSONResponse(status_code=503, content={"error": "MODEL_MISSING"})
    started = time.perf_counter()
    daily = np.array([account.dailySpending for account in body.accounts], dtype=np.int64)
    amounts = living_cost.predict(loaded, daily)
    log.info("%s horizon=%s accounts=%s ms=%.1f", name, body.horizonDays, len(body.accounts),
             (time.perf_counter() - started) * 1000)
    return {"modelVersion": loaded.version, "horizonDays": body.horizonDays,
            "predictions": [{"key": a.key, "amount": int(v)} for a, v in zip(body.accounts, amounts)]}


@app.post("/forecast/living-cost", response_model=None)
def forecast_living_cost(body: LivingCostRequest) -> dict[str, object] | JSONResponse:
    """계좌별 최근 90일(마지막이 asOf 당일) 일별 체크 생활 소비 → 다음 horizonDays일 합계 예측."""
    return _forecast(app.state.living_cost_models, body, "living-cost")


@app.post("/forecast/credit-spending", response_model=None)
def forecast_credit_spending(body: LivingCostRequest) -> dict[str, object] | JSONResponse:
    """신용카드별 최근 90일(마지막이 asOf 당일) 일별 신용 생활 소비 → 다음 horizonDays일 합계 예측(S15P31E103-404).

    잔고예보가 이번 이용기간 마감 전 신용카드 사용을 추정할 때 부른다. 요청·응답 모양은 생활비 예측과 같다.
    """
    return _forecast(app.state.credit_spending_models, body, "credit-spending")


if __name__ == "__main__":
    import uvicorn

    # uvicorn은 자기 로거만 설정한다. 이 설정이 없으면 app.main의 INFO(예측 일수·계좌 수·처리 시간)가 출력되지 않는다.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s:     %(name)s %(message)s")
    uvicorn.run("app.main:app", host="0.0.0.0", port=int(os.getenv("AI_PORT", "8001")))
