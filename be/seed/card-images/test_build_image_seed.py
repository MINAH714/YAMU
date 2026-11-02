import copy
import json
import unittest

from build_image_seed import HERE, quote, render_report, render_sql, validate


class CardImageSeedBuildTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((HERE / "card-images.json").read_text(encoding="utf-8"))

    def test_catalog_contains_seventeen_apply_one_hold_and_two_excluded(self):
        cards = validate(self.document)
        self.assertEqual(len(cards), 20)
        self.assertEqual(sum(c["state"] == "APPLY" for c in cards), 17)
        self.assertEqual(sum(c["state"] == "HOLD" for c in cards), 1)
        self.assertEqual(sum(c["state"] == "EXCLUDED" for c in cards), 2)

    def test_generated_sql_is_current_deterministic_and_omits_held_products(self):
        sql = render_sql(self.document)
        self.assertEqual(sql, (HERE.parent / "03-card-product-images.sql").read_text(encoding="utf-8"))
        reordered = copy.deepcopy(self.document)
        reordered["cards"].reverse()
        self.assertEqual(sql, render_sql(reordered))
        for name in ("shinhan-pointplan", "shinhan-biabix", "hana-wonder2-daily"):
            self.assertNotIn(name, sql)
        self.assertNotIn("INSERT INTO card_product", sql)
        self.assertIn("WHERE p.image_key IS NULL", sql)

    def test_duplicate_id_or_product_identity_is_rejected(self):
        for fields in (("card_id",), ("issuer_code", "product_name", "card_type")):
            with self.subTest(fields=fields):
                doc = copy.deepcopy(self.document)
                for field in fields:
                    doc["cards"][1][field] = doc["cards"][0][field]
                with self.assertRaises(ValueError):
                    validate(doc)

    def test_unsafe_or_wrong_product_image_key_is_rejected(self):
        for key in ("https://other.example/a.png", "cards/../a.png", "cards/%2e%2e/a.png", "cards/wrong/a.png"):
            with self.subTest(key=key):
                doc = copy.deepcopy(self.document)
                doc["cards"][0]["image_key"] = key
                with self.assertRaises(ValueError):
                    validate(doc)

    def test_samsung_animated_original_cannot_replace_static_front(self):
        doc = copy.deepcopy(self.document)
        card = next(c for c in doc["cards"] if c["card_id"] == "samsung-aap1767")
        card["image_key"] = f"cards/samsung-aap1767/original/{card['sha256']}.gif"
        with self.assertRaises(ValueError):
            validate(doc)

    def test_mismatched_checksum_is_rejected(self):
        self.document["cards"][0]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            validate(self.document)

    def test_sql_quotes_are_escaped_without_sql_mode_dependency(self):
        self.assertEqual(quote("card's name"), "'card''s name'")
        for value in ("card\\name", "card\nname"):
            with self.assertRaises(ValueError):
                quote(value)

    def test_existing_seed_product_names_match_the_mapping(self):
        import csv
        with (HERE.parent / "catalog-ab20/db/card_product.csv").open(encoding="utf-8-sig", newline="") as source:
            products = {r["card_id"]: r for r in csv.DictReader(source)}
        for card in self.document["cards"]:
            if card["catalog"] == "02":
                expected = products[card["card_id"]]
                for key in ("issuer_code", "product_name", "card_type"):
                    self.assertEqual(card[key], expected[key])

    def test_report_records_deferred_products_and_unknown_database_state(self):
        report = render_report(self.document)
        self.assertEqual(report, (HERE / "report.md").read_text(encoding="utf-8"))
        for text in ("POINT_PLAN_POLICY_HOLD", "NO_SUPPORTED_BENEFITS", "현재 RDS", "미확인"):
            self.assertIn(text, report)


if __name__ == "__main__":
    unittest.main()
