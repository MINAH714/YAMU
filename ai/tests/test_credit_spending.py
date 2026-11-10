"""신용 생활 소비 예측(S15P31E103-404): 커밋한 신용 모델이 학습 저장소 기대값과 같은지, API, 체크 모델과 따로 동작하는지."""

import json
import os
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
from fastapi.testclient import TestClient

from app import living_cost
from app.main import app

AI_DIR = Path(__file__).resolve().parents[1]
MODEL_DIR = AI_DIR / "models" / "credit-spending"
CHECK_MODEL_DIR = AI_DIR / "models" / "living-cost"
NO_MODEL_DIR = AI_DIR / "models" / "none"
GOLDEN = json.loads((Path(__file__).with_name("golden_credit_spending.json")).read_text(encoding="utf-8"))
CHECK_GOLDEN = json.loads((Path(__file__).with_name("golden_living_cost.json")).read_text(encoding="utf-8"))
URL = "/forecast/credit-spending"


def body(golden: dict = GOLDEN, **overrides) -> dict:
    base = {"asOf": golden["asOf"], "horizonDays": 30,
            "accounts": [{"key": case["name"], "dailySpending": case["dailySpending"]} for case in golden["cases"]]}
    base.update(overrides)
    return base


class Models(unittest.TestCase):

    def test_committed_credit_models_predict_golden_values(self):
        models = living_cost.load_models(MODEL_DIR)

        self.assertEqual(sorted(models), [7, 14, 30])
        daily = np.array([case["dailySpending"] for case in GOLDEN["cases"]], dtype=np.int64)
        for horizon, loaded in models.items():
            self.assertEqual(loaded.version, GOLDEN["modelVersion"])
            expected = [case["predictions"][str(horizon)] for case in GOLDEN["cases"]]
            self.assertEqual(living_cost.predict(loaded, daily).tolist(), expected, horizon)

    def test_credit_golden_uses_same_features_as_check(self):
        # 신용 모델도 서빙 특징 계산을 그대로 쓴다. 같은 입력이면 특징값이 체크 골든과 같아야 한다
        self.assertEqual(GOLDEN["features"], CHECK_GOLDEN["features"])
        check = {case["name"]: case for case in CHECK_GOLDEN["cases"]}
        for case in GOLDEN["cases"]:
            self.assertEqual(case["dailySpending"], check[case["name"]]["dailySpending"])
            self.assertEqual(case["features"], check[case["name"]]["features"], case["name"])


class Api(unittest.TestCase):

    def client(self, credit_dir: Path = MODEL_DIR, check_dir: Path = CHECK_MODEL_DIR) -> TestClient:
        patcher = mock.patch.dict(os.environ, {"AI_CREDIT_SPENDING_MODEL_DIR": str(credit_dir),
                                               "AI_LIVING_COST_MODEL_DIR": str(check_dir)})
        patcher.start()
        self.addCleanup(patcher.stop)
        client = TestClient(app)
        client.__enter__()
        self.addCleanup(client.__exit__, None, None, None)
        return client

    def test_prediction_matches_golden_and_keeps_order(self):
        client = self.client()
        for horizon in (7, 14, 30):
            res = client.post(URL, json=body(horizonDays=horizon))
            self.assertEqual(res.status_code, 200)
            data = res.json()
            self.assertEqual(data["modelVersion"], GOLDEN["modelVersion"])
            self.assertEqual(data["horizonDays"], horizon)
            self.assertEqual(data["predictions"],
                             [{"key": c["name"], "amount": c["predictions"][str(horizon)]} for c in GOLDEN["cases"]])

    def test_health_reports_both_models(self):
        res = self.client().get("/health").json()

        version = GOLDEN["modelVersion"]
        self.assertEqual(res["creditSpendingModel"],
                         {"loaded": True, "horizons": [7, 14, 30],
                          "modelVersion": {"7": version, "14": version, "30": version}})
        self.assertTrue(res["livingCostModel"]["loaded"])

    def test_without_credit_models_only_credit_is_503(self):
        client = self.client(credit_dir=NO_MODEL_DIR)

        self.assertEqual(client.get("/health").json()["creditSpendingModel"], {"loaded": False})
        res = client.post(URL, json=body())
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json(), {"error": "MODEL_MISSING"})
        # 체크 생활비 API는 그대로 동작한다
        check = client.post("/forecast/living-cost", json=body(CHECK_GOLDEN))
        self.assertEqual(check.status_code, 200)
        self.assertEqual(check.json()["modelVersion"], CHECK_GOLDEN["modelVersion"])

    def test_without_check_models_credit_still_works(self):
        client = self.client(check_dir=NO_MODEL_DIR)

        self.assertEqual(client.post("/forecast/living-cost", json=body(CHECK_GOLDEN)).status_code, 503)
        self.assertEqual(client.post(URL, json=body()).status_code, 200)

    def test_invalid_inputs_are_400_like_living_cost(self):
        one = {"key": "a", "dailySpending": [0] * 90}
        cases = {
            "89일": body(accounts=[{**one, "dailySpending": [0] * 89}]),
            "음수": body(accounts=[{**one, "dailySpending": [-4_321] + [0] * 89}]),
            "key 겹침": body(accounts=[one, one]),
            "지원하지 않는 일수": body(horizonDays=10),
            "날짜시각": body(asOf=GOLDEN["asOf"] + "T00:00:00"),
        }
        client = self.client()
        for name, payload in cases.items():
            with self.subTest(name):
                res = client.post(URL, json=payload)
                self.assertEqual(res.status_code, 400)
                self.assertEqual(res.json()["error"], "INVALID_REQUEST")
                self.assertNotIn("4321", res.text)

    def test_log_has_no_request_values(self):
        client = self.client()
        with self.assertLogs("app.main", level="INFO") as logs:
            client.post(URL, json=body(accounts=[{"key": "secret-card", "dailySpending": [4_321] * 90}]))

        text = "\n".join(logs.output)
        self.assertIn("credit-spending", text)
        self.assertNotIn("secret-card", text)
        self.assertNotIn("4321", text)


if __name__ == "__main__":
    unittest.main()
