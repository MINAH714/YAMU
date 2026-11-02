import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from dev_keys import FILES
from validate_compose import render, validate_model


class DevelopmentComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="yamu-dev-config-test-")
        cls.root = Path(cls.temporary.name)
        cls.directory = cls.root / "secrets"
        cls.directory.mkdir()
        for filename in [*FILES.values(), "yamu.crypto.data-key-id", "spring.datasource.password"]:
            (cls.directory / filename).write_text("disposable-test-fixture")
        (cls.root / "public-test-truststore.p12").write_text("disposable-public-certificate-fixture")
        env = cls.root / "settings.env"
        env.write_text("\n".join([
            "YAMU_DEV_BACKEND_IMAGE=yamu-backend:disposable-config-test",
            "YAMU_DEV_RDS_HOST=yamu-mysql.cl622ga6ai2l.ap-northeast-2.rds.amazonaws.com",
            "YAMU_DEV_RDS_TRUSTSTORE_PATH=" + (cls.root / "public-test-truststore.p12").as_posix(),
            "YAMU_DEV_SECRETS_DIR=" + cls.directory.as_posix(),
            "YAMU_DEV_HTTP_PORT=18080", ""]))
        # Do not inherit a caller's Compose override or actual development settings.
        remove = {k: "" for k in os.environ if k.startswith(("COMPOSE_", "YAMU_DEV_"))}
        with patch.dict(os.environ, remove):
            cls.model = render(env, cls.directory)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def rejects(self, change):
        model = copy.deepcopy(self.model)
        change(model)
        with self.assertRaises((ValueError, KeyError)):
            validate_model(model, self.directory)

    def test_real_compose_renders_without_starting_services(self):
        validate_model(self.model, self.directory)

    def test_production_db_is_rejected(self):
        def change(model):
            env = model["services"]["backend"]["environment"]
            env["SPRING_DATASOURCE_URL"] = env["SPRING_DATASOURCE_URL"].replace("/yamu_dev?", "/yamu?")
        self.rejects(change)

    def test_enabled_flyway_and_hibernate_updates_are_rejected(self):
        self.rejects(lambda m: m["services"]["backend"]["environment"].update(SPRING_FLYWAY_ENABLED="true"))
        self.rejects(lambda m: m["services"]["backend"]["environment"].update(SPRING_JPA_HIBERNATE_DDL_AUTO="update"))

    def test_unverified_tls_is_rejected(self):
        def change(model):
            env = model["services"]["backend"]["environment"]
            env["SPRING_DATASOURCE_URL"] = env["SPRING_DATASOURCE_URL"].replace("VERIFY_IDENTITY", "REQUIRED")
        self.rejects(change)

    def test_public_backend_or_redis_ports_are_rejected(self):
        self.rejects(lambda m: m["services"]["backend"]["ports"][0].update(host_ip="0.0.0.0"))
        self.rejects(lambda m: m["services"]["redis"].update(ports=[{"published": "6379", "target": 6379}]))

    def test_production_network_or_volume_reuse_is_rejected(self):
        self.rejects(lambda m: m["networks"]["data"].update(name="yamu-prod_database"))
        self.rejects(lambda m: m["volumes"]["redis_data"].update(name="yamu-prod_redis_data"))

    def test_secret_values_in_environment_are_rejected(self):
        self.rejects(lambda m: m["services"]["backend"]["environment"].update(YAMU_CRYPTO_DATA_KEY_1="not-a-real-key"))

    def test_url_credentials_and_redis_url_override_are_rejected(self):
        def change(model):
            env = model["services"]["backend"]["environment"]
            env["SPRING_DATASOURCE_URL"] += "&password=disposable-test-value"
        self.rejects(change)
        self.rejects(lambda m: m["services"]["backend"]["environment"].update(SPRING_DATA_REDIS_URL="redis://other:6379"))

    def test_missing_secret_and_wrong_property_filename_are_rejected(self):
        self.rejects(lambda m: m["services"]["backend"]["secrets"].pop())
        self.rejects(lambda m: m["services"]["backend"]["secrets"][0].update(target="wrong.property"))

    def test_different_secret_directory_is_rejected(self):
        self.rejects(lambda m: m["secrets"]["dev_data_key"].update(file=str(self.root / "another" / "yamu.crypto.data-keys[1]")))


if __name__ == "__main__":
    unittest.main()
