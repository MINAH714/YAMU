#!/usr/bin/env bash
set -euo pipefail
# Run against the exact deployed Spring Boot jar. Requires JDK 17+ (java, javac, jar).
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo 'usage: run.sh /absolute/server.jar plan|check|apply|verify /absolute/input.private.json [YoonseoBankPrepare|YoonseoSeedTest]' >&2
  exit 2
fi
artifact=$(realpath "$1")
input=$(realpath "$3")
sources=$(cd -- "$(dirname -- "$0")" && pwd)
command=$2
main=${4:-YoonseoSeed}
case "$main" in YoonseoSeed|YoonseoBankPrepare|YoonseoSeedTest) ;; *) exit 2 ;; esac
runtime=$(mktemp -d /tmp/yoonseo-seed.XXXXXXXX)
cleanup() { case "$runtime" in /tmp/yoonseo-seed.*) rm -rf -- "$runtime" ;; esac; }
trap cleanup EXIT
(cd "$runtime" && jar xf "$artifact")
test -d "$runtime/BOOT-INF/classes"
test -d "$runtime/BOOT-INF/lib"
mkdir "$runtime/seed-classes"
classpath="$runtime/BOOT-INF/classes:$runtime/BOOT-INF/lib/*"
javac --release 17 -encoding UTF-8 -cp "$classpath" -d "$runtime/seed-classes" "$sources"/*.java
java -Dfile.encoding=UTF-8 -Duser.timezone=UTC -cp "$runtime/seed-classes:$classpath" "$main" "$command" "$input"
