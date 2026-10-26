#!/bin/sh
# Run on the deployment host with curl, openssl and a Java keytool available.
# The PKCS12 password is public: this file contains public CA certificates only.
set -eu
umask 077
target=${1:?Usage: prepare-truststore.sh ABSOLUTE_OUTPUT_PATH}
case "$target" in /*) ;; *) echo 'Output must be an absolute path' >&2; exit 1;; esac
test ! -e "$target" || { echo 'Output already exists; review before replacing it' >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem \
  -o "$work/bundle.pem"
awk -v dir="$work" '
  /-----BEGIN CERTIFICATE-----/ { n++; file=dir "/ca-" n ".pem" }
  file != "" { print > file }
  /-----END CERTIFICATE-----/ { close(file); file="" }
' "$work/bundle.pem"
count=0
for cert in "$work"/ca-*.pem; do
  test -f "$cert" || { echo 'No CA certificates found' >&2; exit 1; }
  openssl x509 -in "$cert" -noout -checkend 0 >/dev/null
  count=$((count + 1))
  keytool -importcert -noprompt -alias "rds-ca-$count" -file "$cert" \
    -keystore "$work/truststore.p12" -storetype PKCS12 -storepass changeit >/dev/null
done
mkdir -p "$(dirname "$target")"
cp "$work/truststore.p12" "$target"
chmod 644 "$target"
printf 'Imported %s RDS CA certificates\n' "$count"
