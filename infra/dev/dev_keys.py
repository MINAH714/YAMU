"""Linux operator utility. No deploy, database connection, secret printing or automatic key rotation."""
import argparse
import base64
import contextlib
import json
import logging
import os
from pathlib import Path
import secrets
import shutil
import stat
import tempfile

ACCOUNT = "329995585547"
REGION = "ap-northeast-2"
SECRET_NAME = "yamu/dev/backend-keys"
DIRECTORY = Path("/home/ubuntu/.config/yamu/dev/secrets")
DB_ENV = Path("/home/ubuntu/.config/yamu/rds-dev/db.env")
RDS_HOST = "yamu-mysql.cl622ga6ai2l.ap-northeast-2.rds.amazonaws.com"
APP_GID = 10001
FILES = {
    "YAMU_CRYPTO_DATA_KEY_1": "yamu.crypto.data-keys[1]",
    "YAMU_CRYPTO_HMAC_KEY": "yamu.crypto.hmac-key",
    "YAMU_CRYPTO_PIN_PEPPER": "yamu.crypto.pin-pepper",
    "YAMU_JWT_SECRET": "yamu.jwt.secret",
}


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default error echoes unknown argument values, which may be accidental secrets.
        raise ValueError("Invalid arguments; use --help without entering secret values")


def validate_keys(values):
    if str(values.get("YAMU_CRYPTO_DATA_KEY_ID")) != "1":
        raise ValueError("Only the explicitly configured development key version is supported")
    raw = []
    for field in FILES:
        try:
            decoded = base64.b64decode(values[field], validate=True)
        except (KeyError, ValueError, TypeError):
            raise ValueError("Required development key is missing or invalid") from None
        if (field == "YAMU_CRYPTO_DATA_KEY_1" and len(decoded) != 32) or len(decoded) < 32:
            raise ValueError("Invalid development key size")
        raw.append(decoded)
    if len(set(raw)) != len(raw):
        raise ValueError("Development keys must be distinct")
    return {"YAMU_CRYPTO_DATA_KEY_ID": "1", **{field: values[field] for field in FILES}}


def new_keys():
    return validate_keys({"YAMU_CRYPTO_DATA_KEY_ID": "1", **{
        field: base64.b64encode(secrets.token_bytes(32)).decode("ascii") for field in FILES}})


def secure_file(path, mode=None, gid=None):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0:
        raise ValueError("Expected a root-owned regular secret file")
    if mode is None:
        if stat.S_IMODE(info.st_mode) & 0o077:
            raise ValueError("Source credential file must be private")
    elif stat.S_IMODE(info.st_mode) != mode or info.st_gid != gid:
        raise ValueError("Invalid mounted secret permissions")


def read_db_password(path=DB_ENV):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid not in {0, 1000} or stat.S_IMODE(info.st_mode) & 0o077:
        raise ValueError("Development DB credential file must be private and not a symlink")
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        name, separator, value = line.partition("=")
        if not separator:
            raise ValueError("Invalid credential file syntax")
        value = value.strip()
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0] or value[0] in value[1:-1] or "\\" in value:
                raise ValueError("Unsupported quoted credential syntax")
            value = value[1:-1]
        values[name.strip()] = value
    if values.get("DB_NAME") != "yamu_dev" or values.get("DB_USERNAME") != "yamu_dev" or values.get("RDS_HOST") != RDS_HOST:
        raise ValueError("Credential file is not for the expected development database")
    password = values.get("DB_PASSWORD", "")
    if not password or any(ord(c) < 32 for c in password) or "${" in password:
        raise ValueError("Development DB password is missing or contains interpolation")
    return password


@contextlib.contextmanager
def preparation_lock(parent):
    import fcntl
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError("Secret parent directory must be root-owned with mode 0700")
    descriptor = os.open(parent / ".prepare.lock", os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def write_file(path, content, mode, gid):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        os.fchmod(descriptor, mode)
        os.fchown(descriptor, 0, gid)
        with os.fdopen(descriptor, "w", closefd=False) as stream:
            stream.write(content)
            stream.flush()
            os.fsync(descriptor)
    finally:
        os.close(descriptor)


def install_files(directory, values, password):
    values = validate_keys(values)
    if not password or any(ord(c) < 32 for c in password):
        raise ValueError("Invalid development database password")
    with preparation_lock(directory.parent):
        if directory.exists() or directory.is_symlink():
            raise FileExistsError("Existing development secrets are never overwritten")
        staging = Path(tempfile.mkdtemp(prefix=".prepare-", dir=directory.parent))
        try:
            for field, filename in FILES.items():
                write_file(staging / filename, values[field], 0o440, APP_GID)
            write_file(staging / "yamu.crypto.data-key-id", "1", 0o440, APP_GID)
            write_file(staging / "spring.datasource.password", password, 0o440, APP_GID)
            os.rename(staging, directory)
        finally:
            if staging.exists():
                # staging is a private directory created in this invocation, never an input path.
                shutil.rmtree(staging)


def read_keys(directory):
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError("Secret directory must be root-owned with mode 0700")
    for filename in [*FILES.values(), "yamu.crypto.data-key-id", "spring.datasource.password"]:
        secure_file(directory / filename, 0o440, APP_GID)
    values = {field: (directory / filename).read_text().strip() for field, filename in FILES.items()}
    values["YAMU_CRYPTO_DATA_KEY_ID"] = (directory / "yamu.crypto.data-key-id").read_text().strip()
    return validate_keys(values)


def cloud_clients(profile):
    import boto3
    logging.getLogger("botocore").setLevel(logging.WARNING)
    session = boto3.Session(profile_name=profile, region_name=REGION)
    if session.client("sts").get_caller_identity()["Account"] != ACCOUNT:
        raise ValueError("Unexpected AWS account")
    return session.client("secretsmanager")


def retrieve_backup(client):
    return validate_keys(json.loads(client.get_secret_value(SecretId=SECRET_NAME)["SecretString"]))


def backup_keys(client, values):
    values = validate_keys(values)
    try:
        existing = retrieve_backup(client)
    except client.exceptions.ResourceNotFoundException:
        client.create_secret(Name=SECRET_NAME, SecretString=json.dumps(values),
                             Description="YAMU shared development application keys; keep separate from production")
        existing = retrieve_backup(client)
    if existing != values:
        raise ValueError("Existing cloud keys differ; refusing overwrite or rotation")


def seed_env_file(directory, output):
    # Export only keys, never financial API identity/account/PIN credentials. Caller adds those separately.
    if output != directory.parent / ".env.seed.keys.local":
        raise ValueError("Seed key export is restricted to the private development directory")
    with preparation_lock(directory.parent):
        values = read_keys(directory)
        values["YAMU_CRYPTO_DATA_KEY"] = values["YAMU_CRYPTO_DATA_KEY_1"]
        content = "".join(f"{key}={value}\n" for key, value in sorted(values.items()))
        write_file(output, content, 0o600, 0)


def main():
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["init", "check", "backup", "restore", "seed-env"])
    parser.add_argument("--profile", default="yamu", help="Existing operator AWS profile; backup/restore only")
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise PermissionError("Run on the Linux deployment host as root; never enter secret values as arguments")
    if args.action in {"init", "restore"}:
        if DIRECTORY.exists() or DIRECTORY.is_symlink():
            raise FileExistsError("Existing development secrets are never overwritten")
        password = read_db_password()
        values = new_keys() if args.action == "init" else retrieve_backup(cloud_clients(args.profile))
        install_files(DIRECTORY, values, password)
    elif args.action == "backup":
        backup_keys(cloud_clients(args.profile), read_keys(DIRECTORY))
    elif args.action == "seed-env":
        seed_env_file(DIRECTORY, DIRECTORY.parent / ".env.seed.keys.local")
    else:
        read_keys(DIRECTORY)
        password = (DIRECTORY / "spring.datasource.password").read_text()
        if not secrets.compare_digest(password, read_db_password()):
            raise ValueError("Mounted database password differs from the development credential source")
    print(json.dumps({"action": args.action, "ok": True, "secret_values_or_fingerprints_printed": False}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Exception strings, SDK responses and tracebacks can contain secrets. Never log them.
        print(json.dumps({"ok": False, "error_type": type(error).__name__, "details": "Check private file permissions, configuration and operator AWS access; no values printed."}))
        raise SystemExit(1)
