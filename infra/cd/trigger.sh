#!/bin/sh
# Installed root-owned as /usr/local/sbin/yamu-cd-trigger, forced SSH command.
set -eu
exec sudo -n /usr/local/sbin/yamu-cd
