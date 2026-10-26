#!/bin/sh
set -eu

if [ "$(id -un)" != 'yamu-ci' ]; then
    echo 'Run this wrapper as the dedicated yamu-ci user.' >&2
    exit 1
fi

ROOTLESS_DOCKER_SOCKET="/run/user/$(id -u)/docker.sock"
DOCKER_HOST="unix://$ROOTLESS_DOCKER_SOCKET"
export ROOTLESS_DOCKER_SOCKET DOCKER_HOST
CI_HOST_IP=$(tailscale ip -4)
test -n "$CI_HOST_IP"
export CI_HOST_IP
test -S "$ROOTLESS_DOCKER_SOCKET"
if ! docker info --format '{{json .SecurityOptions}}' | grep -q '"name=rootless"'; then
    echo 'Refusing to use a Docker engine that is not rootless.' >&2
    exit 1
fi

cd "$(dirname "$0")/../.."
exec docker compose --env-file infra/.env.ci-agent -f infra/compose.ci-agent.yml "$@"
