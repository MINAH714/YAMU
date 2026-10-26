#!/bin/sh
set -eu

java_version=$(java -version 2>&1)
case "$java_version" in
    *'version "17.'*) ;;
    *) echo 'CI requires Java 17 for the application build.' >&2; exit 1 ;;
esac
node -e 'if (process.versions.node.split(".")[0] !== "24") { throw new Error("CI requires Node.js 24"); }'
python3 --version
npm --version
git --version
java -version
node --version

# A missing Docker runtime would silently skip MySQL tests in this repository.
docker info --format '{{.ServerVersion}}' >/dev/null
if ! docker info --format '{{json .SecurityOptions}}' | grep -q '"name=rootless"'; then
    echo 'CI requires its dedicated Rootless Docker engine, not the production engine.' >&2
    exit 1
fi
echo 'Docker is reachable; MySQL tests must also pass the XML report gate.'

# The tracked wrapper currently contains CRLF; normalize only the disposable checkout.
sed -i 's/\r$//' be/gradlew
