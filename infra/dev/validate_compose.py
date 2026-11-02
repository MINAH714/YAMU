"""Render and check development Compose without starting services or contacting RDS/Redis."""
import json
from pathlib import Path
import subprocess
from urllib.parse import parse_qs, urlsplit

from dev_keys import DIRECTORY, FILES, RDS_HOST, SafeArgumentParser

COMPOSE = Path(__file__).resolve().parents[1] / "compose.dev.yml"


def validate_model(model, secret_directory=DIRECTORY):
    if model.get("name") != "yamu-dev" or set(model.get("services", {})) != {"backend", "redis"}:
        raise ValueError("Unexpected development project or service list")
    backend, redis = model["services"]["backend"], model["services"]["redis"]
    if backend.get("user") != "10001:10001":
        raise ValueError("Backend must run with the file-secret application UID/GID")
    env = backend["environment"]
    if env.get("SPRING_PROFILES_ACTIVE") != "dev" or env.get("SPRING_CONFIG_IMPORT") != "configtree:/run/secrets/":
        raise ValueError("Development profile and mandatory file-secret import are required")
    forbidden = {*FILES, "SPRING_DATASOURCE_PASSWORD", "YAMU_CRYPTO_DATA_KEY", "SPRING_APPLICATION_JSON",
                 "SPRING_DATA_REDIS_URL", "SPRING_REDIS_URL", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION"}
    if forbidden & set(env):
        raise ValueError("Secret values must not be injected through environment variables")
    if str(env.get("SPRING_FLYWAY_ENABLED")).lower() != "false" or env.get("SPRING_JPA_HIBERNATE_DDL_AUTO") != "validate":
        raise ValueError("Automatic schema changes must be disabled")
    url = urlsplit(env["SPRING_DATASOURCE_URL"].removeprefix("jdbc:"))
    query = parse_qs(url.query)
    allowed_query = {"serverTimezone", "characterEncoding", "sslMode", "trustCertificateKeyStoreUrl",
                     "trustCertificateKeyStoreType", "trustCertificateKeyStorePassword", "fallbackToSystemTrustStore"}
    if (url.hostname != RDS_HOST or url.port != 3306 or url.path != "/yamu_dev" or url.username or url.password
            or env.get("SPRING_DATASOURCE_USERNAME") != "yamu_dev"
            or not set(query).issubset(allowed_query)
            or query.get("sslMode") != ["VERIFY_IDENTITY"]
            or query.get("trustCertificateKeyStoreUrl") != ["file:/run/yamu/rds-truststore.p12"]
            or query.get("trustCertificateKeyStoreType") != ["PKCS12"]
            or query.get("trustCertificateKeyStorePassword") != ["changeit"]
            or query.get("fallbackToSystemTrustStore") != ["false"]):
        raise ValueError("Only the certificate-verified development RDS target is allowed")
    if env.get("SPRING_DATA_REDIS_HOST") != "redis" or str(env.get("SPRING_DATA_REDIS_PORT")) != "6379":
        raise ValueError("Backend must use its own Redis service")
    ports = backend.get("ports", [])
    if len(ports) != 1 or ports[0].get("host_ip") != "127.0.0.1" or ports[0].get("target") != 8080:
        raise ValueError("Development BE must bind one localhost port only")
    published = int(ports[0]["published"])
    if published < 1024 or published > 65535 or published in {8080, 8081}:
        raise ValueError("Reserved or invalid development port")
    if redis.get("ports") or redis.get("secrets") or set(redis.get("networks", {})) != {"data"}:
        raise ValueError("Redis must be private and must not receive application keys")
    redis_mounts = redis.get("volumes", [])
    if (len(redis_mounts) != 1 or redis_mounts[0].get("type") != "volume"
            or redis_mounts[0].get("source") != "redis_data" or redis_mounts[0].get("target") != "/data"):
        raise ValueError("Only the development Redis volume may be mounted")
    if set(model.get("networks", {})) != {"app", "data"} or not model["networks"]["data"].get("internal"):
        raise ValueError("Private development data network required")
    for name, net in model["networks"].items():
        if net.get("external") or net.get("name") != "yamu-dev_" + name:
            raise ValueError("External or shared networks are not allowed")
    if set(backend.get("networks", {})) != {"app", "data"}:
        raise ValueError("Backend requires its own egress and private data networks")
    volumes = model.get("volumes", {})
    if (set(volumes) != {"redis_data"} or volumes["redis_data"].get("external")
            or volumes["redis_data"].get("name") != "yamu-dev_redis_data"):
        raise ValueError("Redis volume must belong to the development project")
    if any(service.get("container_name") or service.get("privileged") or service.get("network_mode")
           for service in (backend, redis)):
        raise ValueError("Global container names and privileged/shared namespaces are forbidden")
    targets = {entry["target"] for entry in backend.get("secrets", [])}
    required = {*FILES.values(), "yamu.crypto.data-key-id", "spring.datasource.password"}
    if targets != required or len(backend["secrets"]) != len(required):
        raise ValueError("Exactly the required configtree secret files must be mounted")
    sources = model.get("secrets", {})
    if len(sources) != len(required) or {entry["source"] for entry in backend["secrets"]} != set(sources):
        raise ValueError("Unexpected or unused secret sources")
    if any(not Path(entry.get("file", "")).is_absolute() or entry.get("external")
           or Path(entry["file"]).parent != secret_directory for entry in sources.values()):
        raise ValueError("Secrets must come from explicit local files")
    for entry in backend["secrets"]:
        if Path(sources[entry["source"]]["file"]).name != entry["target"]:
            raise ValueError("Secret property filename does not match the mounted target")
    mounts = backend.get("volumes", [])
    if (len(mounts) != 1 or mounts[0].get("target") != "/run/yamu/rds-truststore.p12"
            or not mounts[0].get("read_only") or mounts[0].get("bind", {}).get("create_host_path", True)):
        raise ValueError("Only the existing public RDS truststore may be bind-mounted read-only")


def render(env_file, secret_directory=DIRECTORY):
    proc = subprocess.run(["docker", "compose", "--env-file", str(env_file), "-f", str(COMPOSE),
                           "config", "--format", "json"], capture_output=True, text=True)
    if proc.returncode:
        raise ValueError("Compose configuration failed; diagnostics suppressed to avoid leaking settings")
    model = json.loads(proc.stdout)
    validate_model(model, secret_directory)
    return model


def main():
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args()
    render(args.env_file)
    print(json.dumps({"valid": True, "services_started": False, "database_contacted": False, "secret_values_printed": False}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"valid": False, "error_type": type(error).__name__, "details": "Development configuration check failed; values suppressed"}))
        raise SystemExit(1)
