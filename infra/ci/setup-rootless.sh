#!/bin/sh
# Ubuntu 24.04: run as root after installing uidmap and slirp4netns.
set -eu

test "$(id -u)" -eq 0
command -v newuidmap >/dev/null
command -v newgidmap >/dev/null
command -v slirp4netns >/dev/null
command -v dockerd-rootless-setuptool.sh >/dev/null
test "$(stat -fc %T /sys/fs/cgroup)" = 'cgroup2fs'

if ! id yamu-ci >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash yamu-ci
    chmod 750 /home/yamu-ci
fi
case " $(id -nG yamu-ci) " in
    *' sudo '*|*' docker '*|*' root '*) echo 'CI user has forbidden groups.' >&2; exit 1 ;;
esac
test "$(getent passwd yamu-ci | cut -d: -f6)" = '/home/yamu-ci'
grep -q '^yamu-ci:' /etc/subuid
grep -q '^yamu-ci:' /etc/subgid

ci_uid=$(id -u yamu-ci)
test "$ci_uid" -ge 1000
dropin="/etc/systemd/system/user@${ci_uid}.service.d"
install -d -m 755 "$dropin"
if [ ! -e "$dropin/yamu-ci-delegate.conf" ]; then
    printf '[Service]\nDelegate=cpu cpuset io memory pids\n' > "$dropin/yamu-ci-delegate.conf"
fi
systemctl daemon-reload
loginctl enable-linger yamu-ci
systemctl start "user@${ci_uid}.service"
# This slice contains the CI daemon, agent, builds and all test containers.
systemctl set-property "user-${ci_uid}.slice" MemoryMax=6G CPUQuota=200% TasksMax=4096

sudo -iu yamu-ci env \
    XDG_RUNTIME_DIR="/run/user/$ci_uid" \
    DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$ci_uid/bus" \
    /usr/bin/dockerd-rootless-setuptool.sh install --force

sudo -iu yamu-ci env DOCKER_HOST="unix:///run/user/$ci_uid/docker.sock" \
    docker info --format 'Security={{json .SecurityOptions}} CgroupDriver={{.CgroupDriver}}'
echo 'Dedicated Rootless Docker setup completed. Production Docker was not restarted.'
