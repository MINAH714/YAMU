"""생활비 예측: 특징·예측이 학습 저장소의 기대값(golden)과 같은지, 모델 읽기 검사, API 입력 검증과 실패 응답."""

import hashlib
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import joblib
import numpy as np
from fastapi.testclient import TestClient

from app import living_cost
from fastapi import FastAPI

from app.main import MAX_BODY_BYTES, MAX_DAILY_AMOUNT, app, docs_enabled, docs_urls

AI_DIR = Path(__file__).resolve().parents[1]
MODEL_DIR = AI_DIR / "models" / "living-cost"
GOLDEN = json.loads((Path(__file__).with_name("golden_living_cost.json")).read_text(encoding="utf-8"))


def golden_daily() -> np.ndarray:
    return np.array([case["dailySpending"] for case in GOLDEN["cases"]], dtype=np.int64)


def body(**overrides) -> dict:
    base = {"asOf": GOLDEN["asOf"], "horizonDays": 30,
            "accounts": [{"key": case["name"], "dailySpending": case["dailySpending"]} for case in GOLDEN["cases"]]}
    base.update(overrides)
    return base


def one_account(**overrides) -> dict:
    account = {"key": "a", "dailySpending": [0] * 90}
    account.update(overrides)
    return body(accounts=[account])


class Features(unittest.TestCase):

    def test_features_equal_training_code_to_the_last_digit(self):
        X = living_cost.make_features(golden_daily())

        self.assertEqual(list(X.columns), GOLDEN["features"])
        for i, case in enumerate(GOLDEN["cases"]):
            for name, expected in case["features"].items():
                actual = X[name].iloc[i].item()
                self.assertEqual(type(actual), type(expected), f"{case['name']} {name}")
                self.assertEqual(actual, expected, f"{case['name']} {name}")

    def test_rejects_wrong_length_and_negative(self):
        with self.assertRaises(ValueError):
            living_cost.make_features(np.zeros((1, 89), dtype=np.int64))
        with self.assertRaises(ValueError):
            living_cost.make_features(np.array([[-1] + [0] * 89]))


class Models(unittest.TestCase):

    def test_committed_models_predict_golden_values(self):
        models = living_cost.load_models(MODEL_DIR)

        self.assertEqual(sorted(models), [7, 14, 30])
        for horizon, loaded in models.items():
            self.assertEqual(loaded.version, GOLDEN["modelVersion"])
            expected = [case["predictions"][str(horizon)] for case in GOLDEN["cases"]]
            self.assertEqual(living_cost.predict(loaded, golden_daily()).tolist(), expected, horizon)

    def test_rejects_missing_file_and_mismatched_meta(self):
        def broken(change) -> Path:
            tmp = Path(tempfile.mkdtemp())
            self.addCleanup(shutil.rmtree, tmp)
            for path in MODEL_DIR.glob("h*"):
                shutil.copy(path, tmp / path.name)
            change(tmp)
            return tmp

        def edit_meta(key, value):
            def change(tmp: Path):
                path = tmp / "h30.meta.json"
                meta = json.loads(path.read_text(encoding="utf-8"))
                meta[key] = value
                path.write_text(json.dumps(meta), encoding="utf-8")
            return change

        def flip_middle_byte(tmp: Path):
            # 숫자 배열 부분이 바뀌면 joblib은 오류 없이 읽으므로(2026-11-04 측정 46가지 중 37가지) 지문으로 막는다
            path = tmp / "h14.joblib"
            data = bytearray(path.read_bytes())
            data[len(data) // 2] ^= 0xFF
            path.write_bytes(bytes(data))

        def not_two_stage(tmp: Path):
            # 지문은 맞춰 두고 형식만 틀리게 해 형식 검사까지 닿게 한다
            joblib.dump({"model": 1}, tmp / "h14.joblib")
            meta_path = tmp / "h14.meta.json"
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            meta["sha256"] = hashlib.sha256((tmp / "h14.joblib").read_bytes()).hexdigest()
            meta_path.write_text(json.dumps(meta), encoding="utf-8")

        cases = {
            "h30 없음": lambda tmp: (tmp / "h30.joblib").unlink(),
            "모델 파일 1바이트 바뀜": flip_middle_byte,
            "지문 없음": edit_meta("sha256", None),
            "설명 없음": lambda tmp: (tmp / "h30.meta.json").unlink(),
            "특징 순서": edit_meta("features", list(reversed(living_cost.FEATURES))),
            "scikit-learn 버전": edit_meta("sklearn_version", "0.0.0"),
            "horizon": edit_meta("horizon", 7),
            "기록 길이": edit_meta("history_days", 84),
            "모델 버전 없음": edit_meta("model_version", ""),
            "모델 종류": edit_meta("model", "HGB_POISSON"),
            "두 단계 형식 아님": not_two_stage,
        }
        for name, change in cases.items():
            with self.subTest(name), self.assertRaises(ValueError):
                living_cost.load_models(broken(change))


class Api(unittest.TestCase):

    def client(self, model_dir: Path = MODEL_DIR) -> TestClient:
        patcher = mock.patch.dict(os.environ, {"AI_LIVING_COST_MODEL_DIR": str(model_dir)})
        patcher.start()
        self.addCleanup(patcher.stop)
        client = TestClient(app)
        client.__enter__()
        self.addCleanup(client.__exit__, None, None, None)
        return client

    DOC_PATHS = ("/docs", "/redoc", "/openapi.json")

    def test_docs_enabled_only_for_true(self):
        # S15P31E103-355: 없거나 true가 아니면 끈다
        for env, expected in (({}, False), ({"AI_DOCS_ENABLED": "false"}, False), ({"AI_DOCS_ENABLED": "1"}, False),
                              ({"AI_DOCS_ENABLED": "true"}, True), ({"AI_DOCS_ENABLED": " TRUE "}, True)):
            with self.subTest(env):
                self.assertEqual(docs_enabled(env), expected)

    def test_real_app_follows_current_environment(self):
        # 실행 환경에 AI_DOCS_ENABLED가 있든 없든, 실제 앱의 문서 주소가 그 값의 판정과 같다
        expected = 200 if docs_enabled(os.environ) else 404
        client = self.client()
        for path in self.DOC_PATHS:
            with self.subTest(path):
                self.assertEqual(client.get(path).status_code, expected)

    def test_docs_paths_answer_200_when_enabled_and_404_when_disabled(self):
        # 켠 설정·끈 설정으로 만든 앱에 세 주소를 실제로 요청한다
        for enabled, expected in ((True, 200), (False, 404)):
            with TestClient(FastAPI(**docs_urls(enabled))) as client:
                for path in self.DOC_PATHS:
                    with self.subTest(enabled=enabled, path=path):
                        self.assertEqual(client.get(path).status_code, expected)

    def test_health_keeps_status_ok_and_reports_models(self):
        res = self.client().get("/health")

        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["status"], "ok")
        version = GOLDEN["modelVersion"]
        self.assertEqual(res.json()["livingCostModel"],
                         {"loaded": True, "horizons": [7, 14, 30],
                          "modelVersion": {"7": version, "14": version, "30": version}})

    def test_prediction_matches_golden_and_keeps_order(self):
        client = self.client()
        for horizon in (7, 14, 30):
            res = client.post("/forecast/living-cost", json=body(horizonDays=horizon))
            self.assertEqual(res.status_code, 200)
            data = res.json()
            self.assertEqual(data["modelVersion"], GOLDEN["modelVersion"])
            self.assertEqual(data["horizonDays"], horizon)
            self.assertEqual(data["predictions"],
                             [{"key": c["name"], "amount": c["predictions"][str(horizon)]} for c in GOLDEN["cases"]])

    def test_log_has_no_request_values(self):
        client = self.client()
        with self.assertLogs("app.main", level="INFO") as logs:
            client.post("/forecast/living-cost", json=one_account(key="secret-key", dailySpending=[4_321] * 90))

        text = "\n".join(logs.output)
        self.assertNotIn("secret-key", text)
        self.assertNotIn("4321", text)

    def test_invalid_inputs_are_400_with_field_errors_and_no_values(self):
        many = [{"key": f"k{i}", "dailySpending": [0] * 90} for i in range(21)]
        cases = {
            "89일": one_account(dailySpending=[0] * 89),
            "91일": one_account(dailySpending=[0] * 91),
            "음수": one_account(dailySpending=[-4_321] + [0] * 89),
            "1조 원 초과": one_account(dailySpending=[1_000_000_000_001] + [0] * 89),
            "문자열 금액": one_account(dailySpending=["4321"] + [0] * 89),
            "소수 금액": one_account(dailySpending=[4321.0] + [0] * 89),
            "불리언 금액": one_account(dailySpending=[True] + [0] * 89),
            "빈 key": one_account(key=""),
            "65자 key": one_account(key="k" * 65),
            "숫자 key": one_account(key=4321),
            "key 겹침": body(accounts=[{"key": "a", "dailySpending": [0] * 90}] * 2),
            "계좌 0개": body(accounts=[]),
            "계좌 21개": body(accounts=many),
            "지원하지 않는 일수": body(horizonDays=10),
            "문자열 일수": body(horizonDays="30"),
            "일수 없음": {"asOf": GOLDEN["asOf"], "accounts": body()["accounts"]},
            "숫자 날짜": body(asOf=4321),
            "잘못된 날짜": body(asOf="2026-13-01"),
            "날짜시각": body(asOf=GOLDEN["asOf"] + "T00:00:00"),
            "구분자 없는 날짜": body(asOf=GOLDEN["asOf"].replace("-", "")),
        }
        client = self.client()
        for name, payload in cases.items():
            with self.subTest(name):
                res = client.post("/forecast/living-cost", json=payload)
                self.assertEqual(res.status_code, 400)
                self.assertEqual(res.json()["error"], "INVALID_REQUEST")
                self.assertTrue(res.json()["fieldErrors"])
                self.assertNotIn("4321", res.text)

        res = client.post("/forecast/living-cost", content=b"{not json", headers={"Content-Type": "application/json"})
        self.assertEqual(res.status_code, 400)

    def test_largest_valid_request_fits_and_larger_body_is_413(self):
        client = self.client()
        # 계좌 20개, key 64자, 날마다 1조 원: 받을 수 있는 가장 큰 정상 요청
        largest = body(accounts=[{"key": f"{i:02d}" + "k" * 62, "dailySpending": [MAX_DAILY_AMOUNT] * 90}
                                 for i in range(20)])
        encoded = json.dumps(largest).encode()
        self.assertLess(len(encoded), MAX_BODY_BYTES)
        self.assertEqual(client.post("/forecast/living-cost", content=encoded,
                                     headers={"Content-Type": "application/json"}).status_code, 200)

        oversized = b'{"pad": "' + b"4321" * (MAX_BODY_BYTES // 4) + b'"}'
        res = client.post("/forecast/living-cost", content=oversized, headers={"Content-Type": "application/json"})
        self.assertEqual(res.status_code, 413)
        self.assertEqual(res.json(), {"error": "PAYLOAD_TOO_LARGE"})

        # Content-Length 없이 나눠 보내는(chunked) 본문도 센다
        chunks = (oversized[i:i + 4096] for i in range(0, len(oversized), 4096))
        res = client.post("/forecast/living-cost", content=chunks, headers={"Content-Type": "application/json"})
        self.assertEqual(res.status_code, 413)

    def test_without_models_server_starts_and_prediction_is_503(self):
        client = self.client(AI_DIR / "models" / "none")

        health = client.get("/health")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json(), {"status": "ok", "livingCostModel": {"loaded": False}})
        res = client.post("/forecast/living-cost", json=body())
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json(), {"error": "MODEL_MISSING"})

    def test_unexpected_load_error_still_starts_server(self):
        # 망가진 joblib은 KeyError처럼 미리 셀 수 없는 예외도 낸다(2026-11-04 측정). 그래도 서버는 뜨고 503이다
        with mock.patch.object(living_cost, "load_models", side_effect=KeyError("broken")), \
                self.assertLogs("app.main", level="ERROR"):
            client = self.client()

        self.assertEqual(client.get("/health").json(), {"status": "ok", "livingCostModel": {"loaded": False}})
        self.assertEqual(client.post("/forecast/living-cost", json=body()).status_code, 503)


if __name__ == "__main__":
    unittest.main()
