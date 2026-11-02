"""Run as container root on Linux; only temporary fixtures and in-memory fake AWS clients are used."""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import dev_keys as keys


class FakeStore:
    class exceptions:
        class ResourceNotFoundException(Exception):
            pass

    def __init__(self, stored=None):
        self.stored = stored
        self.creates = 0

    def get_secret_value(self, **args):
        if args["SecretId"] != keys.SECRET_NAME:
            raise AssertionError("Unexpected secret target")
        if self.stored is None:
            raise self.exceptions.ResourceNotFoundException()
        return {"SecretString": json.dumps(self.stored)}

    def create_secret(self, **args):
        if args["Name"] != keys.SECRET_NAME:
            raise AssertionError("Unexpected secret target")
        self.creates += 1
        self.stored = json.loads(args["SecretString"])


class DevelopmentKeysTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="yamu-dev-key-test-")
        self.parent = Path(self.temp.name) / "private"
        self.directory = self.parent / "secrets"
        self.values = keys.new_keys()

    def tearDown(self):
        self.temp.cleanup()

    def install(self):
        keys.install_files(self.directory, self.values, "disposable-db-password")

    def db_env(self, database="yamu_dev"):
        path = Path(self.temp.name) / "db.env"
        path.write_text(f"DB_NAME={database}\nDB_USERNAME=yamu_dev\nRDS_HOST={keys.RDS_HOST}\nDB_PASSWORD=disposable-db-password\n")
        path.chmod(0o600)
        return path

    def test_generates_valid_distinct_keys(self):
        decoded = [base64.b64decode(self.values[field]) for field in keys.FILES]
        self.assertTrue(all(len(v) == 32 for v in decoded))
        self.assertEqual(len(set(decoded)), 4)

    def test_rejects_missing_duplicate_wrong_version_or_size(self):
        cases = []
        cases.append({**self.values, "YAMU_CRYPTO_DATA_KEY_ID": "2"})
        cases.append({**self.values, "YAMU_CRYPTO_HMAC_KEY": self.values["YAMU_CRYPTO_DATA_KEY_1"]})
        cases.append({**self.values, "YAMU_JWT_SECRET": "invalid"})
        cases.append({**self.values, "YAMU_CRYPTO_DATA_KEY_1": base64.b64encode(b"short").decode()})
        for value in cases:
            with self.assertRaises(ValueError):
                keys.validate_keys(value)

    def test_private_file_permissions_and_configtree_roundtrip(self):
        self.install()
        self.assertTrue(keys.read_keys(self.directory) == self.values)
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        for filename in [*keys.FILES.values(), "yamu.crypto.data-key-id", "spring.datasource.password"]:
            info = (self.directory / filename).stat()
            self.assertEqual((stat.S_IMODE(info.st_mode), info.st_uid, info.st_gid), (0o440, 0, keys.APP_GID))

    def test_existing_keys_are_not_overwritten(self):
        self.install()
        with self.assertRaises(FileExistsError):
            keys.install_files(self.directory, keys.new_keys(), "another-test-password")
        self.assertTrue(keys.read_keys(self.directory) == self.values)

    def test_partial_write_failure_leaves_no_installed_directory(self):
        with patch.object(keys, "write_file", side_effect=OSError("disposable failure")):
            with self.assertRaises(OSError):
                self.install()
        self.assertFalse(self.directory.exists())
        self.assertEqual(len(list(self.parent.glob(".prepare-*"))), 0)

    def test_world_readable_key_is_rejected(self):
        self.install()
        (self.directory / "yamu.jwt.secret").chmod(0o444)
        with self.assertRaises(ValueError):
            keys.read_keys(self.directory)

    def test_symlinked_key_is_rejected(self):
        self.install()
        target = self.directory / "yamu.jwt.secret"
        target.unlink()
        target.symlink_to(self.directory / "yamu.crypto.hmac-key")
        with self.assertRaises(ValueError):
            keys.read_keys(self.directory)

    def test_lock_prevents_concurrent_preparation(self):
        with keys.preparation_lock(self.parent):
            with self.assertRaises(BlockingIOError):
                with keys.preparation_lock(self.parent):
                    self.fail("Second preparation acquired the lock")

    def test_production_or_insecure_db_source_is_rejected(self):
        with self.assertRaises(ValueError):
            keys.read_db_password(self.db_env("yamu"))
        path = self.db_env()
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            keys.read_db_password(path)

    def test_backup_creates_once_then_only_compares(self):
        store = FakeStore()
        keys.backup_keys(store, self.values)
        keys.backup_keys(store, self.values)
        self.assertEqual(store.creates, 1)
        self.assertTrue(store.stored == self.values)

    def test_different_existing_backup_is_never_overwritten(self):
        store = FakeStore(keys.new_keys())
        with self.assertRaises(ValueError):
            keys.backup_keys(store, self.values)
        self.assertEqual(store.creates, 0)

    def test_restored_backup_installs_same_files(self):
        restored = keys.retrieve_backup(FakeStore(self.values))
        keys.install_files(self.directory, restored, "disposable-db-password")
        self.assertTrue(keys.read_keys(self.directory) == self.values)

    def test_seed_export_keeps_legacy_and_runtime_data_key_aliases_equal(self):
        self.install()
        output = self.parent / ".env.seed.keys.local"
        keys.seed_env_file(self.directory, output)
        exported = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertTrue(exported["YAMU_CRYPTO_DATA_KEY"] == exported["YAMU_CRYPTO_DATA_KEY_1"])
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
        with self.assertRaises(FileExistsError):
            keys.seed_env_file(self.directory, output)

    def test_wrong_aws_account_is_rejected_before_secret_access(self):
        session = types.SimpleNamespace(client=lambda service: types.SimpleNamespace(get_caller_identity=lambda: {"Account": "000000000000"}))
        fake = types.SimpleNamespace(Session=lambda **kwargs: session)
        with patch.dict(sys.modules, {"boto3": fake}):
            with self.assertRaises(ValueError):
                keys.cloud_clients("disposable-profile")

    def test_bad_arguments_do_not_echo_accidentally_supplied_values(self):
        value = "disposable-sensitive-argument"
        captured = io.StringIO()
        with patch.object(sys, "argv", ["dev_keys.py", "check", "--unknown", value]), \
                contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            with self.assertRaises(ValueError) as error:
                keys.main()
        self.assertFalse(value in captured.getvalue() or value in str(error.exception))

    def test_check_outputs_only_status_and_does_not_contact_aws(self):
        self.install()
        database = self.db_env()
        captured = io.StringIO()
        with patch.object(keys, "DIRECTORY", self.directory), patch.object(keys, "DB_ENV", database), \
                patch.object(keys, "read_db_password", return_value="disposable-db-password"), \
                patch.object(sys, "argv", ["dev_keys.py", "check"]), contextlib.redirect_stdout(captured):
            keys.main()
        result = json.loads(captured.getvalue())
        self.assertTrue(result["ok"])
        self.assertFalse(result["secret_values_or_fingerprints_printed"])
        self.assertTrue(all(value not in captured.getvalue() for field, value in self.values.items() if field in keys.FILES))


if __name__ == "__main__":
    unittest.main()
