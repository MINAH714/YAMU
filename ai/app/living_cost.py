"""생활비 예측 모델을 읽고, 계좌별 최근 90일 일별 체크 생활 소비로 다음 14·30일 합계를 예측한다.
7일은 AI 모델이 단순 방식(TSB)보다 낫지 않아 BE가 TSB로 계산한다(models/README.md 「평가 결과」).

모델은 개인 저장소(YAMU, 학습 코드)에서 학습한 TWO_STAGE_TREE다. 1단계 분류 모델이 "다음 H일에 쓸 확률"을,
2단계 회귀 모델이 "쓴다면 합계"를 내고, 예측은 둘의 곱이다. 특징 계산은 학습 때와 값이 같아야 하므로
tests/golden_living_cost.json(학습 저장소가 만든 기대값)과 비교해 확인한다. 출처는 models/README.md.

모델 파일은 joblib(pickle) 형식이라 읽을 때 코드가 실행될 수 있다. 이 저장소에 커밋된 파일만 읽는다.
"""

import json
from dataclasses import dataclass
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn

HISTORY_DAYS = 90
HORIZONS = (14, 30)
MODEL_NAME = "TWO_STAGE_TREE"
WINDOWS = (7, 14, 28, 84)
# 모델이 학습한 특징 순서. meta.json의 features와 다르면 읽지 않는다.
FEATURES = (
    "sum_7d", "days_7d", "sum_14d", "days_14d", "sum_28d", "days_28d", "sum_84d", "days_84d",
    "week_minus2", "week_minus3", "week_minus4", "days_since_last", "mean_active_84d", "adi_84d", "cv2_84d",
)
NO_SPEND_DAYS = 999  # 90일 동안 쓴 날이 없을 때의 경과일(학습 때와 같은 값)


@dataclass(frozen=True)
class TwoStage:
    """두 단계 모델. 학습 저장소의 TwoStage.predict와 같은 계산이다."""

    classifier: object
    regressor: object

    def predict(self, X: pd.DataFrame) -> np.ndarray:
        return self.classifier.predict_proba(X)[:, 1] * self.regressor.predict(X)


@dataclass(frozen=True)
class LoadedModel:
    """horizon 하나의 모델과 설명(meta.json)."""

    horizon: int
    model: object
    version: str


def load_models(model_dir: Path) -> dict[int, LoadedModel]:
    """model_dir의 h{H}.joblib과 h{H}.meta.json(H는 HORIZONS)을 읽는다. 하나라도 없거나 설명이 맞지 않으면 ValueError."""
    loaded = {}
    for horizon in HORIZONS:
        model_path = model_dir / f"h{horizon}.joblib"
        meta_path = model_dir / f"h{horizon}.meta.json"
        if not model_path.is_file() or not meta_path.is_file():
            raise ValueError(f"h{horizon} 모델 파일이나 설명 파일이 없습니다")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("horizon") != horizon:
            raise ValueError(f"h{horizon} 설명의 horizon이 다릅니다")
        if tuple(meta.get("features", ())) != FEATURES:
            raise ValueError(f"h{horizon} 모델의 특징이 서빙 코드와 다릅니다")
        if meta.get("history_days") != HISTORY_DAYS:
            raise ValueError(f"h{horizon} 모델의 기록 길이가 다릅니다")
        if meta.get("sklearn_version") != sklearn.__version__:
            # 다른 버전으로 읽으면 경고만 나고 예측이 달라질 수 있어 막는다
            raise ValueError(f"h{horizon} 모델은 scikit-learn {meta.get('sklearn_version')}로 만들었습니다"
                             f"(설치된 버전 {sklearn.__version__})")
        if meta.get("model") != MODEL_NAME:
            raise ValueError(f"h{horizon} 모델 종류가 {MODEL_NAME}이 아닙니다")
        version = meta.get("model_version")
        if not isinstance(version, str) or not version:
            raise ValueError(f"h{horizon} 설명에 모델 버전이 없습니다")
        parts = joblib.load(model_path)
        if not (isinstance(parts, dict) and set(parts) == {"classifier", "regressor"}
                and hasattr(parts["classifier"], "predict_proba")):
            raise ValueError(f"h{horizon} 모델 파일이 두 단계 모델(classifier·regressor) 형식이 아닙니다")
        loaded[horizon] = LoadedModel(horizon, TwoStage(parts["classifier"], parts["regressor"]), version)
    return loaded


def make_features(daily: np.ndarray) -> pd.DataFrame:
    """(계좌 수, 90) 일별 소비(마지막 열이 기준일 당일)를 계좌마다 core 특징 15개 한 행으로 바꾼다.

    core: 최근 7·14·28·84일 합계와 쓴 날 수, 2~4주 전 주별 합계, 마지막 소비 후 경과일,
          84일 동안 쓴 날의 평균 금액, 평균 사용 간격(ADI), 쓴 날 금액의 변동성(CV²)
    누적합의 차이로 계산하는 방식까지 학습 코드와 같게 둔다(실수 특징의 마지막 자리까지 같게).
    """
    if daily.ndim != 2 or daily.shape[1] != HISTORY_DAYS:
        raise ValueError(f"일별 기록은 계좌마다 {HISTORY_DAYS}일이어야 합니다")
    if (daily < 0).any():
        raise ValueError("일별 소비는 0 이상이어야 합니다")
    spend = daily.astype(np.int64)
    n = spend.shape[0]
    end = HISTORY_DAYS  # 누적합에서 기준일 당일까지(포함)의 위치
    zero_int = np.zeros((n, 1), dtype=np.int64)
    cum = np.concatenate([zero_int, np.cumsum(spend, axis=1)], axis=1)
    cum_active = np.concatenate([zero_int, np.cumsum((spend > 0).astype(np.int64), axis=1)], axis=1)
    cum_square = np.concatenate([np.zeros((n, 1)), np.cumsum(spend.astype(np.float64) ** 2, axis=1)], axis=1)

    feats: dict[str, np.ndarray] = {}
    for w in WINDOWS:
        feats[f"sum_{w}d"] = cum[:, end] - cum[:, end - w]
        feats[f"days_{w}d"] = cum_active[:, end] - cum_active[:, end - w]
    for k in (2, 3, 4):
        feats[f"week_minus{k}"] = cum[:, end - 7 * (k - 1)] - cum[:, end - 7 * k]

    last = np.where(spend > 0, np.arange(HISTORY_DAYS), -1).max(axis=1)
    feats["days_since_last"] = np.where(last >= 0, (HISTORY_DAYS - 1) - last, NO_SPEND_DAYS)

    active = feats["days_84d"]
    total = feats["sum_84d"].astype(np.float64)
    mean = np.divide(total, active, out=np.zeros_like(total), where=active > 0)
    square = cum_square[:, end] - cum_square[:, end - 84]
    var = np.divide(square, active, out=np.zeros_like(square), where=active > 0) - mean ** 2
    feats["mean_active_84d"] = mean
    feats["adi_84d"] = np.divide(84.0, active, out=np.full(n, 84.0), where=active > 0)
    feats["cv2_84d"] = np.divide(np.maximum(var, 0), mean ** 2, out=np.zeros_like(mean),
                                 where=(active > 1) & (mean > 0))
    return pd.DataFrame({name: feats[name] for name in FEATURES})


def predict(loaded: LoadedModel, daily: np.ndarray) -> np.ndarray:
    """계좌마다 다음 horizon일(기준일 다음 날부터) 체크 생활 소비 합계(원, 0 이상 정수, 반올림)."""
    pred = np.asarray(loaded.model.predict(make_features(daily)), dtype=np.float64)
    return np.maximum(np.floor(pred + 0.5), 0).astype(np.int64)
