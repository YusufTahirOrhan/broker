#!/usr/bin/env bash
# Bir uygulamayı local profiliyle, DEV'e bağlı çalıştırır (docs/local-run.md).
#   scripts/local-run.sh admin|broker|env-admin|login [--build]
# local.env varsa ortam değişkenleri ondan okunur. Jar yoksa ya da --build verilmişse önce derlenir (testsiz).
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in
  admin) module=broker-admin ;;
  broker) module=broker-runtime ;;
  env-admin) module=broker-env-admin ;;
  login) module=broker-login ;;
  *) echo "Kullanım: scripts/local-run.sh admin|broker|env-admin|login [--build]" >&2; exit 2 ;;
esac

if [ -f local.env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./local.env
  set +a
fi

jar=$(ls "$module"/target/"$module"-*-exec.jar 2>/dev/null | head -n 1 || true)
if [ -z "$jar" ] || [ "${2:-}" = "--build" ]; then
  mvn -B -q -DskipTests -pl "$module" -am package
  jar=$(ls "$module"/target/"$module"-*-exec.jar | head -n 1)
fi

exec java -jar "$jar" --spring.profiles.active=local
